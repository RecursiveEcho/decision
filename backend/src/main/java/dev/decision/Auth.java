package dev.decision;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
class Auth {
  final Config config;
  final Store store;

  Auth(Config c, Store s) {
    config = c;
    store = s;
  }

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String sign(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(config.tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String token(String subject, Instant expires) {
    String body = subject + ":" + expires.getEpochSecond();
    return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(body.getBytes(StandardCharsets.UTF_8))
        + "."
        + sign(body);
  }

  String verify(String header) {
    try {
      if (header == null || !header.startsWith("Bearer ")) throw new IllegalArgumentException();
      String[] t = header.substring(7).split("\\.");
      if (t.length != 2) throw new IllegalArgumentException();
      String body = new String(Base64.getUrlDecoder().decode(t[0]), StandardCharsets.UTF_8);
      if (!MessageDigest.isEqual(
          sign(body).getBytes(StandardCharsets.UTF_8), t[1].getBytes(StandardCharsets.UTF_8)))
        throw new IllegalArgumentException();
      int i = body.lastIndexOf(':');
      String subject = body.substring(0, i);
      if (Instant.ofEpochSecond(Long.parseLong(body.substring(i + 1))).isBefore(Instant.now()))
        throw new IllegalArgumentException();
      if (!subject.equals("owner")) {
        if (!subject.startsWith("device:")) throw new IllegalArgumentException();
        store.get("device", subject.substring(7));
      }
      return subject;
    } catch (Exception e) {
      throw new ApiError(401, "UNAUTHORIZED", "登录已过期或凭据无效");
    }
  }

  boolean codeMatches(String code) {
    return MessageDigest.isEqual(
        hash(code).getBytes(StandardCharsets.UTF_8),
        hash(config.ownerCode).getBytes(StandardCharsets.UTF_8));
  }

  void checkLoginRate(String address) {
    String h = hash(address);
    store.write(
        () -> {
          store.db.update(
              "DELETE FROM login_attempts WHERE window_start < now()-interval '5 minutes'");
          return null;
        });
    List<Integer> n =
        store.db.queryForList(
            "SELECT failures FROM login_attempts WHERE address_hash=?", Integer.class, h);
    if (!n.isEmpty() && n.get(0) >= 10)
      throw new ApiError(429, "LOGIN_RATE_LIMIT", "尝试过多，请五分钟后重试", true);
  }

  void failedLogin(String address) {
    store.write(
        () -> {
          store.db.update(
              "INSERT INTO login_attempts VALUES (?,1,now()) ON CONFLICT(address_hash) DO UPDATE"
                  + " SET failures=login_attempts.failures+1",
              hash(address));
          return null;
        });
  }

  // Idempotent login/device responses contain tokens; encrypt them at rest.
  String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
      byte[] out = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String decrypt(String ciphertext) {
    try {
      String[] p = ciphertext.split("\\.");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          aesKey(),
          new GCMParameterSpec(128, Base64.getDecoder().decode(p[0])));
      return new String(c.doFinal(Base64.getDecoder().decode(p[1])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new ApiError(409, "CREDENTIALS_ROTATED", "凭据已轮换，请使用新的 Idempotency-Key");
    }
  }

  SecretKeySpec aesKey() {
    return new SecretKeySpec(HexFormat.of().parseHex(hash(config.tokenSecret)), "AES");
  }
}
