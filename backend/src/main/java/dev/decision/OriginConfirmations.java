package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable proposals; ordinary chat never calls the Decision domain write methods. */
@Component
class OriginConfirmations {
  final Store s;
  final OriginStore o;
  final Domain d;
  final String executorKey;

  OriginConfirmations(Store s, OriginStore o, Domain d,
      @Value("${app.origin-executor-key:}") String executorKey) {
    this.s = s; this.o = o; this.d = d; this.executorKey = executorKey;
  }

  ObjectNode create(JsonNode body) {
    OriginStore.fields(body, "sessionId", "action", "parameters", "expectedContext");
    String session = Json.required(body, "sessionId", 36);
    o.get("session", session);
    String action = Json.choice(body, "action", "decision.create_problem", "decision.confirm_decision", "execution.authorize");
    JsonNode p = body.path("parameters");
    if (!p.isObject()) throw new ApiError(400, "INVALID_FIELD", "parameters 必须是对象");
    ObjectNode parameters = (ObjectNode) p.deepCopy();
    JsonNode expected = body.has("expectedContext") ? body.get("expectedContext").deepCopy() : Json.obj();
    String target;
    switch (action) {
      case "decision.create_problem" -> {
        OriginStore.fields(p, "title", "goal", "constraints", "unknowns");
        d.problemDraft(p); // Validation only; does not write records, jobs or attachments.
        if (!expected.isObject() || !expected.isEmpty())
          throw new ApiError(400, "INVALID_FIELD", "新建档案没有既有目标版本");
        target = "new-problem";
      }
      case "decision.confirm_decision" -> {
        OriginStore.fields(p, "problemId", "choice", "rationale", "expectedOutcome", "evidence", "reviewAt");
        target = Json.required(p, "problemId", 36);
        d.readProblem(target);
        s.requireDecisionContext(target, expected);
        d.decisionDraft(p);
      }
      default -> {
        OriginStore.fields(p, "decisionId", "executorId", "action", "target", "scope", "arguments");
        target = Json.required(p, "decisionId", 36);
        Json.required(p, "executorId", 80);
        Json.required(p, "action", 120);
        Json.required(p, "target", 300);
        if (!p.path("scope").isObject() || p.path("scope").isEmpty() || !p.path("arguments").isObject())
          throw new ApiError(400, "INVALID_FIELD", "执行授权必须明确范围和参数");
        if (p.toString().length() > 32000) throw new ApiError(400, "INVALID_FIELD", "执行参数过长");
        s.get("decision", target);
        requireExecutionContext(target, expected);
      }
    }
    ObjectNode binding = Json.obj("accountId", AccountScope.id(), "sessionId", session,
        "action", action, "target", target, "parameters", parameters, "expectedContext", expected);
    String digest = Auth.hash(Json.canonical(binding).toString());
    return o.create("proposal", session, Json.obj("action", action, "target", target,
        "parameters", parameters, "expectedContext", expected, "digest", digest,
        "requestId", Json.id(), "state", "pending", "result", null));
  }

  ObjectNode executionContext(String id) {
    ObjectNode record = s.get("decision", id);
    return Json.obj("decisionId", id, "decisionRevision", s.recordRevision("decision", id),
        "problemContext", s.decisionContext(record.path("problemId").asText()));
  }

  void requireExecutionContext(String id, JsonNode expected) {
    if (!Json.canonical(executionContext(id)).toString().equals(Json.canonical(expected).toString()))
      throw new ApiError(409, "CONFIRMATION_STALE", "决定或相关证据已变化，请重新确认授权");
  }

  void checkDigest(ObjectNode proposal, JsonNode body) {
    String digest = Json.required(body, "digest", 64);
    if (!digest.equals(proposal.path("digest").asText()))
      throw new ApiError(409, "CONFIRMATION_MISMATCH", "确认内容与展示的提案不一致");
  }

