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
class MemoryOverviewTest {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired Auth auth;
  @Autowired Config c;
  @Autowired Worker worker;
  String owner;
  String originalModelName;

  @BeforeEach
  void reset() {
    String url = System.getenv("TEST_DATABASE_URL");
    assertNotNull(url);
    assertTrue(url.endsWith("/decision_backend_test"), "Never reset a non-test database");
    s.db.execute(
        "TRUNCATE"
            + " records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    s.db.update("UPDATE chat_memory_state SET epoch=0");
    c.mock = false;
    c.modelKey = "";
    c.modelBase = "https://api.deepseek.com";
    originalModelName = c.modelName;
    owner = auth.token("owner", Instant.now().plusSeconds(600));
  }

  @AfterEach
  void restoreConfig() {
    c.modelName = originalModelName;
    c.mock = false;
    c.modelKey = "";
    c.modelBase = "https://api.deepseek.com";
  }

  JsonNode request(MockHttpServletRequestBuilder builder, String token, JsonNode body, int status)
      throws Exception {
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body != null) builder.contentType("application/json").content(body.toString());
    var response = mvc.perform(builder).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return response.getContentAsString().isBlank()
        ? Json.obj()
        : Json.parse(response.getContentAsString());
  }

  JsonNode overview(String problem) throws Exception {
    return request(
        get("/api/v1/memories/overview" + (problem == null ? "" : "?problemId=" + problem)),
        owner,
        null,
        200);
  }

  ObjectNode body(JsonNode overview, String problem) {
    ObjectNode body = Json.obj("fingerprint", overview.path("fingerprint").asText());
    if (problem != null) body.put("problemId", problem);
    return body;
  }

  JsonNode start(ObjectNode body) throws Exception {
    return start(body, Json.id(), 202);
  }

  JsonNode start(ObjectNode body, String key, int status) throws Exception {
    return request(
        post("/api/v1/memories/overview").header("Idempotency-Key", key), owner, body, status);
  }

  JsonNode job(String id) throws Exception {
    return request(get("/api/v1/jobs/" + id), owner, null, 200);
  }

  int jobCount() {
    return s.db.queryForObject("SELECT count(*) FROM jobs", Integer.class);
  }

  String problem() {
    return s.write(
            () ->
                s.create(
                    "problem",
                    null,
                    Json.obj(
                        "title",
                        "合成概览场景",
                        "goal",
                        "",
                        "constraints",
                        Json.arr(),
                        "unknowns",
                        Json.arr())))
        .path("id")
        .asText();
  }

  ObjectNode memory(String problem, String scope, String content) {
    return memory(problem, scope, content, "user_reported", "active");
  }

  ObjectNode memory(String problem, String scope, String content, String kind, String status) {
    ObjectNode data =
        Json.obj(
            "problemId",
            problem,
            "content",
            content,
            "kind",
            kind,
            "status",
            status,
            "revision",
            1,
            "origin",
            "manual",
            "evidence",
            Json.arr());
    if (scope != null) data.put("scope", scope);
    return s.write(() -> s.create("memory", problem, data));
  }

  JsonNode correct(ObjectNode memory, String content) throws Exception {
    return request(
        patch("/api/v1/memories/" + memory.path("id").asText()),
        owner,
        Json.obj("expectedRevision", memory.path("revision").asInt(), "content", content),
        200);
  }

  static JsonNode input(JsonNode request) {
    return Json.parse(request.path("messages").get(1).path("content").asText());
  }

  static ObjectNode item(JsonNode memory, String summary) {
    String scope = memory.path("scope").asText("problem");
    return Json.obj(
        "summary",
        summary,
        "scope",
        scope,
        "problemId",
        scope.equals("global") ? null : memory.path("problemId"),
        "memoryRefs",
        Json.arr().add(Json.obj("id", memory.path("id"), "revision", memory.path("revision"))));
  }

  static ObjectNode validAnswer(JsonNode request) {
    JsonNode first = input(request).path("memories").get(0);
    return Json.obj("items", Json.arr().add(item(first, "合成概览重点")));
  }

  final class Fixture implements AutoCloseable {
    final HttpServer server;
    final List<JsonNode> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    volatile String rawResponse;

