package dev.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
class ChatMemoryTest {
  @Autowired Store s;
  @Autowired ChatMemory memory;

  @BeforeEach
  void reset() {
    assertTrue(System.getenv("TEST_DATABASE_URL").endsWith("/decision_backend_test"));
    s.db.execute(
        "TRUNCATE"
            + " records,jobs,record_history,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts,chat_memory_tombstones"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    s.db.update("UPDATE chat_memory_state SET epoch=0");
  }

  String problem() {
    return s.write(
        () ->
            s.create(
                    "problem",
                    null,
                    Json.obj(
                        "title",
                        "Isolated memory fixture",
                        "goal",
                        "Fixture only",
                        "constraints",
                        Json.arr(),
                        "unknowns",
                        Json.arr()))
                .path("id")
                .asText());
  }

  String message(String problem, String content) {
    return s.write(
        () -> {
          String id =
              s.create(
                      "message",
                      problem,
                      Json.obj(
                          "problemId",
                          problem,
                          "role",
                          "user",
                          "content",
                          content,
                          "evidence",
                          Json.arr(),
                          "uncertainties",
                          Json.arr(),
                          "mode",
                          "mock"))
                  .path("id")
                  .asText();
          s.db.update(
              "UPDATE records SET memory_epoch=? WHERE id=?::uuid", memory.currentEpoch(), id);
          return id;
        });
  }

  ObjectNode add(String quote, String scope) {
    return Json.obj("action", "ADD", "content", quote, "scope", scope, "sourceQuote", quote);
  }

  ArrayNode apply(String p, String id, JsonNode action, ChatMemory.Snapshot snapshot) {
    return memory.apply(p, id, Json.arr().add(action), snapshot);
  }

  String learn(String p, String quote, String scope) {
    String id = message(p, quote);
    ArrayNode changes = apply(p, id, add(quote, scope), memory.retrieve(p, quote));
    assertEquals(1, changes.size());
    return changes.get(0).path("memoryId").asText();
  }

  @Test
  void extractsFromCurrentQuoteAndRetrievesStableGlobalAcrossProblems() {
    String p = problem(), second = problem();
    String id = learn(p, "我喜欢简短回答", "global");
    ObjectNode saved = s.get("memory", id);
    assertEquals("chat", saved.path("origin").asText());
    assertEquals("user_reported", saved.path("kind").asText());
    assertEquals("我喜欢简短回答", saved.path("sourceQuote").asText());
    assertEquals(0, s.version());
    assertEquals(0, memory.currentEpoch());
    assertEquals(1, memory.list(second).size());
    ChatMemory.Snapshot snapshot = memory.retrieve(second, "回答别太长");
    assertEquals(id, snapshot.memories().get(0).path("id").asText());
    String temporary = learn(p, "我今天只有一个小时", "global");
    assertEquals("problem", s.get("memory", temporary).path("scope").asText());
    assertFalse(
        memory.list(second).stream().anyMatch(m -> m.path("id").asText().equals(temporary)));
  }

  @Test
  void sourceFenceDeduplicatesMultipleActionsAndAddDoesNotInvalidateSnapshot() {
    String p = problem();
    ChatMemory.Snapshot before = memory.retrieve(p, "回答");
    String id = message(p, "我喜欢简短回答。我习惯早上阅读。");
    ArrayNode actions = Json.arr().add(add("我喜欢简短回答", "global")).add(add("我习惯早上阅读", "global"));
    assertEquals(2, memory.apply(p, id, actions, before).size());
    assertEquals(0, memory.apply(p, id, actions, before).size());
    assertEquals(2, memory.list(p).size());
    assertEquals(0, memory.currentEpoch());
    assertDoesNotThrow(() -> memory.requireCurrent(before));
    String repeat = message(p, "我喜欢简短回答");
    assertEquals(0, apply(p, repeat, add("我喜欢简短回答", "global"), memory.retrieve(p, "回答")).size());
  }

  @Test
  void concurrentRepeatedFactHasOneStoredMemory() throws Exception {
    String p = problem();
    String first = message(p, "我喜欢简短回答"), second = message(p, "我喜欢简短回答");
    ChatMemory.Snapshot snapshot = memory.retrieve(p, "回答");
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> apply(p, first, add("我喜欢简短回答", "global"), snapshot).size());
      var b = executor.submit(() -> apply(p, second, add("我喜欢简短回答", "global"), snapshot).size());
      assertEquals(
          1,
          a.get(10, java.util.concurrent.TimeUnit.SECONDS)
              + b.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, memory.list(p).size());
      assertEquals(0, memory.currentEpoch());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void explicitUpdateUsesVisibleRevisionAndInvalidatesOldSnapshotWithoutGlobalBump() {
    String p = problem();
    String target = learn(p, "我喜欢长篇回答", "global");
    String current = "我喜欢长篇回答这条记忆记错了，我现在喜欢简短回答";
    ChatMemory.Snapshot before = memory.retrieve(p, current);
    String msg = message(p, current);
    var action =
        Json.obj(
            "action",
            "UPDATE",
            "content",
            "我现在喜欢简短回答",
            "scope",
            "global",
            "targetId",
            target,
            "expectedRevision",
            1,
            "sourceQuote",
            "我现在喜欢简短回答");
    assertEquals(1, apply(p, msg, action, before).size());
    assertEquals("我现在喜欢简短回答", s.get("memory", target).path("content").asText());
    assertEquals(2, s.get("memory", target).path("revision").asInt());
    assertEquals(1, memory.currentEpoch());
    assertEquals(0, s.version());
    assertThrows(ApiError.class, () -> memory.requireCurrent(before));
    assertEquals(0, apply(p, msg, action, memory.retrieve(p, current)).size());
    assertTrue(
        s.db.queryForObject(
                "SELECT count(*) FROM record_history WHERE record_id=?::uuid",
                Integer.class,
                target)
            > 0);
  }

  @Test
  void explicitForgetCreatesTombstoneAndOnlyNewRestatementMayReturn() {
    String p = problem();
    String target = learn(p, "我喜欢简短回答", "global");
    String queued = message(p, "我喜欢简短回答");
    String quote = "请忘记我喜欢简短回答这条记忆";
    String deletion = message(p, quote);
    ChatMemory.Snapshot before = memory.retrieve(p, quote);
    var action =
        Json.obj(
            "action", "DELETE", "targetId", target, "expectedRevision", 1, "sourceQuote", quote);
    assertEquals(1, apply(p, deletion, action, before).size());
    assertEquals("retracted", s.get("memory", target).path("status").asText());
    assertEquals(1, memory.currentEpoch());
    assertEquals(0, s.version());
    assertThrows(ApiError.class, () -> memory.requireCurrent(before));
    assertTrue(memory.retrieve(p, "回答").memories().isEmpty());
    assertEquals(0, apply(p, queued, add("我喜欢简短回答", "global"), memory.retrieve(p, "回答")).size());
    String fresh = message(p, "我喜欢简短回答");
    assertEquals(1, apply(p, fresh, add("我喜欢简短回答", "global"), memory.retrieve(p, "回答")).size());
  }

  @Test
  void negatedForgetAndDoubleNegationNeverDelete() {
    String p = problem();
    String target = learn(p, "我喜欢简短回答", "global");
    for (String original :
        List.of(
            "不要忘记我喜欢简短回答这条记忆", "别删除我喜欢简短回答这条记忆",
            "请不要再忘记我喜欢简短回答这条记忆", "请不要不删除我喜欢简短回答这条记忆",
            "不能不忘记我喜欢简短回答这条记忆", "不是让你删除我喜欢简短回答这条记忆",
            "不需要删除我喜欢简短回答这条记忆", "请忘记我喜欢简短回答这条记忆，但别删除它",
            "Don't forget 我喜欢简短回答", "Please do not delete 我喜欢简短回答")) {
      var action =
          Json.obj(
              "action",
              "DELETE",
              "targetId",
              target,
              "expectedRevision",
              1,
              "sourceQuote",
              original);
      assertEquals(
          0, apply(p, message(p, original), action, memory.retrieve(p, original)).size(), original);
    }
    assertEquals("active", s.get("memory", target).path("status").asText());
    assertEquals(0, memory.currentEpoch());
  }

  @Test
  void explicitNotebookTopicSupportsNaturalCorrectionAndForget() {
    String p = problem();
    String assertion = "我平时挑选笔记本时更喜欢方格纸，不喜欢横线纸。";
    String original = assertion + "以后帮我选笔记本就按这个偏好。";
    var created =
        apply(p, message(p, original), add(assertion, "global"), memory.retrieve(p, original));
    assertEquals(1, created.size());
    String target = created.get(0).path("memoryId").asText();
    String correction = "我现在更喜欢点阵纸，不再喜欢方格纸，以后选笔记本按点阵纸考虑。";
    var updated =
        Json.obj(
            "action",
            "UPDATE",
            "targetId",
            target,
            "expectedRevision",
            1,
            "scope",
            "global",
            "sourceQuote",
            correction,
            "content",
            correction);
    assertEquals(
        1, apply(p, message(p, correction), updated, memory.retrieve(p, correction)).size());
    assertEquals(correction, s.get("memory", target).path("content").asText());
    String forgetting = "忘记我关于笔记本内页的偏好。";
    var deleted =
        Json.obj(
            "action",
            "DELETE",
            "targetId",
            target,
            "expectedRevision",
            2,
            "sourceQuote",
            forgetting);
    assertEquals(
        1, apply(p, message(p, forgetting), deleted, memory.retrieve(p, forgetting)).size());
    assertEquals("retracted", s.get("memory", target).path("status").asText());
  }

  @Test
  void notebookComputerTopicCannotDeletePaperNotebookPreference() {
    String p = problem();
    String target = learn(p, "我平时挑选笔记本时喜欢方格纸", "global");
    String original = "忘记我关于笔记本电脑的偏好。";
    var deleted =
        Json.obj(
            "action", "DELETE", "targetId", target, "expectedRevision", 1, "sourceQuote", original);
    assertEquals(0, apply(p, message(p, original), deleted, memory.retrieve(p, original)).size());
    assertEquals("active", s.get("memory", target).path("status").asText());
    assertEquals(1, s.get("memory", target).path("revision").asInt());
    assertEquals(0, memory.currentEpoch());
  }

  @Test
  void ambiguousTopicOutsideRetrievedWindowCannotAuthorizeDelete() {
    String p = problem();
    String first = learn(p, "我平时挑选笔记本时喜欢方格纸", "global");
    String second = learn(p, "我平时挑选笔记本时喜欢蓝色封面", "global");
    String original = "忘记我关于笔记本内页的偏好。";
    ChatMemory.Snapshot all = memory.retrieve(p, original);
    ArrayNode onlyFirst = Json.arr();
    all.memories()
        .forEach(
            n -> {
              if (n.path("id").asText().equals(first)) onlyFirst.add(n);
            });
    var partial = new ChatMemory.Snapshot(all.epoch(), onlyFirst);
    var deleted =
        Json.obj(
            "action", "DELETE", "targetId", first, "expectedRevision", 1, "sourceQuote", original);
    assertEquals(0, apply(p, message(p, original), deleted, partial).size());
    assertEquals("active", s.get("memory", first).path("status").asText());
    assertEquals("active", s.get("memory", second).path("status").asText());
  }

  @Test
  void ownerCorrectionProtectsManualEditAndBlocksInFlightOrRetriedOldInput() {
    String p = problem();
    String target = learn(p, "我喜欢长篇回答", "global");
    String queued = message(p, "我喜欢长篇回答");
    ChatMemory.Snapshot inFlight = memory.retrieve(p, "回答");
    s.write(
        () -> {
          ObjectNode old = s.get("memory", target);
          memory.manualChange(old);
          old.put("content", "我喜欢简短回答").put("revision", 2);
          s.save("memory", old);
          return null;
        });
    assertEquals(0, apply(p, queued, add("我喜欢长篇回答", "global"), inFlight).size());
    assertEquals(0, apply(p, queued, add("我喜欢长篇回答", "global"), memory.retrieve(p, "回答")).size());
    String now = "我以前喜欢简短回答，现在我喜欢长篇回答";
    String id = message(p, now);
    var update =
        Json.obj(
            "action",
            "UPDATE",
            "targetId",
            target,
            "expectedRevision",
            2,
            "content",
            "我喜欢长篇回答",
            "sourceQuote",
            now,
            "scope",
            "global");
    assertEquals(0, apply(p, id, update, memory.retrieve(p, now)).size());
    assertEquals("我喜欢简短回答", s.get("memory", target).path("content").asText());
    assertEquals(0, s.version());
  }

  @Test
  void ownerDeletionDoesNotResurrectFromOlderSnapshotOrSource() {
    String p = problem();
    String target = learn(p, "我习惯早上阅读", "global");
    String queued = message(p, "我习惯早上阅读");
    ChatMemory.Snapshot snapshot = memory.retrieve(p, "阅读");
    s.write(
        () -> {
          memory.manualChange(s.get("memory", target));
          s.db.update("UPDATE records SET deleted=true WHERE id=?::uuid", target);
          return null;
        });
    assertThrows(ApiError.class, () -> memory.requireCurrent(snapshot));
    assertEquals(0, apply(p, queued, add("我习惯早上阅读", "global"), memory.retrieve(p, "阅读")).size());
    assertTrue(memory.retrieve(p, "阅读").memories().isEmpty());
    String fresh = message(p, "我习惯早上阅读");
    assertEquals(1, apply(p, fresh, add("我习惯早上阅读", "global"), memory.retrieve(p, "阅读")).size());
  }

  @Test
  void preservesNegationAndRejectsTruncatedCommandsOrThirdPartyAssertions() {
    String p = problem();
    String negative = "我不喜欢喝咖啡";
    String msg = message(p, negative);
    var truncated = add(negative, "global").put("content", "喜欢喝咖啡");
    assertEquals(0, apply(p, msg, truncated, memory.retrieve(p, negative)).size());
    String fresh = message(p, negative);
    assertEquals(1, apply(p, fresh, add(negative, "global"), memory.retrieve(p, negative)).size());
    assertEquals(negative, memory.list(p).get(0).path("content").asText());
    for (String original :
        List.of(
            "我不认为我喜欢简短回答",
            "请忘记我喜欢简短回答这条记忆",
            "我曾经说过我喜欢简短回答，但我现在不再喜欢",
            "我以前喜欢简短回答，但现在不喜欢",
            "我喜欢简短回答，但我不再喜欢简短回答",
            "我妈妈说她喜欢喝咖啡",
            "我妈妈喜欢喝咖啡",
            "我的朋友喜欢简短回答")) {
      String id = message(p, original);
      String quote = original.contains("我喜欢简短回答") ? "我喜欢简短回答" : original;
      assertEquals(
          0, apply(p, id, add(quote, "global"), memory.retrieve(p, original)).size(), original);
    }
    assertEquals(1, memory.list(p).size());
  }

  @Test
  void similarPreferenceWordsNeverAuthorizeDeletingOrUpdatingAnotherObject() {
    String p = problem();
    String tea = learn(p, "我喜欢喝茶", "global");
    String coffee = learn(p, "我喜欢喝咖啡", "global");
    String forgetting = "请忘记我喜欢喝咖啡这条记忆";
    var wrongDelete =
        Json.obj(
            "action", "DELETE", "targetId", tea, "expectedRevision", 1, "sourceQuote", forgetting);
    assertEquals(
        0, apply(p, message(p, forgetting), wrongDelete, memory.retrieve(p, forgetting)).size());
    String correction = "我喜欢喝咖啡这条记忆记错了，我现在喜欢喝牛奶";
    var wrongUpdate =
        Json.obj(
            "action",
            "UPDATE",
            "targetId",
            tea,
            "expectedRevision",
            1,
            "scope",
            "global",
            "sourceQuote",
            "我现在喜欢喝牛奶",
            "content",
            "我现在喜欢喝牛奶");
    assertEquals(
        0, apply(p, message(p, correction), wrongUpdate, memory.retrieve(p, correction)).size());
    assertEquals("active", s.get("memory", tea).path("status").asText());
    assertEquals("我喜欢喝茶", s.get("memory", tea).path("content").asText());
    assertEquals(0, memory.currentEpoch());
    var correctDelete = wrongDelete.deepCopy().put("targetId", coffee);
    assertEquals(
        1, apply(p, message(p, forgetting), correctDelete, memory.retrieve(p, forgetting)).size());
    assertEquals("retracted", s.get("memory", coffee).path("status").asText());
  }

  @Test
  void rejectsNonPersonalFictionQuotedHypotheticalQuestionsAndUnboundQuotes() {
    String p = problem();
    List<String> originals =
        List.of(
            "【合成测试场景】我喜欢简短回答",
            "如果我喜欢简短回答会怎样",
            "朋友说我喜欢简短回答",
            "他说：我喜欢简短回答",
            "引用：“我喜欢简短回答”",
            "请帮我写一段：我喜欢简短回答",
            "我可能喜欢简短回答",
            "我在考虑是否喜欢简短回答",
            "我还在考虑要不要喜欢简短回答",
            "我喜欢简短回答吗？",
            "附件里写着我喜欢简短回答");
    for (String original : originals) {
      String id = message(p, original);
      assertEquals(
          0,
          apply(
                  p,
                  id,
                  add(original.startsWith("我") ? original : "我喜欢简短回答", "global"),
                  memory.retrieve(p, original))
              .size(),
          original);
    }
    String id = message(p, "今天讨论阅读");
    assertEquals(0, apply(p, id, add("我喜欢简短回答", "global"), memory.retrieve(p, "阅读")).size());
    assertTrue(memory.list(p).isEmpty());
  }

  @Test
  void cannotModifyManualUnseenStaleOrUnrelatedTarget() {
    String p = problem();
    String automatic = learn(p, "我喜欢长篇回答", "global");
    String manual =
        s.write(
            () ->
                s.create(
                        "memory",
                        p,
                        Json.obj(
                            "problemId",
                            p,
                            "content",
                            "我喜欢下午阅读",
                            "kind",
                            "user_reported",
                            "status",
                            "active",
                            "revision",
                            1,
                            "evidence",
                            Json.arr()))
                    .path("id")
                    .asText());
    String quote = "我以前喜欢长篇回答，现在我喜欢简短回答";
    for (String id : List.of(manual, automatic)) {
      var action =
          Json.obj(
              "action",
              "UPDATE",
              "targetId",
              id,
              "expectedRevision",
              99,
              "content",
              "我喜欢简短回答",
              "sourceQuote",
              quote);
      assertEquals(0, apply(p, message(p, quote), action, memory.retrieve(p, quote)).size());
    }
    var unseen =
        Json.obj(
            "action",
            "DELETE",
            "targetId",
            automatic,
            "expectedRevision",
            1,
            "sourceQuote",
            "请忘记我喜欢长篇回答这条记忆");
    assertEquals(
        0,
        apply(
                p,
                message(p, unseen.path("sourceQuote").asText()),
                unseen,
                new ChatMemory.Snapshot(memory.currentEpoch(), Json.arr()))
            .size());
    String different = "请忘记我喜欢喝咖啡这条记忆";
    unseen.put("sourceQuote", different);
    assertEquals(0, apply(p, message(p, different), unseen, memory.retrieve(p, "回答")).size());
    assertEquals("active", s.get("memory", automatic).path("status").asText());
  }

  @Test
  void retrievalIsBoundedLexicalAndScopeAware() {
    String p = problem(), other = problem();
    for (int i = 0; i < 22; i++) {
      int n = i;
      s.write(
          () ->
              s.create(
                  "memory",
                  p,
                  Json.obj(
                      "problemId",
                      p,
                      "content",
                      "我喜欢阅读第" + n + "类书",
                      "kind",
                      "user_reported",
                      "status",
                      "active",
                      "revision",
                      1,
                      "scope",
                      "global",
                      "origin",
                      "chat",
                      "sourceQuote",
                      "我喜欢阅读",
                      "evidence",
                      Json.arr())));
    }
    String privateLocal =
        s.write(
            () ->
                s.create(
                        "memory",
                        p,
                        Json.obj(
                            "problemId",
                            p,
                            "content",
                            "这个问题的专用条件",
                            "kind",
                            "user_reported",
                            "status",
                            "active",
                            "revision",
                            1,
                            "scope",
                            "problem",
                            "evidence",
                            Json.arr()))
                    .path("id")
                    .asText());
    ChatMemory.Snapshot retrieved = memory.retrieve(other, "阅读");
    assertTrue(retrieved.memories().size() <= 16);
    for (JsonNode item : retrieved.memories())
      assertNotEquals(privateLocal, item.path("id").asText());
    assertTrue(
        ChatMemory.tokens("Hello world 中文阅读")
            .containsAll(Set.of("hello", "world", "中文", "文阅", "阅读")));
    assertTrue(memory.retrieve(other, "完全无关xyz").memories().size() <= 4);
  }
}
