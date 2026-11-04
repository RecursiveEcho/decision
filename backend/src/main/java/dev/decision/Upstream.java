package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class Upstream {
  static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  static JsonNode post(String url, String token, ObjectNode body) {
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
        throw new IllegalArgumentException();
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
              .build();
      HttpResponse<InputStream> response =
          CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      try (InputStream in = response.body()) {
        bytes = in.readNBytes(1_048_577);
      }
      if (response.statusCode() == 429)
        throw new ApiError(429, "UPSTREAM_RATE_LIMIT", "外部服务限流，请稍后重试", true);
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ApiError(
            502,
            "UPSTREAM_HTTP_" + response.statusCode(),
            "外部服务返回错误；请检查服务端配置",
            response.statusCode() >= 500);
      if (bytes.length > 1_048_576) throw new ApiError(502, "UPSTREAM_TOO_LARGE", "外部响应超过限制");
      try {
        return Json.parse(new String(bytes, StandardCharsets.UTF_8));
      } catch (ApiError e) {
        throw new ApiError(502, "UPSTREAM_FORMAT", "外部服务未返回有效 JSON");
      }
    } catch (ApiError e) {
      throw e;
    } catch (HttpTimeoutException e) {
      throw new ApiError(502, "UPSTREAM_TIMEOUT", "外部服务请求超时", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiError(502, "UPSTREAM_INTERRUPTED", "外部请求已中断", true);
    } catch (Exception e) {
      throw new ApiError(502, "UPSTREAM_UNAVAILABLE", "无法连接外部服务", true);
    }
  }
}
