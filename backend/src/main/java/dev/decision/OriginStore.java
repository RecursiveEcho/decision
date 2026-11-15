package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Independent Origin state, reusing account scope, encryption and the existing transaction lock. */
@Component
class OriginStore {
  final Store s;
  final Auth auth;

  OriginStore(Store s, Auth auth) { this.s = s; this.auth = auth; }

  ObjectNode create(String kind, String session, ObjectNode data) {
    data.put("id", Json.id());
    data.put("createdAt", Json.now());
    data.put("updatedAt", data.path("createdAt").asText());
    if (session != null) data.put("sessionId", session);
    s.db.update("INSERT INTO origin_records(account_id,id,kind,session_id,data)"
        + " VALUES (?::uuid,?::uuid,?,?::uuid,?::jsonb)",
        AccountScope.id(), data.path("id").asText(), kind, session, data.toString());
    return data;
  }

  ObjectNode get(String kind, String id) {
    Json.uuid(id);
    var rows = s.db.queryForList("SELECT data::text FROM origin_records"
        + " WHERE account_id=?::uuid AND id=?::uuid AND kind=?",
        String.class, AccountScope.id(), id, kind);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "记录不存在或无权访问");
    return (ObjectNode) Json.parse(rows.get(0));
  }

  ObjectNode save(String kind, ObjectNode data) {
    get(kind, data.path("id").asText());
    data.put("updatedAt", Json.now());
    s.db.update("UPDATE origin_records SET data=?::jsonb,updated_at=now()"
        + " WHERE account_id=?::uuid AND id=?::uuid AND kind=?",
        data.toString(), AccountScope.id(), data.path("id").asText(), kind);
    return data;
  }

  List<ObjectNode> list(String kind, String session) {
    String sql = "SELECT data::text FROM origin_records WHERE account_id=?::uuid AND kind=?";
    Object[] args = {AccountScope.id(), kind};
    if (session != null) {
      get("session", session);
      sql += " AND session_id=?::uuid";
      args = new Object[] {AccountScope.id(), kind, session};
    }
    return s.db.query(sql + " ORDER BY seq", (rs, i) -> (ObjectNode) Json.parse(rs.getString(1)), args);
  }

  /** Response and side effects commit in one transaction. Request payload cannot change on replay. */
  ObjectNode once(String scope, String key, JsonNode body, Supplier<ObjectNode> action) {
    if (key == null) throw new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "需要 Idempotency-Key UUID");
    Json.uuid(key);
    String hash = Auth.hash(Json.canonical(body).toString());
    return s.write(() -> {
      var rows = s.db.queryForList("SELECT request_hash,response FROM origin_requests"
          + " WHERE account_id=?::uuid AND scope=? AND request_key=?::uuid", AccountScope.id(), scope, key);
      if (!rows.isEmpty()) {
        if (!hash.equals(rows.get(0).get("request_hash")))
          throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "相同请求键不能提交不同内容");
        return (ObjectNode) Json.parse(auth.decrypt((String) rows.get(0).get("response")));
      }
      ObjectNode result = action.get();
      s.db.update("INSERT INTO origin_requests(account_id,scope,request_key,request_hash,response)"
          + " VALUES (?::uuid,?,?::uuid,?,?)", AccountScope.id(), scope, key, hash,
          auth.encrypt(result.toString()));
      return result;
    });
  }

  static void fields(JsonNode body, String... allowed) {
    Set<String> names = Set.of(allowed);
    if (body == null || !body.isObject()
        || body.properties().stream().anyMatch(e -> !names.contains(e.getKey())))
      throw new ApiError(400, "INVALID_FIELD", "请求包含不支持的字段");
  }
}
