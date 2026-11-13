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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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
class ProductRuntimeTest extends AdminFixture {
  @Autowired Store s;
  @Autowired Domain d;
  @Autowired Config c;
  @Autowired Worker worker;
  @Autowired ModelSettings settings;
  @Autowired RunEvents events;
  @Autowired Auth auth;
  @Autowired MockMvc mvc;
  @org.springframework.boot.test.mock.mockito.SpyBean Model model;
  String owner;

  @BeforeEach
  void reset() {
    assertTrue(System.getenv("TEST_DATABASE_URL").endsWith("/decision_backend_test"));
    s.db.execute(
        "TRUNCATE"
            + " model_settings,model_test_requests,records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    s.db.update("UPDATE chat_memory_state SET epoch=0");
    c.modelKey = "synthetic-old-key";
    c.modelName = "fixture-chat";
    c.mock = false;
    owner = auth.token("owner", Instant.now().plusSeconds(600));
  }

  @AfterEach
  void restore() {
    s.db.execute("TRUNCATE model_settings,model_test_requests");
    c.modelKey = "";
    c.modelName = "deepseek-flash";
    c.modelBase = "https://api.deepseek.com";
    c.mock = false;
  }

  JsonNode req(MockHttpServletRequestBuilder req, String token, JsonNode body, int status)
      throws Exception {
    if (token != null) req.header("Authorization", "Bearer " + token);
    if (body != null) req.contentType("application/json").content(body.toString());
    var response = mvc.perform(req).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return response.getContentAsString().isBlank()
        ? Json.obj()
        : Json.parse(response.getContentAsString());
  }

  JsonNode postJson(String path, String token, JsonNode body, int status) throws Exception {
    return req(post("/api/v1" + path).header("Idempotency-Key", Json.id()), token, body, status);
  }

  ObjectNode conversation(String text) {
    return s.write(() -> d.conversation(Json.obj("content", text)));
  }

  String state(String id) {
    return s.get("job", id).path("status").asText();
  }

  static void until(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("Condition timed out");
      Thread.sleep(20);
    }
  }

  final class UpstreamFixture implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newCachedThreadPool();
    final CountDownLatch started = new CountDownLatch(1),
        release = new CountDownLatch(1),
        sent = new CountDownLatch(1);
    final AtomicReference<String> key = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final boolean block, broken;

