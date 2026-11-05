package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Local, source-grounded memory reconciliation. No SDK, embedding service or extra model call. */
@Component
class ChatMemory {
  final Store s;

  ChatMemory(Store s) {
    this.s = s;
  }

  record Snapshot(long epoch, ArrayNode memories) {}

  static String prompt() {
    return "可选返回memoryActions数组，最多5项，每项为{action:ADD/UPDATE/DELETE/NONE,content?,scope:global/problem,targetId?,expectedRevision?,sourceQuote}。"
               + "只从本轮当前用户原话提取明确的本人事实或偏好，不从历史、附件、来源、助手、第三人、引述、假设或合成测试场景提取个人记忆。"
               + "sourceQuote须为当前原话中完整的本人断言分句，保留主语、否定和限定；content必须等于该断言，仅允许去除标点空白，不自由截取、改写或补事实。尽量引用完整当前句，保留主题及新旧偏好的关系，可包含逗号分隔的从句。"
               + "明确稳定的本人事实偏好可global；今天/这次等临时条件只用problem。"
               + "先对照本次提供的memories，重复事实用NONE，明确纠正才UPDATE，用户明确要求忘记才DELETE。"
               + "UPDATE/DELETE只引用本次可见的origin=chat记录id及revision，不能修改手动记录。"
               + "不再从旧对话恢复已忘记事实；只有当前用户重新明确陈述才能提议ADD。"
               + "正文不得声称已经记住、更新或忘记；服务器验证并实际保存后才展示操作结果。来源应如实说明来自用户原话，不声称来自互联网。";
  }

  long epoch() {
    return s.memoryEpoch();
  }

  long currentEpoch() {
    return epoch();
  }

  void requireCurrent(Snapshot snapshot) {
    if (snapshot == null || snapshot.epoch() != epoch())
      throw new ApiError(409, "CONTEXT_CHANGED", "记忆已更正或撤销，请重新提问", true);
    for (JsonNode seen : snapshot.memories()) {
      List<String> rows =
          s.db.queryForList(
              "SELECT data::text FROM records WHERE id=?::uuid AND account_id=?::uuid AND"
                  + " kind='memory' AND NOT deleted",
              String.class,
              seen.path("id").asText(),
              AccountScope.id());
      if (rows.isEmpty()) throw new ApiError(409, "CONTEXT_CHANGED", "引用的记忆已删除，请重新提问", true);
      JsonNode now = Json.parse(rows.get(0));
      if (!now.path("status").asText().equals("active")
          || now.path("revision").asInt() != seen.path("revision").asInt())
        throw new ApiError(409, "CONTEXT_CHANGED", "引用的记忆已更新，请重新提问", true);
    }
  }

  /** API list semantics: unfiltered returns all; a problem includes its local and global items. */
  List<ObjectNode> list(String problemId) {
    if (problemId == null) return s.all("memory", null);
    Json.uuid(problemId);
    return s.db.query(
        "SELECT data::text FROM records WHERE kind='memory' AND account_id=?::uuid AND NOT deleted"
            + " AND (problem_id=?::uuid OR data->>'scope'='global') ORDER BY created_at,id",
        (rs, i) -> (ObjectNode) Json.parse(rs.getString(1)),
        AccountScope.id(),
        problemId);
  }

  Snapshot retrieve(String problemId, String content) {
    return s.write(
        () -> {
          Set<String> query = tokens(content);
          List<ObjectNode> all = new ArrayList<>();
          for (ObjectNode memory : list(problemId))
            if (memory.path("status").asText().equals("active")) all.add(memory);
          Comparator<ObjectNode> order =
              Comparator.<ObjectNode>comparingInt(m -> score(query, m.path("content").asText()))
                  .reversed()
                  .thenComparing(m -> m.path("updatedAt").asText(), Comparator.reverseOrder())
                  .thenComparing(m -> m.path("id").asText());
          all.sort(order);
          ArrayNode chosen = Json.arr();
          Set<String> ids = new HashSet<>();
          for (ObjectNode memory : all) {
            boolean local = memory.path("problemId").asText().equals(problemId);
            if (!local && score(query, memory.path("content").asText()) == 0) continue;
            chosen.add(forModel(memory));
            ids.add(memory.path("id").asText());
            if (chosen.size() == 12) break;
          }
          int fallback = 0;
          for (ObjectNode memory : all) {
            if (!memory.path("scope").asText().equals("global")
                || ids.contains(memory.path("id").asText())) continue;
            chosen.add(forModel(memory));
            if (++fallback == 4 || chosen.size() == 16) break;
          }
          return new Snapshot(epoch(), chosen);
        });
  }

