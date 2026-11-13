package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class Api {
  final Domain d;
  final Store s;
  final Auth auth;
  final Accounts accounts;
  final Config c;
  final MemoryOverview overview;
  final Knowledge knowledge;

  Api(
      Domain d,
      Store s,
      Auth a,
      Config c,
      MemoryOverview overview,
      Knowledge knowledge,
      Accounts accounts) {
    this.d = d;
    this.s = s;
    auth = a;
    this.c = c;
    this.overview = overview;
    this.knowledge = knowledge;
    this.accounts = accounts;
  }

  String who(HttpServletRequest r) {
    return Objects.toString(r.getAttribute("subject"), "anonymous");
  }

  ResponseEntity<?> post(
      HttpServletRequest r, JsonNode body, int status, Supplier<ObjectNode> action) {
    String key = r.getHeader("Idempotency-Key");
    if (key == null)
      throw new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "创建请求需要 Idempotency-Key UUID");
    Json.uuid(key);
    String scope = who(r) + ":" + r.getRequestURI();
    String hash = Auth.hash(Json.canonical(body).toString());
    return s.write(
        () -> {
          List<Map<String, Object>> previous =
              s.db.queryForList(
                  "SELECT request_hash,status,response FROM idempotency_keys WHERE"
                      + " account_id=?::uuid AND scope=? AND key=?::uuid",
                  AccountScope.id(),
                  scope,
                  key);
          if (!previous.isEmpty()) {
            Map<String, Object> p = previous.get(0);
            if (!hash.equals(p.get("request_hash")))
              throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能提交不同内容");
            JsonNode cached = Json.parse(auth.decrypt((String) p.get("response")));
            // Keep the key reserved. A late retry must not create a replacement conversation
            // or redisplay an old job and its messages after deletion.
            String path = r.getRequestURI();
            if (path.equals("/api/v1/conversations") || path.equals("/api/v1/problems")) {
              ObjectNode problem =
                  (ObjectNode) (cached.has("problem") ? cached.get("problem") : cached);
              ObjectNode current = d.readProblem(problem.path("id").asText());
              // A replay can predate status support or a manual rename/archive.
              problem.set("status", current.get("status"));
              problem.set("title", current.get("title"));
            } else if ((status == 202 || path.matches("/api/v1/jobs/[^/]+/cancel"))
                && cached.hasNonNull("id")) {
              // Job creation and cancellation caches must obey current visibility after deletion.
              s.get("job", cached.path("id").asText());
            } else if (cached.hasNonNull("proposalId")) {
              s.get("knowledge_proposal", cached.path("proposalId").asText());
            }
            return ResponseEntity.status((Integer) p.get("status")).body(cached);
          }
          ObjectNode result = action.get();
          s.db.update(
              "INSERT INTO idempotency_keys(account_id,scope,key,request_hash,status,response)"
                  + " VALUES (?::uuid,?,?::uuid,?,?,?)",
              AccountScope.id(),
              scope,
              key,
              hash,
              status,
              auth.encrypt(result.toString()));
          return ResponseEntity.status(status).body(result);
        });
  }

  @GetMapping("/health")
  ObjectNode health() {
    return Json.obj("status", "ok", "version", "0.1.0");
  }

  @PostMapping("/auth/login")
  ObjectNode login(@RequestBody JsonNode b, HttpServletRequest r) {
    return accounts.login(b, r.getHeader("Idempotency-Key"), r.getRemoteAddr());
  }

  @PostMapping("/auth/register")
  ResponseEntity<?> register(@RequestBody JsonNode b, HttpServletRequest r) {
    return ResponseEntity.status(201)
        .body(accounts.register(b, r.getHeader("Idempotency-Key"), r.getRemoteAddr()));
  }

  @GetMapping("/auth/me")
  ObjectNode me() {
    return accounts.current();
  }

  @GetMapping("/status")
  ObjectNode status() {
    return d.status();
  }

  @GetMapping("/settings/model")
  ObjectNode modelSettings() {
    return d.settings.view();
  }

  @PutMapping("/settings/model")
  ObjectNode modelSettings(@RequestBody JsonNode body) {
    return d.settings.update(body);
  }

  @DeleteMapping("/settings/model/key")
  ObjectNode deleteModelKey(@RequestParam String provider) {
    return d.settings.clearKey(provider);
  }

  @GetMapping("/settings/models")
  ObjectNode models(@RequestParam String provider) {
    return d.settings.models(provider);
  }

  @PostMapping("/settings/model/test")
  ObjectNode testModel(@RequestBody JsonNode body, HttpServletRequest request) {
    String key = request.getHeader("Idempotency-Key");
    if (key == null) throw new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "测试请求需要幂等键");
    return d.settings.test(body, key);
  }

  @GetMapping("/settings/memory")
  ObjectNode memorySettings() {
    return Json.obj(
        "mode",
        "local",
        "central",
        Json.obj("connected", false, "enabled", false),
        "notice",
        "当前使用本地记忆；统一个人记忆中心接口已预留，尚未连接或迁移数据。");
  }

  @GetMapping("/conversations/search")
  ObjectNode search(
      @RequestParam String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return d.search(q, cursor, limit);
  }

  @GetMapping("/jobs")
  ObjectNode jobs(
      @RequestParam(defaultValue = "chat") String kind,
      @RequestParam(defaultValue = "active") String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "100") int limit) {
    return d.activeJobs(kind, status, cursor, limit);
  }

  @GetMapping("/jobs/{id}/events")
  ObjectNode events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    return d.runs.events(id, after);
  }

  @PostMapping("/jobs/{id}/cancel")
  ResponseEntity<?> cancel(
      @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return post(
        request,
        body,
        200,
        () -> {
          if (!body.isObject() || !body.isEmpty())
            throw new ApiError(400, "INVALID_REQUEST", "停止请求体必须为空对象");
          return d.runs.cancel(id);
        });
  }

  @PostMapping("/problems")
  ResponseEntity<?> problems(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 201, () -> d.problem(b));
  }

  @PostMapping("/conversations")
  ResponseEntity<?> conversation(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 202, () -> d.conversation(b));
  }

  @PostMapping("/jobs/{id}/retry")
  ResponseEntity<?> retry(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(
        r,
        b,
        202,
        () -> {
          if (!b.isObject() || !b.isEmpty())
            throw new ApiError(400, "INVALID_REQUEST", "重试请求体必须为{}对象");
          return d.retryChat(id);
        });
  }

  @GetMapping("/problems")
  ObjectNode problems(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return d.problems(status == null ? "active" : status, cursor, limit);
  }

  @GetMapping("/problems/{id}")
  ObjectNode problem(@PathVariable String id) {
    return d.readProblem(id);
  }

  @DeleteMapping("/problems/{id}")
  ResponseEntity<?> deleteProblem(@PathVariable String id) {
    s.write(
        () -> {
          d.deleteProblem(id);
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/problems/{id}")
  ObjectNode problem(@PathVariable String id, @RequestBody JsonNode b) {
    return s.write(() -> d.editProblem(id, b));
  }

  @PostMapping("/problems/{id}/messages")
  ResponseEntity<?> chat(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 202, () -> d.chat(id, b));
  }

  @PostMapping("/problems/{id}/analyze")
  ResponseEntity<?> analyze(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(
        r,
        b,
        202,
        () -> {
          if (!b.isObject() || !b.isEmpty())
            throw new ApiError(400, "INVALID_REQUEST", "重新分析的请求体必须为{}对象");
          return d.reanalyze(id);
        });
  }

  @GetMapping("/problems/{id}/messages")
  ObjectNode messages(
      @PathVariable String id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    List<ObjectNode> messages = d.list("message", id);
    messages.forEach(d.attachments::hydrate);
    return d.page(messages, "createdAt", cursor, limit, "messages:" + id);
  }

  @GetMapping("/attachments/{id}")
  ResponseEntity<byte[]> attachment(@PathVariable String id) {
    Attachments.Binary file = d.attachments.get(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.metadata().path("mimeType").asText()))
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(file.metadata().path("name").asText(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(file.bytes());
  }

  @GetMapping("/jobs/{id}")
  ObjectNode job(@PathVariable String id, HttpServletRequest r) {
    return d.job(id, who(r));
  }

  @PostMapping("/sources")
  ResponseEntity<?> sources(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 202, () -> d.ingest(b));
  }

  @GetMapping("/sources/{id}")
  ObjectNode source(@PathVariable String id) {
    return s.get("source", id);
  }

  @DeleteMapping("/sources/{id}")
  ResponseEntity<?> deleteSource(@PathVariable String id) {
    s.write(
        () -> {
          if (!s.deletedHere("source", id)) d.deleteSource(id);
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/memories/overview")
  ObjectNode memoryOverview(@RequestParam(required = false) String problemId) {
    return overview.get(problemId);
  }

  @PostMapping("/memories/overview")
  ResponseEntity<?> memoryOverview(@RequestBody JsonNode b, HttpServletRequest r) {
    return s.write(
        () -> {
          // Validate freshness even before an older idempotency response can be replayed.
          overview.checkRequest(b);
          return post(r, b, 202, () -> overview.request(b));
        });
  }

  @PostMapping("/memories")
  ResponseEntity<?> memories(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 201, () -> d.memory(b));
  }

  @PatchMapping("/memories/{id}")
  ObjectNode memory(@PathVariable String id, @RequestBody JsonNode b) {
    return s.write(() -> d.editMemory(id, b));
  }

  @DeleteMapping("/memories/{id}")
  ResponseEntity<?> deleteMemory(@PathVariable String id) {
    s.write(
        () -> {
          if (!s.deletedHere("memory", id)) d.deleteMemory(id);
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/topics/{id}/sync")
  ResponseEntity<?> sync(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    AccountScope.requireAdmin();
    return post(r, b, 202, () -> d.sync(id));
  }

  @PostMapping("/problems/{id}/knowledge-proposals")
  ResponseEntity<?> knowledgeProposal(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 202, () -> knowledge.request(id, b));
  }

  @GetMapping("/knowledge-proposals")
  ObjectNode knowledgeProposals(
      @RequestParam(required = false) String problemId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.write(
        () -> {
          if (problemId != null) s.get("problem", problemId);
          ObjectNode page =
              d.page(
                  s.all("knowledge_proposal", problemId),
                  "updatedAt",
                  cursor,
                  limit,
                  "knowledge-proposals:" + problemId);
          Map<String, String> fingerprints = new HashMap<>();
          for (JsonNode proposal : page.path("items"))
            knowledge.addFreshness((ObjectNode) proposal, fingerprints);
          return page;
        });
  }

  @GetMapping("/knowledge-proposals/{id}")
  ObjectNode knowledgeProposal(@PathVariable String id) {
    return s.write(
        () -> {
          ObjectNode proposal = s.get("knowledge_proposal", id);
          knowledge.addFreshness(proposal, new HashMap<>());
          return proposal;
        });
  }

  @PostMapping("/knowledge-proposals/{id}/accept")
  ResponseEntity<?> acceptKnowledge(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 200, () -> knowledge.accept(id, b));
  }

  @PostMapping("/knowledge-proposals/{id}/dismiss")
  ResponseEntity<?> dismissKnowledge(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 200, () -> knowledge.dismiss(id, b));
  }

  @GetMapping("/knowledge-entries")
  ObjectNode knowledgeEntries(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
    return d.page(s.all("knowledge_entry", null), "updatedAt", cursor, limit, "knowledge-entries");
  }

  @GetMapping("/knowledge-entries/{id}")
  ObjectNode knowledgeEntry(@PathVariable String id) {
    return s.get("knowledge_entry", id);
  }

  @GetMapping("/knowledge-entries/{id}/history")
  ObjectNode knowledgeHistory(@PathVariable String id) {
    return knowledge.history(id);
  }

  @GetMapping("/knowledge-export")
  ObjectNode knowledgeExport() {
    return knowledge.export();
  }

  @PatchMapping("/discussions/{id}")
  ObjectNode discussion(@PathVariable String id, @RequestBody JsonNode b) {
    return s.write(() -> d.discussion(id, b));
  }

  @PostMapping("/decisions")
  ResponseEntity<?> decisions(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 201, () -> d.decision(b));
  }

  @PostMapping("/decisions/{id}/reviews")
  ResponseEntity<?> reviews(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 201, () -> d.review(id, b));
  }

  @PostMapping("/devices")
  ResponseEntity<?> devices(@RequestBody JsonNode b, HttpServletRequest r) {
    AccountScope.requireAdmin();
    return post(r, b, 201, () -> d.device(b));
  }

  @PatchMapping("/devices/{id}")
  ObjectNode device(@PathVariable String id, @RequestBody JsonNode b) {
    AccountScope.requireAdmin();
    return s.write(() -> d.control(id, b));
  }

  @PostMapping("/devices/{id}/heartbeat")
  ResponseEntity<?> heartbeat(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    AccountScope.requireAdmin();
    return post(r, b, 200, () -> d.heartbeat(id, b, who(r)));
  }

  @PostMapping("/observations/batch")
  ResponseEntity<?> observations(@RequestBody JsonNode b, HttpServletRequest r) {
    AccountScope.requireAdmin();
    return post(r, b, 200, () -> d.observations(b, who(r)));
  }

  @GetMapping("/devices/{id}/deliveries")
  ObjectNode deliveries(
      @PathVariable String id,
      HttpServletRequest r,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    AccountScope.requireAdmin();
    return d.page(d.deliveries(id, who(r)), "createdAt", cursor, limit, "deliveries:" + id);
  }

  @PostMapping("/deliveries/{id}/ack")
  ResponseEntity<?> ack(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    AccountScope.requireAdmin();
    return post(r, b, 200, () -> d.ack(id, b, who(r)));
  }

  @GetMapping({"/sources", "/memories", "/topics", "/discussions", "/decisions", "/devices"})
  ObjectNode list(
      HttpServletRequest r,
      @RequestParam(required = false) String problemId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    String resource = r.getRequestURI().substring("/api/v1/".length());
    if (resource.equals("devices")) AccountScope.requireAdmin();
    String kind =
        resource.equals("memories") ? "memory" : resource.substring(0, resource.length() - 1);
    List<ObjectNode> items = d.list(kind, resource.equals("devices") ? null : problemId);
    if (status != null) items.removeIf(n -> !n.path("status").asText().equals(status));
    String order =
        switch (kind) {
          case "source", "discussion" -> "createdAtDesc";
          case "decision" -> "confirmedAt";
          default -> "updatedAt";
        };
    return d.page(items, order, cursor, limit, resource + ":" + problemId + ":" + status);
  }
}
