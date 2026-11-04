package dev.decision;

class ApiError extends RuntimeException {
  final int status;
  final String code;
  final boolean retryable;

  ApiError(int status, String code, String message) {
    this(status, code, message, false);
  }

  ApiError(int status, String code, String message, boolean retryable) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }

  com.fasterxml.jackson.databind.node.ObjectNode body() {
    return Json.obj("error", detail(), "requestId", Json.id());
  }

  com.fasterxml.jackson.databind.node.ObjectNode detail() {
    return Json.obj("code", code, "message", getMessage(), "retryable", retryable);
  }
}
