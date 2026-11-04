package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Records the user's expressed choice; it never chooses on the user's behalf. */
@Component
class DecisionCapture {
  private static final Pattern COMMITMENT =
      Pattern.compile(
          "(?:^|[，,：:\\s])(?:(?:(?:这次|这回|今天|今晚|明天|现在|接下来|今后|以后|从现在起)\\s*)?我(?:现在|已经|最终|还是|确定|正式|明确|就|这次)?(?:决定|选择|选定|选|承诺)|就(?:选|定|用)|I(?:"
              + " have)? (?:decided|choose|will)|I['’]ve decided)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SYNTHETIC =
      Pattern.compile(
          "虚构|合成|测试场景|测试用例|测试数据|验收场景|示例|举例|假想|演练|role.?play|synthetic|fictional",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern UNCERTAIN =
      Pattern.compile(
          "也许|可能|考虑|犹豫|纠结|尚未|还没|还未|没决定|未决定|决定不了|决定不下来|无法决定|不能决定|没法决定|不是真的|是假的|不确定|要不要|是不是|是否|怎么样|怎么办|可以吗|会不会|如果|假如|假设|假定|若是|倘若|除非|建议|不妨|这句话|这段话|这个句子|这个表述|原话是|台词|例句|措辞|说法|\\b(?:should|maybe|might|if|consider|undecided|suppose)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern THIRD_PARTY =
      Pattern.compile(
          "(?:他|她|别人|朋友|同事|客户|老板|家人|同学|小王|小李).{0,12}(?:说|决定|选择|选定)|(?:he|she|they)"
              + " (?:said|says|decided|chose)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHANGE =
      Pattern.compile(
          "改主意|改成|改为|换成|不再|取消之前|取消原先|重新决定|撤回|改选|changed my mind|instead", Pattern.CASE_INSENSITIVE);
  private static final Pattern REASON =
      Pattern.compile("因为|原因|理由|由于|because|reason", Pattern.CASE_INSENSITIVE);
  private static final Pattern QUESTION =
      Pattern.compile("好不好|行不行|能不能|可不可以|你觉得|你认为|(?:选择|选定|选).{1,80}还是");
  private static final Pattern ATTRIBUTION =
      Pattern.compile("(?:说|问|觉得|认为|建议|要求|让|写道|提到|说过)\\s*[:：]?\\s*$");
  private static final Pattern EXPECTATION =
      Pattern.compile("希望|预期|期望|为了|目的是|expect|hope|so that", Pattern.CASE_INSENSITIVE);
  private static final Pattern OUTER_NEGATION =
      Pattern.compile(
          "没有|从未|不曾|并不|并非|不是|否认|不认同|不同意|不表示|不代表|不意味着|别理解|不要理解|\\b(?:not|never|didn't|don't)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern POSITIVE_CHOICE_VERB = Pattern.compile("^(?:选择|选定|选用|选|采用)\\s*");
  final Store s;

  DecisionCapture(Store s) {
    this.s = s;
  }

  static String prompt() {
    return "当前用户原话明确表达本人已作出的选择或承诺时，返回decisionCandidate:{choice,quote,rationale?,expectedOutcome?,supersedesId?}；不符合则省略。"
               + "quote必须是当前原话中包含明确选择的完整原句，choice保留承诺动词后完整行动及选择对象（购买/采用等行动动词也保留）和全部否定/对比词，不得从不买车截成买车，不自由改写。"
               + "疑问、犹豫、假设、建议、第三人、转述、引号中的话、合成或虚构场景、附件和历史助手内容不能作为本人决定。"
               + "未明确说出的理由和预期省略；提供时也只能逐字摘取当前原话。只有用户明确改口时，才可用supersedesId指向当前问题已有的自动决定。"
               + "不要在content声称已经保存决定；服务端验证并实际保存后才会显示记录。没有明确选择时省略decisionCandidate。";
  }

  /** The outer Worker transaction also saves the answer; nested writes share that transaction. */
  ObjectNode capture(String problemId, String userMessageId, JsonNode candidate) {
    return s.write(() -> captureLocked(problemId, userMessageId, candidate));
  }

  private ObjectNode captureLocked(String problemId, String userMessageId, JsonNode candidate) {
    ObjectNode user;
    try {
      user = s.get("message", userMessageId);
      if (!user.path("problemId").asText().equals(problemId)
          || !user.path("role").asText().equals("user")) return null;
      userMessageId = user.path("id").asText();
    } catch (ApiError e) {
      return null;
    }

    // Include soft-deleted decisions: retry must never resurrect a manually removed record.
    List<Map<String, Object>> previous =
        s.db.queryForList(
            "SELECT data::text,deleted FROM records WHERE kind='decision' AND"
                + " data->>'origin'='chat' AND data->>'userMessageId'=? LIMIT 1",
            userMessageId);
    if (!previous.isEmpty()) {
      if ((Boolean) previous.get(0).get("deleted")) return null;
      return (ObjectNode) Json.parse((String) previous.get(0).get("data"));
    }

    try {
      s.requireCurrentUserMessage(problemId, userMessageId);
    } catch (ApiError e) {
      return null;
    }
    if (candidate == null || !candidate.isObject() || user.path("mode").asText().equals("mock"))
      return null;
    String content = user.path("content").asText();
    String quote = field(candidate, "quote", 12000);
    String choice = field(candidate, "choice", 4000);
    if (quote == null
        || choice == null
        || !quote.contains(choice)
        || SYNTHETIC.matcher(content).find()
        || content.contains("```")
        || !directCommitment(content, quote, choice)) return null;

    long sequence = messageSequence(userMessageId);
    // A delayed older Job cannot introduce an earlier choice after a newer one was recorded.
    // Tombstones also retain chronology: deleting a newer choice does not authorize reviving an old
    // one.
    if (s.db.queryForObject(
            "SELECT count(*) FROM records d JOIN records m ON m.id::text=d.data->>'userMessageId'"
                + " WHERE d.kind='decision' AND d.problem_id=?::uuid AND d.data->>'origin'='chat'"
                + " AND m.chat_source_seq>?",
            Integer.class,
            problemId,
            sequence)
        > 0) return null;

    ObjectNode replaced = null;
    String targetId = null;
    if (candidate.hasNonNull("supersedesId")) {
      targetId = field(candidate, "supersedesId", 36);
      if (targetId == null || !CHANGE.matcher(content).find()) return null;
      try {
        replaced = s.get("decision", targetId);
      } catch (ApiError e) {
        return null;
      }
      if (!replaced.path("origin").asText().equals("chat")
          || !replaced.path("problemId").asText().equals(problemId)
          || !replaced.path("status").asText().equals("active")) return null;
      ObjectNode oldSource = sourceMessage(replaced);
      if (oldSource == null || messageSequence(oldSource.path("id").asText()) >= sequence)
        return null;
      targetId = replaced.path("id").asText();
    }

    ObjectNode data =
        Json.obj(
            "problemId",
            problemId,
            "choice",
            choice,
            "rationale",
            supportedDetail(candidate, "rationale", 10000, content, REASON),
            "expectedOutcome",
            supportedDetail(candidate, "expectedOutcome", 4000, content, EXPECTATION),
            "evidence",
            Json.arr(),
            "confirmedAt",
            user.path("createdAt").asText(),
            "reviewAt",
            null,
            "revision",
            1,
            "reviews",
            Json.arr(),
            "origin",
            "chat",
            "userMessageId",
            userMessageId,
            "quote",
            quote,
            "status",
            "active");
    if (targetId != null) data.put("supersedesId", targetId);
    ObjectNode saved = s.create("decision", problemId, data);
    if (replaced != null) {
      replaced.put("status", "superseded");
      replaced.put("revision", replaced.path("revision").asInt(1) + 1);
      s.save("decision", replaced);
    }
    return saved;
  }

  /** IDs here only authorize proposing a replacement, never deciding that one should occur. */
  ArrayNode context(String problemId) {
    ArrayNode result = Json.arr();
    List<ObjectNode> all =
        s.db.query(
            "SELECT d.data::text FROM records d JOIN records m ON"
                + " m.id::text=d.data->>'userMessageId' WHERE d.kind='decision' AND"
                + " d.problem_id=?::uuid AND NOT d.deleted AND d.data->>'origin'='chat' AND"
                + " d.data->>'status'='active' AND m.kind='message' AND NOT m.deleted AND"
                + " m.problem_id=d.problem_id AND m.data->>'role'='user' ORDER BY m.chat_source_seq"
                + " DESC LIMIT 10",
            (rs, i) -> (ObjectNode) Json.parse(rs.getString(1)),
            problemId);
    for (ObjectNode decision : all) {
      if (!decision.path("origin").asText().equals("chat")
          || !decision.path("status").asText().equals("active")
          || sourceMessage(decision) == null) continue;
      result.add(
          Json.obj(
              "id",
              decision.path("id"),
              "choice",
              decision.path("choice"),
              "userMessageId",
              decision.path("userMessageId"),
              "status",
              "active"));
      if (result.size() == 10) break;
    }
    return result;
  }

  private ObjectNode sourceMessage(ObjectNode decision) {
    try {
      ObjectNode source = s.get("message", decision.path("userMessageId").asText());
      return source.path("role").asText().equals("user")
              && source.path("problemId").asText().equals(decision.path("problemId").asText())
          ? source
          : null;
    } catch (ApiError e) {
      return null;
    }
  }

  private static String field(JsonNode candidate, String key, int max) {
    JsonNode value = candidate.path(key);
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
      return null;
    return value.asText();
  }

  private static String supportedDetail(
      JsonNode candidate, String key, int max, String content, Pattern marker) {
    String value = field(candidate, key, max);
    if (value == null || !content.contains(value)) return "";
    int at = content.indexOf(value);
    int start = sentenceStart(content, at);
    int end = at + value.length();
    while (end < content.length() && "。.!！？?\n".indexOf(content.charAt(end)) < 0) end++;
    String sentence = content.substring(start, end);
    if (insideQuotation(content, at)
        || THIRD_PARTY.matcher(sentence).find()
        || UNCERTAIN.matcher(sentence).find()
        || QUESTION.matcher(sentence).find()
        || (end < content.length() && "？?".indexOf(content.charAt(end)) >= 0)) return "";
    var markers = marker.matcher(sentence);
    while (markers.find()) {
      int markerAt = start + markers.start();
      int clauseStart = markerAt;
      while (clauseStart > start && "，,；;".indexOf(content.charAt(clauseStart - 1)) < 0)
        clauseStart--;
      String prefix = content.substring(clauseStart, markerAt);
      if (OUTER_NEGATION.matcher(prefix).find()
          || prefix.stripTrailing().endsWith("不")
          || prefix.stripTrailing().endsWith("没")) continue;
      int coreStart = start + markers.end();
      String core =
          content
              .substring(coreStart, clauseEnd(content, coreStart))
              .replaceFirst("^[\\s:：]*(?:是)?\\s*", "")
              .strip();
      // Exact complete clause, not an arbitrary substring that can remove a negation.
      if (value.equals(core)) return value;
    }
    return "";
  }

  private long messageSequence(String id) {
    return s.db.queryForObject(
        "SELECT chat_source_seq FROM records WHERE id=?::uuid", Long.class, id);
  }

  private static boolean directCommitment(String content, String quote, String choice) {
    for (int from = 0, at; (at = content.indexOf(quote, from)) >= 0; from = at + 1) {
      if (insideQuotation(content, at)) continue;
      int start = sentenceStart(content, at), end = at + quote.length();
      while (end < content.length() && "。.!！？?\n".indexOf(content.charAt(end)) < 0) end++;
      if (end < content.length() && "？?".indexOf(content.charAt(end)) >= 0) continue;
      String sentence = content.substring(start, end);
      if (sentence.stripLeading().startsWith(">")
          || ATTRIBUTION.matcher(content.substring(start, at)).find()
          || UNCERTAIN.matcher(sentence).find()
          || QUESTION.matcher(sentence).find()
          || THIRD_PARTY.matcher(sentence).find()
          || quote.indexOf('?') >= 0
          || quote.indexOf('？') >= 0) continue;
      var commitment = COMMITMENT.matcher(quote);
      while (commitment.find()) {
        String prefix = content.substring(start, at + commitment.start());
        if (OUTER_NEGATION.matcher(prefix).find()) continue;
        int coreStart = at + commitment.end(), coreEnd = clauseEnd(content, coreStart);
        if (coreEnd > at + quote.length()) continue; // The quote itself cannot crop the commitment.
        String core = content.substring(coreStart, coreEnd).strip();
        if (core.isEmpty()) continue;
        String withoutVerb = POSITIVE_CHOICE_VERB.matcher(core).replaceFirst("");
        if (choice.equals(core) || choice.equals(withoutVerb) || choice.equals(quote.strip()))
          return true;
      }
    }
    return false;
  }

  private static int sentenceStart(String content, int at) {
    int start = at;
    while (start > 0 && "。.!！？?\n".indexOf(content.charAt(start - 1)) < 0) start--;
    return start;
  }

  private static int clauseEnd(String content, int at) {
    int end = at;
    while (end < content.length() && "，,。.!！？?；;\n".indexOf(content.charAt(end)) < 0) end++;
    return end;
  }

  private static boolean insideQuotation(String content, int end) {
    Deque<Character> closing = new ArrayDeque<>();
    boolean ascii = false, code = false;
    for (int i = 0; i < end; i++) {
      char c = content.charAt(i);
      if (c == '"') ascii = !ascii;
      if (c == '`') code = !code;
      int open = "“‘「『".indexOf(c);
      if (open >= 0) closing.push("”’」』".charAt(open));
      else if (!closing.isEmpty() && c == closing.peek()) closing.pop();
    }
    return ascii || code || !closing.isEmpty();
  }
}
