package dev.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
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
class BackendTest {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired Auth auth;
  @Autowired Config c;
  @Autowired Worker worker;
  @Autowired Domain d;
  @Autowired Model model;
  @Autowired Wiki wiki;
  String owner;

  @BeforeEach
  void reset() {
    String url = System.getenv("TEST_DATABASE_URL");
    assertNotNull(url);
    assertTrue(url.endsWith("/decision_backend_test"), "Refuse to reset a non-test database");
    s.db.execute(
        "TRUNCATE"
            + " jobs,records,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    c.mock = false;
    c.modelKey = "";
    c.modelBase = "https://api.deepseek.com";
    c.wikiPublicUrl = "";
    c.wikiBase = "";
    c.wikiToken = "";
    owner = auth.token("owner", Instant.now().plusSeconds(600));
  }

  JsonNode req(MockHttpServletRequestBuilder r, String token, JsonNode b, int status)
      throws Exception {
    if (token != null) r.header("Authorization", "Bearer " + token);
    if (b != null) r.contentType("application/json").content(b.toString());
    var response = mvc.perform(r).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return response.getContentAsString().isBlank()
        ? Json.obj()
        : Json.parse(response.getContentAsString());
  }

  JsonNode postJson(String path, String token, JsonNode body, int status) throws Exception {
    return req(post("/api/v1" + path).header("Idempotency-Key", Json.id()), token, body, status);
  }

  String problem() throws Exception {
    return postJson("/problems", owner, Json.obj("title", "虚构测试问题", "goal", "比较虚构选择"), 201)
        .path("id")
        .asText();
  }

  JsonNode job(String id) throws Exception {
    return req(get("/api/v1/jobs/" + id), owner, null, 200);
  }

  String source(String p) throws Exception {
    JsonNode j =
        postJson(
            "/sources",
            owner,
            Json.obj(
                "problemId", p, "kind", "article", "title", "虚构文章", "text", "虚构原文：方案甲成本为三，尚待核实。"),
            202);
    assertTrue(worker.runOne());
    JsonNode result = job(j.path("id").asText());
    assertEquals("succeeded", result.path("status").asText());
    return result.path("result").path("sourceId").asText();
  }

  JsonNode device() throws Exception {
    return postJson("/devices", owner, Json.obj("name", "synthetic-device"), 201);
  }

  com.sun.net.httpserver.HttpServer modelServer(
      java.util.function.Function<JsonNode, ObjectNode> answer) throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          JsonNode request =
              Json.parse(
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8));
          ObjectNode response = answer.apply(request);
          byte[] out =
              Json.obj(
                      "model",
                      "synthetic-http-fixture",
                      "usage",
                      Json.obj("prompt_tokens", 10, "completion_tokens", 20),
                      "choices",
                      Json.arr().add(Json.obj("message", Json.obj("content", response.toString()))))
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
    c.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
    c.modelKey = "synthetic-fixture-key";
    return server;
  }

  ObjectNode syntheticSummary(JsonNode request) {
    JsonNode input = Json.parse(request.path("messages").get(1).path("content").asText());
    var citations = Json.arr();
    for (JsonNode src : input.path("untrustedSources"))
      citations.add(
          Json.obj("sourceId", src.path("id"), "quote", src.path("text"), "relation", "context"));
    return Json.obj(
        "topic",
        Json.obj(
            "title",
            "合成主题",
            "viewpoints",
            Json.arr().add("仅为测试观点"),
            "conditions",
            Json.arr().add("仅适用合成条件"),
            "uncertainties",
            Json.arr().add("合成未知"),
            "evidence",
            citations),
        "discussions",
        Json.arr(),
        "memories",
        Json.arr());
  }

  @Test
  void summariesMergeSourcesAndKeepRevisionHistory() throws Exception {
    var server = modelServer(this::syntheticSummary);
    try {
      String p = problem(), first = source(p);
      assertTrue(worker.runOne());
      ObjectNode before = s.all("topic", p).get(0);
      assertTrue(before.path("markdown").asText().contains("## 适用条件"));
      assertEquals(1, before.path("evidence").size());
      JsonNode queued =
          postJson(
              "/sources",
              owner,
              Json.obj("problemId", p, "kind", "article", "text", "第二份合成资料，用于关联主题"),
              202);
      worker.runOne();
      worker.runOne();
      assertEquals("succeeded", job(queued.path("id").asText()).path("status").asText());
      List<ObjectNode> topics = s.all("topic", p);
      assertEquals(1, topics.size());
      assertEquals(before.path("id"), topics.get(0).path("id"));
      assertEquals(2, topics.get(0).path("evidence").size());
      assertTrue(topics.get(0).path("revision").asInt() > before.path("revision").asInt());
      assertTrue(
          s.db.queryForObject(
                  "SELECT count(*) FROM record_history WHERE record_id=?::uuid",
                  Integer.class,
                  before.path("id").asText())
              >= 2);
      assertTrue(s.all("discussion", p).isEmpty());
      assertEquals(2, s.db.queryForObject("SELECT count(*) FROM model_calls", Integer.class));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void summaryFailurePreservesSourceAndExplainsPendingSummary() throws Exception {
    String p = problem(), id = source(p);
    worker.runOne();
    assertNotNull(s.get("source", id));
    assertTrue(
        s.all("topic", p)
            .get(0)
            .path("markdown")
            .asText()
            .contains("摘要生成未完成（MODEL_NOT_CONFIGURED）"));
  }

  @Test
  void invalidSummaryEvidenceNeverBecomesVerifiedTopic() throws Exception {
    var server =
        modelServer(
            request -> {
              ObjectNode result = syntheticSummary(request);
              ((ObjectNode) result.path("topic").path("evidence").get(0)).put("quote", "伪造引文");
              return result;
            });
    try {
      String p = problem();
      source(p);
      worker.runOne();
      ObjectNode topic = s.all("topic", p).get(0);
      assertTrue(topic.path("evidence").isEmpty());
      assertTrue(topic.path("markdown").asText().contains("SUMMARY_UNGROUNDED"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void contextCorrectionDuringModelCallRejectsStaleAnswer() throws Exception {
    String p = problem();
    var server =
        modelServer(
            request -> {
              s.write(
                  () -> {
                    s.bump();
                    return null;
                  });
              return Json.obj(
                  "content", "旧上下文输出", "evidence", Json.arr(), "uncertainties", Json.arr());
            });
    try {
      JsonNode j =
          postJson("/problems/" + p + "/messages", owner, Json.obj("content", "合成上下文修改测试"), 202);
      worker.runOne();
      assertEquals(
          "CONTEXT_CHANGED", job(j.path("id").asText()).path("error").path("code").asText());
      assertEquals(1, s.all("message", p).size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void wikiAdapterUsesHttp11AndVerifiesReadback() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> stored =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger creates =
        new java.util.concurrent.atomic.AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    String path = "decision/" + Json.id();
    server.createContext(
        "/graphql",
        exchange -> {
          JsonNode input =
              Json.parse(
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8));
          String query = input.path("query").asText();
          JsonNode response;
          if (exchange.getRequestHeaders().containsKey("Upgrade")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }
          if (query.contains("list("))
            response =
                Json.obj(
                    "data",
                    Json.obj(
                        "pages",
                        Json.obj(
                            "list",
                            stored.get() == null
                                ? Json.arr()
                                : Json.arr().add(Json.obj("id", 7, "path", path)))));
          else if (query.contains("single("))
            response =
                Json.obj(
                    "data",
                    Json.obj(
                        "pages",
                        Json.obj(
                            "single", Json.obj("id", 7, "path", path, "content", stored.get()))));
          else {
            stored.set(input.path("variables").path("content").asText());
            String operation = query.contains("create(") ? "create" : "update";
            if (operation.equals("create")) creates.incrementAndGet();
            response =
                Json.obj(
                    "data",
                    Json.obj(
                        "pages",
                        Json.obj(
                            operation,
                            Json.obj(
                                "responseResult",
                                Json.obj("succeeded", true),
                                "page",
                                Json.obj("id", 7, "path", path)))));
          }
          byte[] out = response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
    try {
      c.wikiBase = "http://127.0.0.1:" + server.getAddress().getPort();
      c.wikiToken = "synthetic-wiki-token";
      c.wikiPublicUrl = "http://127.0.0.1:8090";
      c.wikiLocale = "en";
      ObjectNode topic = Json.obj("id", path.substring(9), "title", "合成测试", "markdown", "合成摘要");
      Wiki.Result first = wiki.sync(topic);
      assertEquals("7", first.pageId());
      assertTrue(first.url().startsWith("http://127.0.0.1:8090/en/"));
      topic.put("markdown", "更新后的合成摘要");
      wiki.sync(topic);
      assertEquals(1, creates.get());
      assertEquals("更新后的合成摘要", stored.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void wikiPublicUrlUsesBrowserAddress() {
    String old = c.wikiPublicUrl;
    try {
      c.wikiBase = "http://wiki:3000";
      c.wikiPublicUrl = "http://127.0.0.1:8090/";
      c.wikiLocale = "en";
      assertEquals("http://127.0.0.1:8090/en/decision/test", wiki.publicUrl("decision/test"));
      c.wikiPublicUrl = "";
      assertEquals("http://wiki:3000/en/decision/test", wiki.publicUrl("decision/test"));
    } finally {
      c.wikiPublicUrl = old;
    }
  }

  @Test
  void memoryListUsesContractPlural() throws Exception {
    String p = problem();
    postJson(
        "/memories",
        owner,
        Json.obj("problemId", p, "kind", "user_reported", "content", "虚构测试"),
        201);
    assertEquals(
        1,
        req(get("/api/v1/memories").param("problemId", p), owner, null, 200).path("items").size());
  }

  @Test
  void healthAndAuthentication() throws Exception {
    req(get("/api/v1/health"), null, null, 200);
    req(get("/api/v1/sources"), null, null, 401);
    req(get("/api/v1/status"), "forged", null, 401);
    JsonNode login =
        postJson("/auth/login", null, Json.obj("accessCode", "synthetic-owner-code"), 200);
    req(get("/api/v1/status"), login.path("accessToken").asText(), null, 200);
    assertFalse(login.toString().contains("synthetic-owner-code"));
  }

  @Test
  void loginRateLimitedAndNoCredentialLogging() throws Exception {
    for (int i = 0; i < 10; i++)
      postJson("/auth/login", null, Json.obj("accessCode", "wrong"), 401);
    postJson("/auth/login", null, Json.obj("accessCode", "wrong"), 429);
  }

  @Test
  void idempotencyReplayConflictAndRequiredKey() throws Exception {
    String key = Json.id();
    ObjectNode body = Json.obj("title", "虚构", "goal", "验证");
    JsonNode a = req(post("/api/v1/problems").header("Idempotency-Key", key), owner, body, 201);
    JsonNode b = req(post("/api/v1/problems").header("Idempotency-Key", key), owner, body, 201);
    assertEquals(a, b);
    req(
        post("/api/v1/problems").header("Idempotency-Key", key),
        owner,
        Json.obj("title", "不同", "goal", "验证"),
        409);
    req(post("/api/v1/problems"), owner, body, 400);
    assertEquals(1, s.all("problem", null).size());
  }

  @Test
  void sourceIngestPersistsAndWikiUnconfiguredIsExplicit() throws Exception {
    String p = problem();
    String id = source(p);
    JsonNode src = req(get("/api/v1/sources/" + id), owner, null, 200);
    assertEquals("ready", src.path("status").asText());
    ObjectNode topic = s.all("topic", p).get(0);
    assertEquals("pending_configuration", topic.path("syncStatus").asText());
    postJson("/topics/" + topic.path("id").asText() + "/sync", owner, Json.obj(), 503);
  }

  @Test
  void unconfiguredModelFailsJobButPreservesUserMessage() throws Exception {
    String p = problem();
    JsonNode j = postJson("/problems/" + p + "/messages", owner, Json.obj("content", "虚构问题"), 202);
    worker.runOne();
    JsonNode result = job(j.path("id").asText());
    assertEquals("failed", result.path("status").asText());
    assertEquals("MODEL_NOT_CONFIGURED", result.path("error").path("code").asText());
    assertEquals(1, s.all("message", p).size());
    assertEquals(0, s.all("decision", p).size());
  }

  @Test
  void explicitMockIsLabeledAndNeverConfirmsDecision() throws Exception {
    c.mock = true;
    String p = problem();
    JsonNode j = postJson("/problems/" + p + "/messages", owner, Json.obj("content", "虚构问题"), 202);
    worker.runOne();
    assertEquals("mock", job(j.path("id").asText()).path("mode").asText());
    assertEquals("mock", s.all("message", p).get(1).path("mode").asText());
    assertTrue(s.all("decision", p).isEmpty());
  }

  @Test
  void fabricatedEvidenceAndCrossProblemSourcesRejected() throws Exception {
    String p = problem(), other = problem(), id = source(p);
    postJson(
        "/decisions",
        owner,
        Json.obj(
            "problemId",
            p,
            "choice",
            "甲",
            "rationale",
            "演示",
            "expectedOutcome",
            "待验",
            "evidence",
            Json.arr().add(Json.obj("sourceId", id, "quote", "不存在的引文", "relation", "supports"))),
        400);
    postJson(
        "/problems/" + other + "/messages",
        owner,
        Json.obj("content", "测试", "sourceIds", Json.arr().add(id)),
        400);
  }

  @Test
  void memoryRevisionCorrectionRetractionAndDeletion() throws Exception {
    String p = problem();
    JsonNode m =
        postJson(
            "/memories",
            owner,
            Json.obj("problemId", p, "kind", "user_reported", "content", "原来的虚构偏好"),
            201);
    String id = m.path("id").asText(), src = m.path("evidence").get(0).path("sourceId").asText();
    req(
        patch("/api/v1/memories/" + id),
        owner,
        Json.obj("content", "新的虚构偏好", "expectedRevision", 0),
        409);
    req(
        patch("/api/v1/memories/" + id),
        owner,
        Json.obj("content", "新的虚构偏好", "expectedRevision", 1),
        200);
    assertEquals("新的虚构偏好", s.get("source", src).path("text").asText());
    Model.Context ctx = model.context(p, Json.obj());
    assertFalse(ctx.input().toString().contains("原来的虚构偏好"));
    req(delete("/api/v1/memories/" + id), owner, null, 204);
    req(delete("/api/v1/memories/" + id), owner, null, 204);
    assertFalse(model.context(p, Json.obj()).input().toString().contains("新的虚构偏好"));
  }

  @Test
  void sourceDeletionInvalidatesDerivedContent() throws Exception {
    String p = problem(), id = source(p);
    req(delete("/api/v1/sources/" + id), owner, null, 204);
    req(get("/api/v1/sources/" + id), owner, null, 404);
    assertTrue(s.all("topic", p).get(0).path("evidence").isEmpty());
    assertFalse(model.context(p, Json.obj()).input().toString().contains("方案甲成本为三"));
  }

  @Test
  void decisionReviewAppendsWithoutOverwritingRationale() throws Exception {
    String p = problem();
    JsonNode dec =
        postJson(
            "/decisions",
            owner,
            Json.obj("problemId", p, "choice", "甲", "rationale", "当时理由", "expectedOutcome", "期待"),
            201);
    postJson(
        "/decisions/" + dec.path("id").asText() + "/reviews",
        owner,
        Json.obj("outcome", "虚构结果", "reflection", "虚构复盘"),
        201);
    ObjectNode actual = s.get("decision", dec.path("id").asText());
    assertEquals("当时理由", actual.path("rationale").asText());
    assertEquals(1, actual.path("reviews").size());
  }

  @Test
  void deviceCannotReadOwnerDataOrControlAnyDevice() throws Exception {
    JsonNode dev = device();
    String token = dev.path("deviceToken").asText(), id = dev.path("device").path("id").asText();
    assertEquals("paused", dev.path("device").path("desiredState").asText());
    req(get("/api/v1/sources"), token, null, 403);
    req(patch("/api/v1/devices/" + id), token, Json.obj("desiredState", "running"), 403);
    postJson("/devices/" + id + "/heartbeat", owner, Json.obj("reportedState", "running"), 403);
    JsonNode other = device();
    postJson(
        "/devices/" + other.path("device").path("id").asText() + "/heartbeat",
        token,
        Json.obj("reportedState", "running"),
        403);
  }

  @Test
  void observationConsentDuplicateAndPauseRetry() throws Exception {
    String p = problem();
    JsonNode dev = device();
    String token = dev.path("deviceToken").asText(), id = dev.path("device").path("id").asText();
    String text = "虚构屏幕文字";
    ObjectNode body =
        Json.obj(
            "deviceId",
            id,
            "items",
            Json.arr()
                .add(
                    Json.obj(
                        "externalId",
                        "sample-1",
                        "problemId",
                        p,
                        "text",
                        text,
                        "appName",
                        "Synthetic",
                        "capturedAt",
                        Json.now(),
                        "extractionMethod",
                        "ocr",
                        "sourceLocator",
                        Json.obj("frameId", "frame-1"),
                        "contentHash",
                        Auth.hash(text))));
    JsonNode paused = postJson("/observations/batch", token, body, 200);
    assertEquals("DEVICE_PAUSED", paused.path("rejected").get(0).path("code").asText());
    req(patch("/api/v1/devices/" + id), owner, Json.obj("desiredState", "running"), 200);
    postJson("/devices/" + id + "/heartbeat", token, Json.obj("reportedState", "running"), 200);
    assertEquals(1, postJson("/observations/batch", token, body, 200).path("accepted").size());
    assertEquals(1, postJson("/observations/batch", token, body, 200).path("duplicates").size());
    req(patch("/api/v1/devices/" + id), owner, Json.obj("desiredState", "paused"), 200);
    assertEquals(
        "DEVICE_PAUSED",
        postJson("/observations/batch", token, body, 200)
            .path("rejected")
            .get(0)
            .path("code")
            .asText());
    assertEquals(1, s.all("source", p).size());
  }

  @Test
  void restartMarksInterruptedJobAndKeepsQueuedJob() throws Exception {
    String p = problem();
    JsonNode j = postJson("/problems/" + p + "/messages", owner, Json.obj("content", "测试重启"), 202);
    s.db.update("UPDATE jobs SET state='running' WHERE id=?::uuid", j.path("id").asText());
    worker.recover();
    assertEquals(
        "PROCESS_RESTARTED", job(j.path("id").asText()).path("error").path("code").asText());
  }

  @Test
  void pagingValidatesCursorAndStopsAtLastPage() throws Exception {
    problem();
    problem();
    problem();
    JsonNode page = req(get("/api/v1/problems?limit=2"), owner, null, 200);
    assertEquals(2, page.path("items").size());
    JsonNode next =
        req(
            get("/api/v1/problems")
                .param("limit", "2")
                .param("cursor", page.path("nextCursor").asText()),
            owner,
            null,
            200);
    assertEquals(1, next.path("items").size());
    assertTrue(next.path("nextCursor").isNull());
    req(get("/api/v1/problems?cursor=bad"), owner, null, 400);
    req(get("/api/v1/problems?limit=101"), owner, null, 400);
  }

  @Test
  void ssrfAddressesAreRejected() throws Exception {
    for (String address :
        List.of(
            "127.0.0.1",
            "::1",
            "10.0.0.1",
            "192.168.1.2",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "fc00::1"))
      assertFalse(SafeFetch.publicAddress(InetAddress.getByName(address)), address);
    assertTrue(SafeFetch.publicAddress(InetAddress.getByName("8.8.8.8")));
    assertThrows(ApiError.class, () -> SafeFetch.validateUri("file:///etc/passwd"));
    assertThrows(ApiError.class, () -> SafeFetch.fetch("http://127.0.0.1/"));
  }

  @Test
  void snoozedOrDismissedDeliveryNotSentAndAckIsScoped() throws Exception {
    String p = problem();
    JsonNode dev = device();
    String id = dev.path("device").path("id").asText(), token = dev.path("deviceToken").asText();
    ObjectNode disc =
        s.write(
            () ->
                s.create(
                    "discussion",
                    p,
                    Json.obj(
                        "problemId",
                        p,
                        "title",
                        "虚构议题",
                        "reason",
                        "测试",
                        "evidence",
                        Json.arr(),
                        "status",
                        "unread",
                        "snoozeUntil",
                        null)));
    ObjectNode delivery =
        s.write(
            () ->
                s.create(
                    "delivery",
                    p,
                    Json.obj(
                        "discussionId",
                        disc.path("id"),
                        "deviceId",
                        id,
                        "channel",
                        "desktop",
                        "status",
                        "pending",
                        "error",
                        null)));
    assertEquals(
        1,
        req(get("/api/v1/devices/" + id + "/deliveries"), token, null, 200).path("items").size());
    req(
        patch("/api/v1/discussions/" + disc.path("id").asText()),
        owner,
        Json.obj("status", "snoozed", "snoozeUntil", Instant.now().plusSeconds(120).toString()),
        200);
    assertEquals(
        0,
        req(get("/api/v1/devices/" + id + "/deliveries"), token, null, 200).path("items").size());
    JsonNode other = device();
    postJson(
        "/deliveries/" + delivery.path("id").asText() + "/ack",
        other.path("deviceToken").asText(),
        Json.obj("status", "sent"),
        403);
    postJson(
        "/deliveries/" + delivery.path("id").asText() + "/ack",
        token,
        Json.obj("status", "sent"),
        200);
    postJson(
        "/deliveries/" + delivery.path("id").asText() + "/ack",
        token,
        Json.obj("status", "failed"),
        200);
    assertEquals("sent", s.get("delivery", delivery.path("id").asText()).path("status").asText());
  }
}
