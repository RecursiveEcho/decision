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
      if (!p.equals("/api/v1/health") && !p.equals("/api/v1/auth/login")) {
        String who = auth.verify(req.getHeader("Authorization"));
        req.setAttribute("subject", who);
        if (!who.equals("owner")) {
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
      chain.doFilter(wrapped, res);
    } catch (ApiError e) {
      res.setStatus(e.status);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write(e.body().toString());
    }
  }
}