  private ObjectNode forModel(ObjectNode memory) {
    String text = memory.path("content").asText();
    return Json.obj(
        "id", memory.path("id"),
        "content", text.substring(0, Math.min(text.length(), 2000)),
        "kind", memory.path("kind"),
        "scope", memory.path("scope").asText("problem"),
        "origin", memory.path("origin").asText("manual"),
        "revision", memory.path("revision"),
        "sourceMessageId", memory.path("sourceMessageId").asText(""),
        "sourceQuote", memory.path("sourceQuote").asText(""));
  }

  ArrayNode apply(String problemId, String userMessageId, JsonNode actions, Snapshot snapshot) {
    return s.write(() -> applyLocked(problemId, userMessageId, actions, snapshot));
  }

  private ArrayNode applyLocked(
      String problemId, String userMessageId, JsonNode actions, Snapshot snapshot) {
    ArrayNode changes = Json.arr();
    if (snapshot == null || snapshot.epoch() != epoch()) return changes;
    ObjectNode message;
    try {
      s.requireCurrentUserMessage(problemId, userMessageId);
      message = s.get("message", userMessageId);
    } catch (ApiError invalid) {
      return changes;
    }
    if (s.db.queryForObject(
            "SELECT memory_epoch FROM records WHERE id=?::uuid AND account_id=?::uuid",
            Long.class,
            userMessageId,
            AccountScope.id())
        != snapshot.epoch()) return changes;
    long sourceSeq =
        s.db.queryForObject(
            "SELECT chat_source_seq FROM records WHERE id=?::uuid AND account_id=?::uuid",
            Long.class,
            userMessageId,
            AccountScope.id());
    if (s.db.update(
            "INSERT INTO chat_memory_processed(message_id,source_seq,epoch) VALUES (?::uuid,?,?)"
                + " ON CONFLICT DO NOTHING",
            userMessageId,
            sourceSeq,
            snapshot.epoch())
        == 0) return changes;
    if (actions == null || !actions.isArray()) return changes;
    String original = message.path("content").asText();
    if (isNonPersonalContext(original)) return changes;
    Map<String, JsonNode> visible = new HashMap<>();
    snapshot.memories().forEach(m -> visible.put(m.path("id").asText(), m));
    boolean changedExisting = false;
    for (int index = 0; index < Math.min(5, actions.size()); index++) {
      JsonNode candidate = actions.get(index);
      if (!candidate.isObject()) continue;
      String action = candidate.path("action").asText();
      if (!Set.of("ADD", "UPDATE", "DELETE").contains(action)) continue;
      String quote = candidate.path("sourceQuote").asText();
      if (quote.isBlank() || quote.length() > 2000 || !original.contains(quote)) continue;
      if (action.equals("DELETE")) {
        if (!explicitForget(original, quote)) continue;
      } else if (!personalAssertion(original, quote)) continue;
      String content = candidate.path("content").asText();
      String scope = candidate.path("scope").asText("problem");
      if (!action.equals("DELETE")) {
        if (content.isBlank()
            || content.length() < 4
            || content.length() > 1000
            || !normalize(quote).equals(normalize(content))) continue;
        if (!Set.of("global", "problem").contains(scope)) continue;
        if (scope.equals("global") && !stablePersonal(quote)) scope = "problem";
      }
      String scopeKey = scope.equals("global") ? "*" : problemId;
      if (action.equals("ADD")) {
        if (Pattern.compile(
                "以前|之前|曾经|过去|原来|不再|\\b(?:used to|previously|formerly)\\b", Pattern.CASE_INSENSITIVE)
            .matcher(containingSentence(original, quote))
            .find()) continue;
        if (blocked(scopeKey, content, sourceSeq) || duplicate(scopeKey, content)) continue;
        ObjectNode memory =
            s.create(
                "memory",
                problemId,
                Json.obj(
                    "problemId",
                    problemId,
                    "content",
                    content,
                    "kind",
                    "user_reported",
                    "evidence",
                    Json.arr(),
                    "status",
                    "active",
                    "revision",
                    1,
                    "scope",
                    scope,
                    "origin",
                    "chat",
                    "sourceMessageId",
                    userMessageId,
                    "sourceQuote",
                    quote));
        s.db.update(
            "INSERT INTO chat_memory_meta(memory_id,fact_key,scope_key,source_seq,topic_terms)"
                + " VALUES (?::uuid,?,?,?,?)",
            memory.path("id").asText(),
            factKey(content),
            scopeKey,
            sourceSeq,
            topicJson(containingSentence(original, quote)));
        changes.add(change("ADD", memory));
        continue;
      }
      String targetId = candidate.path("targetId").asText();
      JsonNode seen = visible.get(targetId);
      if (seen == null
          || !seen.path("origin").asText().equals("chat")
          || !candidate.path("expectedRevision").isIntegralNumber()
          || candidate.path("expectedRevision").asInt() != seen.path("revision").asInt()) continue;
      ObjectNode target;
      try {
        target = s.get("memory", targetId);
      } catch (ApiError invalid) {
        continue;
      }
      if (!target.path("origin").asText().equals("chat")
          || !target.path("status").asText().equals("active")
          || target.path("revision").asInt() != candidate.path("expectedRevision").asInt()
          || !(target.path("problemId").asText().equals(problemId)
              || target.path("scope").asText().equals("global"))) continue;
      List<Map<String, Object>> meta =
          s.db.queryForList(
              "SELECT source_seq,protected FROM chat_memory_meta WHERE memory_id=?::uuid",
              targetId);
      if (meta.isEmpty()
          || Boolean.TRUE.equals(meta.get(0).get("protected"))
          || sourceSeq <= ((Number) meta.get(0).get("source_seq")).longValue()) continue;
      if (action.equals("UPDATE") && !explicitCorrection(original, quote)) continue;
      if (!linkedToTarget(containingSentence(original, quote), target, problemId)) continue;
      // Updates cannot silently widen/narrow an existing memory's scope.
      scope = target.path("scope").asText("problem");
      scopeKey = scope.equals("global") ? "*" : target.path("problemId").asText();
      if (action.equals("UPDATE") && scope.equals("global") && !stablePersonal(quote)) continue;
      if (action.equals("UPDATE") && blocked(scopeKey, content, sourceSeq)) continue;
      tombstone(target, highWater());
      target.put("revision", target.path("revision").asInt() + 1);
      target.put("sourceMessageId", userMessageId);
      target.put("sourceQuote", quote);
      if (action.equals("DELETE")) {
        target.put("status", "retracted");
      } else {
        target.put("content", content);
        s.db.update(
            "UPDATE chat_memory_meta SET fact_key=?,source_seq=? WHERE memory_id=?::uuid",
            factKey(content),
            sourceSeq,
            targetId);
      }
      s.save("memory", target);
      changes.add(change(action, target));
      changedExisting = true;
    }
    if (changedExisting) s.bumpMemoryEpoch();
    return changes;
  }

