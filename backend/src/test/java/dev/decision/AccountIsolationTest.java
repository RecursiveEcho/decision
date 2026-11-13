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
import java.util.concurrent.CopyOnWriteArrayList;
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
class AccountIsolationTest {
  @Autowired MockMvc mvc;
  @Autowired Store store;
  @Autowired Config config;
  @Autowired Auth auth;
  @Autowired Worker worker;
  @org.springframework.boot.test.mock.mockito.SpyBean ModelSettings settings;

  static final String API = "/api/v1";
  static final String PASSWORD = " synthetic passphrase  ";
  static final String PRIVATE_SOURCE = "仅甲账号的合成资料：红色合成信标";
  static final String PRIVATE_PREFERENCE = "我喜欢简短回答";
  static final String PRIVATE_KNOWLEDGE = "仅甲账号的合成知识条目";

  @BeforeEach
  void reset() {
    assertNotNull(System.getenv("TEST_DATABASE_URL"));
    assertTrue(System.getenv("TEST_DATABASE_URL").endsWith("/decision_backend_test"));
    store.db.execute(
        "TRUNCATE"
            + " records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones"
            + " CASCADE");
    config.mock = false;
    config.modelKey = "";
    config.wikiBase = "";
    config.wikiToken = "";
  }

  @AfterEach
  void restoreConfig() {
    config.mock = false;
    config.modelKey = "";
    config.modelBase = "https://api.deepseek.com";
    config.wikiBase = "";
    config.wikiToken = "";
  }

