package dev.decision;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> known(ApiError e) {
    return ResponseEntity.status(e.status).body(e.body());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  ResponseEntity<?> invalid(Exception e) {
    return known(new ApiError(400, "INVALID_REQUEST", "请求格式或参数错误"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e) {
    return known(new ApiError(404, "NOT_FOUND", "接口不存在"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    return known(new ApiError(500, "INTERNAL_ERROR", "请求失败，请稍后重试", true));
  }
}
