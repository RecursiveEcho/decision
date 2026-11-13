package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Account-owned credentials. Only encrypted values are persisted; public responses are projections.
 */
@Component
class ModelSettings {
  record Profile(String provider, String model, String key, String base) {}

  final Store s;
  final Auth auth;
  final Config config;

  ModelSettings(Store s, Auth auth, Config config) {
    this.s = s;
    this.auth = auth;
    this.config = config;
  }

  private ObjectNode stored() {
    var rows =
        s.db.queryForList(
            "SELECT data::text FROM model_settings WHERE account_id=?::uuid",
            String.class,
            AccountScope.id());
    return rows.isEmpty() ? Json.obj() : (ObjectNode) Json.parse(rows.get(0));
  }

  static String provider(String value) {
    if (!Set.of("deepseek", "openai").contains(value))
      throw new ApiError(400, "INVALID_PROVIDER", "仅支持 OpenAI 和 DeepSeek");
    return value;
  }

  static String model(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}"))
      throw new ApiError(400, "INVALID_MODEL", "请填写有效的模型名称");
    return value;
  }

  private static String key(JsonNode body) {
    String value = Json.required(body, "apiKey", 4096).strip();
    if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
      throw new ApiError(400, "INVALID_MODEL_KEY", "密钥为空或含无效字符");
    return value;
  }

  private void save(ObjectNode data) {
    s.db.update(
        "INSERT INTO model_settings(account_id,data) VALUES (?::uuid,?::jsonb) ON"
            + " CONFLICT(account_id) DO UPDATE SET data=EXCLUDED.data,updated_at=now()",
        AccountScope.id(),
        data.toString());
  }

  Profile profile(JsonNode overrides) {
    ObjectNode data = stored();
    String defaultProvider = data.path("provider").asText("deepseek");
    String p =
        provider(
            overrides != null && overrides.has("provider")
                ? Json.required(overrides, "provider", 20)
                : defaultProvider);
    String fallback = p.equals("deepseek") ? config.modelName : "gpt-4.1-mini";
    String m =
        model(
            overrides != null && overrides.has("model")
                ? Json.required(overrides, "model", 120)
                : (p.equals(defaultProvider) ? data.path("model").asText(fallback) : fallback));
    String secret = "";
    JsonNode saved = data.path("keys").path(p);
    if (saved.isTextual()) secret = saved.asText().isEmpty() ? "" : auth.decrypt(saved.asText());
    else if (p.equals("deepseek") && AccountScope.admin()) secret = config.modelKey;
    String base = p.equals("openai") ? "https://api.openai.com/v1" : "https://api.deepseek.com";
    // A deployment-owned legacy endpoint is allowed only for the migrated administrator.
    // Per-account HTTP settings never accept a URL. Loopback is used by isolated tests.
    if (p.equals("deepseek") && AccountScope.admin() && !config.modelBase.isBlank())
      base = config.modelBase.replaceAll("/+$", "");
    URI uri;
    try {
      uri = URI.create(base);
    } catch (Exception e) {
      throw new ApiError(503, "MODEL_ENDPOINT_INVALID", "模型服务地址配置无效");
    }
    if (!Set.of("https", "http").contains(Objects.toString(uri.getScheme(), ""))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new ApiError(503, "MODEL_ENDPOINT_INVALID", "模型服务地址配置无效");
    return new Profile(p, m, secret, base);
  }

  boolean configured() {
    return !profile(null).key().isBlank();
  }

  ObjectNode view() {
    Profile p = profile(null);
    return Json.obj(
        "provider",
        p.provider(),
        "model",
        p.model(),
        "configured",
        !p.key().isBlank(),
        "providers",
        Json.obj(
            "deepseek",
                Json.obj("configured", !profile(Json.obj("provider", "deepseek")).key().isBlank()),
            "openai",
                Json.obj("configured", !profile(Json.obj("provider", "openai")).key().isBlank())));
  }

  ObjectNode update(JsonNode body) {
    if (!body.isObject()
        || body.properties().stream()
            .anyMatch(e -> !Set.of("provider", "model", "apiKey").contains(e.getKey())))
      throw new ApiError(400, "INVALID_FIELD", "仅支持提供商、模型和密钥设置");
    String p = provider(Json.required(body, "provider", 20));
    String m = model(Json.required(body, "model", 120));
    return s.write(
        () -> {
          ObjectNode data = stored();
          data.put("provider", p);
          data.put("model", m);
          if (body.has("apiKey")) data.withObject("keys").put(p, auth.encrypt(key(body)));
          save(data);
          return view();
        });
  }

  ObjectNode clearKey(String p) {
    provider(p);
    return s.write(
        () -> {
          ObjectNode data = stored();
          data.withObject("keys").put(p, "");
          save(data);
          return view();
        });
  }

  String freeze(JsonNode overrides) {
    Profile p = profile(overrides);
    return auth.encrypt(
        Json.obj("provider", p.provider(), "model", p.model(), "key", p.key(), "base", p.base())
            .toString());
  }

  Profile thaw(JsonNode work) {
    if (!work.hasNonNull("modelSnapshot")) return profile(work);
    JsonNode p = Json.parse(auth.decrypt(work.path("modelSnapshot").asText()));
    return new Profile(
        p.path("provider").asText(),
        p.path("model").asText(),
        p.path("key").asText(),
        p.path("base").asText());
  }

  static void adapt(ObjectNode payload, Profile profile) {
    payload.put("model", profile.model());
    if (profile.provider().equals("openai")) {
      boolean thinking = payload.path("thinking").path("type").asText().equals("enabled");
      if (thinking && profile.model().matches("(?i)(gpt-4.*|gpt-3\\.5.*|chatgpt-4.*)"))
        throw new ApiError(400, "MODEL_CAPABILITY_UNSUPPORTED", "当前 OpenAI 模型不支持思考档位，请关闭思考或选择推理模型");
      payload.remove("thinking");
      int budget = payload.path("max_tokens").asInt(4000);
      payload.remove("max_tokens");
      payload.put("max_completion_tokens", budget);
      if (thinking)
        payload.put(
            "reasoning_effort",
            payload.path("reasoning_effort").asText().equals("max")
                ? "high"
                : payload.path("reasoning_effort").asText("low"));
      else payload.remove("reasoning_effort");
    }
  }

  ObjectNode models(String p) {
    Profile profile = profile(Json.obj("provider", provider(p)));
    var items = Json.arr();
    if (!profile.key().isBlank()) {
      try {
        JsonNode response =
            Upstream.get(profile.base() + "/models", profile.key(), Duration.ofSeconds(15));
        for (JsonNode n : response.path("data")) {
          String id = n.path("id").asText();
          if (id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) items.add(Json.obj("id", id));
          if (items.size() >= 500) break;
        }
        if (!items.isEmpty()) return Json.obj("items", items, "source", "provider");
      } catch (ApiError unavailable) {
        /* Catalog availability is not inference authorization. */
      }
    }
    items.add(Json.obj("id", profile.model()));
    return Json.obj(
        "items", items, "source", "configured", "notice", "未取得提供商模型目录；当前名称仅为配置值，可手动输入并测试连接。");
  }

  ObjectNode test(JsonNode body, String requestKey) {
    Json.uuid(requestKey);
    if (!body.isObject()
        || body.properties().stream()
            .anyMatch(e -> !Set.of("provider", "model", "apiKey").contains(e.getKey())))
      throw new ApiError(400, "INVALID_FIELD", "测试请求字段无效");
    provider(Json.required(body, "provider", 20));
    model(Json.required(body, "model", 120));
    Profile selected = profile(body);
    Profile profile =
        body.has("apiKey")
            ? new Profile(selected.provider(), selected.model(), key(body), selected.base())
            : selected;
    if (profile.key().isBlank()) throw new ApiError(503, "MODEL_NOT_CONFIGURED", "请先填写模型密钥");
    String hash = Auth.hash(Json.canonical(body).toString());
    ObjectNode cached =
        s.write(
            () -> {
              var rows =
                  s.db.queryForList(
                      "SELECT request_hash,result::text,created_at < now()-interval '2 minutes' AS"
                          + " expired FROM model_test_requests WHERE account_id=?::uuid AND"
                          + " request_key=?::uuid",
                      AccountScope.id(),
                      requestKey);
              if (!rows.isEmpty()) {
                var row = rows.get(0);
                if (!hash.equals(row.get("request_hash")))
                  throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能测试不同配置");
                if (row.get("result") != null)
                  return (ObjectNode) Json.parse((String) row.get("result"));
                throw new ApiError(
                    409,
                    "MODEL_TEST_PENDING",
                    Boolean.TRUE.equals(row.get("expired"))
                        ? "上次测试已中断，请重新发起一次测试"
                        : "该配置正在测试，请稍后查看结果",
                    true);
              }
              s.db.update(
                  "INSERT INTO model_test_requests(account_id,request_key,request_hash) VALUES"
                      + " (?::uuid,?::uuid,?)",
                  AccountScope.id(),
                  requestKey,
                  hash);
              return null;
            });
    if (cached != null) return testResult(cached);
    ObjectNode result;
    try {
      ObjectNode payload =
          Json.obj(
              "model",
              profile.model(),
              "messages",
              Json.arr().add(Json.obj("role", "user", "content", "Reply with OK only.")),
              "max_tokens",
              512);
      if (profile.provider().equals("deepseek"))
        payload.set("thinking", Json.obj("type", "disabled"));
      adapt(payload, profile);
      JsonNode response =
          Upstream.post(
              profile.base() + "/chat/completions", profile.key(), payload, Duration.ofSeconds(30));
      if (response.path("choices").path(0).path("message").path("content").asText().isBlank())
        throw new ApiError(502, "MODEL_EMPTY", "模型没有返回可用正文；请检查模型或增加输出预算", true);
      result =
          Json.obj(
              "ok",
              true,
              "provider",
              profile.provider(),
              "model",
              profile.model(),
              "verifiedAt",
              Json.now());
    } catch (ApiError error) {
      result = Json.obj("ok", false, "status", error.status, "error", error.detail());
    }
    final ObjectNode saved = result;
    s.write(
        () -> {
          s.db.update(
              "UPDATE model_test_requests SET result=?::jsonb WHERE account_id=?::uuid AND"
                  + " request_key=?::uuid",
              saved.toString(),
              AccountScope.id(),
              requestKey);
          return null;
        });
    return testResult(result);
  }

  private ObjectNode testResult(ObjectNode result) {
    if (result.path("ok").asBoolean()) return result;
    JsonNode e = result.path("error");
    throw new ApiError(
        result.path("status").asInt(502),
        e.path("code").asText(),
        e.path("message").asText(),
        e.path("retryable").asBoolean());
  }
}
