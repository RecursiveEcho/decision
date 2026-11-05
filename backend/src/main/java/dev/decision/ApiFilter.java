package dev.decision;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ApiFilter extends OncePerRequestFilter {
  final Auth auth;

  ApiFilter(Auth a) {
    auth = a;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    try {
      String p = req.getRequestURI();
      // Spring may match a decoded route or strip matrix parameters; cache keys and
      // device capabilities must refer to exactly the same path as the controller.
      if (!p.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
        throw new ApiError(400, "INVALID_PATH", "API 路径须使用规范格式");
      String accountId = null;
      if (!p.equals("/api/v1/health")
          && !p.equals("/api/v1/auth/login")
          && !p.equals("/api/v1/auth/register")) {
        String who = auth.verify(req.getHeader("Authorization"));
        accountId = who.startsWith("account:") ? who.substring(8) : AccountScope.ADMIN;
        // Preserve the old admin idempotency scope, including when using a new account token.
        req.setAttribute(
            "subject",
            who.startsWith("account:") && accountId.equals(AccountScope.ADMIN) ? "owner" : who);
        if (who.startsWith("device:")) {
          String id = who.substring(7);
          String m = req.getMethod();
          boolean allowed =
              (m.equals("POST")
                      && (p.equals("/api/v1/devices/" + id + "/heartbeat")
                          || p.equals("/api/v1/observations/batch")
                          || p.matches("/api/v1/deliveries/[0-9a-fA-F-]+/ack")))
                  || (m.equals("GET")
                      && (p.equals("/api/v1/devices/" + id + "/deliveries")
                          || p.matches("/api/v1/jobs/[0-9a-fA-F-]+")));
          if (!allowed) throw new ApiError(403, "FORBIDDEN", "设备凭据不能访问此资源");
        }
      }
      int limit =
          req.getMethod().equals("POST")
                  && (p.equals("/api/v1/conversations")
                      || p.matches("/api/v1/problems/[0-9a-fA-F-]+/messages"))
              ? 24 * 1024 * 1024
              : 1_048_576;
      if (req.getContentLengthLong() > limit) throw new ApiError(413, "BODY_TOO_LARGE", "请求超过大小限制");
      HttpServletRequest wrapped = req;
      if (req.getMethod().equals("POST") || req.getMethod().equals("PATCH")) {
        byte[] body = req.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) throw new ApiError(413, "BODY_TOO_LARGE", "请求超过大小限制");
        wrapped =
            new HttpServletRequestWrapper(req) {
              @Override
              public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                  public int read() {
                    return in.read();
                  }

                  public boolean isFinished() {
                    return in.available() == 0;
                  }

                  public boolean isReady() {
                    return true;
                  }

                  public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                  }
                };
              }

              @Override
              public BufferedReader getReader() {
                return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
              }
            };
      }
      try (AccountScope ignored = accountId == null ? null : AccountScope.enter(accountId)) {
        chain.doFilter(wrapped, res);
      }
    } catch (ApiError e) {
      res.setStatus(e.status);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write(e.body().toString());
    }
  }
}
