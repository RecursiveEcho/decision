package dev.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL}",
      "spring.datasource.username=decision_test",
      "spring.datasource.password=synthetic-test-only",
      "app.owner-code=synthetic-owner-code",
      "app.token-secret=synthetic-token-secret-for-local-tests-only",
      "app.model-key=",
      "app.wiki-base=",
      "app.wiki-token=",
      "app.mock=false",
      "app.worker-enabled=false"
    })
@AutoConfigureMockMvc
class KnowledgeTest extends AdminFixture {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired Auth auth;
  @Autowired Config c;
  @Autowired Worker worker;
  @Autowired Knowledge knowledge;
  @Autowired Model model;
  String owner;

  @BeforeEach
  void reset() {
    assertNotNull(System.getenv("TEST_DATABASE_URL"));
    assertTrue(System.getenv("TEST_DATABASE_URL").endsWith("/decision_backend_test"));
    s.db.execute(
        "TRUNCATE"
            + " records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    s.db.update("UPDATE chat_memory_state SET epoch=0");
    c.mock = false;
    c.modelKey = "";
    c.modelBase = "https://api.deepseek.com";
    owner = auth.token("owner", Instant.now().plusSeconds(600));
  }

  @AfterEach
  void restore() {
    c.mock = false;
    c.modelKey = "";
    c.modelBase = "https://api.deepseek.com";
  }

  JsonNode request(MockHttpServletRequestBuilder builder, JsonNode body, int status)
      throws Exception {
    builder.header("Authorization", "Bearer " + owner);
    if (body != null) builder.contentType("application/json").content(body.toString());
    var response = mvc.perform(builder).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return Json.parse(response.getContentAsString());
  }

  JsonNode postBody(String path, JsonNode body, int status) throws Exception {
    return request(post("/api/v1" + path).header("Idempotency-Key", Json.id()), body, status);
  }

  ObjectNode problem() {
    return s.write(
        () ->
            s.create(
                "problem",
                null,
                Json.obj(
                    "title",
                    "合成知识讨论",
                    "goal",
                    "",
                    "constraints",
                    Json.arr(),
                    "unknowns",
                    Json.arr())));
  }

  ObjectNode message(String p, String text) {
    return s.write(
        () ->
            s.create(
                "message",
                p,
                Json.obj(
                    "problemId",
                    p,
                    "role",
                    "user",
                    "content",
                    text,
                    "evidence",
                    Json.arr(),
                    "uncertainties",
                    Json.arr(),
                    "mode",
                    "real")));
  }

  ObjectNode candidate(JsonNode input, String action, String text) {
    JsonNode m = input.path("messages").get(input.path("messages").size() - 1);
    ObjectNode result =
        Json.obj(
            "action",
            action,
            "title",
            "合成：重试与幂等",
            "markdown",
            text,
            "domains",
            Json.arr().add("软件工程").add("可靠性"),
            "relatedEntryIds",
            Json.arr(),
            "refs",
            Json.arr()
                .add(
                    Json.obj(
                        "kind",
                        "message",
                        "id",
                        m.path("id"),
                        "role",
                        m.path("role"),
                        "quote",
                        m.path("content"))),
            "reason",
            "值得长期保存，补充适用条件",
            "targetEntryId",
            null,
            "targetRevision",
            null);
    if (action.equals("merge") || action.equals("revise")) {
      JsonNode target = input.path("knowledge").get(0);
      result.set("targetEntryId", target.path("id"));
      result.set("targetRevision", target.path("revision"));
    }
    if (action.equals("skip")) {
      result.put("markdown", "");
      result.set("refs", Json.arr());
    }
    return result;
  }

  class Gateway implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile JsonNode input;
    volatile JsonNode payload;

    Gateway(Function<JsonNode, ObjectNode> answer) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            try {
              payload =
                  Json.parse(
                      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              input = Json.parse(payload.path("messages").get(1).path("content").asText());
              calls.incrementAndGet();
              ObjectNode response = answer.apply(input);
              byte[] bytes =
                  Json.obj(
                          "choices",
                          Json.arr()
                              .add(
                                  Json.obj(
                                      "finish_reason",
                                      "stop",
                                      "message",
                                      Json.obj("content", response.toString()))),
                          "usage",
                          Json.obj("prompt_tokens", 1, "completion_tokens", 1))
                      .toString()
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      c.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
      c.modelKey = "synthetic-knowledge-test-key";
    }

    public void close() {
      server.stop(0);
    }
  }

  JsonNode prepare(String p) throws Exception {
    JsonNode job = postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
    worker.runOne();
    JsonNode done = request(get("/api/v1/jobs/" + job.path("id").asText()), null, 200);
    assertEquals("succeeded", done.path("status").asText(), done.toString());
    return request(
        get("/api/v1/knowledge-proposals/" + done.path("result").path("proposalId").asText()),
        null,
        200);
  }

  JsonNode accept(JsonNode proposal, int status) throws Exception {
    return postBody(
        "/knowledge-proposals/" + proposal.path("id").asText() + "/accept",
        Json.obj("expectedTargetRevision", proposal.path("targetRevision")),
        status);
  }

  ObjectNode reply(JsonNode intent) {
    ObjectNode response =
        Json.obj(
            "content", "合成结论：重试同一写入时复用幂等键。", "evidence", Json.arr(), "uncertainties", Json.arr());
    if (intent != null) response.set("suggestKnowledge", intent);
    return response;
  }

  JsonNode sendAndReadAnswer(String p, String content) throws Exception {
    JsonNode chat = postBody("/problems/" + p + "/messages", Json.obj("content", content), 202);
    assertTrue(worker.runOne());
    JsonNode done = request(get("/api/v1/jobs/" + chat.path("id").asText()), null, 200);
    assertEquals("succeeded", done.path("status").asText());
    JsonNode history = request(get("/api/v1/problems/" + p + "/messages"), null, 200).path("items");
    String id = done.at("/result/assistantMessageId").asText();
    for (JsonNode message : history) if (message.path("id").asText().equals(id)) return message;
    throw new AssertionError("Assistant message missing after successful chat");
  }

  @Test
  void onlyBooleanTrueCanCreateSuggestionAndOrdinaryChatMakesOneCall() throws Exception {
    String p = problem().path("id").asText();
    for (JsonNode intent :
        Arrays.<JsonNode>asList(
            null, Json.M.valueToTree(false), Json.M.valueToTree("true"), Json.M.valueToTree(1))) {
      try (Gateway gateway = new Gateway(in -> reply(intent))) {
        assertFalse(sendAndReadAnswer(p, "合成：还在探索。").has("knowledgeSuggestion"));
        assertFalse(worker.runOne());
        assertEquals(1, gateway.calls.get());
        assertTrue(gateway.payload.at("/messages/0/content").asText().contains("suggestKnowledge"));
      }
    }
  }

  @Test
  void suggestedJobPersistsAndSharesOneDraftWithConcurrentManualRequests() throws Exception {
    String p = problem().path("id").asText();
    try (Gateway gateway =
        new Gateway(
            in ->
                in.has("request")
                    ? reply(Json.M.valueToTree(true))
                    : candidate(in, "create", "重试写入应复用幂等键。"))) {
      JsonNode answer = sendAndReadAnswer(p, "合成：我们收敛为重试沿用原有键。");
      String jobId = answer.at("/knowledgeSuggestion/jobId").textValue();
      assertNotNull(jobId);
      var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        java.util.concurrent.Callable<JsonNode> submit =
            () -> postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
        for (var task : executor.invokeAll(List.of(submit, submit)))
          assertEquals(jobId, task.get().path("id").asText());
      } finally {
        executor.shutdownNow();
      }
      assertTrue(worker.runOne());
      assertFalse(worker.runOne());
      JsonNode job = request(get("/api/v1/jobs/" + jobId), null, 200);
      String draft = job.at("/result/proposalId").asText();
      JsonNode proposal = request(get("/api/v1/knowledge-proposals/" + draft), null, 200);
      assertEquals(Json.M.valueToTree(false), proposal.get("requiresRefresh"));
      assertEquals("pending", proposal.path("status").asText());
      assertTrue(s.all("knowledge_entry", null).isEmpty());
      assertEquals(2, gateway.calls.get());
      worker.recover();
      assertEquals(
          jobId,
          s.get("message", answer.path("id").asText()).at("/knowledgeSuggestion/jobId").asText());
      assertEquals(
          jobId,
          postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202).path("id").asText());
      assertFalse(worker.runOne());
      accept(proposal, 200);
      assertEquals(1, s.all("knowledge_entry", null).size());
    }
  }

  @Test
  void newerConclusionCanReplaceStaleDraftButQueuedOlderTurnDoesNotSuggest() throws Exception {
    String p = problem().path("id").asText();
    try (Gateway gateway =
        new Gateway(
            in ->
                in.has("request")
                    ? reply(Json.M.valueToTree(true))
                    : candidate(in, "create", "合成长期结论"))) {
      String legacyJob =
          postBody("/problems/" + p + "/messages", Json.obj("content", "合成旧轮"), 202)
              .path("id")
              .asText();
      // Seed a pre-V10 queue containing two turns; the current API correctly rejects this overlap.
      s.db.update("UPDATE jobs SET state='failed' WHERE id=?::uuid", legacyJob);
      postBody("/problems/" + p + "/messages", Json.obj("content", "合成新轮"), 202);
      s.db.update("UPDATE jobs SET state='queued' WHERE id=?::uuid", legacyJob);
      assertTrue(worker.runOne());
      assertTrue(s.all("message", p).stream().noneMatch(m -> m.has("knowledgeSuggestion")));
      assertTrue(worker.runOne());
      assertTrue(worker.runOne());
      ObjectNode old = s.all("knowledge_proposal", p).get(0);
      assertTrue(sendAndReadAnswer(p, "合成：补充稳定的适用边界。").has("knowledgeSuggestion"));
      assertTrue(
          request(get("/api/v1/knowledge-proposals/" + old.path("id").asText()), null, 200)
              .path("requiresRefresh")
              .asBoolean());
      assertTrue(
          request(get("/api/v1/knowledge-proposals").param("problemId", p), null, 200)
              .at("/items/0/requiresRefresh")
              .asBoolean());
      assertFalse(s.get("knowledge_proposal", old.path("id").asText()).has("requiresRefresh"));
      assertTrue(
          Knowledge.chatContext(s, c, p, "新结论").at("/pendingProposal/requiresRefresh").asBoolean());
      accept(old, 409);
      assertTrue(worker.runOne());
      assertEquals(2, s.all("knowledge_proposal", p).size());
      assertTrue(s.all("knowledge_entry", null).isEmpty());
    }
  }

  @Test
  void failedOptionalInsertRollsBackOnlySuggestionAndNextChatStillWorks() throws Exception {
    String p = problem().path("id").asText();
    // Fail the second of the two job inserts, after its records row already exists.
    s.db.execute(
        "ALTER TABLE jobs ADD CONSTRAINT synthetic_reject_knowledge"
            + " CHECK(input->>'operation'<>'knowledge_proposal')");
    try (Gateway gateway = new Gateway(in -> reply(Json.M.valueToTree(true)))) {
      assertFalse(sendAndReadAnswer(p, "合成值得保留的结论").has("knowledgeSuggestion"));
      assertFalse(worker.runOne());
      assertEquals(1, s.all("job", p).size());
      assertEquals(1, gateway.calls.get());
    } finally {
      s.db.execute("ALTER TABLE jobs DROP CONSTRAINT synthetic_reject_knowledge");
    }
    try (Gateway gateway = new Gateway(in -> reply(Json.M.valueToTree(false)))) {
      assertFalse(sendAndReadAnswer(p, "继续合成讨论").has("knowledgeSuggestion"));
    }
  }

  @Test
  void retryOfChatEmitsOneSuggestionAndWithdrawnSnapshotNeverProducesDraft() throws Exception {
    String p = problem().path("id").asText();
    JsonNode memory =
        postBody(
            "/memories", Json.obj("problemId", p, "kind", "user_reported", "content", "合成前提"), 201);
    String failed =
        postBody("/problems/" + p + "/messages", Json.obj("content", "结论依赖该前提"), 202)
            .path("id")
            .asText();
    assertTrue(worker.runOne());
    assertEquals("failed", s.get("job", failed).path("status").asText());
    try (Gateway gateway = new Gateway(in -> reply(Json.M.valueToTree(true)))) {
      String retryPath = "/jobs/" + failed + "/retry";
      JsonNode retry = postBody(retryPath, Json.obj(), 202);
      assertTrue(worker.runOne());
      assertEquals(retry.path("id"), postBody(retryPath, Json.obj(), 202).path("id"));
      String assistant =
          s.get("job", retry.path("id").asText()).at("/result/assistantMessageId").asText();
      String suggestion = s.get("message", assistant).at("/knowledgeSuggestion/jobId").asText();
      assertFalse(suggestion.isEmpty());
      request(
          patch("/api/v1/memories/" + memory.path("id").asText()),
          Json.obj("status", "retracted", "expectedRevision", 1),
          200);
      assertTrue(worker.runOne());
      assertEquals("KNOWLEDGE_CHANGED", s.get("job", suggestion).at("/error/code").asText());
      assertEquals(1, gateway.calls.get());
      assertTrue(s.all("knowledge_proposal", p).isEmpty());
      assertTrue(s.all("knowledge_entry", null).isEmpty());
      assertFalse(worker.runOne());
    }
  }

  @Test
  void deleteCancelsAutomaticDraftWithoutCallingModelOrRevivingMessage() throws Exception {
    String p = problem().path("id").asText();
    try (Gateway gateway = new Gateway(in -> reply(Json.M.valueToTree(true)))) {
      JsonNode answer = sendAndReadAnswer(p, "合成结论");
      String job = answer.at("/knowledgeSuggestion/jobId").asText();
      assertFalse(job.isEmpty());
      assertEquals(
          204,
          mvc.perform(delete("/api/v1/problems/" + p).header("Authorization", "Bearer " + owner))
              .andReturn()
              .getResponse()
              .getStatus());
      worker.recover();
      assertFalse(worker.runOne());
      request(get("/api/v1/jobs/" + job), null, 404);
      assertTrue(s.all("message", p).isEmpty());
      assertTrue(s.all("knowledge_proposal", p).isEmpty());
      assertTrue(s.all("knowledge_entry", null).isEmpty());
      assertEquals(1, gateway.calls.get());
    }
  }

  @Test
  void skipAndModelFailureLeaveSavedAnswerAndKnowledgeIntact() throws Exception {
    for (boolean skip : List.of(true, false)) {
      String p = problem().path("id").asText();
      try (Gateway gateway =
          new Gateway(
              in ->
                  in.has("request")
                      ? reply(Json.M.valueToTree(true))
                      : skip ? candidate(in, "skip", "") : Json.obj("invalid", true))) {
        JsonNode answer = sendAndReadAnswer(p, "合成讨论");
        assertTrue(worker.runOne());
        JsonNode job =
            request(
                get("/api/v1/jobs/" + answer.at("/knowledgeSuggestion/jobId").asText()), null, 200);
        assertEquals(skip ? "succeeded" : "failed", job.path("status").asText());
        if (skip) {
          JsonNode proposal = s.get("knowledge_proposal", job.at("/result/proposalId").asText());
          assertEquals("skip", proposal.path("action").asText());
          accept(proposal, 409);
        } else assertTrue(s.all("knowledge_proposal", p).isEmpty());
        assertEquals(
            answer.path("content"), s.get("message", answer.path("id").asText()).path("content"));
        assertTrue(s.all("knowledge_entry", null).isEmpty());
        assertFalse(worker.runOne());
      }
    }
  }

  @Test
  void confirmedKnowledgeKeepsSnapshotAndHistoryWhenOriginalChatIsDeleted() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：写入涉及幂等判断。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "写入涉及幂等判断。"))) {
      String entry = accept(prepare(p), 200).path("entryId").asText();
      JsonNode pending = prepare(p);
      JsonNode before = request(get("/api/v1/knowledge-entries/" + entry), null, 200);
      assertEquals(
          204,
          mvc.perform(delete("/api/v1/problems/" + p).header("Authorization", "Bearer " + owner))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(before, request(get("/api/v1/knowledge-entries/" + entry), null, 200));
      request(get("/api/v1/knowledge-entries/" + entry + "/history"), null, 200);
      assertTrue(
          knowledge.export().path("references").get(0).path("title").asText().contains("不可用"));
      accept(pending, 404);
    }
  }

  @Test
  void preparingDoesNotWriteKnowledgeAndAcceptanceIsIdempotentAcrossKeysAndRestart()
      throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可以安全重试同一请求。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "讨论结论：有幂等键时可重试。"))) {
      JsonNode proposal = prepare(p);
      assertEquals(0, s.all("knowledge_entry", null).size());
      assertEquals("pending", proposal.path("status").asText());
      assertEquals(
          "knowledge_proposal",
          postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202)
              .path("kind")
              .asText());
      assertEquals(1, gateway.calls.get());
      JsonNode saved = accept(proposal, 200);
      assertEquals(saved, accept(proposal, 200));
      worker.recover();
      assertEquals(saved, accept(proposal, 200));
      assertEquals(1, s.all("knowledge_entry", null).size());
      assertEquals(1, s.all("knowledge_entry", null).get(0).path("revision").asInt());
      assertEquals(0, knowledge.history(saved.path("entryId").asText()).path("items").size());
      assertEquals(
          proposal.path("coverage"), s.all("knowledge_entry", null).get(0).path("coverage"));
    }
  }

  @Test
  void crossConversationMergeThenRevisionKeepsHistoryAndConditionalDifferences() throws Exception {
    String first = problem().path("id").asText();
    message(first, "合成：查询可以重试；写入需考虑幂等。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "查询可重试；写入需考虑幂等。"))) {
      accept(prepare(first), 200);
    }
    String second = problem().path("id").asText();
    message(second, "合成：补充条件，不可幂等的写入不能盲目重试。");
    String id;
    try (Gateway gateway =
        new Gateway(in -> candidate(in, "merge", "查询可重试；有幂等键的写入可重试，不可幂等写入不能盲目重试。"))) {
      JsonNode proposal = prepare(second);
      String systemPrompt = gateway.payload.path("messages").get(0).path("content").asText();
      assertTrue(systemPrompt.contains("未提及不等于不存在或禁止"));
      assertTrue(systemPrompt.contains("不能推出该场景不采用B，也不能推出其他场景不得采用A"));
      assertTrue(systemPrompt.contains("保留仍成立的已确认条件和未知"));
      assertTrue(systemPrompt.contains("新的推理必须在相应正文处标为建议或待确认"));
      assertTrue(systemPrompt.contains("markdown是本人确认后长期保存的知识正文"));
      assertTrue(systemPrompt.contains("流程状态仅放reason或由界面展示"));
      assertTrue(systemPrompt.contains("必须在正文保留这些实质未知"));
      assertTrue(
          gateway.input.path("knowledge").get(0).path("markdown").asText().contains("写入需考虑幂等"),
          "Prior confirmed conditions must reach the model along with the new discussion");
      assertEquals(1, proposal.path("coverage").path("knowledgeConsidered").asInt());
      id = accept(proposal, 200).path("entryId").asText();
      assertEquals(2, s.get("knowledge_entry", id).path("revision").asInt());
      assertEquals(2, s.get("knowledge_entry", id).path("refs").size());
    }
    message(second, "合成：裁剪重复句，保留两种写入条件。");
    try (Gateway gateway =
        new Gateway(in -> candidate(in, "revise", "讨论结论：查询及幂等写入可重试；其他写入需人工判断。"))) {
      accept(prepare(second), 200);
      assertEquals(1, s.all("knowledge_entry", null).size());
      assertEquals(3, s.get("knowledge_entry", id).path("revision").asInt());
      assertEquals(2, knowledge.history(id).path("items").size());
      assertEquals(2, knowledge.history(id).path("items").get(0).path("revision").asInt());
      ObjectNode export = knowledge.export();
      assertEquals(1, export.path("entries").size());
      assertEquals(2, export.path("histories").get(0).path("versions").size());
      assertFalse(
          export
              .path("histories")
              .get(0)
              .path("versions")
              .get(0)
              .path("coverage")
              .path("attachmentsIncluded")
              .asBoolean());
      assertEquals(3, export.path("references").size());
      assertFalse(export.toString().contains("synthetic-knowledge-test-key"));
    }
  }

  @Test
  void skipAndDismissDoNotWriteAndAcceptedProposalCannotBeDismissed() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：暂时想喝水。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "skip", ""))) {
      JsonNode proposal = prepare(p);
      accept(proposal, 409);
      String path = "/knowledge-proposals/" + proposal.path("id").asText() + "/dismiss";
      assertEquals(postBody(path, Json.obj(), 200), postBody(path, Json.obj(), 200));
      assertEquals(0, s.all("knowledge_entry", null).size());
    }
    message(p, "合成：有幂等键时可重试。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "有幂等键时可重试。"))) {
      JsonNode proposal = prepare(p);
      accept(proposal, 200);
      postBody(
          "/knowledge-proposals/" + proposal.path("id").asText() + "/dismiss", Json.obj(), 409);
    }
  }

  @Test
  void staleConversationAndTargetCannotBeAccepted() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可重试。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "有幂等键时可重试。"))) {
      JsonNode proposal = prepare(p);
      message(p, "合成：刚才条件还没讲完，先别存。");
      assertEquals("KNOWLEDGE_CHANGED", accept(proposal, 409).path("error").path("code").asText());
      assertEquals(0, s.all("knowledge_entry", null).size());
      accept(prepare(p), 200);
    }
    message(p, "合成：补充网络超时条件。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "merge", "有幂等键且网络超时时可重试。"))) {
      JsonNode proposal = prepare(p);
      s.write(
          () -> {
            ObjectNode old = s.all("knowledge_entry", null).get(0);
            old.put("revision", 2);
            s.save("knowledge_entry", old);
            return null;
          });
      accept(proposal, 409);
    }
  }

  @Test
  void changedContextDuringModelCallFailsWithoutProposalOrKnowledge() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可重试。");
    try (Gateway gateway =
        new Gateway(
            in -> {
              ObjectNode result = candidate(in, "create", "讨论结论");
              try (var owner = AccountScope.enter(AccountScope.ADMIN)) {
                message(p, "合成：新消息改变上下文。");
              }
              return result;
            })) {
      JsonNode job = postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
      worker.runOne();
      JsonNode failed = s.get("job", job.path("id").asText());
      assertEquals("failed", failed.path("status").asText());
      assertEquals("KNOWLEDGE_CHANGED", failed.path("error").path("code").asText());
      assertTrue(s.all("knowledge_proposal", null).isEmpty());
      assertTrue(s.all("knowledge_entry", null).isEmpty());
    }
  }

  @Test
  void fabricatedQuoteRoleTargetOrDomainIsRejected() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可重试。");
    List<Function<ObjectNode, ObjectNode>> corruptions =
        List.of(
            n -> {
              ((ObjectNode) n.path("refs").get(0)).put("quote", "并不存在的引用");
              return n;
            },
            n -> {
              ((ObjectNode) n.path("refs").get(0)).put("role", "assistant");
              return n;
            },
            n -> {
              n.put("targetEntryId", Json.id());
              return n;
            },
            n -> {
              n.set("relatedEntryIds", Json.arr().add(Json.id()));
              return n;
            },
            n -> {
              n.set("domains", Json.arr().add("bad\npath"));
              return n;
            });
    for (var corrupt : corruptions) {
      try (Gateway gateway = new Gateway(in -> corrupt.apply(candidate(in, "create", "讨论结论")))) {
        JsonNode job = postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
        worker.runOne();
        assertEquals(
            "KNOWLEDGE_MODEL_INVALID",
            s.get("job", job.path("id").asText()).path("error").path("code").asText());
        assertTrue(s.all("knowledge_proposal", null).isEmpty());
      }
    }
    assertTrue(s.all("knowledge_entry", null).isEmpty());
  }

  @Test
  void snapshotFiltersWithdrawnContextAndExplainsAttachmentsAndBounds() {
    String p = problem().path("id").asText();
    message(p, "合成：旧文字已更正，不得复活。");
    s.db.update("UPDATE context_version SET revision=revision+1");
    s.db.update("UPDATE chat_memory_state SET epoch=epoch+1");
    for (int i = 0; i < 30; i++) {
      ObjectNode m = message(p, "合成新记录" + i + "字".repeat(6001));
      s.db.update("UPDATE records SET memory_epoch=1 WHERE id=?::uuid", m.path("id").asText());
    }
    Knowledge.Snapshot snapshot = s.write(() -> knowledge.snapshot(p));
    assertFalse(snapshot.input().toString().contains("旧文字已更正"));
    assertEquals(31, snapshot.coverage().path("messagesTotal").asInt());
    assertTrue(snapshot.coverage().path("messagesConsidered").asInt() <= 24);
    assertTrue(snapshot.coverage().path("truncated").asBoolean());
    assertFalse(snapshot.coverage().path("attachmentsIncluded").asBoolean());
    assertTrue(snapshot.coverage().path("notice").asText().contains("附件"));
  }

  @Test
  void deletedReferenceInvalidatesAcceptanceAndIsNotUsedForChat() throws Exception {
    String p = problem().path("id").asText();
    ObjectNode m = message(p, "合成：有幂等键时可重试。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "讨论结论"))) {
      JsonNode proposal = prepare(p);
      s.write(
          () -> {
            s.delete("message", m.path("id").asText());
            return null;
          });
      accept(proposal, 409);
      assertTrue(s.all("knowledge_entry", null).isEmpty());
    }
  }

  @Test
  void pendingAndAcceptedKnowledgeAreAvailableToChatAsUntrustedData() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可重试。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "create", "讨论结论"))) {
      JsonNode proposal = prepare(p);
      ObjectNode pending = s.write(() -> Knowledge.chatContext(s, c, p, "幂等"));
      assertEquals(proposal.path("id"), pending.path("pendingProposal").path("id"));
      assertEquals(0, pending.path("entries").size());
      accept(proposal, 200);
      String second = problem().path("id").asText();
      ObjectNode available = s.write(() -> Knowledge.chatContext(s, c, second, "幂等"));
      assertEquals(1, available.path("entries").size());
      assertTrue(available.path("pendingProposal").isNull());
      assertTrue(available.path("notice").asText().contains("不是已证实事实"));
    }
    message(p, "合成：补充新的适用条件。");
    try (Gateway gateway = new Gateway(in -> candidate(in, "merge", "讨论结论与新条件"))) {
      prepare(p);
    }
    s.write(
        () -> {
          ObjectNode entry = s.all("knowledge_entry", null).get(0);
          entry.put("revision", 2);
          s.save("knowledge_entry", entry);
          return null;
        });
    try (Gateway gateway =
        new Gateway(
            in ->
                Json.obj(
                    "content",
                    "可以讨论内容变化，再重新整理。",
                    "evidence",
                    Json.arr(),
                    "uncertainties",
                    Json.arr()))) {
      model.call(model.context(p, Json.obj("content", "请帮我对照已有内容")), false);
      JsonNode supplied = gateway.input.path("untrustedKnowledge");
      assertEquals(2, supplied.path("entries").get(0).path("revision").asInt());
      assertEquals("pending", supplied.path("pendingProposal").path("status").asText());
      assertTrue(supplied.path("pendingProposal").path("requiresRefresh").asBoolean());
      assertEquals(2, supplied.path("pendingProposal").path("targetCurrentRevision").asInt());
      String systemPrompt = gateway.payload.path("messages").get(0).path("content").asText();
      assertTrue(systemPrompt.contains("版本增长只是正常历史"));
      assertTrue(systemPrompt.contains("不抛内部UUID、revision或版本核对作业"));
      assertTrue(systemPrompt.contains("说明已有内容更新、需重新整理提案即可"));
    }
  }

  @Test
  void ownerAuthenticationDeviceIsolationAndRequestValidation() throws Exception {
    String p = problem().path("id").asText();
    postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 400);
    message(p, "合成：有幂等键时可重试。");
    postBody("/problems/" + p + "/knowledge-proposals", Json.obj("markdown", "禁止客户端直接传正文"), 400);
    var response = mvc.perform(get("/api/v1/knowledge-export")).andReturn().getResponse();
    assertEquals(401, response.getStatus());
    String deviceId =
        s.write(() -> s.create("device", null, Json.obj("name", "合成设备"))).path("id").asText();
    String device = auth.token("device:" + deviceId, Instant.now().plusSeconds(600));
    for (String path : List.of("/knowledge-export", "/knowledge-entries", "/knowledge-proposals"))
      assertEquals(
          403,
          mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + device))
              .andReturn()
              .getResponse()
              .getStatus());
    JsonNode export = request(get("/api/v1/knowledge-export"), null, 200);
    assertTrue(export.path("entries").isEmpty());
  }

  @Test
  void missingModelFailureAndInterruptedJobCanBeExplicitlyRegenerated() throws Exception {
    String p = problem().path("id").asText();
    message(p, "合成：有幂等键时可重试。");
    JsonNode first = postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
    worker.runOne();
    assertEquals(
        "MODEL_NOT_CONFIGURED",
        s.get("job", first.path("id").asText()).path("error").path("code").asText());
    JsonNode second = postBody("/problems/" + p + "/knowledge-proposals", Json.obj(), 202);
    assertNotEquals(first.path("id"), second.path("id"));
    s.db.update("UPDATE jobs SET state='running' WHERE id=?::uuid", second.path("id").asText());
    worker.recover();
    assertEquals(
        "PROCESS_RESTARTED",
        s.get("job", second.path("id").asText()).path("error").path("code").asText());
    assertTrue(s.all("knowledge_entry", null).isEmpty());
  }
}