    UpstreamFixture(boolean block, boolean broken) throws Exception {
      this.block = block;
      this.broken = broken;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/models",
          exchange -> {
            byte[] bytes =
                "{\"data\":[{\"id\":\"fixture-chat\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/chat/completions",
          exchange -> {
            calls.incrementAndGet();
            key.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request =
                Json.parse(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!request.path("stream").asBoolean()) {
              byte[] bytes =
                  "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
              exchange.close();
              return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
              var value =
                  Json.obj(
                      "content",
                      "逐步收到😀回答",
                      "evidence",
                      Json.arr(),
                      "uncertainties",
                      Json.arr(),
                      "memoryActions",
                      Json.arr()
                          .add(
                              Json.obj(
                                  "action",
                                  "ADD",
                                  "content",
                                  "我喜欢简短回答",
                                  "scope",
                                  "global",
                                  "sourceQuote",
                                  "我喜欢简短回答")),
                      "decisionCandidate",
                      Json.obj("choice", "我决定选方案甲", "quote", "我决定选方案甲"),
                      "suggestKnowledge",
                      true);
              String raw = value.toString();
              int split = raw.indexOf("回答") + 1;
              String first = raw.substring(0, split), rest = raw.substring(split);
              output.write(event(first, null).getBytes(StandardCharsets.UTF_8));
              output.flush();
              started.countDown();
              if (block) release.await(8, TimeUnit.SECONDS);
              if (!broken) {
                output.write(event(rest, "stop").getBytes(StandardCharsets.UTF_8));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
              }
              sent.countDown();
            } catch (Exception ignored) {
              sent.countDown();
            } finally {
              exchange.close();
            }
          });
      server.start();
      c.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String event(String text, String finish) {
      return "data: "
          + Json.obj(
              "model",
              "fixture-chat",
              "choices",
              Json.arr()
                  .add(
                      Json.obj(
                          "index", 0, "delta", Json.obj("content", text), "finish_reason", finish)))
          + "\n\n";
    }

    public void close() {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void streamEventsSnapshotAndFrozenCredentialCommitOnce() throws Exception {
    try (var fixture = new UpstreamFixture(true, false)) {
      var convo = conversation("我喜欢简短回答。我决定选方案甲。");
      String id = convo.path("job").path("id").asText();
      CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOne);
      assertTrue(fixture.started.await(5, TimeUnit.SECONDS));
      until(() -> !s.get("job", id).path("partialContent").asText().isEmpty());
      var before = events.events(id, 0);
      assertEquals("running", before.path("job").path("status").asText());
      assertTrue(before.path("job").path("partialContent").asText().startsWith("逐步收到😀"));
      assertFalse(before.toString().contains("memoryActions"));
      settings.update(
          Json.obj(
              "provider", "deepseek", "model", "changed-model", "apiKey", "synthetic-new-key"));
      fixture.release.countDown();
      assertTrue(running.get(5, TimeUnit.SECONDS));
      assertEquals("succeeded", state(id));
      assertEquals("Bearer synthetic-old-key", fixture.key.get());
      assertEquals(1, s.all("memory", null).size());
      assertEquals(1, s.all("decision", null).size());
      assertEquals(
          1,
          s.db.queryForObject(
              "SELECT count(*) FROM jobs WHERE input->>'operation'='knowledge_proposal'",
              Integer.class));
      assertEquals("succeeded", events.cancel(id).path("status").asText());
      assertEquals(
          1,
          s.all("message", convo.path("problem").path("id").asText()).stream()
              .filter(m -> m.path("role").asText().equals("assistant"))
              .count());
      var later = events.events(id, before.path("nextCursor").asLong());
      assertTrue(later.path("items").size() > 0);
      assertFalse(s.get("job", id).toString().contains("synthetic-old-key"));
    }
  }

  @Test
  void cancelWinsBeforeCommitAndPreventsAllLateSideEffects() throws Exception {
    try (var fixture = new UpstreamFixture(true, false)) {
      var convo = conversation("我喜欢简短回答。我决定选方案甲。");
      String id = convo.path("job").path("id").asText();
      CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOne);
      assertTrue(fixture.started.await(5, TimeUnit.SECONDS));
      until(() -> !s.get("job", id).path("partialContent").asText().isEmpty());
      s.write(
          () -> {
            fixture.release.countDown();
            try {
              assertTrue(fixture.sent.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            events.cancel(id);
            return null;
          });
      assertTrue(running.get(5, TimeUnit.SECONDS));
      assertEquals("cancelled", state(id));
      assertFalse(s.get("job", id).path("partialContent").asText().isEmpty());
      assertEquals(1, s.all("message", null).size());
      assertTrue(s.all("memory", null).isEmpty());
      assertTrue(s.all("decision", null).isEmpty());
      assertTrue(s.all("knowledge_proposal", null).isEmpty());
      assertEquals("cancelled", events.cancel(id).path("status").asText());
    }
  }

  @Test
  void cancellationAfterParsedResponseStillWinsBeforeBusinessCommit() throws Exception {
    var parsed = new CountDownLatch(1);
    var allowReturn = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              parsed.countDown();
              assertTrue(allowReturn.await(5, TimeUnit.SECONDS));
              return result;
            })
        .when(model)
        .call(
            org.mockito.ArgumentMatchers.any(Model.Context.class),
            org.mockito.ArgumentMatchers.eq(false));
    try (var fixture = new UpstreamFixture(false, false)) {
      var convo = conversation("我喜欢简短回答。我决定选方案甲。");
      String id = convo.path("job").path("id").asText();
      CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOne);
      assertTrue(parsed.await(5, TimeUnit.SECONDS));
      events.cancel(id);
      allowReturn.countDown();
      assertTrue(running.get(5, TimeUnit.SECONDS));
      assertEquals("cancelled", state(id));
      assertEquals(1, s.all("message", null).size());
      assertTrue(s.all("memory", null).isEmpty());
      assertTrue(s.all("decision", null).isEmpty());
      assertEquals(
          0,
          s.db.queryForObject(
              "SELECT count(*) FROM jobs WHERE input->>'operation'='knowledge_proposal'",
              Integer.class));
    } finally {
      allowReturn.countDown();
      org.mockito.Mockito.reset(model);
    }
  }

  @Test
  void cancelInterruptsBlockedBodyAndQueuedCancelNeverCallsUpstream() throws Exception {
    try (var fixture = new UpstreamFixture(true, false)) {
      var convo = conversation("我喜欢简短回答。我决定选方案甲。");
      String id = convo.path("job").path("id").asText();
      CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOne);
      assertTrue(fixture.started.await(5, TimeUnit.SECONDS));
      events.cancel(id);
      assertTrue(running.get(3, TimeUnit.SECONDS));
      assertEquals("cancelled", state(id));
      var queued = conversation("排队后取消");
      events.cancel(queued.path("job").path("id").asText());
      assertFalse(worker.runOne());
      assertEquals(1, fixture.calls.get());
    }
  }

  @Test
  void brokenStreamHasRecoverablePartialAndNoBusinessWrites() throws Exception {
    try (var fixture = new UpstreamFixture(false, true)) {
      var convo = conversation("我喜欢简短回答。我决定选方案甲。");
      String id = convo.path("job").path("id").asText();
      worker.runOne();
      assertEquals("failed", state(id));
      assertEquals(
          "UPSTREAM_STREAM_INTERRUPTED", s.get("job", id).path("error").path("code").asText());
      assertFalse(s.get("job", id).path("partialContent").asText().isEmpty());
      assertEquals(1, s.all("message", null).size());
      assertTrue(s.all("memory", null).isEmpty());
    }
  }

  @Test
  void settingsCatalogTemporaryTestKeyAndClearAreAccountScoped() throws Exception {
    try (var fixture = new UpstreamFixture(false, false)) {
      var body =
          Json.obj(
              "provider", "deepseek", "model", "fixture-chat", "apiKey", "synthetic-transient-key");
      String testKey = Json.id();
      var tested =
          req(
              post("/api/v1/settings/model/test").header("Idempotency-Key", testKey),
              owner,
              body,
              200);
      assertTrue(tested.path("ok").asBoolean());
      assertFalse(tested.toString().contains("synthetic-transient-key"));
      req(post("/api/v1/settings/model/test").header("Idempotency-Key", testKey), owner, body, 200);
      assertEquals(1, fixture.calls.get());
      assertEquals("synthetic-old-key", settings.profile(null).key());
      var saved = req(put("/api/v1/settings/model"), owner, body, 200);
      assertFalse(saved.toString().contains("synthetic-transient-key"));
      assertFalse(
          s.db
              .queryForObject(
                  "SELECT data::text FROM model_settings WHERE account_id=?::uuid",
                  String.class,
                  AccountScope.ADMIN)
              .contains("synthetic-transient-key"));
      assertEquals(
          "provider",
          req(get("/api/v1/settings/models?provider=deepseek"), owner, null, 200)
              .path("source")
              .asText());
      req(delete("/api/v1/settings/model/key?provider=deepseek"), owner, null, 200);
      assertFalse(settings.view().path("configured").asBoolean());
      assertEquals("", settings.profile(null).key());
    }
  }

  @Test
  void memberWithoutKeyDoesNotCallAdminModelAndCannotReadEvents() throws Exception {
    try (var fixture = new UpstreamFixture(false, false)) {
      var ownerConvo = conversation("管理员独有合成内容");
      var member =
          postJson(
              "/auth/register",
              null,
              Json.obj(
                  "username",
                  "member_" + Json.id().substring(0, 8),
                  "password",
                  "synthetic-password-123"),
              201);
      String token = member.path("accessToken").asText();
      assertFalse(
          req(get("/api/v1/settings/model"), token, null, 200).path("configured").asBoolean());
      var convo = postJson("/conversations", token, Json.obj("content", "成员没有配置"), 202);
      events.cancel(ownerConvo.path("job").path("id").asText());
      worker.runOne();
      var job = req(get("/api/v1/jobs/" + convo.path("job").path("id").asText()), token, null, 200);
      assertEquals("MODEL_NOT_CONFIGURED", job.path("error").path("code").asText());
      assertEquals(0, fixture.calls.get());
      String ownerJob = ownerConvo.path("job").path("id").asText();
      req(get("/api/v1/jobs/" + ownerJob + "/events"), token, null, 404);
      postJson("/jobs/" + ownerJob + "/cancel", token, Json.obj(), 404);
      assertTrue(
          req(get("/api/v1/conversations/search?q=管理员"), token, null, 200).path("items").isEmpty());
    }
  }

  @Test
  void metadataSearchConflictAndDeletedHistoryAreConsistent() throws Exception {
    var convo = conversation("搜索针：绿色计划");
    String problem = convo.path("problem").path("id").asText();
    String id = convo.path("job").path("id").asText();
    long revision = s.version();
    var edited =
        req(
            patch("/api/v1/problems/" + problem),
            owner,
            Json.obj("pinned", true, "group", "生活"),
            200);
    assertTrue(edited.path("pinned").asBoolean());
    assertEquals(revision, s.version());
    postJson("/problems/" + problem + "/messages", owner, Json.obj("content", "重复在途"), 409);
    assertEquals(1, s.all("message", null).size());
    var results = req(get("/api/v1/conversations/search?q=绿色&limit=1"), owner, null, 200);
    assertEquals(1, results.path("items").size());
    assertTrue(results.hasNonNull("nextCursor"));
    req(
        get("/api/v1/conversations/search")
            .param("q", "另一查询")
            .param("cursor", results.path("nextCursor").asText()),
        owner,
        null,
        400);
    assertEquals(
        1, req(get("/api/v1/jobs?kind=chat&status=active"), owner, null, 200).path("items").size());
    req(delete("/api/v1/problems/" + problem), owner, null, 204);
    req(get("/api/v1/jobs/" + id + "/events"), owner, null, 404);
    assertTrue(
        req(get("/api/v1/conversations/search?q=绿色"), owner, null, 200).path("items").isEmpty());
  }
}
