package dev.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Synthetic data only. HTTP providers are transport fixtures, never real-model evidence. */
@SpringBootTest(properties={
  "spring.datasource.url=${TEST_DATABASE_URL}", "spring.datasource.username=decision_test",
  "spring.datasource.password=synthetic-test-only", "app.owner-code=synthetic-owner-code",
  "app.token-secret=synthetic-token-secret-for-local-tests-only", "app.model-key=",
  "app.wiki-base=", "app.wiki-token=", "app.mock=false", "app.worker-enabled=false",
  "app.origin-worker-enabled=false", "app.origin-executor-key=synthetic-executor-only"
})
@AutoConfigureMockMvc
class OriginContinuationTest extends AdminFixture {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired OriginStore o;
  @Autowired OriginChat chat;
  @Autowired Domain d;
  @Autowired Auth auth;
  @Autowired Config c;
  String owner;
  final List<HttpServer> servers = new ArrayList<>();

  @BeforeEach void reset() {
    assertTrue(System.getenv("TEST_DATABASE_URL").endsWith("/decision_backend_test"));
    s.db.execute("TRUNCATE origin_records,origin_requests,model_settings,model_test_requests,records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    s.db.update("UPDATE chat_memory_state SET epoch=0");
    c.mock=false; c.modelKey=""; c.modelName="fixture-origin";
    owner=auth.token("owner",Instant.now().plusSeconds(1200));
  }
  @AfterEach void cleanup() {
    servers.forEach(server -> server.stop(0));
    c.modelKey=""; c.modelName="deepseek-flash"; c.modelBase="https://api.deepseek.com";
    s.db.execute("TRUNCATE origin_records,origin_requests,model_settings,model_test_requests CASCADE");
  }
  JsonNode req(MockHttpServletRequestBuilder request, String token, JsonNode body, int status) throws Exception {
    if(token!=null) request.header("Authorization","Bearer "+token);
    if(body!=null) request.contentType("application/json").content(body.toString());
    var response=mvc.perform(request).andReturn().getResponse();
    assertEquals(status,response.getStatus(),response.getContentAsString());
    return response.getContentAsString().isBlank()?Json.obj():Json.parse(response.getContentAsString());
  }
  JsonNode postJson(String path, JsonNode body, int status) throws Exception {
    return req(post("/api/v1"+path).header("Idempotency-Key",Json.id()),owner,body,status);
  }
  String session() throws Exception {
    return postJson("/origin/sessions",Json.obj("title","Synthetic continuation"),201).path("id").asText();
  }
  JsonNode history(String id) throws Exception {
    return req(get("/api/v1/origin/sessions/"+id),owner,null,200);
  }
  JsonNode proposal(String sid, String action, JsonNode parameters, JsonNode context) throws Exception {
    return postJson("/origin/proposals",Json.obj("sessionId",sid,"action",action,"parameters",parameters,"expectedContext",context),201);
  }
  JsonNode newProblem(String sid) throws Exception {
    return proposal(sid,"decision.create_problem",Json.obj("title","Synthetic problem","goal","Compare synthetic alternatives"),Json.obj());
  }
  JsonNode commit(JsonNode p,int status) throws Exception {
    return req(post("/api/v1/origin/proposals/"+p.path("id").asText()+"/commit")
        .header("Idempotency-Key",p.path("requestId").asText()),owner,
        Json.obj("digest",p.path("digest"),"requestId",p.path("requestId")),status);
  }
  Map<String,List<String>> businessSnapshot() {
    Map<String,List<String>> snapshot=new LinkedHashMap<>();
    for(String table:List.of("records","record_history","jobs","attachments","analysis_pending","model_calls"))
      snapshot.put(table,s.db.queryForList("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY row_to_json(t)::text",String.class));
    return snapshot;
  }
  ObjectNode upload(String name,String mime,byte[] bytes) {
    return Json.obj("name",name,"dataUrl","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes));
  }
  HttpServer provider(java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler) throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/chat/completions",exchange->{try{handler.accept(exchange);}finally{exchange.close();}});
    server.start(); servers.add(server);
    c.modelBase="http://127.0.0.1:"+server.getAddress().getPort(); c.modelKey="synthetic-provider-only";
    return server;
  }
  void answer(com.sun.net.httpserver.HttpExchange exchange,String text) {
    OriginBoundaryTest.successfulStream(exchange,text);
  }

  @Test void reopenRestoresPendingAndCancelledCardsWithoutWritingBusiness() throws Exception {
    String sid=session(); JsonNode first=newProblem(sid), second=newProblem(sid);
    postJson("/origin/proposals/"+second.path("id").asText()+"/cancel",Json.obj("digest",second.path("digest")),200);
    var before=businessSnapshot();
    JsonNode reopened=history(sid);
    assertEquals(2,reopened.path("proposals").size());
    assertEquals(first,reopened.path("proposals").get(0));
    assertEquals("pending",reopened.path("proposals").get(0).path("state").asText());
    assertEquals("cancelled",reopened.path("proposals").get(1).path("state").asText());
    assertTrue(reopened.path("executions").isArray()); assertTrue(reopened.path("executions").isEmpty());
    assertEquals(before,businessSnapshot());
    assertEquals(reopened,history(sid));
    assertEquals(first.path("requestId"),history(sid).path("proposals").get(0).path("requestId"));
    commit(second,409);
    assertEquals(before,businessSnapshot());
  }

  @Test void reopenKeepsSeparateCreationFinalDecisionAndExecutionReceipts() throws Exception {
    String sid=session(); JsonNode created=commit(newProblem(sid),200);
    String problem=created.path("result").path("resourceId").asText();
    JsonNode context=req(get("/api/v1/problems/"+problem+"/confirmation-context"),owner,null,200);
    JsonNode finalProposal=proposal(sid,"decision.confirm_decision",Json.obj("problemId",problem,
        "choice","A","rationale","Synthetic reason","expectedOutcome","Synthetic expected result"),context);
    JsonNode confirmed=commit(finalProposal,200);
    String decision=confirmed.path("result").path("resourceId").asText();
    JsonNode executionContext=req(get("/api/v1/origin/decisions/"+decision+"/execution-context"),owner,null,200);
    JsonNode authorization=commit(proposal(sid,"execution.authorize",Json.obj("decisionId",decision,
        "executorId","synthetic-executor","action","fixture-action","target","fixture-target",
        "scope",Json.obj("workspace","synthetic"),"arguments",Json.obj()),executionContext),200);
    var before=businessSnapshot(); JsonNode reopened=history(sid);
    assertEquals(3,reopened.path("proposals").size());
    assertEquals(created,reopened.path("proposals").get(0));
    assertEquals(confirmed,reopened.path("proposals").get(1));
    assertEquals(authorization,reopened.path("proposals").get(2));
    assertEquals(1,reopened.path("executions").size());
    assertEquals(authorization.path("result").path("resourceId"),reopened.path("executions").get(0).path("id"));
    assertEquals("authorized",reopened.path("executions").get(0).path("state").asText());
    assertTrue(reopened.path("executions").get(0).path("result").isNull());
    assertEquals(before,businessSnapshot());
  }

  @Test void reopenedPendingProposalStillRejectsChangedBusinessContext() throws Exception {
    String sid=session();
    String problem=s.write(()->d.problem(Json.obj("title","Synthetic issue","goal","Synthetic goal"))).path("id").asText();
    JsonNode context=req(get("/api/v1/problems/"+problem+"/confirmation-context"),owner,null,200);
    JsonNode p=proposal(sid,"decision.confirm_decision",Json.obj("problemId",problem,
        "choice","A","rationale","Synthetic reason","expectedOutcome","Synthetic expectation"),context);
    req(patch("/api/v1/problems/"+problem),owner,Json.obj("goal","Changed by legacy application"),200);
    JsonNode restored=history(sid).path("proposals").get(0);
    assertEquals(p,restored);
    var before=businessSnapshot();
    JsonNode rejection=commit(restored,409);
    assertEquals("CONFIRMATION_STALE",rejection.path("error").path("code").asText());
    assertEquals(before,businessSnapshot());
    assertEquals("pending",history(sid).path("proposals").get(0).path("state").asText());
  }

  @Test void missingAttachmentRemainsVisibleAndStopsGenerationBeforeProviderCall() throws Exception {
    AtomicInteger calls=new AtomicInteger(); provider(exchange->{calls.incrementAndGet();answer(exchange,"Must not be called");});
    String sid=session();
    JsonNode submitted=postJson("/origin/sessions/"+sid+"/messages",Json.obj("content","Read the attachment",
        "attachments",Json.arr().add(upload("synthetic.txt","text/plain","Synthetic file".getBytes(StandardCharsets.UTF_8)))),202);
    String gid=submitted.path("generationId").asText(), mid=submitted.path("messageId").asText();
    JsonNode original=o.get("message",mid).deepCopy();
    String file=original.path("attachments").get(0).path("id").asText();
    // Fault injection affects only this synthetic Origin attachment row, not any business table.
    s.db.update("DELETE FROM origin_attachments WHERE account_id=?::uuid AND id=?::uuid",AccountScope.id(),file);
    var before=businessSnapshot(); JsonNode restored=history(sid);
    assertEquals(1,restored.path("messages").get(0).path("attachments").size());
    JsonNode missing=restored.path("messages").get(0).path("attachments").get(0);
    assertEquals(file,missing.path("id").asText()); assertEquals("synthetic.txt",missing.path("name").asText());
    assertEquals("unavailable",missing.path("status").asText());
    assertEquals("ATTACHMENT_REFERENCE_UNAVAILABLE",missing.path("error").path("code").asText());
    assertEquals(original,o.get("message",mid)); // Read-time warning does not rewrite the original reference.
    assertTrue(chat.run(gid));
    assertEquals("failed",chat.publicGeneration(gid).path("state").asText());
    assertEquals("ATTACHMENT_REFERENCE_UNAVAILABLE",chat.publicGeneration(gid).path("error").path("code").asText());
    assertEquals(0,calls.get()); assertEquals(1,o.list("message",sid).size());
    req(get("/api/v1/origin/attachments/"+file),owner,null,404);
    assertEquals(before,businessSnapshot());
  }

  @Test void pdfAndWordUseRealParsersPersistOriginalBytesAndDoNotCreateBusiness() throws Exception {
    var pdfBytes=new ByteArrayOutputStream();
    try(var pdf=new PDDocument()) {
      PDPage page=new PDPage();pdf.addPage(page);
      try(var content=new PDPageContentStream(pdf,page)) {
        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
        content.newLineAtOffset(30,700);content.showText("Synthetic PDF evidence alpha");content.endText();
      }
      pdf.save(pdfBytes);
    }
    var docxBytes=new ByteArrayOutputStream();
    try(var docx=new XWPFDocument()) {
      docx.createParagraph().createRun().setText("Synthetic Word evidence beta");docx.write(docxBytes);
    }
    AtomicReference<JsonNode> payload=new AtomicReference<>();
    provider(exchange->{try{payload.set(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));answer(exchange,"Synthetic document answer");}catch(Exception error){throw new RuntimeException(error);}});
    var before=businessSnapshot();String sid=session();
    JsonNode submitted=postJson("/origin/sessions/"+sid+"/messages",Json.obj("content","Compare both synthetic files",
        "attachments",Json.arr().add(upload("fixture.pdf","application/pdf",pdfBytes.toByteArray()))
        .add(upload("fixture.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",docxBytes.toByteArray()))),202);
    String gid=submitted.path("generationId").asText(); assertTrue(chat.run(gid));
    assertEquals("succeeded",chat.publicGeneration(gid).path("state").asText(),chat.publicGeneration(gid).toString());
    assertTrue(payload.get().toString().contains("Synthetic PDF evidence alpha"));
    assertTrue(payload.get().toString().contains("Synthetic Word evidence beta"));
    JsonNode reopened=history(sid), files=reopened.path("messages").get(0).path("attachments");
    assertEquals(2,files.size());assertEquals(2,reopened.path("messages").size());
    for(JsonNode file:files) assertEquals("ready",file.path("status").asText(),file.toString());
    assertArrayEquals(pdfBytes.toByteArray(),chat.attachment(files.get(0).path("id").asText()).bytes());
    assertArrayEquals(docxBytes.toByteArray(),chat.attachment(files.get(1).path("id").asText()).bytes());
    assertEquals(before,businessSnapshot());
  }