    Fixture(Function<JsonNode, JsonNode> answer) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            JsonNode request =
                Json.parse(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(request);
            JsonNode answerBody = answer.apply(request);
            byte[] bytes =
                Json.obj(
                        "model",
                        "synthetic-overview-http",
                        "usage",
                        Json.obj("prompt_tokens", 20, "completion_tokens", 10),
                        "choices",
                        Json.arr()
                            .add(
                                Json.obj(
                                    "finish_reason",
                                    "stop",
                                    "message",
                                    Json.obj("content", answerBody.toString()))))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (rawResponse != null) bytes = rawResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      c.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
      c.modelKey = "synthetic-overview-fixture-key";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  void emptyGetAndPostNeverQueueOrCallModel() throws Exception {
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      JsonNode empty = overview(null);
      assertEquals("empty", empty.path("status").asText());
      assertTrue(empty.path("items").isEmpty());
      assertTrue(empty.path("jobId").isNull());
      assertEquals(0, empty.path("coverage").path("eligibleCount").asInt());
      JsonNode result = start(body(empty, null));
      assertEquals("empty", result.path("status").asText());
      assertTrue(result.path("jobId").isNull());
      assertEquals(0, jobCount());
      assertFalse(worker.runOne());
      assertTrue(fixture.calls.isEmpty());
    }
  }

  @Test
  void generationIsCachedAndJobEndpointNeverExposesSummary() throws Exception {
    memory(problem(), "global", "合成测试偏好：仅在工作日不参加晚间会议。");
    try (Fixture fixture =
        new Fixture(
            request ->
                Json.obj(
                    "items",
                    Json.arr().add(item(input(request).path("memories").get(0), "仅工作日不参加晚间会议"))))) {
      JsonNode initial = overview(null);
      assertEquals("missing", initial.path("status").asText());
      assertEquals(0, jobCount());
      JsonNode accepted = start(body(initial, null));
      assertEquals("pending", accepted.path("status").asText());
      assertFalse(accepted.has("items"));
      String id = accepted.path("jobId").asText();
      assertTrue(worker.runOne());
      JsonNode done = job(id);
      assertEquals("succeeded", done.path("status").asText());
      assertEquals("memory_overview", done.path("kind").asText());
      assertEquals(Json.obj("fingerprint", initial.path("fingerprint")), done.path("result"));
      assertFalse(done.toString().contains("仅工作日不参加晚间会议"));
      for (int i = 0; i < 3; i++) {
        JsonNode ready = overview(null);
        assertEquals("ready", ready.path("status").asText());
        assertEquals("仅工作日不参加晚间会议", ready.path("items").get(0).path("summary").asText());
        assertEquals(id, start(body(ready, null)).path("jobId").asText());
      }
      assertEquals(1, jobCount());
      assertEquals(1, fixture.calls.size());
      assertEquals("disabled", fixture.calls.get(0).path("thinking").path("type").asText());
      assertTrue(fixture.calls.get(0).path("max_tokens").asInt() <= 4000);
    }
  }

  @Test
  void differentIdempotencyKeysAndConcurrentRequestsShareTheInflightJob() throws Exception {
    memory(problem(), "global", "合成概览并发原话");
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      ObjectNode requestBody = body(overview(null), null);
      ExecutorService pool = Executors.newFixedThreadPool(4);
      try {
        List<Future<JsonNode>> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) requests.add(pool.submit(() -> start(requestBody)));
        Set<String> ids = new HashSet<>();
        for (Future<JsonNode> request : requests)
          ids.add(request.get(10, TimeUnit.SECONDS).path("jobId").asText());
        assertEquals(1, ids.size());
        assertEquals(1, jobCount());
        assertTrue(fixture.calls.isEmpty());
        assertTrue(worker.runOne());
        assertFalse(worker.runOne());
        assertEquals(1, fixture.calls.size());
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void correctionAndDeletionHideOldCacheAndRejectEvenAnOldIdempotencyReplay() throws Exception {
    ObjectNode memory = memory(problem(), "global", "旧合成条件：不参加晚间会议");
    try (Fixture fixture =
        new Fixture(
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(item(input(request).path("memories").get(0), "旧合成摘要：不参加晚间会议"))))) {
      JsonNode old = overview(null);
      ObjectNode originalBody = body(old, null);
      String key = Json.id();
      String id = start(originalBody, key, 202).path("jobId").asText();
      assertTrue(worker.runOne());
      assertEquals("ready", overview(null).path("status").asText());
      correct(memory, "新合成条件：只在周末参加晚间会议");
      JsonNode changed = overview(null);
      assertEquals("missing", changed.path("status").asText());
      assertTrue(changed.path("items").isEmpty());
      assertNotEquals(old.path("fingerprint"), changed.path("fingerprint"));
      assertEquals(
          "OVERVIEW_CHANGED", start(originalBody, key, 409).path("error").path("code").asText());
      assertEquals(
          "OVERVIEW_CHANGED",
          start(originalBody, Json.id(), 409).path("error").path("code").asText());
      JsonNode oldJob = job(id);
      assertFalse(oldJob.toString().contains("旧合成摘要"));
      assertFalse(oldJob.path("result").has("items"));
      request(delete("/api/v1/memories/" + memory.path("id").asText()), owner, null, 204);
      JsonNode empty = overview(null);
      assertEquals("empty", empty.path("status").asText());
      assertTrue(empty.path("items").isEmpty());
      assertEquals(1, fixture.calls.size());
      assertEquals(1, jobCount());
    }
  }

