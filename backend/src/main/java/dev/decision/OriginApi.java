package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/origin")
class OriginApi {
  final OriginStore o;
  final OriginChat chat;
  final OriginConfirmations confirmations;

  OriginApi(OriginStore o, OriginChat chat, OriginConfirmations confirmations) {
    this.o = o; this.chat = chat; this.confirmations = confirmations;
  }

  ResponseEntity<ObjectNode> post(HttpServletRequest request, JsonNode body, int status, Supplier<ObjectNode> action) {
    return ResponseEntity.status(status).body(o.once(request.getRequestURI(),
        request.getHeader("Idempotency-Key"), body, action));
  }

  @PostMapping("/sessions")
  ResponseEntity<ObjectNode> session(@RequestBody JsonNode body, HttpServletRequest request) {
    return post(request, body, 201, () -> chat.session(body));
  }

  @GetMapping("/sessions")
  ObjectNode sessions() {
    return Json.obj("items", o.list("session", null), "nextCursor", null);
  }

  @GetMapping("/sessions/{id}")
  ObjectNode history(@PathVariable String id) {
    return o.s.write(() -> chat.history(id));
  }

  @PostMapping("/sessions/{id}/messages")
  ResponseEntity<ObjectNode> message(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return post(request, body, 202, () -> chat.submit(id, body));
  }

  @GetMapping("/generations/{id}")
  ObjectNode generation(@PathVariable String id) { return chat.publicGeneration(id); }

  @GetMapping("/generations/{id}/events")
  ObjectNode events(@PathVariable String id, @RequestParam(defaultValue="0") long after) {
    return o.s.write(() -> chat.events(id, after));
  }

  @PostMapping("/generations/{id}/stop")
  ResponseEntity<ObjectNode> stop(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    OriginStore.fields(body);
    return post(request, body, 200, () -> chat.stop(id));
  }

  @PostMapping("/generations/{id}/retry")
  ResponseEntity<ObjectNode> retry(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    OriginStore.fields(body);
    return post(request, body, 202, () -> chat.retry(id));
  }

  @GetMapping("/attachments/{id}")
  ResponseEntity<byte[]> attachment(@PathVariable String id) {
    var file = chat.attachment(id);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.metadata().path("mimeType").asText()))
        .header("Content-Disposition", ContentDisposition.attachment().filename(file.metadata().path("name").asText(), StandardCharsets.UTF_8).build().toString())
        .body(file.bytes());
  }

  @PostMapping("/proposals")
  ResponseEntity<ObjectNode> proposal(@RequestBody JsonNode body, HttpServletRequest request) {
    return post(request, body, 201, () -> confirmations.create(body));
  }

  @GetMapping("/proposals/{id}")
  ObjectNode proposal(@PathVariable String id) { return o.get("proposal", id); }

  @PostMapping("/proposals/{id}/commit")
  ResponseEntity<ObjectNode> commit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return post(request, body, 200, () -> confirmations.commit(id, body, request.getHeader("Idempotency-Key")));
  }

  @PostMapping("/proposals/{id}/cancel")
  ResponseEntity<ObjectNode> cancel(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return post(request, body, 200, () -> confirmations.cancel(id, body));
  }

  @GetMapping("/decisions/{id}/execution-context")
  ObjectNode executionContext(@PathVariable String id) { return o.s.write(() -> confirmations.executionContext(id)); }

  @GetMapping("/executions/{id}")
  ObjectNode execution(@PathVariable String id) { return o.get("authorization", id); }

  @PostMapping("/executions/{id}/claim")
  ResponseEntity<ObjectNode> claim(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    String credential = request.getHeader("X-Origin-Executor-Key");
    confirmations.executor(credential); // Also required on an idempotent response replay.
    return post(request, body, 200, () -> confirmations.claimExecution(id, body, credential));
  }

  @PostMapping("/executions/{id}/result")
  ResponseEntity<ObjectNode> result(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    String credential = request.getHeader("X-Origin-Executor-Key");
    confirmations.executor(credential);
    return post(request, body, 200, () -> confirmations.executionResult(id, body, credential));
  }
}
