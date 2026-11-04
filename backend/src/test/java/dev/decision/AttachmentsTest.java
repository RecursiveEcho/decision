package dev.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
class AttachmentsTest {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired Auth auth;
  @Autowired Config c;
  @Autowired Worker worker;
  @Autowired Attachments attachments;
  String owner;

  @BeforeEach
  void reset() {
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

  JsonNode request(MockHttpServletRequestBuilder request, JsonNode body, int status)
      throws Exception {
    request.header("Authorization", "Bearer " + owner);
    if (body != null) request.contentType("application/json").content(body.toString());
    var response = mvc.perform(request).andReturn().getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return response.getContentAsString().isBlank()
        ? Json.obj()
        : Json.parse(response.getContentAsString());
  }

  JsonNode post(String path, JsonNode body, int status) throws Exception {
    return request(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1" + path)
            .header("Idempotency-Key", Json.id()),
        body,
        status);
  }

  JsonNode messages(String p) throws Exception {
    return request(get("/api/v1/problems/" + p + "/messages"), null, 200).path("items");
  }

  JsonNode job(String id) throws Exception {
    return request(get("/api/v1/jobs/" + id), null, 200);
  }

  static ObjectNode file(String name, String mime, byte[] bytes) {
    return Json.obj(
        "name",
        name,
        "dataUrl",
        "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
  }

  static byte[] image(int width, int height) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  static ObjectNode body(String text, String thinking, ObjectNode... files) {
    ArrayNode array = Json.arr();
    for (var f : files) array.add(f);
    return Json.obj("content", text, "thinking", thinking, "attachments", array);
  }

  com.sun.net.httpserver.HttpServer model(List<JsonNode> calls) throws Exception {
    return model(
        calls,
        request ->
            Json.obj("content", "合成附件回答", "evidence", Json.arr(), "uncertainties", Json.arr()));
  }

  com.sun.net.httpserver.HttpServer model(
      List<JsonNode> calls, java.util.function.Function<JsonNode, ObjectNode> answer)
      throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          calls.add(
              Json.parse(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] bytes =
              Json.obj(
                      "choices",
                      Json.arr()
                          .add(
                              Json.obj(
                                  "finish_reason",
                                  "stop",
                                  "message",
                                  Json.obj(
                                      "content",
                                      answer.apply(calls.get(calls.size() - 1)).toString(),
                                      "reasoning_content",
                                      "synthetic private reasoning"))),
                      "usage",
                      Json.obj("prompt_tokens", 12, "completion_tokens", 8))
                  .toString()
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    c.modelBase = "http://127.0.0.1:" + server.getAddress().getPort();
    c.modelKey = "synthetic-test-key";
    return server;
  }

  @Test
  void imageOnlyIsAtomicIdempotentOwnerOnlyAndParsed() throws Exception {
    byte[] bytes = image(8, 8);
    var b = body("", "off", file("测试 图.png", "image/png", bytes));
    String key = Json.id();
    var result =
        request(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/conversations")
                .header("Idempotency-Key", key),
            b,
            202);
    var repeated =
        request(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/conversations")
                .header("Idempotency-Key", key),
            b,
            202);
    assertEquals(result, repeated);
    assertEquals(1, s.db.queryForObject("SELECT count(*) FROM attachments", Integer.class));
    String p = result.path("problem").path("id").asText();
    var meta = messages(p).get(0).path("attachments").get(0);
    assertEquals("pending", meta.path("status").asText());
    assertEquals(8, meta.path("width").asInt());
    String path = "/api/v1/attachments/" + meta.path("id").asText();
    var raw =
        mvc.perform(get(path).header("Authorization", "Bearer " + owner)).andReturn().getResponse();
    assertArrayEquals(bytes, raw.getContentAsByteArray());
    assertEquals("no-store", raw.getHeader("Cache-Control"));
    assertEquals("nosniff", raw.getHeader("X-Content-Type-Options"));
    assertEquals(401, mvc.perform(get(path)).andReturn().getResponse().getStatus());
    String device =
        post("/devices", Json.obj("name", "synthetic-attachment-device"), 201)
            .path("deviceToken")
            .asText();
    assertEquals(
        403,
        mvc.perform(get(path).header("Authorization", "Bearer " + device))
            .andReturn()
            .getResponse()
            .getStatus());
    c.mock = true;
    assertTrue(worker.runOne());
    assertEquals("ready", messages(p).get(0).path("attachments").get(0).path("status").asText());
    assertFalse(
        s.db
            .queryForObject(
                "SELECT input::text FROM jobs WHERE id=?::uuid",
                String.class,
                result.path("job").path("id").asText())
            .contains("data:"));
    b.put("thinking", "high");
    request(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/conversations")
            .header("Idempotency-Key", key),
        b,
        409);
  }

  @Test
  void thinkingMapsToRealImageBlocksAndHistoryExpires() throws Exception {
    List<JsonNode> calls = new ArrayList<>();
    var server = model(calls);
    try {
      String p = "";
      int i = 0;
      int[] budgets = {4000, 8000, 16000, 32000};
      for (String level : List.of("off", "low", "high", "max")) {
        var result =
            post(
                "/conversations",
                body("查看合成图", level, file("red.png", "image/png", image(4, 4))),
                202);
        p = result.path("problem").path("id").asText();
        assertTrue(worker.runOne());
        assertEquals(
            "succeeded", job(result.path("job").path("id").asText()).path("status").asText());
        var payload = calls.get(i);
        assertEquals(budgets[i++], payload.path("max_tokens").asInt());
        assertEquals(
            level.equals("off") ? "disabled" : "enabled",
            payload.path("thinking").path("type").asText());
        assertEquals(level.equals("off") ? "" : level, payload.path("reasoning_effort").asText());
        var content = payload.path("messages").get(1).path("content");
        assertTrue(content.isArray());
        assertEquals("image_url", content.get(2).path("type").asText());
        assertTrue(
            content
                .get(2)
                .path("image_url")
                .path("url")
                .asText()
                .startsWith("data:image/png;base64,"));
        assertFalse(content.get(0).path("text").asText().contains("base64"));
        assertEquals(level, messages(p).get(1).path("thinking").asText());
        assertTrue(messages(p).get(1).path("uncertainties").isEmpty());
        assertFalse(messages(p).toString().contains("synthetic private reasoning"));
      }
      post("/problems/" + p + "/messages", Json.obj("content", "继续看上一张图"), 202);
      worker.runOne();
      assertTrue(calls.get(4).path("messages").get(1).path("content").isArray());
      assertTrue(messages(p).get(3).path("uncertainties").isEmpty());
      request(patch("/api/v1/problems/" + p), Json.obj("goal", "修改合成目标"), 200);
      post("/problems/" + p + "/messages", Json.obj("content", "新的上下文"), 202);
      worker.runOne();
      assertTrue(calls.get(5).path("messages").get(1).path("content").isTextual());
      assertTrue(messages(p).get(5).path("uncertainties").toString().contains("没有可核验的来源引用"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void partialAttachmentsKeepReadNoticesAndInvalidSourceCitationsStillWarn() throws Exception {
    List<JsonNode> calls = new ArrayList<>();
    var server =
        model(
            calls,
            request -> {
              JsonNode raw = request.path("messages").get(1).path("content");
              JsonNode input =
                  Json.parse(raw.isArray() ? raw.get(0).path("text").asText() : raw.asText());
              String attachmentId = input.path("untrustedAttachments").get(0).path("id").asText();
              return Json.obj(
                  "content",
                  "仅依据已读部分回答",
                  "evidence",
                  Json.arr()
                      .add(
                          Json.obj(
                              "sourceId", attachmentId, "quote", "不得假作来源", "relation", "supports"))
                      .add(
                          Json.obj("sourceId", Json.id(), "quote", "伪造来源", "relation", "supports")),
                  "uncertainties",
                  Json.arr());
            });
    try {
      var result =
          post(
              "/conversations",
              body(
                  "阅读合成文件",
                  "off",
                  file(
                      "long.txt",
                      "text/plain",
                      "合成片段".repeat(10000).getBytes(StandardCharsets.UTF_8))),
              202);
      String p = result.path("problem").path("id").asText();
      worker.runOne();
      assertEquals(
          "succeeded", job(result.path("job").path("id").asText()).path("status").asText());
      JsonNode answer = messages(p).get(1);
      assertEquals(
          "partial", messages(p).get(0).path("attachments").get(0).path("status").asText());
      assertTrue(answer.path("content").asText().startsWith("附件读取说明："));
      assertTrue(answer.path("evidence").isEmpty());
      assertTrue(answer.path("uncertainties").toString().contains("部分模型引用无法核验"));
      assertTrue(answer.path("uncertainties").toString().contains("long.txt"));
      assertFalse(answer.path("uncertainties").toString().contains("本回答没有可核验的来源引用"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsBadEncodingsTypesDimensionsAndSizeWithoutWrites() throws Exception {
    post("/conversations", body("", "off"), 400);
    post("/conversations", body("hello", "medium"), 400);
    post(
        "/conversations",
        body("", "off", file("fake.png", "image/png", "not image".getBytes())),
        400);
    post(
        "/conversations",
        body("", "off", Json.obj("name", "x.txt", "dataUrl", "https://example.com/x")),
        400);
    post(
        "/conversations",
        body("", "off", Json.obj("name", "x.txt", "dataUrl", "data:text/plain;base64,???")),
        400);
    post("/conversations", body("", "off", file("../x.txt", "text/plain", new byte[] {1})), 400);
    post("/conversations", body("", "off", file("big.png", "image/png", image(5000, 1))), 413);
    post(
        "/conversations",
        body("", "off", file("large.txt", "text/plain", new byte[8 * 1024 * 1024 + 1])),
        413);
    var f = file("a.txt", "text/plain", new byte[] {65});
    post("/conversations", body("", "off", f, f, f, f, f), 400);
    assertEquals(0, s.db.queryForObject("SELECT count(*) FROM attachments", Integer.class));
    assertEquals(0, s.db.queryForObject("SELECT count(*) FROM records", Integer.class));
  }

  @Test
  void failedParseRetryReusesBytesAndThinking() throws Exception {
    var result =
        post(
            "/conversations",
            body("", "max", file("bad.txt", "text/plain", new byte[] {(byte) 255})),
            202);
    String p = result.path("problem").path("id").asText(),
        id = result.path("job").path("id").asText();
    worker.runOne();
    assertEquals("failed", job(id).path("status").asText());
    assertEquals("failed", messages(p).get(0).path("attachments").get(0).path("status").asText());
    var retry = post("/jobs/" + id + "/retry", Json.obj(), 202);
    assertEquals(
        "max",
        Json.parse(
                s.db.queryForObject(
                    "SELECT input::text FROM jobs WHERE id=?::uuid",
                    String.class,
                    retry.path("id").asText()))
            .path("thinking")
            .asText());
    worker.runOne();
    assertEquals(1, s.db.queryForObject("SELECT count(*) FROM attachments", Integer.class));
    assertEquals(1, messages(p).size());
    assertEquals(0, s.db.queryForObject("SELECT count(*) FROM model_calls", Integer.class));
  }

  @Test
  void queueFullRollsBackAttachmentAndMessageTogether() throws Exception {
    String p =
        post("/problems", Json.obj("title", "synthetic", "goal", "synthetic"), 201)
            .path("id")
            .asText();
    s.write(
        () -> {
          for (int i = 0; i < 200; i++) s.job("chat", p, Json.obj(), "mock");
          return null;
        });
    post("/conversations", body("", "off", file("x.png", "image/png", image(2, 2))), 429);
    assertEquals(0, s.db.queryForObject("SELECT count(*) FROM attachments", Integer.class));
    assertEquals(
        1, s.db.queryForObject("SELECT count(*) FROM records WHERE kind='problem'", Integer.class));
    assertEquals(
        0, s.db.queryForObject("SELECT count(*) FROM records WHERE kind='message'", Integer.class));
  }

  @Test
  void actualChildParsesOfficeTextAndScannedPdfWithCoverage() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var doc = new XWPFDocument()) {
      doc.createParagraph().createRun().setText("Synthetic document alpha");
      doc.write(out);
    }
    assertTrue(
        DocumentParser.isolated(out.toByteArray(), "docx")
            .path("text")
            .asText()
            .contains("Synthetic document alpha"));
    out.reset();
    try (var book = new XSSFWorkbook()) {
      var row = book.createSheet("Synthetic").createRow(0);
      row.createCell(0).setCellValue("Test cell");
      row.createCell(1).setCellFormula("1+1");
      book.write(out);
    }
    var sheet = DocumentParser.isolated(out.toByteArray(), "xlsx");
    assertTrue(sheet.path("text").asText().contains("Test cell"));
    assertFalse(sheet.path("text").asText().contains("1+1"));
    for (String type : List.of("txt", "md", "csv"))
      assertEquals(
          "synthetic 中文",
          DocumentParser.isolated("synthetic 中文".getBytes(StandardCharsets.UTF_8), type)
              .path("text")
              .asText());
    var large = DocumentParser.isolated("a".repeat(40000).getBytes(), "txt");
    assertEquals("partial", large.path("status").asText());
    assertEquals(32000, large.path("text").asText().length());
    out.reset();
    try (var pdf = new PDDocument()) {
      for (int i = 0; i < 4; i++) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var stream = new PDPageContentStream(pdf, page)) {
          stream.drawImage(
              LosslessFactory.createFromImage(
                  pdf, new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)),
              10,
              10,
              40,
              40);
        }
      }
      pdf.save(out);
    }
    var scan = DocumentParser.isolated(out.toByteArray(), "pdf");
    assertEquals("partial", scan.path("status").asText());
    assertEquals(4, scan.path("coverage").path("pages").asInt());
    assertEquals(3, scan.path("images").size());
    assertEquals(3, scan.path("coverage").path("processedPages").asInt());
  }

  @Test
  void childRejectsZipBombEntitiesMacrosAndIsKillable() throws Exception {
    byte[] bomb = zip(Map.of("word/document.xml", new byte[2 * 1024 * 1024]));
    assertEquals(
        "ATTACHMENT_ZIP_LIMIT",
        assertThrows(ApiError.class, () -> DocumentParser.isolated(bomb, "docx")).code);
    byte[] entity =
        zip(
            Map.of(
                "word/document.xml",
                "<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><x>&p;</x>".getBytes()));
    assertThrows(ApiError.class, () -> DocumentParser.isolated(entity, "docx"));
    byte[] macro = zip(Map.of("word/vbaProject.bin", new byte[] {1}));
    assertEquals(
        "ATTACHMENT_UNSAFE_DOCUMENT",
        assertThrows(ApiError.class, () -> DocumentParser.isolated(macro, "docx")).code);
    byte[] external =
        zip(
            Map.of(
                "word/document.xml",
                "<x/>".getBytes(),
                "word/_rels/document.xml.rels",
                "<Relationships><Relationship TargetMode='External' Target='http://127.0.0.1:1'/></Relationships>"
                    .getBytes()));
    assertThrows(ApiError.class, () -> DocumentParser.isolated(external, "docx"));
    assertEquals(
        "ATTACHMENT_PARSE_TIMEOUT",
        assertThrows(ApiError.class, () -> DocumentParser.isolated(new byte[] {65}, "txt", 1))
            .code);
  }

  static byte[] zip(Map<String, byte[]> entries) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var z = new ZipOutputStream(out)) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void currentDocumentsTakeBudgetBeforeHistoricalDocumentsAndWarn() throws Exception {
    List<JsonNode> calls = new ArrayList<>();
    var server = model(calls);
    try {
      String p =
          post(
                  "/conversations",
                  body("读附件", "off", file("old.txt", "text/plain", "older".getBytes())),
                  202)
              .path("problem")
              .path("id")
              .asText();
      worker.runOne();
      ObjectNode[] files = new ObjectNode[4];
      for (int i = 0; i < 4; i++)
        files[i] = file("current" + i + ".txt", "text/plain", ("n" + i).repeat(16000).getBytes());
      post("/problems/" + p + "/messages", body("读本次四个文件", "off", files), 202);
      worker.runOne();
      var input = Json.parse(calls.get(1).path("messages").get(1).path("content").asText());
      assertEquals(4, input.path("untrustedAttachments").size());
      assertTrue(input.path("attachmentReadNotices").toString().contains("省略"));
      assertTrue(messages(p).get(3).path("content").asText().contains("历史附件"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void contextChangeDuringChildParsingTerminatesMetadataWithoutModelCall() throws Exception {
    var result =
        post(
            "/conversations",
            body("", "off", file("x.txt", "text/plain", "synthetic text".getBytes())),
            202);
    String p = result.path("problem").path("id").asText(),
        id = result.path("job").path("id").asText();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var task = executor.submit(() -> worker.runOne());
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      boolean processing = false;
      while (System.nanoTime() < deadline) {
        if (s.db.queryForObject(
                "SELECT count(*) FROM attachments WHERE metadata->>'status'='processing'",
                Integer.class)
            == 1) {
          processing = true;
          break;
        }
        Thread.sleep(5);
      }
      assertTrue(processing);
      request(
          patch("/api/v1/problems/" + p),
          Json.obj("goal", "synthetic correction during parse"),
          200);
      assertTrue(task.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("CONTEXT_CHANGED", job(id).path("error").path("code").asText());
      assertEquals("failed", messages(p).get(0).path("attachments").get(0).path("status").asText());
      assertEquals(0, s.db.queryForObject("SELECT count(*) FROM model_calls", Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void restartStopsProcessingAttachmentsAndEncryptedPdfRemainsDownloadable() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.protect(
          new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
              "owner-secret",
              "synthetic-password",
              new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
      doc.save(out);
    }
    var result =
        post(
            "/conversations",
            body("", "off", file("locked.pdf", "application/pdf", out.toByteArray())),
            202);
    String p = result.path("problem").path("id").asText(),
        id = result.path("job").path("id").asText();
    worker.runOne();
    assertEquals("ATTACHMENT_ENCRYPTED", job(id).path("error").path("code").asText());
    var meta = messages(p).get(0).path("attachments").get(0);
    assertArrayEquals(out.toByteArray(), attachments.get(meta.path("id").asText()).bytes());
    var retry = post("/jobs/" + id + "/retry", Json.obj(), 202);
    String retryId = retry.path("id").asText();
    s.db.update("UPDATE jobs SET state='running' WHERE id=?::uuid", retryId);
    s.db.update(
        "UPDATE attachments SET metadata=jsonb_set(metadata,'{status}','\"processing\"'::jsonb)");
    worker.recover();
    assertEquals("PROCESS_RESTARTED", job(retryId).path("error").path("code").asText());
    assertEquals("failed", messages(p).get(0).path("attachments").get(0).path("status").asText());
    assertEquals(1, s.db.queryForObject("SELECT count(*) FROM attachments", Integer.class));
  }
}