  @Test
  void memoryChangeDuringModelCallCannotPublishStaleSummaryOrHoldWriteLock() throws Exception {
    ObjectNode memory = memory(problem(), "global", "模型调用前的合成原话");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Fixture fixture =
        new Fixture(
            request -> {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("Fixture not released");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return Json.obj(
                  "items", Json.arr().add(item(input(request).path("memories").get(0), "在途旧摘要")));
            })) {
      JsonNode before = overview(null);
      String id = start(body(before, null)).path("jobId").asText();
      Future<Boolean> running = pool.submit(() -> worker.runOne());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<JsonNode> changed = pool.submit(() -> correct(memory, "模型调用期间纠正后的合成原话"));
      assertNotNull(changed.get(3, TimeUnit.SECONDS), "Model I/O must not hold the write lock");
      assertTrue(overview(null).path("items").isEmpty());
      release.countDown();
      assertTrue(running.get(10, TimeUnit.SECONDS));
      assertEquals("failed", job(id).path("status").asText());
      assertFalse(job(id).toString().contains("在途旧摘要"));
      JsonNode current = overview(null);
      assertNotEquals(before.path("fingerprint"), current.path("fingerprint"));
      assertEquals("missing", current.path("status").asText());
      assertTrue(current.path("items").isEmpty());
      assertEquals(1, fixture.calls.size());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void retryableFailureRequiresExplicitRetryAndReusesItsReplacement() throws Exception {
    memory(problem(), "global", "合成失败重试偏好");
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      fixture.status.set(503);
      ObjectNode body = body(overview(null), null);
      String first = start(body).path("jobId").asText();
      assertTrue(worker.runOne());
      JsonNode failed = overview(null);
      assertEquals("failed", failed.path("status").asText());
      assertTrue(failed.path("error").path("retryable").asBoolean());
      assertTrue(failed.path("items").isEmpty());
      assertEquals(first, start(body).path("jobId").asText());
      assertEquals(first, start(body).path("jobId").asText());
      assertEquals(1, jobCount());
      assertEquals(1, fixture.calls.size());
      fixture.status.set(200);
      ObjectNode retry = body.deepCopy().put("retry", true);
      String replacement = start(retry).path("jobId").asText();
      assertNotEquals(first, replacement);
      assertEquals(replacement, start(retry).path("jobId").asText());
      assertEquals(2, jobCount());
      assertTrue(worker.runOne());
      assertEquals("ready", overview(null).path("status").asText());
      assertEquals(2, fixture.calls.size());
    }
  }

  @Test
  void nonRetryableUpstreamFormatFailureCannotCreateAnAutomaticOrExplicitRetryLoop()
      throws Exception {
    memory(problem(), "global", "合成格式错误输入");
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      fixture.rawResponse = "this is not a JSON response";
      ObjectNode body = body(overview(null), null);
      String id = start(body).path("jobId").asText();
      assertTrue(worker.runOne());
      JsonNode failed = overview(null);
      assertEquals("failed", failed.path("status").asText());
      assertEquals("UPSTREAM_FORMAT", failed.path("error").path("code").asText());
      assertFalse(failed.path("error").path("retryable").asBoolean());
      assertEquals(id, start(body).path("jobId").asText());
      assertEquals(id, start(body.deepCopy().put("retry", true)).path("jobId").asText());
      assertEquals(1, jobCount());
      assertEquals(1, fixture.calls.size());
    }
  }

