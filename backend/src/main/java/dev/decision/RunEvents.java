package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Persistent event/snapshot projection. All writes share the worker's transaction boundary. */
@Component
class RunEvents {
  final Store s;
  final ModelSettings settings;
  private final ConcurrentHashMap<String, Active> running = new ConcurrentHashMap<>();
  private final ThreadLocal<Active> local = new ThreadLocal<>();

  RunEvents(Store s, ModelSettings settings) {
    this.s = s;
    this.settings = settings;
  }

  final class Active implements AutoCloseable {
    final String id;
    final ModelSettings.Profile profile;
    final Upstream.Cancellation cancellation = new Upstream.Cancellation();
    final StringBuilder buffered = new StringBuilder();
    long lastFlush;

    Active(JsonNode work) {
      id = work.path("jobId").asText();
      profile = settings.thaw(work);
    }

    void delta(String value) {
      cancellation.check();
      buffered.append(value);
      if (buffered.length() >= 64 || System.nanoTime() - lastFlush >= 80_000_000L) flush();
    }

    void flush() {
      if (buffered.isEmpty()) return;
      String value = buffered.toString();
      buffered.setLength(0);
      lastFlush = System.nanoTime();
      emit(id, "delta", Json.obj("text", value));
    }

    public void close() {
      running.remove(id, this);
      local.remove();
    }
  }

  Active activate(JsonNode work) {
    Active a = new Active(work);
    running.put(a.id, a);
    local.set(a);
    return a;
  }

  Active current() {
    return local.get();
  }

  ModelSettings.Profile profile() {
    return current() == null ? settings.profile(null) : current().profile;
  }

  void check() {
    Active a = current();
    if (a == null) return;
    a.cancellation.check();
    if (!Boolean.TRUE.equals(
        s.db.queryForObject(
            "SELECT state='running' FROM jobs WHERE id=?::uuid AND account_id=?::uuid",
            Boolean.class,
            a.id,
            AccountScope.id()))) throw new ApiError(409, "JOB_CANCELLED", "任务已停止");
  }

  void stage(String id, String stage, String label) {
    emit(id, "stage", Json.obj("stage", stage, "label", label));
  }

  void emit(String id, String type, ObjectNode data) {
    s.write(
        () -> {
          ObjectNode job = s.get("job", id);
          if (!Set.of("queued", "running").contains(job.path("status").asText())
              && !type.equals("terminal")) return null;
          long seq =
              s.db.queryForObject(
                  "SELECT COALESCE(MAX(seq),0)+1 FROM job_events WHERE job_id=?::uuid",
                  Long.class,
                  id);
          if (type.equals("delta")) {
            String partial = job.path("partialContent").asText() + data.path("text").asText();
            if (partial.length() > 20000)
              throw new ApiError(502, "MODEL_TOO_LONG", "模型正文超过长度限制", true);
            job.put("partialContent", partial);
          }
          if (type.equals("stage")) job.put("stage", data.path("stage").asText());
          job.put("updatedAt", Json.now());
          s.db.update(
              "UPDATE records SET data=?::jsonb,updated_at=now() WHERE id=?::uuid AND"
                  + " account_id=?::uuid",
              job.toString(),
              id,
              AccountScope.id());
          s.db.update(
              "INSERT INTO job_events(job_id,account_id,seq,type,data) VALUES"
                  + " (?::uuid,?::uuid,?,?,?::jsonb)",
              id,
              AccountScope.id(),
              seq,
              type,
              data.toString());
          return null;
        });
  }

  ObjectNode events(String id, long after) {
    if (after < 0) throw new ApiError(400, "INVALID_CURSOR", "事件游标无效");
    // The shared transaction lock serializes the snapshot with event and terminal commits.
    return s.write(
        () -> {
          ObjectNode job = s.get("job", id);
          if (!job.path("kind").asText().equals("chat"))
            throw new ApiError(400, "INVALID_JOB", "仅聊天任务提供事件");
          var rows =
              s.db.queryForList(
                  "SELECT seq,type,data::text FROM job_events WHERE job_id=?::uuid AND"
                      + " account_id=?::uuid AND seq>? ORDER BY seq LIMIT 200",
                  id,
                  AccountScope.id(),
                  after);
          var items = Json.arr();
          long cursor = after;
          for (var row : rows) {
            cursor = ((Number) row.get("seq")).longValue();
            items.add(
                Json.obj(
                    "id",
                    cursor,
                    "type",
                    row.get("type"),
                    "data",
                    Json.parse((String) row.get("data"))));
          }
          // Snapshot is authoritative. Polling clients can use it instead of replaying deltas.
          return Json.obj("items", items, "nextCursor", cursor, "job", s.get("job", id));
        });
  }

  ObjectNode cancel(String id) {
    ObjectNode result =
        s.write(
            () -> {
              ObjectNode job = s.get("job", id);
              if (!job.path("kind").asText().equals("chat"))
                throw new ApiError(400, "INVALID_JOB", "仅聊天任务可停止");
              if (Set.of("queued", "running").contains(job.path("status").asText())) {
                job.put("status", "cancelled");
                job.put("stage", "cancelled");
                job.putNull("error");
                s.save("job", job);
                s.db.update(
                    "UPDATE jobs SET state='cancelled' WHERE id=?::uuid AND account_id=?::uuid",
                    id,
                    AccountScope.id());
                emit(id, "terminal", Json.obj("status", "cancelled"));
              }
              return s.get("job", id);
            });
    if (result.path("status").asText().equals("cancelled")) signal(id);
    return result;
  }

  void signal(String id) {
    Active active = running.get(id);
    if (active != null) active.cancellation.cancel();
  }
}
