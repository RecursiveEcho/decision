package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class Upstream {
  static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  static JsonNode post(String url, String token, ObjectNode body) {
    return post(url, token, body, Duration.ofSeconds(60));
  }

  static JsonNode post(String url, String token, ObjectNode body, Duration deadline) {
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
        throw new IllegalArgumentException();
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(deadline)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
              .build();
      // Deadline includes the entire response body, not merely receipt of headers.
      pending = CLIENT.sendAsync(request, info -> new BoundedBody());
      HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 429)
        throw new ApiError(429, "UPSTREAM_RATE_LIMIT", "外部服务限流，请稍后重试", true);
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ApiError(
            502,
            "UPSTREAM_HTTP_" + response.statusCode(),
            "外部服务返回错误；请检查服务端配置",
            response.statusCode() >= 500);
      try {
        return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
      } catch (ApiError e) {
        throw new ApiError(502, "UPSTREAM_FORMAT", "外部服务未返回有效 JSON");
      }
    } catch (ApiError e) {
      throw e;
    } catch (TimeoutException e) {
      throw new ApiError(502, "UPSTREAM_TIMEOUT", "外部服务请求超时", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiError(502, "UPSTREAM_INTERRUPTED", "外部请求已中断", true);
    } catch (Exception e) {
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ApiError api) throw api;
      if (cause instanceof HttpTimeoutException)
        throw new ApiError(502, "UPSTREAM_TIMEOUT", "外部服务请求超时", true);
      throw new ApiError(502, "UPSTREAM_UNAVAILABLE", "无法连接外部服务", true);
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer b : buffers) {
        if (output.size() + b.remaining() > 1_048_576) {
          subscription.cancel();
          result.completeExceptionally(new ApiError(502, "UPSTREAM_TOO_LARGE", "外部响应超过限制"));
          return;
        }
        byte[] chunk = new byte[b.remaining()];
        b.get(chunk);
        output.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(output.toByteArray());
    }
  }
}