  @Test void encryptedPdfFailurePreservesDownloadAndDoesNotDispatchProvider() throws Exception {
    var bytes=new ByteArrayOutputStream();
    try(var pdf=new PDDocument()) {
      pdf.addPage(new PDPage());pdf.protect(new StandardProtectionPolicy("fixture-owner","fixture-password",new AccessPermission()));pdf.save(bytes);
    }
    AtomicInteger calls=new AtomicInteger();provider(exchange->{calls.incrementAndGet();answer(exchange,"Must not be called");});
    var before=businessSnapshot();String sid=session();
    JsonNode submitted=postJson("/origin/sessions/"+sid+"/messages",Json.obj("content","Read synthetic locked file",
        "attachments",Json.arr().add(upload("locked.pdf","application/pdf",bytes.toByteArray()))),202);
    String gid=submitted.path("generationId").asText();assertTrue(chat.run(gid));
    JsonNode failure=chat.publicGeneration(gid);assertEquals("failed",failure.path("state").asText());
    assertEquals("ATTACHMENT_ENCRYPTED",failure.path("error").path("code").asText());
    JsonNode file=history(sid).path("messages").get(0).path("attachments").get(0);
    assertEquals("failed",file.path("status").asText());
    assertArrayEquals(bytes.toByteArray(),chat.attachment(file.path("id").asText()).bytes());
    assertEquals(0,calls.get());assertEquals(before,businessSnapshot());
  }

