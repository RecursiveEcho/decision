package dev.decision;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/** Servlet configuration loaded explicitly by the desktop launcher. */
@Configuration(proxyBeanMethods = false)
public class DesktopConfiguration {
  private static final String READY_PATH = "/_desktop/ready";

  @Bean
  FilterRegistrationBean<ApiFilter> desktopApiFilter(ApiFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/api/*");
    return registration;
  }

  @Bean
  FilterRegistrationBean<Filter> desktopLocalGuard(
      @Value("${server.port}") int port,
      @Value("${decision.desktop.challenge-key}") String challengeKey,
      JdbcTemplate database) {
    byte[] key = HexFormat.of().parseHex(challengeKey);
    if (key.length != 32) throw new IllegalArgumentException("Invalid desktop challenge key");
    String host = "127.0.0.1:" + port;
    String origin = "http://" + host;

    Filter guard = (request, response, chain) -> {
      var req = (jakarta.servlet.http.HttpServletRequest) request;
      var res = (jakarta.servlet.http.HttpServletResponse) response;
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Referrer-Policy", "no-referrer");
      res.setHeader("X-Frame-Options", "DENY");
      String requestOrigin = req.getHeader("Origin");
      String site = req.getHeader("Sec-Fetch-Site");
      if (!host.equals(req.getHeader("Host"))
          || (requestOrigin != null && !origin.equals(requestOrigin))
          || "cross-site".equals(site) || "same-site".equals(site)) {
        res.setStatus(403);
        return;
      }
      if (!READY_PATH.equals(req.getRequestURI())) {
        chain.doFilter(request, response);
        return;
      }

      res.setHeader("Cache-Control", "no-store");
      String challenge = req.getHeader("X-Decision-Challenge");
      if (!"GET".equals(req.getMethod()) || challenge == null || !challenge.matches("[0-9a-f]{64}")) {
        res.setStatus(400);
        return;
      }
      try {
        database.queryForObject("SELECT 1", Integer.class);
      } catch (org.springframework.dao.DataAccessException unavailable) {
        res.setStatus(503);
        return;
      }
      res.setContentType("text/plain;charset=UTF-8");
      res.getWriter().write(sign(challenge, key));
    };
    var registration = new FilterRegistrationBean<>(guard);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  private static String sign(String challenge, byte[] key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(challenge.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Desktop health proof is unavailable", unavailable);
    }
  }
}
