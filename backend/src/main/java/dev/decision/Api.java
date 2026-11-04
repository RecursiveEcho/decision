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
  final Config c;

  Api(Domain d, Store s, Auth a, Config c) {
    this.d = d;
    this.s = s;
    auth = a;
    this.c = c;
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
                  "SELECT request_hash,status,response FROM idempotency_keys WHERE scope=? AND"
                      + " key=?::uuid",
                  scope,
                  key);
          if (!previous.isEmpty()) {
            Map<String, Object> p = previous.get(0);
            if (!hash.equals(p.get("request_hash")))
              throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能提交不同内容");
            return ResponseEntity.status((Integer) p.get("status"))
                .body(Json.parse(auth.decrypt((String) p.get("response"))));
          }
          ObjectNode result = action.get();
          s.db.update(
              "INSERT INTO idempotency_keys(scope,key,request_hash,status,response) VALUES"
                  + " (?,?::uuid,?,?,?)",
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
  ResponseEntity<?> login(@RequestBody JsonNode b, HttpServletRequest r) {
    auth.checkLoginRate(r.getRemoteAddr());
    String code = Json.required(b, "accessCode", 500);
    if (!auth.codeMatches(code)) {
      auth.failedLogin(r.getRemoteAddr());
      throw new ApiError(401, "UNAUTHORIZED", "访问码不正确");
    }
    return post(
        r,
        b,
        200,
        () -> {
          Instant expiry = Instant.now().plus(Duration.ofHours(24));
          return Json.obj(
              "accessToken", auth.token("owner", expiry), "expiresAt", expiry.toString());
        });
  }

  @GetMapping("/status")
  ObjectNode status() {
    return d.status();
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
          try {
            d.deleteSource(id);
          } catch (ApiError e) {
            if (e.status != 404) throw e;
          }
          return null;
        });
    return ResponseEntity.noContent().build();
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
          try {
            d.deleteMemory(id);
          } catch (ApiError e) {
            if (e.status != 404) throw e;
          }
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/topics/{id}/sync")
  ResponseEntity<?> sync(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 202, () -> d.sync(id));
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
    return post(r, b, 201, () -> d.device(b));
  }

  @PatchMapping("/devices/{id}")
  ObjectNode device(@PathVariable String id, @RequestBody JsonNode b) {
    return s.write(() -> d.control(id, b));
  }

  @PostMapping("/devices/{id}/heartbeat")
  ResponseEntity<?> heartbeat(
      @PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 200, () -> d.heartbeat(id, b, who(r)));
  }

  @PostMapping("/observations/batch")
  ResponseEntity<?> observations(@RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 200, () -> d.observations(b, who(r)));
  }

  @GetMapping("/devices/{id}/deliveries")
  ObjectNode deliveries(
      @PathVariable String id,
      HttpServletRequest r,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return d.page(d.deliveries(id, who(r)), "createdAt", cursor, limit, "deliveries:" + id);
  }

  @PostMapping("/deliveries/{id}/ack")
  ResponseEntity<?> ack(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return post(r, b, 200, () -> d.ack(id, b, who(r)));
  }

  @GetMapping({
    "/problems",
    "/sources",
    "/memories",
    "/topics",
    "/discussions",
    "/decisions",
    "/devices"
  })
  ObjectNode list(
      HttpServletRequest r,
      @RequestParam(required = false) String problemId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") int limit) {
    String resource = r.getRequestURI().substring("/api/v1/".length());
    String kind =
        resource.equals("memories") ? "memory" : resource.substring(0, resource.length() - 1);
    List<ObjectNode> items =
        d.list(kind, resource.equals("problems") || resource.equals("devices") ? null : problemId);
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
