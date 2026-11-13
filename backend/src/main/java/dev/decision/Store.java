package dev.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Store {
  final JdbcTemplate db;
  final TransactionTemplate tx;

  Store(JdbcTemplate db, PlatformTransactionManager tm) {
    this.db = db;
    this.tx = new TransactionTemplate(tm);
  }

  // Shared transaction lock serializes idempotency, job claiming and aggregate revisions.
  <T> T write(Supplier<T> action) {
    return tx.execute(
        s -> {
          db.execute("SELECT pg_advisory_xact_lock(403120241104)");
          return action.get();
        });
  }

  ObjectNode create(String kind, String problemId, ObjectNode data) {
    data.put("id", Json.id());
    String now = Json.now();
    if (!data.has("createdAt")) data.put("createdAt", now);
    if (!data.has("updatedAt")) data.put("updatedAt", now);
    db.update(
        "INSERT INTO records(account_id,id,kind,problem_id,data,context_revision)"
            + " VALUES (?::uuid,?::uuid,?,?::uuid,?::jsonb,?)",
        AccountScope.id(),
        data.path("id").asText(),
        kind,
        problemId,
        data.toString(),
        kind.equals("message") ? version() : null);
    return data;
  }

  ObjectNode get(String kind, String id) {
    Json.uuid(id);
    List<String> r =
        db.queryForList(
            "SELECT data::text FROM records WHERE id=?::uuid AND kind=? AND NOT deleted AND"
                + " account_id=?::uuid",
            String.class,
            id,
            kind,
            AccountScope.id());
    if (r.isEmpty()) throw new ApiError(404, "NOT_FOUND", "记录不存在或已删除");
    return (ObjectNode) Json.parse(r.get(0));
  }

  boolean deletedHere(String kind, String id) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM records WHERE account_id=?::uuid AND id=?::uuid AND kind=?"
                + " AND deleted)",
            Boolean.class,
            AccountScope.id(),
            id,
            kind));
  }

  ObjectNode save(String kind, ObjectNode data) {
    ObjectNode old = get(kind, data.path("id").asText());
    db.update(
        "INSERT INTO record_history(record_id,kind,data) VALUES (?::uuid,?,?::jsonb)",
        old.path("id").asText(),
        kind,
        old.toString());
    data.put("updatedAt", Json.now());
    db.update(
        "UPDATE records SET data=?::jsonb,updated_at=now() WHERE id=?::uuid",
        data.toString(),
        data.path("id").asText());
    return data;
  }

  void delete(String kind, String id) {
    get(kind, id);
    db.update("UPDATE records SET deleted=true,updated_at=now() WHERE id=?::uuid", id);
    bump();
  }

  List<ObjectNode> all(String kind, String problemId) {
    String q = "SELECT data::text FROM records WHERE account_id=?::uuid AND kind=? AND NOT deleted";
    Object[] args = {AccountScope.id(), kind};
    if (problemId != null) {
      Json.uuid(problemId);
      q += " AND problem_id=?::uuid";
      args = new Object[] {AccountScope.id(), kind, problemId};
    }
    return db.query(
        q + " ORDER BY created_at,id", (rs, i) -> (ObjectNode) Json.parse(rs.getString(1)), args);
  }

  long version() {
    return db.queryForObject(
        "SELECT revision FROM context_version WHERE account_id=?::uuid",
        Long.class,
        AccountScope.id());
  }

  void bump() {
    db.update(
        "UPDATE context_version SET revision=revision+1 WHERE account_id=?::uuid",
        AccountScope.id());
  }

  long memoryEpoch() {
    return db.queryForObject(
        "SELECT epoch FROM chat_memory_state WHERE account_id=?::uuid",
        Long.class,
        AccountScope.id());
  }

  void bumpMemoryEpoch() {
    db.update(
        "UPDATE chat_memory_state SET epoch=epoch+1 WHERE account_id=?::uuid", AccountScope.id());
  }

  void requireCurrentUserMessage(String problemId, String messageId) {
    boolean validId = true;
    try {
      Json.uuid(messageId);
    } catch (ApiError e) {
      validId = false;
    }
    if (!validId
        || db.queryForObject(
                "SELECT count(*) FROM records WHERE id=?::uuid AND problem_id=?::uuid AND"
                    + " kind='message' AND NOT deleted AND data->>'role'='user' AND"
                    + " context_revision=? AND account_id=?::uuid",
                Integer.class,
                messageId,
                problemId,
                version(),
                AccountScope.id())
            != 1) throw new ApiError(409, "CONTEXT_CHANGED", "原话对应的资料或记忆已更正，请保留原话并调整后重新发送", true);
  }

  boolean jobRunning(JsonNode work) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT state='running' FROM jobs WHERE account_id=?::uuid AND id=?::uuid",
            Boolean.class,
            AccountScope.id(),
            work.path("jobId").asText()));
  }

  ObjectNode job(String operation, String problemId, ObjectNode input, String mode) {
    // This is the shared worker's capacity, never a user-facing usage count.
    if (db.queryForObject(
            "SELECT count(*) FROM jobs WHERE state IN ('queued','running')", Integer.class)
        >= 200) throw new ApiError(429, "JOB_QUEUE_FULL", "后台队列繁忙，请稍后提交", true);
    input.put("operation", operation);
    String kind =
        switch (operation) {
          case "chat" -> "chat";
          case "ingest" -> "source_ingest";
          case "wiki" -> "topic_sync";
          case "memory_overview" -> "memory_overview";
          case "knowledge_proposal" -> "knowledge_proposal";
          default -> "analysis";
        };
    ObjectNode j =
        create(
            "job",
            problemId,
            Json.obj(
                "kind",
                kind,
                "status",
                "queued",
                "result",
                null,
                "error",
                null,
                "mode",
                mode,
                "problemId",
                problemId,
                "partialContent",
                "",
                "provider",
                input.path("provider").asText("deepseek"),
                "model",
                input.path("model").asText()));
    db.update(
        "INSERT INTO jobs(account_id,id,problem_id,input) VALUES"
            + " (?::uuid,?::uuid,?::uuid,?::jsonb)",
        AccountScope.id(),
        j.path("id").asText(),
        problemId,
        input.toString());
    return j;
  }

  ObjectNode analyze(String problemId, String mode) {
    db.update(
        "INSERT INTO analysis_pending(problem_id,dirty) VALUES (?::uuid,true) ON"
            + " CONFLICT(problem_id) DO UPDATE SET dirty=true",
        problemId);
    int count =
        db.queryForObject(
            "SELECT count(*) FROM jobs WHERE problem_id=?::uuid AND state IN ('queued','running')"
                + " AND input->>'operation'='analysis'",
            Integer.class,
            problemId);
    if (count == 0) return job("analysis", problemId, Json.obj(), mode);
    String id =
        db.queryForObject(
            "SELECT id::text FROM jobs WHERE problem_id=?::uuid AND state IN ('queued','running')"
                + " AND input->>'operation'='analysis' LIMIT 1",
            String.class,
            problemId);
    return get("job", id);
  }
}
