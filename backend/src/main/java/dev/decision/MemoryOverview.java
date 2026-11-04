package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read-only projection of owner facts; cached jobs never replace or mutate the source memories. */
@Component
class MemoryOverview {
  static final String VERSION = "memory-overview-schema1-prompt1";
  private static final String OPERATION = "memory_overview";
  private final Store s;
  private final Config c;
  private final Model model;

  MemoryOverview(Store s, Config c, Model model) {
    this.s = s;
    this.c = c;
    this.model = model;
  }

  private record Snapshot(String fingerprint, ObjectNode input, ObjectNode coverage) {}

  ObjectNode get(String problemId) {
    return s.write(() -> view(problemId, snapshot(problemId)));
  }

  /** Caller also holds the API idempotency lock, so stale replay cannot bypass this check. */
  void checkRequest(JsonNode body) {
    s.write(
        () -> {
          String problemId = requestProblem(body);
          requireFingerprint(body.path("fingerprint").asText(), snapshot(problemId));
          return null;
        });
  }

  ObjectNode request(JsonNode body) {
    return s.write(
        () -> {
          String problemId = requestProblem(body);
          Snapshot snapshot = snapshot(problemId);
          requireFingerprint(body.path("fingerprint").asText(), snapshot);
          ObjectNode current = view(problemId, snapshot);
          String status = current.path("status").asText();
          boolean retry =
              status.equals("failed")
                  && body.path("retry").asBoolean(false)
                  && current.path("error").path("retryable").asBoolean(false);
          if (!status.equals("missing") && !retry) return metadata(current);
          ObjectNode input =
              Json.obj(
                  "operation",
                  OPERATION,
                  "scopeKey",
                  scopeKey(problemId),
                  "problemId",
                  problemId,
                  "fingerprint",
                  snapshot.fingerprint());
          ObjectNode job = s.job(OPERATION, problemId, input, c.mode());
          return Json.obj(
              "jobId", job.path("id"), "fingerprint", snapshot.fingerprint(), "status", "pending");
        });
  }

  /**
   * Worker invokes this outside its claim transaction; the provider call must not hold the lock.
   */
  void run(ObjectNode work) {
    String problemId = work.hasNonNull("problemId") ? work.path("problemId").asText() : null;
    String fingerprint = work.path("fingerprint").asText();
    Snapshot before =
        s.write(
            () -> {
              Snapshot current = snapshot(problemId);
              requireFingerprint(fingerprint, current);
              return current;
            });
    ObjectNode output =
        before.input().path("memories").isEmpty()
            ? Json.obj("items", Json.arr())
            : model.overview(problemId, before.input().deepCopy());
    ArrayNode items = validate(output, before.input());
    s.write(
        () -> {
          Snapshot current = snapshot(problemId);
          requireFingerprint(fingerprint, current);
          String id = work.path("jobId").asText();
          ObjectNode job = s.get("job", id);
          job.put("status", "succeeded");
          job.set(
              "result",
              Json.obj("fingerprint", fingerprint, "items", items, "coverage", before.coverage()));
          job.putNull("error");
          s.save("job", job);
          s.db.update("UPDATE jobs SET state='succeeded' WHERE id=?::uuid", id);
          return null;
        });
  }

  private String requestProblem(JsonNode body) {
    if (body == null
        || !body.isObject()
        || !onlyFields(body, Set.of("fingerprint", "problemId", "retry")))
      throw new ApiError(400, "INVALID_FIELD", "概览请求字段无效");
    Json.required(body, "fingerprint", 128);
    if (body.has("retry") && (!body.path("retry").isBoolean() || !body.path("retry").asBoolean()))
      throw new ApiError(400, "INVALID_FIELD", "retry 只能为 true");
    if (!body.hasNonNull("problemId")) return null;
    String problemId = Json.required(body, "problemId", 36);
    return Json.uuid(problemId).toLowerCase(Locale.ROOT);
  }

  private Snapshot snapshot(String problemId) {
    if (problemId != null) {
      Json.uuid(problemId);
      s.get("problem", problemId);
      problemId = problemId.toLowerCase(Locale.ROOT);
    }
    List<ObjectNode> eligible = new ArrayList<>();
    for (ObjectNode memory : s.all("memory", null)) {
      if (!memory.path("status").asText().equals("active")
          || !memory.path("kind").asText().equals("user_reported")) continue;
      String scope = memory.path("scope").asText("problem");
      if (!scope.equals("global")) scope = "problem";
      String ownerProblem =
          memory.hasNonNull("problemId") ? memory.path("problemId").asText() : null;
      if (problemId != null && !scope.equals("global") && !problemId.equals(ownerProblem)) continue;
      eligible.add(
          Json.obj(
              "id",
              memory.path("id"),
              "revision",
              memory.path("revision"),
              "content",
              memory.path("content"),
              "scope",
              scope,
              "problemId",
              ownerProblem,
              "status",
              "active"));
    }
    eligible.sort(Comparator.comparing(n -> n.path("id").asText()));
    ArrayNode canonicalMemories = Json.arr();
    eligible.forEach(canonicalMemories::add);
    String fingerprint =
        Auth.hash(
            Json.canonical(
                    Json.obj(
                        "version",
                        VERSION,
                        "model",
                        c.modelName,
                        "mode",
                        c.mode(),
                        "scopeKey",
                        scopeKey(problemId),
                        "memories",
                        canonicalMemories))
                .toString());
    eligible.sort(
        Comparator.<ObjectNode>comparingInt(n -> n.path("scope").asText().equals("global") ? 0 : 1)
            .thenComparing(n -> n.path("id").asText()));
    ArrayNode selected = Json.arr();
    int characters = 0;
    for (ObjectNode memory : eligible) {
      if (selected.size() == 100) break;
      String content = memory.path("content").asText();
      int count = content.codePointCount(0, content.length());
      if (characters + count > 32000) continue;
      ObjectNode inputMemory = memory.deepCopy();
      inputMemory.remove("status");
      if (memory.path("scope").asText().equals("global")) inputMemory.putNull("problemId");
      selected.add(inputMemory);
      characters += count;
    }
    ObjectNode coverage =
        Json.obj(
            "eligibleCount",
            eligible.size(),
            "consideredCount",
            selected.size(),
            "truncated",
            selected.size() < eligible.size());
    return new Snapshot(
        fingerprint, Json.obj("memories", selected, "coverage", coverage), coverage);
  }