  JsonNode call(MockHttpServletRequestBuilder request, String token, JsonNode body, int status)
      throws Exception {
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.contentType("application/json").content(body.toString());
    var response = mvc.perform(request).andReturn().getResponse();
    assertEquals(
        status,
        response.getStatus(),
        () ->
            response.getErrorMessage()
                + " "
                + new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    byte[] bytes = response.getContentAsByteArray();
    return bytes.length == 0 ? Json.obj() : Json.M.readTree(bytes);
  }

  JsonNode read(String path, String token) throws Exception {
    return call(get(API + path), token, null, 200);
  }

  JsonNode create(String path, String token, JsonNode body, int status, String key)
      throws Exception {
    return call(post(API + path).header("Idempotency-Key", key), token, body, status);
  }

  JsonNode create(String path, String token, JsonNode body, int status) throws Exception {
    return create(path, token, body, status, Json.id());
  }

  String username() {
    return "case_" + Json.id().substring(0, 8);
  }

  JsonNode credentials(String name) {
    return Json.obj("username", name, "password", PASSWORD);
  }

  String register() throws Exception {
    JsonNode session = create("/auth/register", null, credentials(username()), 201);
    assertEquals("member", session.at("/account/role").asText());
    return session.path("accessToken").asText();
  }

  String problem(String token, String title) throws Exception {
    return create("/problems", token, Json.obj("title", title, "goal", "合成验证目标"), 201)
        .path("id")
        .asText();
  }

  String legacyToken() {
    return auth.token("owner", Instant.now().plusSeconds(600));
  }

  @Test
  void registerAutomaticallySignsInAndNeverConfusesMemberWithMigratedOwner() throws Exception {
    String name = username();
    ObjectNode mixedCase =
        Json.obj("username", name.toUpperCase(Locale.ROOT), "password", PASSWORD);
    String key = Json.id();
    JsonNode first = create("/auth/register", null, mixedCase, 201, key);
    JsonNode account = first.path("account");
    assertEquals(name, account.path("username").asText());
    assertEquals("member", account.path("role").asText());
    UUID.fromString(account.path("id").asText());
    assertTrue(Instant.parse(first.path("expiresAt").asText()).isAfter(Instant.now()));
    assertEquals(account, read("/auth/me", first.path("accessToken").asText()));
    assertEquals(first, create("/auth/register", null, mixedCase, 201, key));
    JsonNode duplicate = create("/auth/register", null, credentials(name), 409);
    assertEquals("ACCOUNT_UNAVAILABLE", duplicate.at("/error/code").asText());
    JsonNode signedIn = create("/auth/login", null, credentials(name), 200);
    assertEquals(account, signedIn.path("account"));
    call(get(API + "/auth/me"), null, null, 401);
    create("/auth/login", null, Json.obj("username", name, "password", PASSWORD.trim()), 401);
    create("/auth/login", null, Json.obj("username", username(), "password", PASSWORD), 401);
    create(
        "/auth/register",
        null,
        Json.obj("username", username(), "password", PASSWORD, "role", "admin"),
        400);
    create(
        "/auth/login",
        null,
        Json.obj("username", name, "password", PASSWORD, "accessCode", config.ownerCode),
        400);
    create("/auth/register", null, credentials("owner"), 409);
    JsonNode admin = read("/auth/me", legacyToken());
    assertEquals("admin", admin.path("role").asText());
    assertEquals("owner", admin.path("username").asText());
    assertNotEquals(admin.path("id"), account.path("id"));
    for (JsonNode body :
        List.of(
            Json.obj("accessCode", config.ownerCode),
            Json.obj("username", "owner", "password", config.ownerCode))) {
      JsonNode login = create("/auth/login", null, body, 200);
      assertEquals(admin, login.path("account"));
      assertEquals(admin, read("/auth/me", login.path("accessToken").asText()));
    }
    String name2 = username();
    create("/auth/register", null, credentials(name2), 201);
    List<String> hashes =
        store.db.queryForList(
            "SELECT password_hash FROM accounts WHERE username IN (?,?)",
            String.class,
            name,
            name2);
    assertEquals(2, hashes.size());
    assertNotEquals(hashes.get(0), hashes.get(1), "Identical passwords need different salts");
    assertFalse(hashes.contains(Auth.hash(PASSWORD)));
    assertTrue(hashes.stream().noneMatch(h -> h.contains(PASSWORD)));
    String fastGuess = Auth.hash(Json.canonical(mixedCase).toString());
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT count(*) FROM idempotency_keys WHERE request_hash=?",
            Integer.class,
            fastGuess));
  }

  @Test
  void onlyVerifiedLegacyAccessCodeCanReplayMigratedLoginReceipt() throws Exception {
    String key = Json.id(), marker = "legacy-access-code-verified", originalToken = legacyToken();
    JsonNode old =
        Json.obj(
            "accessToken", originalToken, "expiresAt", Instant.now().plusSeconds(600).toString());
    store.db.update(
        "INSERT INTO idempotency_keys(account_id,scope,key,request_hash,status,response)"
            + " VALUES (?::uuid,'anonymous:/api/v1/auth/login',?::uuid,?,200,?)",
        AccountScope.ADMIN,
        key,
        marker,
        auth.encrypt(old.toString()));
    create("/auth/login", null, Json.obj("accessCode", "wrong"), 401, key);
    create(
        "/auth/login", null, Json.obj("username", "owner", "password", config.ownerCode), 409, key);
    String hash = "SELECT request_hash FROM idempotency_keys WHERE key=?::uuid";
    assertEquals(marker, store.db.queryForObject(hash, String.class, key));
    JsonNode upgraded =
        create("/auth/login", null, Json.obj("accessCode", config.ownerCode), 200, key);
    assertEquals(originalToken, upgraded.path("accessToken").asText());
    assertEquals(read("/auth/me", originalToken), upgraded.path("account"));
    assertNotEquals(marker, store.db.queryForObject(hash, String.class, key));
    assertEquals(
        upgraded, create("/auth/login", null, Json.obj("accessCode", config.ownerCode), 200, key));
    for (String path :
        List.of("/api/v1/auth/login;extra=1", "/api/v1/auth//login", "/api/v1/%61uth/login")) {
      JsonNode error =
          call(
              post(java.net.URI.create(path)).header("Idempotency-Key", key),
              null,
              Json.obj("accessCode", config.ownerCode),
              400);
      assertEquals("INVALID_PATH", error.at("/error/code").asText());
    }
  }

  @Test
  void sameAnonymousKeyCannotReplayAnotherAccountsCredentials() throws Exception {
    String key = Json.id(), nameA = username(), nameB = username();
    JsonNode sessionA = create("/auth/register", null, credentials(nameA), 201, key);
    JsonNode sessionB = create("/auth/register", null, credentials(nameB), 201, key);
    assertNotEquals(sessionA.path("account"), sessionB.path("account"));
    String loginKey = Json.id();
    JsonNode loginA = create("/auth/login", null, credentials(nameA), 200, loginKey);
    JsonNode loginB = create("/auth/login", null, credentials(nameB), 200, loginKey);
    assertEquals(sessionA.path("account"), read("/auth/me", loginA.path("accessToken").asText()));
    assertEquals(sessionB.path("account"), read("/auth/me", loginB.path("accessToken").asText()));
    create(
        "/auth/login",
        null,
        Json.obj("username", nameA, "password", "an incorrect synthetic password"),
        401,
        loginKey);
    create(
        "/auth/register",
        null,
        Json.obj("username", nameA, "password", "an incorrect synthetic password"),
        409,
        key);
    assertEquals(loginB, create("/auth/login", null, credentials(nameB), 200, loginKey));
  }

  @Test
  void invalidNamesAndPasswordsCannotCreateAccounts() throws Exception {
    for (String name : List.of("ab", "_".repeat(33), ".synthetic", "Kelvin", "合成用户"))
      create("/auth/register", null, credentials(name), 400);
    for (String password : List.of("x".repeat(11), "x".repeat(129)))
      create("/auth/register", null, Json.obj("username", username(), "password", password), 400);
    call(post(API + "/auth/register"), null, credentials(username()), 400);
  }

  @Test
  void registrationAndBadLoginsAreRateLimited() throws Exception {
    String name = username();
    create("/auth/register", null, credentials(name), 201);
    for (int i = 1; i < 10; i++) create("/auth/register", null, credentials(name), 409);
    create("/auth/register", null, credentials(username()), 429);
    for (int i = 0; i < 10; i++)
      create(
          "/auth/login",
          null,
          Json.obj("username", name, "password", "synthetic-invalid-passphrase"),
          401);
    create("/auth/login", null, credentials(name), 429);
  }

  @Test
  void accountKeysPaginationAndSharedServicePermissionsStaySeparate() throws Exception {
    String owner = legacyToken(), a = register(), b = register();
    String ownerProblem = problem(owner, "合成管理员私有对话");
    for (String path :
        List.of(
            "/problems",
            "/sources",
            "/memories",
            "/topics",
            "/decisions",
            "/knowledge-entries",
            "/knowledge-proposals")) {
      assertEquals(0, read(path, b).path("items").size(), path);
    }
    assertEquals("empty", read("/memories/overview", b).path("status").asText());
    call(get(API + "/problems/" + ownerProblem), b, null, 404);
    String key = Json.id();
    var body = Json.obj("title", "合成同名对话", "goal", "合成目标");
    JsonNode aa = create("/problems", a, body, 201, key);
    JsonNode bb = create("/problems", b, body, 201, key);
    assertNotEquals(aa.path("id"), bb.path("id"));
    assertEquals(aa, create("/problems", a, body, 201, key));
    assertEquals(bb, create("/problems", b, body, 201, key));
    problem(a, "合成第二条");
    String cursor = read("/problems?limit=1", a).path("nextCursor").asText();
    assertFalse(cursor.isEmpty());
    call(get(API + "/problems").param("cursor", cursor), b, null, 400);
    call(
        patch(API + "/problems/" + aa.path("id").asText()), b, Json.obj("status", "archived"), 404);
    call(delete(API + "/problems/" + aa.path("id").asText()), b, null, 404);
    call(get(API + "/problems/" + bb.path("id").asText() + "/messages"), a, null, 404);
    assertEquals(2, read("/problems", a).path("items").size());
    call(get(API + "/problems/" + aa.path("id").asText()), owner, null, 404);
    JsonNode device = create("/devices", owner, Json.obj("name", "合成设备"), 201);
    String deviceId = device.at("/device/id").asText();
    UUID.fromString(deviceId);
    call(get(API + "/auth/me"), device.path("deviceToken").asText(), null, 403);
    call(get(API + "/devices"), a, null, 403);
    create("/devices", a, Json.obj("name", "无权创建设备"), 403);
    call(get(API + "/devices/" + deviceId + "/deliveries"), b, null, 403);
    create("/observations/batch", b, Json.obj("deviceId", deviceId, "items", Json.arr()), 403);
    create("/topics/" + Json.id() + "/sync", b, Json.obj(), 403);
    config.wikiBase = "http://127.0.0.1:1";
    config.wikiToken = "synthetic-wiki-test-token";
    assertTrue(read("/status", owner).at("/wiki/configured").asBoolean());
    assertFalse(read("/status", b).at("/wiki/configured").asBoolean());
    assertTrue(read("/status", b).at("/observer/lastSeenAt").isNull());
    String ingest =
        create(
                "/sources",
                b,
                Json.obj(
                    "problemId", bb.path("id"), "kind", "article", "text", "合成来源", "title", "合成标题"),
                202)
            .path("id")
            .asText();
    assertTrue(worker.runOne());
    assertEquals("succeeded", read("/jobs/" + ingest, b).path("status").asText());
    assertTrue(worker.runOne());
    assertFalse(worker.runOne(), "Member topic analysis must not enqueue a shared Wiki sync");
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT count(*) FROM jobs WHERE input->>'operation'='wiki'", Integer.class));
  }

  @Test
  void jobsModelInputsAndDerivedContentCannotCrossBetweenMembers() throws Exception {
    String a = register(), b = register();
    String pa = problem(a, "合成甲问题"), pb = problem(b, "合成乙问题");
    JsonNode memory =
        create(
            "/memories",
            a,
            Json.obj("problemId", pa, "kind", "user_reported", "content", PRIVATE_SOURCE),
            201);
    String mem = memory.path("id").asText(), src = memory.at("/evidence/0/sourceId").asText();
    String attachmentText = "合成甲附件 alpha-fixture.txt";
    String data =
        Base64.getEncoder().encodeToString(attachmentText.getBytes(StandardCharsets.UTF_8));
    try (Gateway gateway = new Gateway()) {
      call(
          put(API + "/settings/model"),
          a,
          Json.obj(
              "provider",
              "deepseek",
              "model",
              "synthetic-model",
              "apiKey",
              "synthetic-account-a-key"),
          200);
      call(
          put(API + "/settings/model"),
          b,
          Json.obj(
              "provider",
              "deepseek",
              "model",
              "synthetic-model",
              "apiKey",
              "synthetic-account-b-key"),
          200);
      String ajob =
          create(
                  "/problems/" + pa + "/messages",
                  a,
                  Json.obj(
                      "content",
                      PRIVATE_PREFERENCE,
                      "attachments",
                      Json.arr()
                          .add(
                              Json.obj(
                                  "name",
                                  "alpha-fixture.txt",
                                  "dataUrl",
                                  "data:text/plain;base64," + data))),
                  202)
              .path("id")
              .asText();
      // A's queued context must stay valid when B bumps its own context revision.
      create(
          "/memories",
          b,
          Json.obj("problemId", pb, "kind", "user_reported", "content", "仅乙账号的合成前提"),
          201);
      assertTrue(worker.runOne());
      assertEquals("succeeded", read("/jobs/" + ajob, a).path("status").asText());
      assertTrue(gateway.inputs.get(0).toString().contains(PRIVATE_SOURCE));
      JsonNode messages = read("/problems/" + pa + "/messages", a).path("items");
      String attachment = messages.get(0).at("/attachments/0/id").asText();
      assertFalse(attachment.isEmpty());
      assertEquals(
          attachmentText,
          mvc.perform(
                  get(API + "/attachments/" + attachment).header("Authorization", "Bearer " + a))
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8));
      String proposalJob =
          create("/problems/" + pa + "/knowledge-proposals", a, Json.obj(), 202)
              .path("id")
              .asText();
      assertTrue(worker.runOne());
      String prop = read("/jobs/" + proposalJob, a).at("/result/proposalId").asText();
      assertFalse(prop.isEmpty());
      JsonNode acceptBody = Json.obj("expectedTargetRevision", null);
      String entry =
          create("/knowledge-proposals/" + prop + "/accept", a, acceptBody, 200)
              .path("entryId")
              .asText();
      assertTrue(read("/knowledge-export", a).toString().contains(PRIVATE_KNOWLEDGE));

      for (String path :
          List.of(
              "/problems/" + pa,
              "/problems/" + pa + "/messages",
              "/sources/" + src,
              "/attachments/" + attachment,
              "/knowledge-proposals/" + prop,
              "/knowledge-entries/" + entry,
              "/knowledge-entries/" + entry + "/history",
              "/jobs/" + ajob,
              "/jobs/" + proposalJob,
              "/memories?problemId=" + pa,
              "/memories/overview?problemId=" + pa)) call(get(API + path), b, null, 404);
      create("/knowledge-proposals/" + prop + "/accept", b, acceptBody, 404);
      create("/jobs/" + ajob + "/retry", b, Json.obj(), 404);
      call(
          patch(API + "/memories/" + mem),
          b,
          Json.obj("content", "外部改写", "expectedRevision", 1),
          404);
      call(delete(API + "/sources/" + src), b, null, 404);
      call(delete(API + "/memories/" + mem), b, null, 404);
      assertEquals(PRIVATE_SOURCE, read("/sources/" + src, a).path("text").asText());
      assertTrue(read("/memories", a).path("items").toString().contains(PRIVATE_PREFERENCE));
      assertFalse(read("/memories?problemId=" + pb, b).toString().contains(PRIVATE_PREFERENCE));
      assertFalse(read("/knowledge-export", legacyToken()).toString().contains(PRIVATE_KNOWLEDGE));
      JsonNode export = read("/knowledge-export", b);
      for (String collection : List.of("entries", "histories", "references", "domains"))
        assertEquals(0, export.path(collection).size(), collection);
      assertEquals(0, read("/status", b).at("/usage/requests").asInt());
      assertEquals(2, read("/status", a).at("/usage/requests").asInt());
      String bjob =
          create("/problems/" + pb + "/messages", b, Json.obj("content", "合成乙：请按我的回答偏好讨论。"), 202)
              .path("id")
              .asText();
      assertTrue(worker.runOne());
      assertEquals("succeeded", read("/jobs/" + bjob, b).path("status").asText());
      JsonNode context = gateway.inputs.get(2);
      assertEquals(pb, context.at("/problem/id").asText());
      assertTrue(context.path("untrustedKnowledge").path("entries").isEmpty());
      assertTrue(context.toString().contains("仅乙账号"));
      for (String privateValue :
          List.of(
              PRIVATE_SOURCE,
              PRIVATE_PREFERENCE,
              PRIVATE_KNOWLEDGE,
              "alpha-fixture.txt",
              pa,
              ajob,
              entry,
              src,
              mem)) assertFalse(context.toString().contains(privateValue), privateValue);
      assertEquals(1, read("/status", b).at("/usage/requests").asInt());
      assertEquals(2, read("/status", a).at("/usage/requests").asInt());
      assertFalse(worker.runOne());
    }
  }

  /** Loopback model records the actual outbound model inputs without using any external key. */
  private class Gateway implements AutoCloseable {
    final HttpServer server;
    final List<JsonNode> inputs = new CopyOnWriteArrayList<>();

    Gateway() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            try {
              JsonNode wire = Json.M.readTree(exchange.getRequestBody());
              JsonNode input = Json.parse(wire.at("/messages/1/content").asText());
              inputs.add(input);
              ObjectNode reply;
              if (input.has("request")) {
                reply =
                    Json.obj(
                        "content", "合成答复", "uncertainties", Json.arr(), "evidence", Json.arr());
                if (PRIVATE_PREFERENCE.equals(input.path("request").asText()))
                  reply.set(
                      "memoryActions",
                      Json.arr()
                          .add(
                              Json.obj(
                                  "action",
                                  "ADD",
                                  "scope",
                                  "global",
                                  "content",
                                  PRIVATE_PREFERENCE,
                                  "sourceQuote",
                                  PRIVATE_PREFERENCE)));
              } else {
                JsonNode quote = input.path("messages").get(0);
                reply =
                    Json.obj(
                        "action",
                        "create",
                        "title",
                        PRIVATE_KNOWLEDGE,
                        "markdown",
                        "合成待审知识。",
                        "domains",
                        Json.arr().add("软件工程"),
                        "relatedEntryIds",
                        Json.arr(),
                        "targetEntryId",
                        null,
                        "targetRevision",
                        null,
                        "reason",
                        "合成回归用例",
                        "refs",
                        Json.arr()
                            .add(
                                Json.obj(
                                    "id",
                                    quote.path("id"),
                                    "kind",
                                    "message",
                                    "role",
                                    quote.path("role"),
                                    "quote",
                                    quote.path("content"))));
              }
              byte[] bytes =
                  Json.obj(
                          "model",
                          "synthetic-fixture",
                          "usage",
                          Json.obj("prompt_tokens", 1, "completion_tokens", 2),
                          "choices",
                          Json.arr()
                              .add(
                                  Json.obj(
                                      "finish_reason",
                                      "stop",
                                      "message",
                                      Json.obj("content", reply.toString()))))
                      .toString()
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      config.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
      config.modelKey = "synthetic-loopback-fixture";
      org.mockito.Mockito.doAnswer(
              invocation -> {
                ModelSettings.Profile p = (ModelSettings.Profile) invocation.callRealMethod();
                return new ModelSettings.Profile(
                    p.provider(), p.model(), p.key(), config.modelBase);
              })
          .when(settings)
          .profile(org.mockito.ArgumentMatchers.nullable(JsonNode.class));
    }

    @Override
    public void close() {
      server.stop(0);
      org.mockito.Mockito.reset(settings);
    }
  }
}