  ObjectNode commit(String id, JsonNode body, String key) {
    OriginStore.fields(body, "digest", "requestId");
    ObjectNode proposal = o.get("proposal", id);
    checkDigest(proposal, body);
    if (!proposal.path("requestId").asText().equals(Json.required(body, "requestId", 36))
        || !proposal.path("requestId").asText().equals(key))
      throw new ApiError(409, "CONFIRMATION_REQUEST_KEY", "提交必须使用该提案固定的请求键");
    String state = proposal.path("state").asText();
    if (state.equals("committed")) return proposal;
    if (!state.equals("pending")) throw new ApiError(409, "CONFIRMATION_CLOSED", "提案已经取消");
    ObjectNode parameters = (ObjectNode) proposal.path("parameters").deepCopy();
    String action = proposal.path("action").asText();
    ObjectNode result;
    switch (action) {
      case "decision.create_problem" -> result = d.problem(parameters);
      case "decision.confirm_decision" -> {
        s.requireDecisionContext(parameters.path("problemId").asText(), proposal.path("expectedContext"));
        parameters.set("expectedContext", proposal.get("expectedContext"));
        result = d.decision(parameters); // Sets confirmedAt only here, after explicit consent.
      }
      case "execution.authorize" -> {
        requireExecutionContext(parameters.path("decisionId").asText(), proposal.path("expectedContext"));
        result = o.create("authorization", proposal.path("sessionId").asText(),
            Json.obj("proposalId", id, "parameters", parameters, "expectedContext", proposal.get("expectedContext"),
                "digest", proposal.get("digest"), "state", "authorized", "result", null,
                "executionKey", Json.id(), "attemptId", null));
      }
      default -> throw new ApiError(409, "UNSUPPORTED_ACTION", "该操作没有实现的适配器");
    }
    proposal.put("state", "committed");
    proposal.put("committedAt", Json.now());
    // Store a receipt, not a mutable copy of the entire business document.
    proposal.set("result", Json.obj("resourceId", result.path("id"), "action", action));
    return o.save("proposal", proposal);
  }

  ObjectNode cancel(String id, JsonNode body) {
    OriginStore.fields(body, "digest");
    ObjectNode proposal = o.get("proposal", id);
    checkDigest(proposal, body);
    if (proposal.path("state").asText().equals("committed"))
      throw new ApiError(409, "ALREADY_COMMITTED", "已经提交，不能按取消提案回滚业务记录");
    if (proposal.path("state").asText().equals("cancelled")) return proposal;
    proposal.put("state", "cancelled");
    return o.save("proposal", proposal);
  }

  // An owner token alone cannot impersonate the executor. Unconfigured by default.
  void executor(String supplied) {
    if (executorKey.isBlank()) throw new ApiError(503, "EXECUTOR_NOT_CONFIGURED", "未配置可信执行器");
    if (supplied == null || !MessageDigest.isEqual(executorKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ApiError(403, "EXECUTOR_FORBIDDEN", "需要可信执行器凭据");
  }

  ObjectNode claimExecution(String id, JsonNode body, String supplied) {
    executor(supplied);
    OriginStore.fields(body, "executorId", "digest", "executionKey");
    ObjectNode a = o.get("authorization", id);
    if (!a.path("parameters").path("executorId").asText().equals(Json.required(body, "executorId", 80))
        || !a.path("digest").asText().equals(Json.required(body, "digest", 64))
        || !a.path("executionKey").asText().equals(Json.required(body, "executionKey", 36)))
      throw new ApiError(409, "EXECUTION_SCOPE_MISMATCH", "执行器、参数或范围与授权不一致");
    if (!a.path("state").asText().equals("authorized")) return a;
    requireExecutionContext(a.path("parameters").path("decisionId").asText(), a.path("expectedContext"));
    a.put("state", "running");
    a.put("attemptId", Json.id());
    return o.save("authorization", a);
  }

  ObjectNode executionResult(String id, JsonNode body, String supplied) {
    executor(supplied);
    OriginStore.fields(body, "executorId", "attemptId", "status", "receipt", "actualResult");
    ObjectNode a = o.get("authorization", id);
    if (!a.path("parameters").path("executorId").asText().equals(Json.required(body, "executorId", 80))
        || !a.path("attemptId").asText().equals(Json.required(body, "attemptId", 36)))
      throw new ApiError(409, "EXECUTION_SCOPE_MISMATCH", "执行回执不属于该次授权");
    String status = Json.choice(body, "status", "succeeded", "failed", "unknown");
    Json.required(body, "receipt", 300);
    if (!body.path("actualResult").isObject() || body.path("actualResult").isEmpty()
        || body.path("actualResult").toString().length() > 32000)
      throw new ApiError(400, "EXECUTION_RESULT_REQUIRED", "必须提供执行器的实际结果");
    if (Set.of("succeeded", "failed", "unknown").contains(a.path("state").asText())) {
      if (!Json.canonical(a.path("result")).toString().equals(Json.canonical(body).toString()))
        throw new ApiError(409, "EXECUTION_RESULT_CONFLICT", "已有回执不可被不同结果覆盖");
      return a;
    }
    if (!a.path("state").asText().equals("running"))
      throw new ApiError(409, "EXECUTION_NOT_STARTED", "授权尚未由执行器领取");
    a.put("state", status);
    a.set("result", body.deepCopy());
    return o.save("authorization", a);
  }
}
