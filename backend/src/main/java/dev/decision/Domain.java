package dev.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class Domain {
  final Store s;
  final Config c;
  final Evidence evidence;
  final Auth auth;

  Domain(Store s, Config c, Evidence e, Auth a) {
    this.s = s;
    this.c = c;
    this.evidence = e;
    this.auth = a;
  }

  ObjectNode problem(JsonNode b) {
    return s.create(
        "problem",
        null,
        Json.obj(
            "title",
            Json.required(b, "title", 300),
            "goal",
            Json.required(b, "goal", 4000),
            "constraints",
            Json.strings(b, "constraints"),
            "unknowns",
            Json.strings(b, "unknowns")));
  }

  ObjectNode editProblem(String id, JsonNode b) {
    ObjectNode p = s.get("problem", id);
    for (String k : List.of("title", "goal"))
      if (b.has(k)) p.put(k, Json.required(b, k, k.equals("title") ? 300 : 4000));
    for (String k : List.of("constraints", "unknowns")) if (b.has(k)) p.set(k, Json.strings(b, k));
    s.bump();
    return s.save("problem", p);
  }

  String problemId(JsonNode b) {
    String id = Json.required(b, "problemId", 36);
    s.get("problem", id);
    return id;
  }

  ObjectNode ingest(JsonNode b) {
    String p = problemId(b);
    String kind = Json.choice(b, "kind", "article", "user_statement");
    String text = Json.optional(b, "text", "", 100000);
    String url = Json.optional(b, "url", "", 4096);
    if (text.isBlank() && url.isBlank())
      throw new ApiError(400, "CONTENT_REQUIRED", "正文或 URL 至少提供一项");
    if (!url.isBlank()) SafeFetch.validateUri(url);
    return s.job(
        "ingest",
        p,
        Json.obj(
            "problemId",
            p,
            "kind",
            kind,
            "title",
            Json.optional(b, "title", "", 300),
            "text",
            text,
            "url",
            url,
            "author",
            Json.optional(b, "author", null, 300)),
        c.mode());
  }

  ObjectNode chat(String id, JsonNode b) {
    s.get("problem", id);
    String content = Json.required(b, "content", 12000);
    ArrayNode ids = Json.strings(b, "sourceIds");
    for (JsonNode v : ids) {
      ObjectNode source = s.get("source", v.asText());
      if (!source.path("problemId").asText().equals(id))
        throw new ApiError(400, "INVALID_SOURCE", "来源不属于当前问题");
    }
    ObjectNode user =
        s.create(
            "message",
            id,
            Json.obj(
                "problemId",
                id,
                "role",
                "user",
                "content",
                content,
                "evidence",
                Json.arr(),
                "uncertainties",
                Json.arr(),
                "mode",
                c.mode()));
    return s.job(
        "chat",
        id,
        Json.obj(
            "problemId",
            id,
            "content",
            content,
            "sourceIds",
            ids,
            "userMessageId",
            user.path("id")),
        c.mode());
  }

  ObjectNode source(
      String p,
      String kind,
      String title,
      String text,
      String url,
      String author,
      String method,
      JsonNode locator,
      String captured) {
    return s.create(
        "source",
        p,
        Json.obj(
            "problemId",
            p,
            "kind",
            kind,
            "title",
            title.isBlank() ? "未命名资料" : title,
            "text",
            text,
            "originUrl",
            url == null || url.isBlank() ? null : url,
            "author",
            author,
            "capturedAt",
            captured,
            "status",
            "ready",
            "extractionMethod",
            method,
            "locator",
            locator));
  }

  ObjectNode memory(JsonNode b) {
    String p = problemId(b);
    Json.choice(b, "kind", "user_reported");
    String text = Json.required(b, "content", 10000);
    ObjectNode source =
        source(p, "user_statement", "本人陈述", text, null, null, "user_input", Json.obj(), Json.now());
    ArrayNode ev =
        Json.arr()
            .add(Json.obj("sourceId", source.path("id"), "quote", text, "relation", "context"));
    s.bump();
    return s.create(
        "memory",
        p,
        Json.obj(
            "problemId",
            p,
            "content",
            text,
            "kind",
            "user_reported",
            "evidence",
            ev,
            "status",
            "active",
            "revision",
            1));
  }

  void revision(ObjectNode n, JsonNode b) {
    if (!b.path("expectedRevision").isIntegralNumber())
      throw new ApiError(400, "REVISION_REQUIRED", "请提供 expectedRevision");
    if (b.path("expectedRevision").asInt() != n.path("revision").asInt())
      throw new ApiError(409, "REVISION_CONFLICT", "记录已更新，请重新读取");
  }

  ObjectNode editMemory(String id, JsonNode b) {
    ObjectNode m = s.get("memory", id);
    revision(m, b);
    String p = m.path("problemId").asText();
    if (b.has("content")) {
      String text = Json.required(b, "content", 10000);
      m.put("content", text);
      if (m.path("kind").asText().equals("user_reported"))
        for (JsonNode ev : m.path("evidence")) {
          ObjectNode src = s.get("source", ev.path("sourceId").asText());
          src.put("text", text);
          s.save("source", src);
          ((ObjectNode) ev).put("quote", text);
        }
    }
    if (b.has("status")) m.put("status", Json.choice(b, "status", "active", "retracted"));
    if (m.path("status").asText().equals("retracted")) {
      for (JsonNode ev : m.path("evidence"))
        if (m.path("kind").asText().equals("user_reported"))
          s.delete("source", ev.path("sourceId").asText());
    }
    m.put("revision", m.path("revision").asInt() + 1);
    invalidate(p, id);
    s.bump();
    return s.save("memory", m);
  }

  void deleteMemory(String id) {
    ObjectNode m = s.get("memory", id);
    if (m.path("kind").asText().equals("user_reported"))
      for (JsonNode e : m.path("evidence"))
        try {
          s.delete("source", e.path("sourceId").asText());
        } catch (ApiError ignored) {
        }
    s.delete("memory", id);
    invalidate(m.path("problemId").asText(), id);
  }

  void deleteSource(String id) {
    ObjectNode source = s.get("source", id);
    s.delete("source", id);
    invalidate(source.path("problemId").asText(), null);
  }

  // Conservative invalidation prevents derived summaries from reintroducing corrected/deleted
  // facts.
  void invalidate(String p, String keepMemory) {
    for (ObjectNode m : s.all("memory", p))
      if (!m.path("id").asText().equals(keepMemory)
          && !m.path("kind").asText().equals("user_reported"))
        s.delete("memory", m.path("id").asText());
    for (ObjectNode m : s.all("memory", p))
      if (!m.path("id").asText().equals(keepMemory)
          && m.path("kind").asText().equals("user_reported")) {
        boolean missing = false;
        for (JsonNode e : m.path("evidence"))
          try {
            s.get("source", e.path("sourceId").asText());
          } catch (ApiError ex) {
            missing = true;
          }
        if (missing) {
          m.put("status", "retracted");
          m.put("revision", m.path("revision").asInt() + 1);
          s.save("memory", m);
        }
      }
    for (ObjectNode t : s.all("topic", p)) {
      t.put("markdown", "来源或记忆已更正/撤销，旧摘要已失效。请依据当前来源重新讨论。");
      t.set("evidence", Json.arr());
      t.put("revision", t.path("revision").asInt() + 1);
      t.put("syncStatus", c.wikiConfigured() ? "pending" : "pending_configuration");
      s.save("topic", t);
      if (c.wikiConfigured()) s.job("wiki", p, Json.obj("topicId", t.path("id")), c.mode());
    }
    for (ObjectNode d : s.all("discussion", p)) {
      d.put("status", "dismissed");
      s.save("discussion", d);
    }
    for (ObjectNode d : s.all("delivery", p))
      if (d.path("status").asText().equals("pending")
          || d.path("status").asText().equals("failed")) {
        d.put("status", "failed");
        d.put("error", "SOURCE_INVALIDATED");
        s.save("delivery", d);
      }
  }

  ObjectNode decision(JsonNode b) {
    String p = problemId(b);
    return s.create(
        "decision",
        p,
        Json.obj(
            "problemId",
            p,
            "choice",
            Json.required(b, "choice", 4000),
            "rationale",
            Json.required(b, "rationale", 10000),
            "expectedOutcome",
            Json.required(b, "expectedOutcome", 4000),
            "evidence",
            evidence.validate(b.get("evidence"), p, true, null),
            "confirmedAt",
            Json.now(),
            "reviewAt",
            Json.datetime(b, "reviewAt", false),
            "revision",
            1,
            "reviews",
            Json.arr()));
  }

  ObjectNode review(String id, JsonNode b) {
    ObjectNode d = s.get("decision", id);
    String p = d.path("problemId").asText();
    ObjectNode r =
        s.create(
            "review",
            p,
            Json.obj(
                "outcome",
                Json.required(b, "outcome", 10000),
                "reflection",
                Json.required(b, "reflection", 10000),
                "evidence",
                evidence.validate(b.get("evidence"), p, true, null),
                "recordedAt",
                Json.now()));
    ((ArrayNode) d.get("reviews")).add(r);
    d.put("revision", d.path("revision").asInt() + 1);
    s.save("decision", d);
    return r;
  }

  ObjectNode discussion(String id, JsonNode b) {
    ObjectNode d = s.get("discussion", id);
    String state = Json.choice(b, "status", "unread", "read", "snoozed", "dismissed");
    String until = Json.datetime(b, "snoozeUntil", true);
    if (state.equals("snoozed") && until == null)
      throw new ApiError(400, "INVALID_TIME", "稍后提醒需要将来时间");
    d.put("status", state);
    d.put("snoozeUntil", state.equals("snoozed") ? until : null);
    return s.save("discussion", d);
  }

  ObjectNode device(JsonNode b) {
    ObjectNode d =
        s.create(
            "device",
            null,
            Json.obj(
                "name",
                Json.required(b, "name", 200),
                "desiredState",
                "paused",
                "reportedState",
                "paused",
                "lastHeartbeatAt",
                null,
                "lastObservationAt",
                null,
                "lastError",
                null));
    return Json.obj(
        "device",
        d,
        "deviceToken",
        auth.token("device:" + d.path("id").asText(), Instant.now().plus(Duration.ofDays(30))));
  }

  ObjectNode control(String id, JsonNode b) {
    ObjectNode d = s.get("device", id);
    d.put("desiredState", Json.choice(b, "desiredState", "paused", "running"));
    return s.save("device", d);
  }

  void deviceOnly(String subject, String id) {
    if (!subject.equals("device:" + id))
      throw new ApiError(403, "DEVICE_TOKEN_REQUIRED", "需要本设备凭据");
  }

  ObjectNode heartbeat(String id, JsonNode b, String subject) {
    deviceOnly(subject, id);
    ObjectNode d = s.get("device", id);
    d.put(
        "reportedState", Json.choice(b, "reportedState", "paused", "running", "error", "offline"));
    d.put("lastHeartbeatAt", Json.now());
    if (b.has("lastObservationAt"))
      d.put("lastObservationAt", Json.datetime(b, "lastObservationAt", false));
    d.put("lastError", b.hasNonNull("lastError") ? "DEVICE_REPORTED_ERROR" : null);
    s.save("device", d);
    return Json.obj("device", d);
  }

  ObjectNode observations(JsonNode b, String subject) {
    String id = Json.required(b, "deviceId", 36);
    deviceOnly(subject, id);
    ObjectNode device = s.get("device", id);
    if (!b.path("items").isArray() || b.get("items").size() > 50)
      throw new ApiError(400, "INVALID_BATCH", "最多50条观察");
    ArrayNode accepted = Json.arr(), duplicates = Json.arr(), rejected = Json.arr();
    for (JsonNode item : b.get("items")) {
      String external = Json.optional(item, "externalId", "", 200);
      try {
        if (!device.path("desiredState").asText().equals("running"))
          throw new ApiError(409, "DEVICE_PAUSED", "设备已暂停");
        String last = device.path("lastHeartbeatAt").asText("");
        if (last.isBlank()
            || Instant.parse(last).isBefore(Instant.now().minusSeconds(90))
            || !device.path("reportedState").asText().equals("running"))
          throw new ApiError(409, "DEVICE_OFFLINE", "请先发送运行心跳");
        Json.required(item, "externalId", 200);
        String p = problemId(item);
        String text = Json.required(item, "text", 20000);
        String hash = Json.required(item, "contentHash", 64);
        if (!hash.equalsIgnoreCase(Auth.hash(text)))
          throw new ApiError(400, "HASH_MISMATCH", "contentHash 必须是 text 的 SHA-256 十六进制值");
        String method = Json.choice(item, "extractionMethod", "ocr", "accessibility");
        String captured = Json.datetime(item, "capturedAt", false);
        if (captured == null) throw new ApiError(400, "INVALID_TIME", "缺少 capturedAt");
        Json.required(item, "appName", 200);
        JsonNode loc = item.path("sourceLocator");
        if (!loc.isObject()) throw new ApiError(400, "INVALID_LOCATOR", "sourceLocator 需要对象");
        // Only opaque caller IDs survive. Never persist file paths, screenshots, or arbitrary
        // locator fields.
        ObjectNode safe =
            Json.obj("externalId", external, "appName", item.path("appName").asText());
        for (String k : List.of("frameId", "resourceId"))
          if (loc.has(k)) {
            String v = Json.required(loc, k, 160);
            if (!v.matches("[A-Za-z0-9_.:-]+"))
              throw new ApiError(400, "INVALID_LOCATOR", "定位值只能使用不透明标识");
            safe.put(k, v);
          }
        List<String> old =
            s.db.queryForList(
                "SELECT content_hash FROM observation_keys WHERE device_id=?::uuid AND"
                    + " external_id=?",
                String.class,
                id,
                external);
        if (!old.isEmpty()) {
          if (!old.get(0).equalsIgnoreCase(hash))
            throw new ApiError(409, "EXTERNAL_ID_CONFLICT", "同一 externalId 的内容不能改变");
          duplicates.add(external);
          continue;
        }
        ObjectNode src = source(p, "observation", "观察线索", text, null, null, method, safe, captured);
        s.db.update(
            "INSERT INTO observation_keys VALUES (?::uuid,?,?,?::uuid)",
            id,
            external,
            hash.toLowerCase(Locale.ROOT),
            src.path("id").asText());
        s.create(
            "memory",
            p,
            Json.obj(
                "problemId",
                p,
                "content",
                text,
                "kind",
                "observed",
                "evidence",
                Json.arr()
                    .add(
                        Json.obj("sourceId", src.path("id"), "quote", text, "relation", "context")),
                "status",
                "active",
                "revision",
                1));
        device.put("lastObservationAt", captured);
        s.analyze(p, c.mode());
        accepted.add(external);
      } catch (ApiError e) {
        rejected.add(Json.obj("externalId", external, "code", e.code, "message", e.getMessage()));
      }
    }
    s.save("device", device);
    return Json.obj("accepted", accepted, "duplicates", duplicates, "rejected", rejected);
  }

  ObjectNode ack(String id, JsonNode b, String subject) {
    ObjectNode d = s.get("delivery", id);
    deviceOnly(subject, d.path("deviceId").asText());
    String status = Json.choice(b, "status", "sent", "failed");
    if (!d.path("status").asText().equals("sent")
        && !d.path("status").asText().equals("user_confirmed")) {
      d.put("status", status);
      d.put("error", status.equals("failed") ? "DEVICE_NOTIFICATION_FAILED" : null);
      s.save("delivery", d);
    }
    return d;
  }

  List<ObjectNode> deliveries(String id, String subject) {
    deviceOnly(subject, id);
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode delivery : s.all("delivery", null)) {
      if (!delivery.path("deviceId").asText().equals(id)
          || !List.of("pending", "failed").contains(delivery.path("status").asText())
          || delivery.path("error").asText().equals("SOURCE_INVALIDATED")) continue;
      ObjectNode discussion = s.get("discussion", delivery.path("discussionId").asText());
      String state = discussion.path("status").asText();
      if (state.equals("dismissed") || state.equals("read")) continue;
      if (state.equals("snoozed")
          && Instant.parse(discussion.path("snoozeUntil").asText()).isAfter(Instant.now()))
        continue;
      out.add(
          Json.obj(
              "delivery",
              delivery,
              "discussion",
              Json.obj(
                  "id",
                  discussion.path("id"),
                  "title",
                  discussion.path("title"),
                  "reason",
                  discussion.path("reason"))));
    }
    return out;
  }

  ObjectNode job(String id, String subject) {
    ObjectNode j = s.get("job", id);
    if (!subject.equals("owner")) {
      List<String> ids =
          s.db.queryForList(
              "SELECT input->>'deviceId' FROM jobs WHERE id=?::uuid", String.class, id);
      if (ids.isEmpty() || !subject.equals("device:" + ids.get(0)))
        throw new ApiError(403, "FORBIDDEN", "此任务不属于该设备");
    }
    return j;
  }

  ObjectNode sync(String id) {
    ObjectNode t = s.get("topic", id);
    if (!c.wikiConfigured()) throw new ApiError(503, "WIKI_NOT_CONFIGURED", "Wiki 尚未配置");
    t.put("syncStatus", "pending");
    s.save("topic", t);
    return s.job("wiki", t.path("problemId").asText(), Json.obj("topicId", id), c.mode());
  }

  List<ObjectNode> list(String kind, String p) {
    if (p != null) s.get("problem", p);
    List<ObjectNode> items = s.all(kind, p);
    if (kind.equals("device"))
      for (ObjectNode d : items)
        if (d.hasNonNull("lastHeartbeatAt")
            && Instant.parse(d.get("lastHeartbeatAt").asText())
                .isBefore(Instant.now().minusSeconds(90))) d.put("reportedState", "offline");
    return items;
  }

  ObjectNode page(List<ObjectNode> items, String order, String cursor, int limit, String scope) {
    if (limit < 1 || limit > 100) throw new ApiError(400, "INVALID_LIMIT", "limit 范围为1至100");
    boolean asc = order.equals("createdAt");
    Comparator<ObjectNode> cmp =
        Comparator.comparing((ObjectNode n) -> sortValue(n, order)).thenComparing(n -> entityId(n));
    if (!asc) cmp = cmp.reversed();
    items.sort(cmp);
    String after = null;
    if (cursor != null) {
      try {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int i = raw.lastIndexOf('|');
        String body = raw.substring(0, i);
        if (!auth.sign(scope + body).equals(raw.substring(i + 1)))
          throw new IllegalArgumentException();
        after = body;
      } catch (Exception e) {
        throw new ApiError(400, "INVALID_CURSOR", "游标无效");
      }
    }
    ArrayNode out = Json.arr();
    boolean started = after == null;
    boolean more = false;
    for (ObjectNode n : items) {
      String pos = sortValue(n, order) + "/" + entityId(n);
      if (!started) {
        int comparison = pos.compareTo(after);
        if (asc ? comparison <= 0 : comparison >= 0) continue;
        started = true;
      }
      if (out.size() == limit) {
        more = true;
        break;
      }
      out.add(n);
    }
    String next = null;
    if (more) {
      ObjectNode n = (ObjectNode) out.get(out.size() - 1);
      String body = sortValue(n, order) + "/" + entityId(n);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (body + "|" + auth.sign(scope + body)).getBytes(StandardCharsets.UTF_8));
    }
    return Json.obj("items", out, "nextCursor", next);
  }

  String sortValue(ObjectNode n, String order) {
    JsonNode e = n.has("delivery") ? n.get("delivery") : n;
    return new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(9)
        .toFormatter()
        .format(Instant.parse(e.path(order).asText(e.path("createdAt").asText())));
  }

  String entityId(ObjectNode n) {
    return (n.has("delivery") ? n.get("delivery") : n).path("id").asText();
  }

  ObjectNode status() {
    List<String> last =
        s.db.queryForList(
            "SELECT status FROM model_calls ORDER BY created_at DESC LIMIT 1", String.class);
    Map<String, Object> usage =
        s.db.queryForMap(
            "SELECT count(*) AS requests,coalesce(sum(input_tokens),0) AS"
                + " inputs,coalesce(sum(output_tokens),0) AS outputs FROM model_calls");
    String seen = null;
    for (ObjectNode d : s.all("device", null))
      if (d.hasNonNull("lastHeartbeatAt")
          && (seen == null || seen.compareTo(d.get("lastHeartbeatAt").asText()) < 0))
        seen = d.get("lastHeartbeatAt").asText();
    String wikiStatus = c.wikiConfigured() ? "pending" : "pending_configuration";
    List<ObjectNode> topics = s.all("topic", null);
    if (!topics.isEmpty()) wikiStatus = topics.get(topics.size() - 1).path("syncStatus").asText();
    return Json.obj(
        "model",
        Json.obj(
            "configured",
            c.modelConfigured(),
            "lastCallStatus",
            last.isEmpty() ? "never" : last.get(0)),
        "wiki",
        Json.obj("configured", c.wikiConfigured(), "status", wikiStatus),
        "observer",
        Json.obj("lastSeenAt", seen),
        "usage",
        Json.obj(
            "requests",
            usage.get("requests"),
            "inputTokens",
            usage.get("inputs"),
            "outputTokens",
            usage.get("outputs"),
            "costEstimate",
            null,
            "currency",
            null));
  }
}