  private ObjectNode view(String problemId, Snapshot snapshot) {
    ObjectNode value =
        Json.obj(
            "status",
            "missing",
            "fingerprint",
            snapshot.fingerprint(),
            "items",
            Json.arr(),
            "jobId",
            null,
            "error",
            null,
            "coverage",
            snapshot.coverage());
    if (snapshot.coverage().path("eligibleCount").asInt() == 0) {
      value.put("status", "empty");
      return value;
    }
    List<String> ids =
        s.db.queryForList(
            "SELECT j.id::text FROM jobs j JOIN records r ON r.id=j.id WHERE r.kind='job' AND NOT"
                + " r.deleted AND j.input->>'operation'=? AND j.input->>'scopeKey'=? AND"
                + " j.input->>'fingerprint'=? ORDER BY r.chat_source_seq DESC LIMIT 1",
            String.class,
            OPERATION,
            scopeKey(problemId),
            snapshot.fingerprint());
    if (ids.isEmpty()) return value;
    ObjectNode job = s.get("job", ids.get(0));
    value.set("jobId", job.path("id"));
    switch (job.path("status").asText()) {
      case "queued", "running" -> value.put("status", "pending");
      case "succeeded" -> {
        value.put("status", "ready");
        value.set("items", job.path("result").path("items").deepCopy());
      }
      case "failed" -> {
        value.put("status", "failed");
        value.set("error", job.path("error").deepCopy());
      }
      default -> throw new ApiError(500, "OVERVIEW_STATE", "记忆概览任务状态无效", true);
    }
    return value;
  }

  private ArrayNode validate(JsonNode output, ObjectNode input) {
    if (output == null
        || !output.isObject()
        || !onlyFields(output, Set.of("items"))
        || !output.path("items").isArray()
        || output.path("items").size() > 5
        || (!input.path("memories").isEmpty() && output.path("items").isEmpty())) throw schema();
    Map<String, JsonNode> allowed = new HashMap<>();
    input.path("memories").forEach(m -> allowed.put(m.path("id").asText(), m));
    Set<String> used = new HashSet<>();
    ArrayNode items = Json.arr();
    for (JsonNode item : output.path("items")) {
      if (!item.isObject()
          || !onlyFields(item, Set.of("summary", "scope", "problemId", "memoryRefs"))
          || !item.path("summary").isTextual()
          || item.path("summary").asText().isBlank()
          || !item.path("scope").isTextual()
          || !item.has("problemId")
          || !item.path("memoryRefs").isArray()
          || item.path("memoryRefs").isEmpty()) throw schema();
      String summary = item.path("summary").asText();
      if (summary.codePointCount(0, summary.length()) > 80) throw schema();
      String scope = item.path("scope").asText();
      if (!Set.of("global", "problem").contains(scope)) throw schema();
      if (scope.equals("global") && !item.path("problemId").isNull()) throw schema();
      if (scope.equals("problem")
          && (!item.path("problemId").isTextual() || item.path("problemId").asText().isBlank()))
        throw schema();
      ArrayNode refs = Json.arr();
      for (JsonNode ref : item.path("memoryRefs")) {
        if (!ref.isObject()
            || !onlyFields(ref, Set.of("id", "revision"))
            || !ref.path("id").isTextual()
            || !ref.path("revision").isIntegralNumber()
            || !ref.path("revision").canConvertToLong()) throw schema();
        String id = ref.path("id").asText();
        JsonNode source = allowed.get(id);
        if (source == null
            || !used.add(id)
            || ref.path("revision").asLong() != source.path("revision").asLong()
            || !source.path("scope").asText().equals(scope)
            || (scope.equals("problem")
                && !source.path("problemId").equals(item.path("problemId")))) throw schema();
        refs.add(Json.obj("id", id, "revision", ref.path("revision")));
      }
      items.add(
          Json.obj(
              "summary",
              summary,
              "scope",
              scope,
              "problemId",
              item.path("problemId"),
              "memoryRefs",
              refs));
    }
    return items;
  }

  private static boolean onlyFields(JsonNode object, Set<String> fields) {
    var names = object.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) return false;
    return true;
  }

  private static ObjectNode metadata(ObjectNode view) {
    return Json.obj(
        "jobId",
        view.path("jobId"),
        "fingerprint",
        view.path("fingerprint"),
        "status",
        view.path("status"));
  }

  private static String scopeKey(String problemId) {
    return problemId == null ? "*" : problemId.toLowerCase(Locale.ROOT);
  }

  private static void requireFingerprint(String provided, Snapshot current) {
    if (!provided.equals(current.fingerprint()))
      throw new ApiError(409, "OVERVIEW_CHANGED", "记忆已变更，请刷新概览后再试", true);
  }

  private static ApiError schema() {
    return new ApiError(502, "MODEL_SCHEMA", "模型概览格式或记忆引用无效，可明确重试", true);
  }
}
