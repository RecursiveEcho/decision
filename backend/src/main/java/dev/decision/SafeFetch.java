package dev.decision;

import java.io.*;
import java.net.*;
import java.util.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;

final class SafeFetch {
  record Page(String text, String title, String url) {}

  static URI validateUri(String url) {
    try {
      URI u = URI.create(url);
      if (!List.of("https", "http").contains(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getFragment() != null
          || !(u.getPort() == -1 || u.getPort() == 80 || u.getPort() == 443))
        throw new IllegalArgumentException();
      return u;
    } catch (Exception e) {
      throw new ApiError(400, "UNSAFE_URL", "只允许不含凭据的标准端口 HTTP(S) URL");
    }
  }

  static boolean publicAddress(InetAddress a) {
    byte[] b = a.getAddress();
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) return false;
    if (b.length == 4) {
      int x = b[0] & 255, y = b[1] & 255;
      return x != 0
          && x != 127
          && x < 224
          && !(x == 100 && y >= 64 && y <= 127)
          && !(x == 169 && y == 254)
          && !(x == 192 && (y == 0 || y == 168))
          && !(x == 198 && (y == 18 || y == 19 || y == 51))
          && !(x == 203 && y == 0)
          && !(x == 10)
          && !(x == 172 && y >= 16 && y <= 31);
    }
    return (b[0] & 0xe0) == 0x20
        && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && (b[3] & 255) == 0xb8);
  }

  static InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = InetAddress.getAllByName(host);
    for (InetAddress a : addresses)
      if (!publicAddress(a)) throw new UnknownHostException("Non-public address rejected");
    return addresses;
  }

  static Page fetch(String raw) {
    URI uri = validateUri(raw);
    DnsResolver resolver =
        new DnsResolver() {
          public InetAddress[] resolve(String h) throws UnknownHostException {
            return SafeFetch.resolve(h);
          }

          public String resolveCanonicalHostname(String h) throws UnknownHostException {
            return h;
          }
        };
    // Validate at connection time, using these exact resolved addresses (prevents DNS rebinding).
    try (var cm =
            PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        var client =
            HttpClients.custom()
                .setConnectionManager(cm)
                .disableRedirectHandling()
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .build())
                .build()) {
      for (int hop = 0; hop < 4; hop++) {
        URI current = uri;
        record Result(int status, String location, String type, byte[] bytes) {}
        Result r =
            client.execute(
                new HttpGet(current),
                response -> {
                  int code = response.getCode();
                  String location =
                      response.getFirstHeader("Location") == null
                          ? null
                          : response.getFirstHeader("Location").getValue();
                  String type =
                      response.getFirstHeader("Content-Type") == null
                          ? ""
                          : response.getFirstHeader("Content-Type").getValue();
                  byte[] bytes = new byte[0];
                  if (code == 200 && response.getEntity() != null) {
                    try (InputStream in = response.getEntity().getContent()) {
                      bytes = in.readNBytes(1_048_577);
                    }
                  }
                  return new Result(code, location, type, bytes);
                });
        if (r.status >= 300 && r.status < 400 && r.location != null) {
          uri = validateUri(current.resolve(r.location).toString());
          continue;
        }
        if (r.status != 200) throw new ApiError(502, "FETCH_FAILED", "网页读取失败，可粘贴正文", true);
        if (r.bytes.length > 1_048_576)
          throw new ApiError(400, "SOURCE_TOO_LARGE", "网页正文超过限制，请粘贴摘录");
        if (!r.type.toLowerCase(Locale.ROOT).startsWith("text/"))
          throw new ApiError(400, "UNSUPPORTED_CONTENT", "目前只接受文本网页");
        var doc = Jsoup.parse(new ByteArrayInputStream(r.bytes), null, current.toString());
        doc.select("script,style,noscript,nav,header,footer").remove();
        String text = doc.body().text();
        if (text.isBlank()) throw new ApiError(502, "EMPTY_SOURCE", "网页无可用正文，请粘贴正文");
        if (text.length() > 100000) text = text.substring(0, 100000);
        return new Page(text, doc.title(), current.toString());
      }
      throw new ApiError(502, "TOO_MANY_REDIRECTS", "网页重定向过多，请粘贴正文");
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(502, "FETCH_FAILED", "网页无法安全读取，请粘贴正文", true);
    }
  }
}
