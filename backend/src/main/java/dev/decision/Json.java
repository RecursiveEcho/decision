package dev.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

final class Json {
  static final ObjectMapper M = new ObjectMapper();

  static ObjectNode obj(Object... pairs) {
    ObjectNode n = M.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2)
      n.set(pairs[i].toString(), M.valueToTree(pairs[i + 1]));
    return n;
  }

  static ArrayNode arr() {
    return M.createArrayNode();
  }

  static JsonNode parse(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_JSON", "JSON 格式错误");
    }
  }

  static String now() {
    return Instant.now().toString();
  }

  static String id() {
    return UUID.randomUUID().toString();
  }

  static String uuid(String s) {
    try {
      if (!UUID.fromString(s).toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
      return s;
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_ID", "需要 UUID");
    }
  }

  static String required(JsonNode n, String k, int max) {
    if (!n.path(k).isTextual() || n.path(k).asText().isBlank() || n.path(k).asText().length() > max)
      throw new ApiError(400, "INVALID_FIELD", k + " 不能为空且不能超过 " + max + " 字符");
    return n.path(k).asText();
  }

  static String optional(JsonNode n, String k, String fallback, int max) {
    if (!n.hasNonNull(k)) return fallback;
    if (!n.get(k).isTextual() || n.get(k).asText().length() > max)
      throw new ApiError(400, "INVALID_FIELD", k + " 格式错误");
    return n.get(k).asText();
  }

  static String choice(JsonNode n, String k, String... options) {
    String v = required(n, k, 100);
    if (!Arrays.asList(options).contains(v)) throw new ApiError(400, "INVALID_FIELD", k + " 值无效");
    return v;
  }

  static ArrayNode strings(JsonNode n, String k) {
    if (!n.has(k)) return arr();
    if (!n.get(k).isArray() || n.get(k).size() > 100)
      throw new ApiError(400, "INVALID_FIELD", k + " 必须为数组，最多100项");
    ArrayNode a = arr();
    for (JsonNode v : n.get(k)) {
      if (!v.isTextual() || v.asText().length() > 2000)
        throw new ApiError(400, "INVALID_FIELD", k + " 元素格式错误");
      a.add(v);
    }
    return a;
  }

  static String datetime(JsonNode n, String k, boolean future) {
    if (!n.hasNonNull(k)) return null;
    try {
      Instant t = Instant.parse(n.get(k).asText());
      if (future && !t.isAfter(Instant.now())) throw new IllegalArgumentException();
      return t.toString();
    } catch (Exception e) {
      throw new ApiError(
          400, "INVALID_TIME", k + " 需要" + (future ? "将来的" : "") + " UTC RFC3339 时间");
    }
  }

  static JsonNode canonical(JsonNode n) {
    if (n.isObject()) {
      ObjectNode o = obj();
      TreeSet<String> keys = new TreeSet<>();
      n.fieldNames().forEachRemaining(keys::add);
      for (String k : keys) o.set(k, canonical(n.get(k)));
      return o;
    }
    if (n.isArray()) {
      ArrayNode a = arr();
      n.forEach(v -> a.add(canonical(v)));
      return a;
    }
    return n;
  }
}