  @Test
  void missingKeyCanRecoverWithExplicitRetryWithoutChangingFingerprint() throws Exception {
    memory(problem(), "global", "合成配置恢复偏好");
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      c.modelKey = "";
      JsonNode initial = overview(null);
      ObjectNode requestBody = body(initial, null);
      String failedId = start(requestBody).path("jobId").asText();
      assertTrue(worker.runOne());
      JsonNode failed = overview(null);
      assertEquals("failed", failed.path("status").asText());
      assertEquals("MODEL_NOT_CONFIGURED", failed.path("error").path("code").asText());
      assertTrue(failed.path("error").path("retryable").asBoolean());
      assertTrue(fixture.calls.isEmpty());

      c.modelKey = "synthetic-overview-fixture-key";
      assertEquals(initial.path("fingerprint"), overview(null).path("fingerprint"));
      assertEquals(failedId, start(requestBody).path("jobId").asText());
      assertEquals(1, jobCount());
      assertFalse(worker.runOne());
      String newId = start(requestBody.deepCopy().put("retry", true)).path("jobId").asText();
      assertNotEquals(failedId, newId);
      assertTrue(worker.runOne());
      assertEquals("ready", overview(null).path("status").asText());
      assertEquals(2, jobCount());
      assertEquals(1, fixture.calls.size());
    }
  }

  @Test
  void repairedUpstreamCredentialsRequireExplicitRetryOfTheSameFingerprint() throws Exception {
    memory(problem(), "global", "合成外部凭据恢复偏好");
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      fixture.status.set(401);
      JsonNode initial = overview(null);
      ObjectNode requestBody = body(initial, null);
      String first = start(requestBody).path("jobId").asText();
      assertTrue(worker.runOne());
      JsonNode failed = overview(null);
      assertEquals("failed", failed.path("status").asText());
      assertTrue(failed.path("error").path("retryable").asBoolean());
      assertEquals(1, fixture.calls.size());

      fixture.status.set(200);
      c.modelKey = "synthetic-repaired-fixture-key";
      assertEquals(initial.path("fingerprint"), overview(null).path("fingerprint"));
      assertEquals(first, start(requestBody).path("jobId").asText());
      assertFalse(worker.runOne());
      String replacement = start(requestBody.deepCopy().put("retry", true)).path("jobId").asText();
      assertNotEquals(first, replacement);
      assertTrue(worker.runOne());
      assertEquals("ready", overview(null).path("status").asText());
      assertEquals(2, fixture.calls.size());
    }
  }

  @Test
  void eligibilityAndProblemScopeExcludeInferredObservedRetractedDeletedAndForeignLocal()
      throws Exception {
    String one = problem(), two = problem();
    ObjectNode global = memory(two, "global", "全局合成偏好");
    ObjectNode local = memory(one, "problem", "本问题合成条件");
    ObjectNode legacy = memory(one, null, "旧缺scope的本问题条件");
    memory(two, "problem", "另一个问题的条件");
    memory(one, "global", "模型推断", "inferred", "active");
    memory(one, "global", "观察线索", "observed", "active");
    memory(one, "global", "已忘记条件", "user_reported", "retracted");
    ObjectNode removed = memory(one, "global", "已删除条件");
    s.db.update("UPDATE records SET deleted=true WHERE id=?::uuid", removed.path("id").asText());
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      JsonNode scoped = overview(one);
      assertEquals(3, scoped.path("coverage").path("eligibleCount").asInt());
      assertEquals(4, overview(null).path("coverage").path("eligibleCount").asInt());
      start(body(scoped, one));
      assertTrue(worker.runOne());
      JsonNode supplied = input(fixture.calls.get(0)).path("memories");
      Set<String> ids = new HashSet<>();
      supplied.forEach(m -> ids.add(m.path("id").asText()));
      assertEquals(
          Set.of(global.path("id").asText(), local.path("id").asText(), legacy.path("id").asText()),
          ids);
      assertEquals("global", supplied.get(0).path("scope").asText());
      supplied.forEach(
          m -> {
            if (m.path("id").equals(legacy.path("id")))
              assertEquals("problem", m.path("scope").asText());
          });
    }
  }

  @Test
  void validGroupsPreserveNegationConditionsAndDoNotWriteBackToMemories() throws Exception {
    String p = problem();
    ObjectNode first = memory(p, "global", "合成偏好：工作日不安排晚间会议");
    ObjectNode second = memory(p, "global", "合成偏好：仅在工作日拒绝晚间会议");
    ObjectNode local = memory(p, "problem", "合成条件：此项目预算不超过300元");
    List<ObjectNode> originals = s.all("memory", null);
    try (Fixture fixture =
        new Fixture(
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(
                            Json.obj(
                                "summary",
                                "工作日不安排晚间会议",
                                "scope",
                                "global",
                                "problemId",
                                null,
                                "memoryRefs",
                                Json.arr()
                                    .add(Json.obj("id", first.path("id"), "revision", 1))
                                    .add(Json.obj("id", second.path("id"), "revision", 1))))
                        .add(item(local, "此项目预算不超过300元"))))) {
      start(body(overview(null), null));
      assertTrue(worker.runOne());
      JsonNode ready = overview(null);
      assertEquals("ready", ready.path("status").asText());
      assertEquals(2, ready.path("items").size());
      assertEquals("工作日不安排晚间会议", ready.path("items").get(0).path("summary").asText());
      assertEquals("此项目预算不超过300元", ready.path("items").get(1).path("summary").asText());
      assertEquals(originals, s.all("memory", null));
      assertEquals(0, s.version());
      assertEquals(1, fixture.calls.size());
    }
  }

  @Test
  void invalidReferencesScopesVersionsDuplicatesAndShapesFailClosed() throws Exception {
    String one = problem(), two = problem();
    ObjectNode global = memory(one, "global", "全局合成条件");
    ObjectNode localOne = memory(one, "problem", "本问题合成条件");
    ObjectNode localTwo = memory(two, "problem", "外部问题合成条件");
    List<Function<JsonNode, JsonNode>> invalid =
        List.of(
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(
                            item(global, "错误ID")
                                .set(
                                    "memoryRefs",
                                    Json.arr().add(Json.obj("id", Json.id(), "revision", 1))))),
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(
                            item(global, "错误版本")
                                .set(
                                    "memoryRefs",
                                    Json.arr()
                                        .add(Json.obj("id", global.path("id"), "revision", 999))))),
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(item(global, "跨scope").put("scope", "problem").put("problemId", one))),
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(item(localOne, "错误全局").put("scope", "global").putNull("problemId"))),
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(
                            item(localOne, "跨问题")
                                .set(
                                    "memoryRefs",
                                    Json.arr()
                                        .add(Json.obj("id", localOne.path("id"), "revision", 1))
                                        .add(Json.obj("id", localTwo.path("id"), "revision", 1))))),
            request ->
                Json.obj(
                    "items", Json.arr().add(item(localOne, "跨组重复")).add(item(localOne, "重复的第二组"))),
            request ->
                Json.obj(
                    "items", Json.arr().add(item(localOne, "无依据").set("memoryRefs", Json.arr()))),
            request -> Json.obj("items", Json.arr().add(item(localOne, "x".repeat(81)))),
            request -> Json.obj("items", Json.arr().add(item(localOne, ""))),
            request -> Json.obj("items", Json.arr().add(item(localOne, "数值摘要").put("summary", 17))),
            request ->
                Json.obj(
                    "items",
                    Json.arr()
                        .add(item(localOne, "1"))
                        .add(item(localOne, "2"))
                        .add(item(localOne, "3"))
                        .add(item(localOne, "4"))
                        .add(item(localOne, "5"))
                        .add(item(localOne, "6"))),
            request -> Json.arr());
    AtomicInteger index = new AtomicInteger();
    try (Fixture fixture = new Fixture(request -> invalid.get(index.get()).apply(request))) {
      for (int i = 0; i < invalid.size(); i++) {
        index.set(i);
        // Each invalid output gets a new fingerprint; failed caches are deliberately not retried.
        final int revision = i + 2;
        s.write(
            () -> {
              ObjectNode changed = s.get("memory", global.path("id").asText());
              changed.put("revision", revision);
              s.save("memory", changed);
              return null;
            });
        String id = start(body(overview(null), null)).path("jobId").asText();
        assertTrue(worker.runOne());
        assertEquals("failed", job(id).path("status").asText(), "case " + i);
        JsonNode failed = overview(null);
        assertEquals("failed", failed.path("status").asText(), "case " + i);
        assertEquals("MODEL_SCHEMA", failed.path("error").path("code").asText(), "case " + i);
        assertTrue(failed.path("items").isEmpty(), "case " + i);
      }
      assertEquals(invalid.size(), fixture.calls.size());
    }
  }

  @Test
  void recordBudgetIsWholeEntryAndFingerprintIncludesEligibleRecordsOutsideBudget()
      throws Exception {
    String p = problem();
    Map<String, String> originals = new HashMap<>();
    for (int i = 0; i < 4; i++) {
      String content = "记录" + i + "字".repeat(9997);
      ObjectNode m = memory(p, "problem", content);
      originals.put(m.path("id").asText(), content);
    }
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      JsonNode before = overview(null);
      assertEquals(4, before.path("coverage").path("eligibleCount").asInt());
      assertEquals(3, before.path("coverage").path("consideredCount").asInt());
      assertTrue(before.path("coverage").path("truncated").asBoolean());
      start(body(before, null));
      assertTrue(worker.runOne());
      JsonNode supplied = input(fixture.calls.get(0)).path("memories");
      assertEquals(3, supplied.size());
      Set<String> suppliedIds = new HashSet<>();
      int chars = 0;
      for (JsonNode m : supplied) {
        String id = m.path("id").asText();
        suppliedIds.add(id);
        assertEquals(originals.get(id), m.path("content").asText());
        chars += m.path("content").asText().length();
      }
      assertTrue(chars <= 32000);
      String omitted =
          originals.keySet().stream()
              .filter(id -> !suppliedIds.contains(id))
              .findFirst()
              .orElseThrow();
      ObjectNode old = s.get("memory", omitted);
      correct(old, "预算外记录也发生了合成纠正");
      JsonNode after = overview(null);
      assertNotEquals(before.path("fingerprint"), after.path("fingerprint"));
      assertTrue(after.path("items").isEmpty());
      assertEquals(1, fixture.calls.size());
    }
  }

  @Test
  void hundredRecordLimitPrioritizesGlobalAndReportsCoverage() throws Exception {
    String p = problem();
    ObjectNode global = memory(p, "global", "最早创建的全局合成偏好");
    for (int i = 0; i < 100; i++) memory(p, "problem", "较新局部合成条件" + i);
    try (Fixture fixture = new Fixture(MemoryOverviewTest::validAnswer)) {
      JsonNode before = overview(null);
      assertEquals(101, before.path("coverage").path("eligibleCount").asInt());
      assertEquals(100, before.path("coverage").path("consideredCount").asInt());
      assertTrue(before.path("coverage").path("truncated").asBoolean());
      start(body(before, null));
      assertTrue(worker.runOne());
      JsonNode supplied = input(fixture.calls.get(0)).path("memories");
      assertEquals(100, supplied.size());
      assertEquals(global.path("id"), supplied.get(0).path("id"));
    }
  }

  @Test
  void modelAndModeChangesInvalidateFingerprintWithoutCallingTheModel() throws Exception {
    memory(problem(), "global", "合成模型配置指纹测试");
    JsonNode before = overview(null);
    c.modelName = originalModelName + "-different-fixture";
    JsonNode modelChanged = overview(null);
    assertNotEquals(before.path("fingerprint"), modelChanged.path("fingerprint"));
    c.modelName = originalModelName;
    c.mock = true;
    assertNotEquals(before.path("fingerprint"), overview(null).path("fingerprint"));
    assertEquals(0, jobCount());
  }

  @Test
  void ownerAuthenticationAndDeviceBoundaryApplyToBothOverviewRoutes() throws Exception {
    memory(problem(), "global", "不能向设备公开的合成概览");
    ObjectNode body = body(overview(null), null);
    request(get("/api/v1/memories/overview"), null, null, 401);
    request(
        post("/api/v1/memories/overview").header("Idempotency-Key", Json.id()), null, body, 401);
    ObjectNode device =
        s.write(() -> s.create("device", null, Json.obj("name", "isolated-overview-device")));
    String token =
        auth.token("device:" + device.path("id").asText(), Instant.now().plusSeconds(600));
    request(get("/api/v1/memories/overview"), token, null, 403);
    request(
        post("/api/v1/memories/overview").header("Idempotency-Key", Json.id()), token, body, 403);
    assertEquals(0, jobCount());
  }

  @Test
  void queuedOverviewDoesNotTakePriorityOverANewChat() throws Exception {
    c.mock = true;
    memory(problem(), "global", "合成队列优先级偏好");
    String overviewId = start(body(overview(null), null)).path("jobId").asText();
    JsonNode conversation =
        request(
            post("/api/v1/conversations").header("Idempotency-Key", Json.id()),
            owner,
            Json.obj("content", "【虚构测试】先处理聊天"),
            202);
    String chatId = conversation.path("job").path("id").asText();
    assertTrue(worker.runOne());
    assertEquals("succeeded", job(chatId).path("status").asText());
    assertEquals("queued", job(overviewId).path("status").asText());
  }
}