  /** Call inside the same transaction BEFORE an owner PATCH/DELETE of an automatic memory. */
  void manualChange(ObjectNode before) {
    if (!before.path("origin").asText().equals("chat")) return;
    s.write(
        () -> {
          tombstone(before, highWater());
          s.db.update(
              "UPDATE chat_memory_meta SET protected=true WHERE memory_id=?::uuid",
              before.path("id").asText());
          s.bumpMemoryEpoch();
          return null;
        });
  }

  private long highWater() {
    return s.db.queryForObject(
        "SELECT coalesce(max(chat_source_seq),0) FROM records WHERE account_id=?::uuid",
        Long.class,
        AccountScope.id());
  }

  private void tombstone(ObjectNode memory, long through) {
    String scopeKey =
        memory.path("scope").asText().equals("global") ? "*" : memory.path("problemId").asText();
    String text = normalize(memory.path("content").asText());
    s.db.update(
        "INSERT INTO"
            + " chat_memory_tombstones(account_id,scope_key,fact_key,fact_text,blocked_through_seq)"
            + " VALUES (?::uuid,?,?,?,?) ON CONFLICT(account_id,scope_key,fact_key) DO UPDATE SET"
            + " blocked_through_seq=greatest(chat_memory_tombstones.blocked_through_seq,excluded.blocked_through_seq)",
        AccountScope.id(),
        scopeKey,
        factKey(text),
        text,
        through);
  }

  private boolean blocked(String scopeKey, String content, long seq) {
    String normalized = normalize(content);
    for (String old :
        s.db.queryForList(
            "SELECT fact_text FROM chat_memory_tombstones WHERE account_id=?::uuid AND scope_key IN"
                + " (?, '*') AND blocked_through_seq>=?",
            String.class,
            AccountScope.id(),
            scopeKey,
            seq))
      if (normalized.equals(old) || normalized.contains(old) || old.contains(normalized))
        return true;
    return false;
  }

  private boolean duplicate(String scopeKey, String content) {
    String key = factKey(content);
    for (String current :
        s.db.queryForList(
            "SELECT r.data->>'content' FROM chat_memory_meta m JOIN records r ON r.id=m.memory_id"
                + " WHERE r.account_id=?::uuid AND m.scope_key=? AND NOT r.deleted AND"
                + " r.data->>'status'='active'",
            String.class,
            AccountScope.id(),
            scopeKey)) if (key.equals(factKey(current))) return true;
    return false;
  }

