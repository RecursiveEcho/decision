package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
    return post(url, token, body, deadline, 1_048_576);
  }

  static JsonNode post(String url, String token, ObjectNode body, Duration deadline, int maxBytes) {
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
      pending = CLIENT.sendAsync(request, info -> new BoundedBody(maxBytes));
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

  static JsonNode get(String url, String token, Duration deadline) {
    CompletableFuture<HttpResponse<byte[]>> pending=null;
    try {
      pending=CLIENT.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(deadline)
          .header("Authorization","Bearer "+token).GET().build(), info -> new BoundedBody());
      HttpResponse<byte[]> response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);
      checkStatus(response.statusCode());
      return Json.parse(new String(response.body(),StandardCharsets.UTF_8));
    } catch(ApiError e) { throw e; }
    catch(Exception e) { throw new ApiError(502,"UPSTREAM_UNAVAILABLE","无法读取模型目录",true); }
    finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
  }

  static void checkStatus(int status) {
    if(status==429) throw new ApiError(429,"UPSTREAM_RATE_LIMIT","模型服务限流，请稍后重试",true);
    if(status<200 || status>=300) throw new ApiError(502,"UPSTREAM_HTTP_"+status,
        status==401 || status==403 ? "模型认证或权限未通过，请检查密钥和所选模型" : "模型服务返回错误，请检查配置与模型能力",status>=500);
  }

  static final class Cancellation {
    private boolean cancelled;
    private Runnable action;
    synchronized void bind(Runnable action) { this.action=action; if(cancelled) action.run(); }
    synchronized void cancel() { cancelled=true; if(action!=null) action.run(); }
    synchronized void clear() { action=null; }
    synchronized void check() { if(cancelled) throw new ApiError(409,"JOB_CANCELLED","任务已停止"); }
  }
  static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t=new Thread(r,"model-stream-deadline"); t.setDaemon(true); return t;
  });

  /** SSE is decoded on the scoped worker thread. Closing the body interrupts a blocked read. */
  static JsonNode stream(String url,String token,ObjectNode body,Duration deadline,int maxBytes,
      Consumer<String> content, Cancellation cancellation, Runnable bufferedResponse) {
    CompletableFuture<HttpResponse<InputStream>> pending=null;
    InputStream input=null;
    ScheduledFuture<?> timer=null;
    AtomicBoolean expired=new AtomicBoolean();
    long expires=System.nanoTime()+deadline.toNanos();
    try {
      cancellation.check();
      pending=CLIENT.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(deadline)
          .header("Content-Type","application/json").header("Authorization","Bearer "+token)
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofInputStream());
      var future=pending;
      cancellation.bind(() -> future.cancel(true));
      HttpResponse<InputStream> response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);
      input=response.body();
      final InputStream stream=input;
      cancellation.bind(() -> { try { stream.close(); } catch(IOException ignored) {} future.cancel(true); });
      cancellation.check();
      checkStatus(response.statusCode());
      timer=DEADLINES.schedule(() -> { expired.set(true); try { stream.close(); } catch(IOException ignored) {} },
          Math.max(1,expires-System.nanoTime()),TimeUnit.NANOSECONDS);
      if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
        bufferedResponse.run();
        byte[] bytes=input.readNBytes(maxBytes+1);
        cancellation.check();
        if(expired.get()) throw new TimeoutException();
        if(bytes.length>maxBytes) throw new ApiError(502,"UPSTREAM_TOO_LARGE","外部响应超过限制");
        return Json.parse(new String(bytes,StandardCharsets.UTF_8));
      }
      BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
      StringBuilder data=new StringBuilder(), raw=new StringBuilder();
      ObjectNode result=Json.obj();
      String finish="";
      boolean done=false;
      int total=0;
      while(!done) {
        cancellation.check();
        String line=readLine(reader,Math.min(maxBytes,262144));
        if(line==null) break;
        total+=line.length();
        if(total>maxBytes*4) throw new ApiError(502,"UPSTREAM_TOO_LARGE","流式响应超过限制");
        if(line.startsWith("data:")) {
          if(!data.isEmpty()) data.append('\n');
          data.append(line.substring(5).stripLeading());
          if(data.length()>maxBytes) throw new ApiError(502,"UPSTREAM_TOO_LARGE","流式事件超过限制");
        } else if(line.isEmpty() && !data.isEmpty()) {
          String event=data.toString(); data.setLength(0);
          if(event.equals("[DONE]")) { done=true; break; }
          JsonNode chunk=Json.parse(event);
          if(chunk.has("error")) throw new ApiError(502,"UPSTREAM_STREAM_ERROR","模型流式请求失败",true);
          if(chunk.has("model")) result.set("model",chunk.get("model"));
          if(chunk.hasNonNull("usage")) result.set("usage",chunk.get("usage"));
          JsonNode choice=chunk.path("choices").path(0);
          String text=choice.path("delta").path("content").asText("");
          raw.append(text);
          if(raw.length()>maxBytes) throw new ApiError(502,"UPSTREAM_TOO_LARGE","模型正文超过限制");
          if(!text.isEmpty()) content.accept(text);
          if(choice.hasNonNull("finish_reason")) finish=choice.path("finish_reason").asText();
        }
      }
      cancellation.check();
      if(expired.get()) throw new TimeoutException();
      if(!done) throw new ApiError(502,"UPSTREAM_STREAM_INTERRUPTED","连接中断，已保留收到的回答片段；请明确重试",true);
      result.set("choices",Json.arr().add(Json.obj("message",Json.obj("content",raw.toString()),"finish_reason",finish)));
      return result;
    } catch(ApiError error) { throw error; }
    catch(Exception error) {
      cancellation.check();
      if(expired.get() || error instanceof TimeoutException || error instanceof HttpTimeoutException)
        throw new ApiError(502,"UPSTREAM_TIMEOUT","模型响应超时，已保留收到的片段",true);
      throw new ApiError(502,"UPSTREAM_UNAVAILABLE","模型连接中断，已保留收到的片段",true);
    } finally {
      if(timer!=null) timer.cancel(false);
      cancellation.clear();
      if(input!=null) try { input.close(); } catch(IOException ignored) {}
      if(pending!=null && !pending.isDone()) pending.cancel(true);
    }
  }
  private static String readLine(Reader reader,int max) throws IOException {
    StringBuilder line=new StringBuilder();
    int c;
    while((c=reader.read())!=-1) {
      if(c=='\n') break;
      if(c!='\r') line.append((char)c);
      if(line.length()>max) throw new ApiError(502,"UPSTREAM_TOO_LARGE","流式事件行超过限制");
    }
    return c==-1 && line.isEmpty() ? null : line.toString();
  }

  static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    final int maxBytes;

    BoundedBody() {
      this(1_048_576);
    }

    BoundedBody(int maxBytes) {
      this.maxBytes = maxBytes;
    }

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
        if (output.size() + b.remaining() > maxBytes) {
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
