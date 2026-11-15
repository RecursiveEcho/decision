package dev.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={
  "spring.datasource.url=${TEST_DATABASE_URL}", "spring.datasource.username=decision_test",
  "spring.datasource.password=synthetic-test-only", "app.owner-code=synthetic-owner-code",
  "app.token-secret=synthetic-token-secret-for-local-tests-only", "app.model-key=",
  "app.wiki-base=", "app.wiki-token=", "app.mock=false", "app.worker-enabled=false",
  "app.origin-worker-enabled=false", "app.origin-executor-key=synthetic-executor-only"
})
@AutoConfigureMockMvc
class OriginBoundaryTest extends AdminFixture {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired OriginStore o;
  @Autowired OriginChat chat;
  @Autowired OriginConfirmations confirmations;
  @Autowired ModelSettings settings;
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
    owner=auth.token("owner", Instant.now().plusSeconds(1200));
  }
  @AfterEach void cleanup() {
    servers.forEach(x -> x.stop(0));
    c.modelKey=""; c.modelName="deepseek-flash"; c.modelBase="https://api.deepseek.com";
    s.db.execute("TRUNCATE origin_records,origin_requests,model_settings,model_test_requests CASCADE");
  }

  JsonNode req(MockHttpServletRequestBuilder r, String token, JsonNode b, int status) throws Exception {
    if(token!=null) r.header("Authorization", "Bearer " + token);
    if(b!=null) r.contentType("application/json").content(b.toString());
    var result=mvc.perform(r).andReturn().getResponse();
    assertEquals(status,result.getStatus(),result.getContentAsString());
    return result.getContentAsString().isBlank()?Json.obj():Json.parse(result.getContentAsString());
  }
  JsonNode postJson(String path, JsonNode body, int status) throws Exception {
    return req(post("/api/v1"+path).header("Idempotency-Key",Json.id()),owner,body,status);
  }
  String session() throws Exception {
    return postJson("/origin/sessions",Json.obj("title","合成隔离对话"),201).path("id").asText();
  }
  String problem() {
    return s.write(() -> d.problem(Json.obj("title","合成问题","goal","比较两个合成方案"))).path("id").asText();
  }
  String source(String problem) {
    return s.write(() -> d.source(problem,"user_statement","合成来源","合成事实：方案甲成本三。",null,null,"user_input",Json.obj(),Json.now())).path("id").asText();
  }
  JsonNode context(String problem) throws Exception {
    return req(get("/api/v1/problems/"+problem+"/confirmation-context"),owner,null,200);
  }
  JsonNode propose(String session,String action,JsonNode parameters,JsonNode context) throws Exception {
    return postJson("/origin/proposals",Json.obj("sessionId",session,"action",action,"parameters",parameters,"expectedContext",context),201);
  }
  JsonNode newProblemProposal(String session) throws Exception {
    return propose(session,"decision.create_problem",Json.obj("title","待确认合成档案","goal","合成目标"),Json.obj());
  }
  ObjectNode decisionParameters(String p,String source) {
    return Json.obj("problemId",p,"choice","方案甲","rationale","合成取舍","expectedOutcome","合成预期",
        "evidence",source==null?Json.arr():Json.arr().add(Json.obj("sourceId",source,"quote","方案甲成本三","relation","supports")));
  }
  JsonNode commit(JsonNode p,int status) throws Exception {
    return req(post("/api/v1/origin/proposals/"+p.path("id").asText()+"/commit")
        .header("Idempotency-Key",p.path("requestId").asText()),owner,
        Json.obj("digest",p.path("digest"),"requestId",p.path("requestId")),status);
  }
  int records(String kind) {
    return s.db.queryForObject("SELECT count(*) FROM records WHERE account_id=?::uuid AND kind=? AND NOT deleted",Integer.class,AccountScope.id(),kind);
  }
  Map<String,List<String>> businessSnapshot() {
    Map<String,List<String>> result=new LinkedHashMap<>();
    for(String table:List.of("records","record_history","jobs","attachments","analysis_pending","model_calls"))
      result.put(table,s.db.queryForList("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY row_to_json(t)::text",String.class));
    return result;
  }
  ObjectNode upload(String name,String type,byte[] bytes) {
    return Json.obj("name",name,"dataUrl","data:"+type+";base64,"+Base64.getEncoder().encodeToString(bytes));
  }

  @Test void ordinaryChatAndCancelledProposalDoNotTouchBusinessRecordsAttachmentsOrJobs() throws Exception {
    s.write(() -> d.conversation(Json.obj("content","保留的旧聊天","attachments",Json.arr().add(upload("old.txt","text/plain","旧附件合成内容".getBytes(StandardCharsets.UTF_8))))));
    var before=businessSnapshot();
    String session=session();
    JsonNode msg=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","只是讨论，不创建业务档案",
        "attachments",Json.arr().add(upload("note.txt","text/plain","合成附件".getBytes(StandardCharsets.UTF_8)))),202);
    postJson("/origin/generations/"+msg.path("generationId").asText()+"/stop",Json.obj(),200);
    JsonNode p=newProblemProposal(session);
    postJson("/origin/proposals/"+p.path("id").asText()+"/cancel",Json.obj("digest",p.path("digest")),200);
    commit(p,409);
    assertEquals(before,businessSnapshot());
    assertEquals(1,o.list("message",session).size());
    assertEquals(1,s.db.queryForObject("SELECT count(*) FROM origin_attachments",Integer.class));
  }

  @Test void messageRetryUsesOneOriginalMessageAttachmentAndGeneration() throws Exception {
    String session=session(), key=Json.id(), path="/api/v1/origin/sessions/"+session+"/messages";
    ObjectNode b=Json.obj("content","合成重复传输","attachments",Json.arr().add(upload("note.txt","text/plain",new byte[]{65,66})));
    JsonNode a=req(post(path).header("Idempotency-Key",key),owner,b,202);
    JsonNode replay=req(post(path).header("Idempotency-Key",key),owner,b,202);
    assertEquals(a,replay);
    req(post(path).header("Idempotency-Key",key),owner,Json.obj("content","不同参数"),409);
    assertEquals(1,o.list("message",session).size()); assertEquals(1,o.list("generation",session).size());
    assertEquals(1,s.db.queryForObject("SELECT count(*) FROM origin_attachments",Integer.class));
    assertEquals(0,records("problem"));
  }

  @Test void concurrentDoubleConfirmAndLostResponseReconcileOneId() throws Exception {
    JsonNode p=newProblemProposal(session());
    var pool=Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start=new CountDownLatch(1);
      Callable<JsonNode> call=()->{start.await(); return commit(p,200);};
      var a=pool.submit(call); var b=pool.submit(call); start.countDown();
      JsonNode first=a.get(10,TimeUnit.SECONDS), second=b.get(10,TimeUnit.SECONDS);
      assertEquals(first.path("result").path("resourceId"),second.path("result").path("resourceId"));
      // Drop the successful response then retry the fixed request key, as after client timeout.
      JsonNode replay=commit(p,200);
      assertEquals(first.path("result"),replay.path("result"));
      assertEquals(1,records("problem")); assertEquals(0,records("decision"));
      req(post("/api/v1/origin/proposals/"+p.path("id").asText()+"/commit").header("Idempotency-Key",p.path("requestId").asText()),
          owner,Json.obj("digest","a".repeat(64),"requestId",p.path("requestId")),409);
    } finally {pool.shutdownNow();}
  }

  @Test void cancellationAndCommitAreMutuallyExclusive() throws Exception {
    JsonNode p=newProblemProposal(session()); String id=p.path("id").asText();
    var pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
    try {
      var submit=pool.submit(()->{start.await(); return mvc.perform(post("/api/v1/origin/proposals/"+id+"/commit")
          .header("Authorization","Bearer "+owner).header("Idempotency-Key",p.path("requestId").asText())
          .contentType("application/json").content(Json.obj("digest",p.path("digest"),"requestId",p.path("requestId")).toString())).andReturn().getResponse().getStatus();});
      var cancel=pool.submit(()->{start.await(); return mvc.perform(post("/api/v1/origin/proposals/"+id+"/cancel")
          .header("Authorization","Bearer "+owner).header("Idempotency-Key",Json.id()).contentType("application/json")
          .content(Json.obj("digest",p.path("digest")).toString())).andReturn().getResponse().getStatus();});
      start.countDown(); int a=submit.get(10,TimeUnit.SECONDS),b=cancel.get(10,TimeUnit.SECONDS);
      assertTrue((a==200&&b==409)||(a==409&&b==200));
      String state=o.get("proposal",id).path("state").asText();
      assertEquals(state.equals("committed")?1:0,records("problem"));
    }finally {pool.shutdownNow();}
  }

  @Test void proposalDigestAndFixedRequestKeyCannotBeChanged() throws Exception {
    JsonNode p=newProblemProposal(session()); String path="/api/v1/origin/proposals/"+p.path("id").asText()+"/commit";
    req(post(path).header("Idempotency-Key",Json.id()),owner,Json.obj("digest",p.path("digest"),"requestId",p.path("requestId")),409);
    req(post(path).header("Idempotency-Key",p.path("requestId").asText()),owner,Json.obj("digest","f".repeat(64),"requestId",p.path("requestId")),409);
    req(post(path).header("Idempotency-Key",p.path("requestId").asText()),owner,Json.obj("digest",p.path("digest"),"requestId",p.path("requestId"),"parameters",Json.obj("title","篡改")),400);
    assertEquals(0,records("problem"));
  }

  @Test void oldApplicationProblemEditMakesConfirmationStaleWithNoWrites() throws Exception {
    String p=problem(), session=session();
    JsonNode proposal=propose(session,"decision.confirm_decision",decisionParameters(p,null),context(p));
    req(patch("/api/v1/problems/"+p),owner,Json.obj("goal","另一个真实目标"),200);
    var before=businessSnapshot();
    JsonNode response=commit(proposal,409);
    assertEquals("CONFIRMATION_STALE",response.path("error").path("code").asText());
    assertEquals(before,businessSnapshot());
    assertEquals("pending",o.get("proposal",proposal.path("id").asText()).path("state").asText());
  }

  @Test void changedEvidenceIncludingUnchangedQuoteInvalidatesProposal() throws Exception {
    String p=problem(), source=source(p);
    JsonNode proposal=propose(session(),"decision.confirm_decision",decisionParameters(p,source),context(p));
    s.write(()->{ObjectNode src=s.get("source",source); src.put("title","标题被旧入口修改，引用原句不变"); return s.save("source",src);});
    var before=businessSnapshot(); commit(proposal,409); assertEquals(before,businessSnapshot());
  }

  @Test void addedOrDeletedEvidenceRequiresNewConfirmation() throws Exception {
    String p=problem(), source=source(p), session=session();
    JsonNode first=propose(session,"decision.confirm_decision",decisionParameters(p,source),context(p));
    source(p); commit(first,409);
    JsonNode second=propose(session,"decision.confirm_decision",decisionParameters(p,source),context(p));
    req(delete("/api/v1/sources/"+source),owner,null,204);
    var before=businessSnapshot(); commit(second,409); assertEquals(before,businessSnapshot());
  }

  @Test void sameContentChangedAwayAndBackStillInvalidatesVersion() throws Exception {
    String p=problem(); JsonNode proposal=propose(session(),"decision.confirm_decision",decisionParameters(p,null),context(p));
    req(patch("/api/v1/problems/"+p),owner,Json.obj("goal","短暂变化"),200);
    req(patch("/api/v1/problems/"+p),owner,Json.obj("goal","比较两个合成方案"),200);
    commit(proposal,409); assertEquals(0,records("decision"));
  }

  @Test void legacyDecisionEndpointChecksOptionalVersionInsideTransaction() throws Exception {
    String p=problem(); ObjectNode params=decisionParameters(p,null); params.set("expectedContext",context(p));
    req(patch("/api/v1/problems/"+p),owner,Json.obj("title","已经改名"),200);
    var before=businessSnapshot(); postJson("/decisions",params,409); assertEquals(before,businessSnapshot());
    params.set("expectedContext",context(p)); postJson("/decisions",params,201);
    assertEquals(1,records("decision"));
  }

  @Test void createFinalDecisionAndExecutionAreThreeSeparateReceipts() throws Exception {
    String session=session(); JsonNode created=commit(newProblemProposal(session),200);
    String p=created.path("result").path("resourceId").asText();
    assertEquals(0,records("decision")); assertEquals(0,o.list("authorization",session).size());
    JsonNode finalProposal=propose(session,"decision.confirm_decision",decisionParameters(p,null),context(p));
    assertEquals(0,records("decision"));
    String decision=commit(finalProposal,200).path("result").path("resourceId").asText();
    assertFalse(s.get("decision",decision).path("confirmedAt").asText().isBlank());
    JsonNode authProposal=propose(session,"execution.authorize",Json.obj("decisionId",decision,"executorId","synthetic-executor",
        "action","create_test_file","target","synthetic-target","scope",Json.obj("workspace","synthetic-only"),"arguments",Json.obj("name","fixture")),
        req(get("/api/v1/origin/decisions/"+decision+"/execution-context"),owner,null,200));
    assertEquals(0,o.list("authorization",session).size());
    String id=commit(authProposal,200).path("result").path("resourceId").asText();
    ObjectNode authorization=o.get("authorization",id);
    assertEquals("authorized",authorization.path("state").asText()); assertTrue(authorization.path("result").isNull());
    JsonNode claim=Json.obj("executorId","synthetic-executor","digest",authorization.path("digest"),"executionKey",authorization.path("executionKey"));
    postJson("/origin/executions/"+id+"/claim",claim,403);
    JsonNode running=req(post("/api/v1/origin/executions/"+id+"/claim").header("Idempotency-Key",Json.id())
        .header("X-Origin-Executor-Key","synthetic-executor-only"),owner,claim,200);
    assertEquals("running",running.path("state").asText());
    JsonNode result=Json.obj("executorId","synthetic-executor","attemptId",running.path("attemptId"),"status","succeeded",
        "receipt","synthetic-fixture-result","actualResult",Json.obj("fixture",true,"recordId","test-123"));
    String key=Json.id(), path="/api/v1/origin/executions/"+id+"/result";
    JsonNode done=req(post(path).header("Idempotency-Key",key).header("X-Origin-Executor-Key","synthetic-executor-only"),owner,result,200);
    assertEquals("succeeded",done.path("state").asText());
    req(post(path).header("Idempotency-Key",key),owner,result,403); // Replay also requires executor credentials.
    assertEquals(done,req(post(path).header("Idempotency-Key",key).header("X-Origin-Executor-Key","synthetic-executor-only"),owner,result,200));
    ObjectNode changed=(ObjectNode)result.deepCopy(); changed.put("status","failed");
    req(post(path).header("Idempotency-Key",Json.id()).header("X-Origin-Executor-Key","synthetic-executor-only"),owner,changed,409);
  }

  @Test void businessDecisionCanStillBeReadAndReviewedWithoutOrigin() throws Exception {
    String p=problem(); JsonNode decision=postJson("/decisions",decisionParameters(p,null),201);
    postJson("/decisions/"+decision.path("id").asText()+"/reviews",Json.obj("outcome","合成结果","reflection","合成复盘"),201);
    JsonNode list=req(get("/api/v1/decisions").param("problemId",p),owner,null,200);
    assertEquals(1,list.path("items").size()); assertEquals(1,list.path("items").get(0).path("reviews").size());
  }

  @Test void crossAccountAndDeviceAccessAreRejected() throws Exception {
    String session=session();
    JsonNode msg=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","合成私有消息","attachments",Json.arr().add(upload("note.txt","text/plain",new byte[]{65}))),202);
    JsonNode proposal=newProblemProposal(session);
    String username="member_"+Json.id().replace("-","").substring(0,12);
    String member=postJson("/auth/register",Json.obj("username",username,"password","synthetic-member-password"),201).path("accessToken").asText();
    for(String path:List.of("/origin/sessions/"+session,"/origin/generations/"+msg.path("generationId").asText(),"/origin/proposals/"+proposal.path("id").asText()))
      req(get("/api/v1"+path),member,null,404);
    String fid=chat.history(session).path("messages").get(0).path("attachments").get(0).path("id").asText();
    req(get("/api/v1/origin/attachments/"+fid),member,null,404);
    req(post("/api/v1/origin/proposals/"+proposal.path("id").asText()+"/commit").header("Idempotency-Key",proposal.path("requestId").asText()),member,
        Json.obj("digest",proposal.path("digest"),"requestId",proposal.path("requestId")),404);
    String device=postJson("/devices",Json.obj("name","synthetic-origin-device"),201).path("deviceToken").asText();
    req(get("/api/v1/origin/sessions/"+session),device,null,403);
    assertEquals(0,records("problem"));
  }

  HttpServer provider(java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler) throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/chat/completions",exchange->{try{handler.accept(exchange);}finally{exchange.close();}});
    server.start(); servers.add(server); c.modelBase="http://127.0.0.1:"+server.getAddress().getPort(); c.modelKey="synthetic-provider-only";
    return server;
  }
  static String delta(String text) {
    return "data: "+Json.obj("choices",Json.arr().add(Json.obj("delta",Json.obj("content",text)))).toString()+"\n\n";
  }
  static void successfulStream(com.sun.net.httpserver.HttpExchange exchange,String... chunks) {
    try {
      exchange.getResponseHeaders().set("Content-Type","text/event-stream"); exchange.sendResponseHeaders(200,0);
      for(String chunk:chunks){exchange.getResponseBody().write(delta(chunk).getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
      exchange.getResponseBody().write(("data: "+Json.obj("choices",Json.arr().add(Json.obj("delta",Json.obj(),"finish_reason","stop")))+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
      exchange.getResponseBody().flush();
    }catch(Exception e){throw new RuntimeException(e);}
  }

  @Test void streamingTextImagesAndDocumentsStayInOriginAndReloadFromStorage() throws Exception {
    AtomicReference<JsonNode> request=new AtomicReference<>();
    provider(exchange->{try{request.set(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));successfulStream(exchange,"合成","回答");}catch(Exception e){throw new RuntimeException(e);}});
    String session=session(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
    var before=businessSnapshot();
    JsonNode result=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","请阅读合成资料","attachments",Json.arr()
        .add(upload("picture.png","image/png",bytes.toByteArray()))
        .add(upload("document.txt","text/plain","合成文档：不创建任务。".getBytes(StandardCharsets.UTF_8)))),202);
    String generation=result.path("generationId").asText(); assertTrue(chat.run(generation));
    JsonNode g=chat.publicGeneration(generation);
    assertEquals("succeeded",g.path("state").asText(),g.toString()); assertFalse(g.has("modelSnapshot"));
    assertEquals("合成回答",g.path("partialContent").asText());
    String payload=request.get().toString(); assertTrue(payload.contains("image_url")); assertTrue(payload.contains("合成文档：不创建任务。"));
    assertFalse(request.get().has("tools"));
    OriginChat reopened=new OriginChat(s,new OriginStore(s,auth),settings);
    JsonNode history=reopened.history(session);
    assertEquals(2,history.path("messages").size()); assertEquals("合成回答",history.path("messages").get(1).path("content").asText());
    assertTrue(chat.events(generation,0).path("items").size()>=4);
    String fid=history.path("messages").get(0).path("attachments").get(0).path("id").asText();
    assertArrayEquals(bytes.toByteArray(),reopened.attachment(fid).bytes());
    req(get("/api/v1/origin/attachments/"+Json.id()),owner,null,404);
    assertEquals(before,businessSnapshot());
  }

  @Test void providerFailureAndExplicitRetryKeepOneUserMessage() throws Exception {
    AtomicBoolean fail=new AtomicBoolean(true); AtomicInteger calls=new AtomicInteger();
    provider(exchange->{calls.incrementAndGet(); if(fail.get())try{exchange.sendResponseHeaders(503,-1);}catch(Exception e){throw new RuntimeException(e);}else successfulStream(exchange,"恢复后的合成回答");});
    String session=session(); String id=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","合成失败恢复"),202).path("generationId").asText();
    chat.run(id); assertEquals("failed",chat.publicGeneration(id).path("state").asText());
    fail.set(false); String retry=postJson("/origin/generations/"+id+"/retry",Json.obj(),202).path("generationId").asText();
    assertEquals(retry,postJson("/origin/generations/"+id+"/retry",Json.obj(),202).path("generationId").asText());
    chat.run(retry); assertEquals("succeeded",chat.publicGeneration(retry).path("state").asText());
    assertEquals(2,calls.get());
    assertEquals(1,o.list("message",session).stream().filter(m->m.path("role").asText().equals("user")).count());
    assertEquals(0,records("problem")); assertEquals(0,records("decision"));
  }

  @Test void stopDuringStreamingPreservesPartialAndCannotCommitAnAssistantAfterCancellation() throws Exception {
    CountDownLatch emitted=new CountDownLatch(1), release=new CountDownLatch(1);
    provider(exchange->{try{
      exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
      exchange.getResponseBody().write(delta("已收到片段").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();emitted.countDown();
      release.await(8,TimeUnit.SECONDS);
      exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }catch(Exception ignored){}});
    String session=session(); String id=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","合成停止"),202).path("generationId").asText();
    var pool=Executors.newSingleThreadExecutor();
    try {
      var run=pool.submit(()->{try(var ignored=AccountScope.enter(AccountScope.ADMIN)){return chat.run(id);}});
      assertTrue(emitted.await(5,TimeUnit.SECONDS));
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(chat.publicGeneration(id).path("partialContent").asText().isBlank()&&System.nanoTime()<until) Thread.sleep(10);
      postJson("/origin/generations/"+id+"/stop",Json.obj(),200);release.countDown();run.get(8,TimeUnit.SECONDS);
      assertEquals("stopped",chat.publicGeneration(id).path("state").asText());
      assertEquals("已收到片段",chat.publicGeneration(id).path("partialContent").asText());
      assertEquals(1,o.list("message",session).size());assertEquals(0,records("problem"));
    } finally {release.countDown();pool.shutdownNow();}
  }

  @Test void expiredGenerationRemainsRecoverableAndDoesNotPretendSuccess() throws Exception {
    String session=session(); String id=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","合成进程中断"),202).path("generationId").asText();
    s.write(()->{ObjectNode g=o.get("generation",id);g.put("state","running").put("leaseUntil",Instant.now().minusSeconds(1).toString()).put("partialContent","中断前片段");return o.save("generation",g);});
    assertEquals(1,chat.recoverExpired());
    JsonNode g=chat.publicGeneration(id);assertEquals("failed",g.path("state").asText());
    assertEquals("GENERATION_INTERRUPTED",g.path("error").path("code").asText());assertEquals("中断前片段",g.path("partialContent").asText());
    postJson("/origin/generations/"+id+"/retry",Json.obj(),202);assertEquals(1,o.list("message",session).size());
  }

  @Test void missingProviderNeverProducesAFakeSuccessfulAnswer() throws Exception {
    String session=session();String id=postJson("/origin/sessions/"+session+"/messages",Json.obj("content","模型未配置"),202).path("generationId").asText();
    chat.run(id);JsonNode g=chat.publicGeneration(id);
    assertEquals("failed",g.path("state").asText());assertEquals("MODEL_NOT_CONFIGURED",g.path("error").path("code").asText());
    assertEquals(1,o.list("message",session).size());
  }

  @Test void invalidProposalOrUnsupportedProgressAdapterCannotWriteBusiness() throws Exception {
    String session=session();var before=businessSnapshot();
    postJson("/origin/proposals",Json.obj("sessionId",session,"action","decision.create_problem","parameters",Json.obj("title","缺少目标")),400);
    postJson("/origin/proposals",Json.obj("sessionId",session,"action","progress.report","parameters",Json.obj()),400);
    assertEquals(before,businessSnapshot());assertTrue(o.list("proposal",session).isEmpty());
  }
}