  private ObjectNode change(String action, ObjectNode memory) {
    return Json.obj(
        "action", action,
        "memoryId", memory.path("id"),
        "content", memory.path("content"),
        "scope", memory.path("scope").asText("problem"),
        "revision", memory.path("revision"));
  }

  private static final Pattern NON_PERSONAL =
      Pattern.compile(
          "虚构|合成|模拟|测试场景|假设|假如|如果|比如|例如|举例|角色扮演|小说|故事|转述|引用|原文|截图|附件|文档|文章|帮我(?:写|编)|代写|代拟|润色|翻译|请(?:写|改写)|"
              + "我(?:的)?(?:妈妈|爸爸|母亲|父亲|父母|家人|朋友|同事|老板|同学|老师|妻子|丈夫|对象|孩子|儿子|女儿|哥哥|姐姐|弟弟|妹妹)|(?:朋友|同事|家人|他|她|别人)(?:说|觉得|喜欢|认为|想)|[“”\"「」『』`]|\\b(?:synthetic|fictional|hypothetical|pretend|suppose|if|quote|someone)\\b|my"
              + " friend|\\b(?:write|draft|translate|rewrite) (?:a|an|this|the)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern UNCERTAIN =
      Pattern.compile(
          "不确定|也许|或许|大概|可能|似乎|犹豫|(?:我(?:正?在)?|正在|还在)考虑|考虑(?:是否|要不要|能否)|要不要|是否|[?？]|吗(?:[。！!\\s]|$)|"
              + "\\b(?:maybe|might|perhaps|should i|could i|would i)\\b",
          Pattern.CASE_INSENSITIVE);

  private static boolean isNonPersonalContext(String original) {
    return NON_PERSONAL.matcher(original).find();
  }

  private static String containingSentence(String original, String quote) {
    int at = original.indexOf(quote);
    if (at < 0) return "";
    int start = at, end = at + quote.length();
    while (start > 0 && "。！？!?\n；;".indexOf(original.charAt(start - 1)) < 0) start--;
    while (end < original.length()
        && "。！？!?\n；;".indexOf(original.charAt(end - 1)) < 0
        && "。！？!?\n；;".indexOf(original.charAt(end)) < 0) end++;
    if (end < original.length() && "。！？!?\n；;".indexOf(original.charAt(end - 1)) < 0) end++;
    return original.substring(start, end);
  }

  private static boolean personalAssertion(String original, String quote) {
    String sentence = containingSentence(original, quote);
    if (quote.length() < 4
        || UNCERTAIN.matcher(sentence).find()
        || explicitForget(original, quote)
        || Pattern.compile(
                "不认为|不是说|并非|否认|没有说|说过但|不再认为|\\b(?:deny|denied|never said)\\b",
                Pattern.CASE_INSENSITIVE)
            .matcher(sentence)
            .find()) return false;
    // A substring cannot discard a leading negation, attribution, or the enclosing command.
    int at = original.indexOf(quote), start = at, end = at + quote.length();
    while (start > 0 && "。！？!?\n；;，,".indexOf(original.charAt(start - 1)) < 0) start--;
    while (end < original.length()
        && "。！？!?\n；;，,".indexOf(original.charAt(end - 1)) < 0
        && "。！？!?\n；;，,".indexOf(original.charAt(end)) < 0) end++;
    if (!normalize(original.substring(start, end)).equals(normalize(quote))) return false;
    return Pattern.compile(
            "^(?:(?:现在|目前|以后|今后|其实|改为|改成)\\s*)?(?:我(?:的)?|本人|I(?:['’]m| am| prefer| like| dislike|"
                + " usually| always| work| have| need)|my )",
            Pattern.CASE_INSENSITIVE)
        .matcher(quote.strip())
        .find();
  }

  private static boolean stablePersonal(String quote) {
    if (Pattern.compile(
            "今天|明天|昨天|这次|这周|本周|下周|今晚|暂时|这一|\\b(?:today|tomorrow|tonight|this week|for now)\\b",
            Pattern.CASE_INSENSITIVE)
        .matcher(quote)
        .find()) return false;
    return Pattern.compile(
            "我(?:现在|以后|今后|目前)?(?:更|最|不|一直|通常|长期|平时|一般|比较)?(?:喜欢|偏好|习惯|爱|讨厌)|我(?:通常|长期|一直|平时)|我(?:是|叫)|我的(?:职业|名字|工作|专业)|\\bI"
                + " (?:prefer|like|dislike|usually|always|work as|am a)|\\bmy"
                + " (?:name|profession)\\b",
            Pattern.CASE_INSENSITIVE)
        .matcher(quote)
        .find();
  }

  private static boolean explicitCorrection(String original, String quote) {
    return Pattern.compile(
            "改成|改为|更正|纠正|不再|以前|之前|现在|其实|记错|换成|改了|\\b(?:instead|no"
                + " longer|actually|correction|now)\\b",
            Pattern.CASE_INSENSITIVE)
        .matcher(containingSentence(original, quote))
        .find();
  }

  private static boolean explicitForget(String original, String quote) {
    String sentence = containingSentence(original, quote).strip();
    if (UNCERTAIN.matcher(sentence).find()
        || Pattern.compile(
                "(?:不要|别|不能|不许|不必|不用|无需|不想|不希望|不打算|没有要求|并非|不是|不要不|不能不).{0,8}(?:忘记|删除|删掉|移除)|\\b(?:not|never|don't|don’t|cannot|can't|can’t)\\b.{0,24}\\b(?:forget|delete|remove)\\b",
                Pattern.CASE_INSENSITIVE)
            .matcher(sentence)
            .find()) return false;
    // Accept a direct request, never a mere mention or a negated/indirect request.
    return Pattern.compile(
            "^(?:(?:请(?:你)?|麻烦(?:你)?|帮我|请帮我)\\s*)?(?:忘记|删除|删掉|移除|别再记|不要(?:再)?记)|^(?:please\\s+)?(?:forget|delete|remove)\\b",
            Pattern.CASE_INSENSITIVE)
        .matcher(sentence)
        .find();
  }

  private boolean linkedToTarget(String sentence, ObjectNode target, String problemId) {
    if (normalize(sentence).contains(normalize(target.path("content").asText()))) return true;
    // Only explicitly named topic objects can resolve a paraphrase. Generic preference words
    // and arbitrary overlapping bigrams are never deletion/update authority.
    Set<String> currentTopics = topics(sentence);
    for (String topic : savedTopics(target.path("id").asText())) {
      if (!currentTopics.contains(topic)) continue;
      boolean ambiguous = false;
      for (JsonNode other : list(problemId)) {
        if (!other.path("status").asText().equals("active")) continue;
        if (other.path("id").asText().equals(target.path("id").asText())) continue;
        if (normalize(other.path("content").asText()).contains(topic)
            || savedTopics(other.path("id").asText()).contains(topic)) {
          ambiguous = true;
          break;
        }
      }
      if (!ambiguous) return true;
    }
    return false;
  }

  private Set<String> savedTopics(String id) {
    Set<String> result = new HashSet<>();
    for (String row :
        s.db.queryForList(
            "SELECT topic_terms FROM chat_memory_meta WHERE memory_id=?::uuid", String.class, id))
      Json.parse(row).forEach(n -> result.add(n.asText()));
    return result;
  }

  private static Set<String> topics(String text) {
    Set<String> result = new HashSet<>();
    var matcher =
        Pattern.compile("(?:挑选|选购|购买|选|关于)([\\p{IsHan}]{2,12}?)(?:时|按|就|的|内页|[，,。；;])")
            .matcher(text);
    while (matcher.find()) {
      String object = matcher.group(1);
      if (!Pattern.compile("这个|那个|什么|自己|别人|东西|事情|偏好|记忆|喜欢|讨厌|不|我|他|她").matcher(object).find())
        result.add(normalize(object));
    }
    return result;
  }

  private static String topicJson(String original) {
    ArrayNode terms = Json.arr();
    topics(original).forEach(terms::add);
    return terms.toString();
  }

  static Set<String> tokens(String input) {
    Set<String> tokens = new HashSet<>();
    String value = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    var words = Pattern.compile("[a-z0-9]{2,}").matcher(value);
    while (words.find()) tokens.add(words.group());
    int[] points = value.codePoints().toArray();
    for (int i = 0; i + 1 < points.length; i++)
      if (Character.UnicodeScript.of(points[i]) == Character.UnicodeScript.HAN
          && Character.UnicodeScript.of(points[i + 1]) == Character.UnicodeScript.HAN)
        tokens.add(new String(points, i, 2));
    return tokens;
  }

  private static int score(Set<String> query, String content) {
    Set<String> fact = tokens(content);
    int score = 0;
    for (String token : query) if (fact.contains(token)) score++;
    return score;
  }

  private static String normalize(String content) {
    return Normalizer.normalize(content, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
  }

  private static String factKey(String content) {
    return Auth.hash(normalize(content));
  }
}
