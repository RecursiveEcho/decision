package dev.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
class DecisionCaptureTest {
  @Autowired Store s;
  @Autowired DecisionCapture capture;
  String problem;
  long clock;

  @BeforeEach
  void reset() {
    String url = System.getenv("TEST_DATABASE_URL");
    assertNotNull(url);
    assertTrue(url.endsWith("/decision_backend_test"), "Never reset a non-test database");
    s.db.execute(
        "TRUNCATE"
            + " records,record_history,jobs,idempotency_keys,analysis_pending,observation_keys,discussion_keys,model_calls,login_attempts"
            + " CASCADE");
    s.db.update("UPDATE context_version SET revision=0");
    problem = problem();
    clock = 0;
  }

  String problem() {
    return s.write(
            () ->
                s.create(
                    "problem",
                    null,
                    Json.obj(
                        "title",
                        "Isolated decision test",
                        "goal",
                        "",
                        "constraints",
                        Json.arr(),
                        "unknowns",
                        Json.arr())))
        .path("id")
        .asText();
  }

  String message(String text) {
    return message(problem, text);
  }

  String message(String p, String text) {
    String time = Instant.parse("2026-11-04T00:00:00Z").plusSeconds(++clock).toString();
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
                        "createdAt",
                        time,
                        "mode",
                        "real",
                        "evidence",
                        Json.arr(),
                        "uncertainties",
                        Json.arr())))
        .path("id")
        .asText();
  }

  ObjectNode candidate(String quote, String choice) {
    return Json.obj("quote", quote, "choice", choice);
  }

  int count() {
    return s.all("decision", problem).size();
  }

  @Test
  void recordsOnlyExpressedChoiceAndGroundedDetailsWithoutChangingContext() {
    String text = "我决定选择方案乙，因为费用更低。希望下周完成。";
    String mid = message(text);
    ObjectNode result =
        capture.capture(
            problem,
            mid,
            candidate("我决定选择方案乙", "方案乙").put("rationale", "费用更低").put("expectedOutcome", "下周完成"));
    assertNotNull(result);
    assertEquals("方案乙", result.path("choice").asText());
    assertEquals("费用更低", result.path("rationale").asText());
    assertEquals("下周完成", result.path("expectedOutcome").asText());
    assertEquals("chat", result.path("origin").asText());
    assertEquals("active", result.path("status").asText());
    assertEquals(mid, result.path("userMessageId").asText());
    assertEquals(0, s.version());
    assertTrue(result.path("evidence").isEmpty());
    assertTrue(result.path("reviews").isEmpty());
  }

  @Test
  void rejectsSyntheticThirdPartyTentativeConditionalQuotedAndQuestionStatements() {
    List<String> messages =
        List.of(
            "【虚构体验场景】我决定选择方案乙。",
            "合成事实：我决定选择方案乙。",
            "如果预算够，我决定选择方案乙。",
            "我决定选择方案乙吗？",
            "我决定选择方案乙好不好",
            "我决定选择方案乙还是方案甲",
            "我可能还是犹豫，我决定选择方案乙。",
            "他说我决定选择方案乙。",
            "朋友说：我决定选择方案乙。",
            "王教授认为：我决定选择方案乙。",
            "他在纸上写：‘我决定选择方案乙。’",
            "\"我决定选择方案乙。\"",
            "> 我决定选择方案乙。",
            "`我决定选择方案乙。`",
            "建议我决定选择方案乙。",
            "我决定选择方案乙这个表述怎么样？");
    for (String text : messages) {
      assertNull(capture.capture(problem, message(text), candidate("我决定选择方案乙", "方案乙")), text);
    }
    assertEquals(0, count());
  }

  @Test
  void permitsACommittedChoiceFollowedByAnUnrelatedQuestion() {
    String text = "我决定选择方案乙。你能帮我排一下时间吗？";
    assertNotNull(capture.capture(problem, message(text), candidate("我决定选择方案乙", "方案乙")));
  }

  @Test
  void abilityAndUnsettledStatementsAreNotCommitments() {
    assertNull(capture.capture(problem, message("我会英语。"), candidate("我会英语", "英语")));
    assertNull(capture.capture(problem, message("我会用Java。"), candidate("我会用Java", "用Java")));
    assertNull(capture.capture(problem, message("我决定不了选择方案乙。"), candidate("我决定不了选择方案乙", "方案乙")));
    assertEquals(0, count());
  }

  @Test
  void choiceMustPreserveNegationAndTheEntireCommittedObject() {
    String negative = message("我决定不买车。");
    assertNull(capture.capture(problem, negative, candidate("我决定不买车", "买车")));
    ObjectNode kept = capture.capture(problem, negative, candidate("我决定不买车", "不买车"));
    assertNotNull(kept);
    assertEquals("不买车", kept.path("choice").asText());

    String contrast = message("我决定不选方案甲而选方案乙。");
    assertNull(capture.capture(problem, contrast, candidate("我决定不选方案甲而选方案乙", "方案甲")));
    assertNull(capture.capture(problem, contrast, candidate("我决定不选方案甲而选方案乙", "选方案乙")));
    ObjectNode contrastKept =
        capture.capture(problem, contrast, candidate("我决定不选方案甲而选方案乙", "不选方案甲而选方案乙"));
    assertNotNull(contrastKept);
    assertEquals("不选方案甲而选方案乙", contrastKept.path("choice").asText());

    String positive = message("我决定选择方案乙。");
    assertNotNull(capture.capture(problem, positive, candidate("我决定选择方案乙", "选择方案乙")));
  }

  @Test
  void croppedQuoteCannotHideOuterDenialOrTailOfTheChoice() {
    assertNull(capture.capture(problem, message("我没有说我决定买车。"), candidate("我决定买车", "买车")));
    assertNull(capture.capture(problem, message("并非我决定买车。"), candidate("我决定买车", "买车")));
    assertNull(capture.capture(problem, message("我不确定我决定不买车。"), candidate("我决定不买车", "不买车")));
    assertNull(capture.capture(problem, message("我决定买车而不是租车。"), candidate("我决定买车", "买车")));
    assertNull(capture.capture(problem, message("我决定不买车。"), candidate("我决定不买", "不买")));
    assertEquals(0, count());
  }

  @Test
  void optionalDetailsCannotDropNegationOrClaimADeniedReason() {
    String first = message("我决定选择方案乙，因为价格不便宜。希望不加班。");
    ObjectNode cropped =
        capture.capture(
            problem,
            first,
            candidate("我决定选择方案乙", "方案乙").put("rationale", "便宜").put("expectedOutcome", "加班"));
    assertNotNull(cropped);
    assertEquals("", cropped.path("rationale").asText());
    assertEquals("", cropped.path("expectedOutcome").asText());

    String second = message("我决定选择方案甲，因为价格不贵。希望不加班。");
    ObjectNode full =
        capture.capture(
            problem,
            second,
            candidate("我决定选择方案甲", "方案甲").put("rationale", "价格不贵").put("expectedOutcome", "不加班"));
    assertNotNull(full);
    assertEquals("价格不贵", full.path("rationale").asText());
    assertEquals("不加班", full.path("expectedOutcome").asText());

    String third = message("我决定选择方案丙，不是因为便宜。我不希望加班。");
    ObjectNode denied =
        capture.capture(
            problem,
            third,
            candidate("我决定选择方案丙", "方案丙").put("rationale", "便宜").put("expectedOutcome", "加班"));
    assertNotNull(denied);
    assertEquals("", denied.path("rationale").asText());
    assertEquals("", denied.path("expectedOutcome").asText());
  }

  @Test
  void optionalDetailsCannotBorrowThirdPartyOrQuotedReasons() {
    String text = "我决定选择方案乙。他说因为费用更低。她说希望下周完成。";
    ObjectNode record =
        capture.capture(
            problem,
            message(text),
            candidate("我决定选择方案乙", "方案乙").put("rationale", "费用更低").put("expectedOutcome", "下周完成"));
    assertNotNull(record);
    assertEquals("", record.path("rationale").asText());
    assertEquals("", record.path("expectedOutcome").asText());
  }

  @Test
  void malformedOrInventedOptionalFieldsDoNotCreateInventedFacts() {
    String text = "我决定选择方案乙。";
    String mid = message(text);
    assertNull(capture.capture(problem, mid, candidate("我决定选择方案甲", "方案甲")));
    assertNull(capture.capture(problem, mid, candidate("我决定选择方案乙", "省钱的方案乙")));
    assertNull(capture.capture(problem, mid, Json.obj("choice", 7, "quote", text)));
    ObjectNode record =
        capture.capture(
            problem,
            mid,
            candidate("我决定选择方案乙", "方案乙")
                .put("rationale", "它比所有方案更便宜")
                .put("expectedOutcome", "下周完成"));
    assertEquals("", record.path("rationale").asText());
    assertEquals("", record.path("expectedOutcome").asText());
    assertEquals(1, count());
  }

  @Test
  void replayReusesTheRecordAndNeverResurrectsDeletedRecord() {
    String mid = message("我决定选择方案乙。");
    ObjectNode c = candidate("我决定选择方案乙", "方案乙");
    ObjectNode first = capture.capture(problem, mid, c);
    assertEquals(first.path("id"), capture.capture(problem, mid, c).path("id"));
    assertEquals(1, count());
    s.write(
        () -> {
          s.delete("decision", first.path("id").asText());
          return null;
        });
    assertNull(capture.capture(problem, mid, c));
    assertEquals(0, count());
    assertEquals(
        1,
        s.db.queryForObject("SELECT count(*) FROM records WHERE kind='decision'", Integer.class));
  }

  @Test
  void explicitChangePreservesHistoryAndOldRetryCannotOverwriteIt() {
    String oldMessage = message("我决定选择方案甲。");
    ObjectNode oldCandidate = candidate("我决定选择方案甲", "方案甲");
    ObjectNode old = capture.capture(problem, oldMessage, oldCandidate);
    String nextMessage = message("我改主意了，我决定选择方案乙。");
    ObjectNode replacement =
        capture.capture(
            problem,
            nextMessage,
            candidate("我决定选择方案乙", "方案乙").put("supersedesId", old.path("id").asText()));
    assertNotNull(replacement);
    assertEquals(old.path("id"), replacement.path("supersedesId"));
    assertEquals("superseded", s.get("decision", old.path("id").asText()).path("status").asText());
    assertEquals(
        "superseded", capture.capture(problem, oldMessage, oldCandidate).path("status").asText());
    assertEquals(
        "active", s.get("decision", replacement.path("id").asText()).path("status").asText());
    assertEquals(
        1,
        s.db.queryForObject(
            "SELECT count(*) FROM record_history WHERE kind='decision'", Integer.class));
    assertEquals(0, s.version());
  }

  @Test
  void cannotReplaceManualCrossProblemOrUnchangedDecision() {
    ObjectNode manual =
        s.write(
            () -> s.create("decision", problem, Json.obj("problemId", problem, "choice", "方案甲")));
    String manualChange = message("我改主意了，我决定选择方案乙。");
    assertNull(
        capture.capture(
            problem,
            manualChange,
            candidate("我决定选择方案乙", "方案乙").put("supersedesId", manual.path("id").asText())));
    String other = problem();
    ObjectNode foreign =
        capture.capture(other, message(other, "我决定选择方案甲。"), candidate("我决定选择方案甲", "方案甲"));
    assertNull(
        capture.capture(
            problem,
            message("我改主意了，我决定选择方案乙。"),
            candidate("我决定选择方案乙", "方案乙").put("supersedesId", foreign.path("id").asText())));
    ObjectNode old = capture.capture(problem, message("我决定选择方案甲。"), candidate("我决定选择方案甲", "方案甲"));
    assertNull(
        capture.capture(
            problem,
            message("我决定选择方案乙。"),
            candidate("我决定选择方案乙", "方案乙").put("supersedesId", old.path("id").asText())));
    assertEquals("active", s.get("decision", old.path("id").asText()).path("status").asText());
  }

  @Test
  void delayedOldJobCannotAddChoiceAfterNewerChoiceWasSaved() {
    String older = message("我决定选择方案甲。");
    String newer = message("我决定选择方案乙。");
    assertNotNull(capture.capture(problem, newer, candidate("我决定选择方案乙", "方案乙")));
    assertNull(capture.capture(problem, older, candidate("我决定选择方案甲", "方案甲")));
    assertEquals(1, count());
  }

  @Test
  void chronologyUsesDatabaseSequenceEvenWithIdenticalTimestampsAndDeletedNewerChoice() {
    String older = message("我决定选择方案甲。");
    String newer = message("我决定选择方案乙。");
    s.write(
        () -> {
          ObjectNode first = s.get("message", older), second = s.get("message", newer);
          second.set("createdAt", first.path("createdAt"));
          s.save("message", second);
          return null;
        });
    ObjectNode later = capture.capture(problem, newer, candidate("我决定选择方案乙", "方案乙"));
    assertNotNull(later);
    // Keep revision fixed here to isolate chronology protection from context invalidation.
    s.db.update("UPDATE records SET deleted=true WHERE id=?::uuid", later.path("id").asText());
    assertNull(capture.capture(problem, older, candidate("我决定选择方案甲", "方案甲")));
    assertEquals(0, count());
  }

  @Test
  void staleDeletedWrongProblemAssistantAndMockMessagesAreIgnored() {
    String stale = message("我决定选择方案乙。");
    s.write(
        () -> {
          s.bump();
          return null;
        });
    assertNull(capture.capture(problem, stale, candidate("我决定选择方案乙", "方案乙")));
    String deleted = message("我决定选择方案乙。");
    s.write(
        () -> {
          s.delete("message", deleted);
          return null;
        });
    assertNull(capture.capture(problem, deleted, candidate("我决定选择方案乙", "方案乙")));
    String mid = message("我决定选择方案乙。");
    assertNull(capture.capture(problem(), mid, candidate("我决定选择方案乙", "方案乙")));
    s.write(
        () -> {
          ObjectNode user = s.get("message", mid);
          user.put("role", "assistant");
          s.save("message", user);
          return null;
        });
    assertNull(capture.capture(problem, mid, candidate("我决定选择方案乙", "方案乙")));
    String mock = message("我决定选择方案乙。");
    s.write(
        () -> {
          ObjectNode user = s.get("message", mock);
          user.put("mode", "mock");
          s.save("message", user);
          return null;
        });
    assertNull(capture.capture(problem, mock, candidate("我决定选择方案乙", "方案乙")));
    assertEquals(0, count());
  }

  @Test
  void contextIsBoundedAndContainsOnlyActiveAutomaticDecisions() {
    for (int i = 0; i < 12; i++) {
      String q = "我决定选择选项" + i;
      capture.capture(problem, message(q + "。"), candidate(q, "选项" + i));
    }
    s.write(() -> s.create("decision", problem, Json.obj("problemId", problem, "choice", "手动选择")));
    var context = capture.context(problem);
    assertEquals(10, context.size());
    assertEquals("选项11", context.get(0).path("choice").asText());
    context.forEach(n -> assertEquals("active", n.path("status").asText()));
  }

  @Test
  void outerFailureRollsBackReplacementWithTheAnswer() {
    ObjectNode old = capture.capture(problem, message("我决定选择方案甲。"), candidate("我决定选择方案甲", "方案甲"));
    String next = message("我改主意了，我决定选择方案乙。");
    assertThrows(
        IllegalStateException.class,
        () ->
            s.write(
                () -> {
                  assertNotNull(
                      capture.capture(
                          problem,
                          next,
                          candidate("我决定选择方案乙", "方案乙")
                              .put("supersedesId", old.path("id").asText())));
                  throw new IllegalStateException("synthetic answer save failure");
                }));
    assertEquals(1, count());
    assertEquals("active", s.get("decision", old.path("id").asText()).path("status").asText());
  }

  @Test
  void concurrentReplayHasOnePersistedDecision() throws Exception {
    String mid = message("我决定选择方案乙。");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<String>> responses = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        responses.add(
            pool.submit(
                () ->
                    capture
                        .capture(problem, mid, candidate("我决定选择方案乙", "方案乙"))
                        .path("id")
                        .asText()));
      Set<String> ids = new HashSet<>();
      for (Future<String> response : responses) ids.add(response.get(10, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
      assertEquals(1, count());
    } finally {
      pool.shutdownNow();
    }
  }
}
