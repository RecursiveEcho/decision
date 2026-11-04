package dev.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Worker {
  final Store s;
  final Config c;
  final Domain d;
  final Model model;
  final Wiki wiki;
  final Evidence evidence;

  @Value("${app.worker-enabled}")
  boolean enabled;

  volatile boolean ready;

  Worker(Store s, Config c, Domain d, Model model, Wiki wiki, Evidence evidence) {
    this.s = s;
    this.c = c;
    this.d = d;
    this.model = model;
    this.wiki = wiki;
    this.evidence = evidence;
  }

  @EventListener(ApplicationReadyEvent.class)
  void recover() {
    s.write(
        () -> {
          List<String> ids =
              s.db.queryForList("SELECT id::text FROM jobs WHERE state='running'", String.class);
          for (String id : ids)
            fail(id, new ApiError(503, "PROCESS_RESTARTED", "服务重启中断了任务；请明确重试", true));
          return null;
        });
    ready = true;
  }

  @Scheduled(fixedDelay = 500)
  void tick() {
    if (ready && enabled) runOne();
  }

  synchronized boolean runOne() {
    ObjectNode work =
        s.write(
            () -> {
              List<Map<String, Object>> jobs =
                  s.db.queryForList(
                      "SELECT id::text,input::text FROM jobs WHERE state='queued' ORDER BY"
                          + " created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
              if (jobs.isEmpty()) return null;
              String id = (String) jobs.get(0).get("id");
              ObjectNode input = (ObjectNode) Json.parse((String) jobs.get(0).get("input"));
              input.put("jobId", id);
              ObjectNode job = s.get("job", id);
              job.put("status", "running");
              s.save("job", job);
              s.db.update("UPDATE jobs SET state='running' WHERE id=?::uuid", id);
              if (input.path("operation").asText().equals("analysis")) {
                List<String> ps =
                    s.db.queryForList(
                        "SELECT problem_id::text FROM jobs WHERE id=?::uuid", String.class, id);
                input.put("problemId", ps.get(0));
                s.db.update(
                    "UPDATE analysis_pending SET dirty=false WHERE problem_id=?::uuid", ps.get(0));
              }
              return input;
            });
    if (work == null) return false;
    try {
      switch (work.path("operation").asText()) {
        case "ingest" -> ingest(work);
        case "chat" -> chat(work);
        case "analysis" -> analyze(work);
        case "wiki" -> sync(work);
        default -> throw new ApiError(400, "UNKNOWN_JOB", "未知任务类型");
      }
    } catch (ApiError e) {
      s.write(
          () -> {
            fail(work.path("jobId").asText(), e);
            if (work.path("operation").asText().equals("wiki")) {
              ObjectNode topic = s.get("topic", work.path("topicId").asText());
              topic.put(
                  "syncStatus",
                  e.code.equals("WIKI_NOT_CONFIGURED") ? "pending_configuration" : "failed");
              s.save("topic", topic);
            }
            return null;
          });
    } catch (Exception e) {
      s.write(
          () -> {
            fail(
                work.path("jobId").asText(), new ApiError(500, "JOB_FAILED", "后台处理失败；请明确重试", true));
            return null;
          });
    } finally {
      if (work.path("operation").asText().equals("analysis"))
        s.write(
            () -> {
              String p = work.path("problemId").asText();
              List<Boolean> dirty =
                  s.db.queryForList(
                      "SELECT dirty FROM analysis_pending WHERE problem_id=?::uuid",
                      Boolean.class,
                      p);
              if (!dirty.isEmpty() && dirty.get(0)) s.analyze(p, c.mode());
              return null;
            });
    }
    return true;
  }

  void success(String id, ObjectNode result) {
    ObjectNode job = s.get("job", id);
    job.put("status", "succeeded");
    job.set("result", result);
    job.putNull("error");
    s.save("job", job);
    s.db.update("UPDATE jobs SET state='succeeded' WHERE id=?::uuid", id);
  }

  void fail(String id, ApiError error) {
    ObjectNode job = s.get("job", id);
    job.put("status", "failed");
    job.set("error", error.detail());
    s.save("job", job);
    s.db.update("UPDATE jobs SET state='failed' WHERE id=?::uuid", id);
  }

  void current(Model.Context ctx) {
    if (s.version() != ctx.version())
      throw new ApiError(409, "CONTEXT_CHANGED", "处理期间资料/记忆已更正，请重新提问", true);
  }

  void ingest(ObjectNode work) {
    String text = work.path("text").asText();
    String title = work.path("title").asText();
    String url = work.path("url").asText();
    String method = "user_input";
    if (text.isBlank()) {
      SafeFetch.Page page = SafeFetch.fetch(url);
      text = page.text();
      if (title.isBlank()) title = page.title();
      url = page.url();
      method = "http";
    }
    final String content = text, heading = title, origin = url, extraction = method;
    s.write(
        () -> {
          String p = work.path("problemId").asText();
          ObjectNode src =
              d.source(
                  p,
                  work.path("kind").asText(),
                  heading,
                  content,
                  origin,
                  work.hasNonNull("author") ? work.get("author").asText() : null,
                  extraction,
                  Json.obj(),
                  Json.now());
          String excerpt = content.substring(0, Math.min(content.length(), 2000));
          ObjectNode topic =
              s.create(
                  "topic",
                  p,
                  Json.obj(
                      "problemId",
                      p,
                      "title",
                      src.path("title"),
                      "markdown",
                      "# 资料摘录（非模型总结）\n\n"
                          + escape(excerpt)
                          + "\n\n来源 ID："
                          + src.path("id").asText(),
                      "evidence",
                      Json.arr()
                          .add(
                              Json.obj(
                                  "sourceId",
                                  src.path("id"),
                                  "quote",
                                  excerpt,
                                  "relation",
                                  "context")),
                      "revision",
                      1,
                      "wikiPageId",
                      null,
                      "wikiUrl",
                      null,
                      "syncStatus",
                      c.wikiConfigured() ? "pending" : "pending_configuration"));
          if (c.wikiConfigured()) s.job("wiki", p, Json.obj("topicId", topic.path("id")), c.mode());
          s.analyze(p, c.mode());
          success(
              work.path("jobId").asText(),
              Json.obj("sourceId", src.path("id"), "topicIds", Json.arr().add(topic.path("id"))));
          return null;
        });
  }

  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("!", "\\!");
  }

  void chat(ObjectNode work) {
    String p = work.path("problemId").asText();
    Model.Context ctx = model.context(p, work);
    ObjectNode response = model.call(ctx, false);
    s.write(
        () -> {
          current(ctx);
          String content = Json.required(response, "content", 20000);
          ArrayNode citations = model.citations(response.get("evidence"), ctx, evidence);
          ArrayNode uncertainties = Json.strings(response, "uncertainties");
          if (response.path("evidence").size() > citations.size())
            uncertainties.add("部分模型引用无法核验，已移除；相关陈述未获得来源支持。");
          if (citations.isEmpty()) uncertainties.add("本回答没有可核验的来源引用，请勿当作已证实的个人事实。");
          ObjectNode assistant =
              s.create(
                  "message",
                  p,
                  Json.obj(
                      "problemId",
                      p,
                      "role",
                      "assistant",
                      "content",
                      content,
                      "evidence",
                      citations,
                      "uncertainties",
                      uncertainties,
                      "mode",
                      c.mode()));
          success(
              work.path("jobId").asText(),
              Json.obj(
                  "userMessageId",
                  work.path("userMessageId"),
                  "assistantMessageId",
                  assistant.path("id")));
          return null;
        });
  }

  void analyze(ObjectNode work) {
    String p = work.path("problemId").asText();
    Model.Context ctx = model.context(p, work);
    ObjectNode response = model.call(ctx, true);
    s.write(
        () -> {
          current(ctx);
          ArrayNode ids = Json.arr();
          JsonNode discussions = response.path("discussions");
          if (!discussions.isArray())
            throw new ApiError(502, "MODEL_FORMAT", "分析结果缺少 discussions 数组");
          for (JsonNode candidate : discussions) {
            if (ids.size() >= 1) break;
            ArrayNode ev = model.citations(candidate.get("evidence"), ctx, evidence);
            if (ev.isEmpty()) continue;
            String title = Json.required(candidate, "title", 300),
                reason = Json.required(candidate, "reason", 4000);
            TreeSet<String> sourceHashes = new TreeSet<>();
            for (JsonNode e : ev)
              sourceHashes.add(
                  Auth.hash(s.get("source", e.path("sourceId").asText()).path("text").asText()));
            String fingerprint = Auth.hash(String.join(",", sourceHashes));
            if (s.db.queryForObject(
                    "SELECT count(*) FROM discussion_keys WHERE problem_id=?::uuid AND"
                        + " fingerprint=?",
                    Integer.class,
                    p,
                    fingerprint)
                > 0) continue;
            ObjectNode disc =
                s.create(
                    "discussion",
                    p,
                    Json.obj(
                        "problemId",
                        p,
                        "title",
                        title,
                        "reason",
                        reason,
                        "evidence",
                        ev,
                        "status",
                        "unread",
                        "snoozeUntil",
                        null));
            s.db.update(
                "INSERT INTO discussion_keys VALUES (?::uuid,?,?::uuid)",
                p,
                fingerprint,
                disc.path("id").asText());
            ids.add(disc.path("id"));
            for (ObjectNode device : s.all("device", null))
              s.create(
                  "delivery",
                  p,
                  Json.obj(
                      "discussionId",
                      disc.path("id"),
                      "deviceId",
                      device.path("id"),
                      "channel",
                      "desktop",
                      "status",
                      "pending",
                      "error",
                      null));
          }
          int count = 0;
          for (JsonNode mem : response.path("memories")) {
            if (count++ >= 2) break;
            ArrayNode ev = model.citations(mem.get("evidence"), ctx, evidence);
            if (ev.isEmpty()) continue;
            String content = Json.required(mem, "content", 4000);
            boolean exists =
                s.all("memory", p).stream()
                    .anyMatch(
                        m ->
                            m.path("content").asText().equals(content)
                                && m.path("status").asText().equals("active"));
            if (!exists)
              s.create(
                  "memory",
                  p,
                  Json.obj(
                      "problemId",
                      p,
                      "content",
                      content,
                      "kind",
                      "inferred",
                      "evidence",
                      ev,
                      "status",
                      "active",
                      "revision",
                      1));
          }
          success(work.path("jobId").asText(), Json.obj("discussionIds", ids));
          return null;
        });
  }

  void sync(ObjectNode work) {
    ObjectNode topic = s.get("topic", work.path("topicId").asText());
    Wiki.Result result = wiki.sync(topic);
    s.write(
        () -> {
          ObjectNode current = s.get("topic", topic.path("id").asText());
          if (current.path("revision").asInt() != topic.path("revision").asInt())
            throw new ApiError(409, "TOPIC_CHANGED", "同步期间主题已改变，请重试", true);
          current.put("wikiPageId", result.pageId());
          current.put("wikiUrl", result.url());
          current.put("syncStatus", "synced");
          s.save("topic", current);
          success(
              work.path("jobId").asText(),
              Json.obj("topicId", current.path("id"), "wikiUrl", result.url()));
          return null;
        });
  }
}