  @Test void reopenedCardsRemainAccountScoped() throws Exception {
    String sid=session();newProblem(sid);
    String name="member_"+Json.id().replace("-","").substring(0,12);
    String other=req(post("/api/v1/auth/register"),null,Json.obj("username",name,"password","synthetic-member-password"),201).path("accessToken").asText();
    req(get("/api/v1/origin/sessions/"+sid),other,null,404);
    JsonNode sessions=req(get("/api/v1/origin/sessions"),other,null,200);
    assertTrue(sessions.path("items").isEmpty());
    assertEquals(1,history(sid).path("proposals").size());
  }

  @Test void actualHttpTimeoutAfterCommitReplaysSameReceiptWithoutSecondWrite() throws Exception {
    JsonNode p=newProblem(session());
    String route="/api/v1/origin/proposals/"+p.path("id").asText()+"/commit";
    String key=p.path("requestId").asText();
    ObjectNode body=Json.obj("digest",p.path("digest"),"requestId",key);
    CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);
    AtomicReference<JsonNode> first=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
    // A loopback HTTP bridge invokes the real Spring controller/filter/database transaction,
    // then withholds its response. It is not a production network partition or full Tomcat test.
    HttpServer bridge=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);servers.add(bridge);
    bridge.createContext("/confirm",exchange->{
      try {
        JsonNode received=Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        first.set(req(post(route).header("Idempotency-Key",key),owner,received,200));committed.countDown();
        release.await(8,TimeUnit.SECONDS);
      } catch(Throwable error){failure.set(error);committed.countDown();}
      finally{exchange.close();}
    });
    bridge.start();
    try {
      HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bridge.getAddress().getPort()+"/confirm"))
          .timeout(Duration.ofSeconds(2)).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
      var response=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
      assertTrue(committed.await(6,TimeUnit.SECONDS));assertNull(failure.get());assertNotNull(first.get());
      ExecutionException error=assertThrows(ExecutionException.class,()->response.get(6,TimeUnit.SECONDS));
      assertInstanceOf(HttpTimeoutException.class,error.getCause());
      release.countDown();
      JsonNode replay=req(post(route).header("Idempotency-Key",key),owner,body,200);
      assertEquals(first.get(),replay);
      assertEquals(1,s.db.queryForObject("SELECT count(*) FROM records WHERE kind='problem'",Integer.class));
      assertEquals(first.get().path("result"),req(get("/api/v1/origin/proposals/"+p.path("id").asText()),owner,null,200).path("result"));
    } finally {release.countDown();}
  }
}
