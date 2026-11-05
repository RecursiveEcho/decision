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
      if (subject.startsWith("account:")) {
        String account = Json.uuid(subject.substring(8));
        if (!Boolean.TRUE.equals(
            store.db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM accounts WHERE id=?::uuid)", Boolean.class, account)))
          throw new IllegalArgumentException();
      } else if (subject.startsWith("device:")) {
        // Legacy devices have always belonged to the migrated administrator.
        try (AccountScope ignored = AccountScope.enter(AccountScope.ADMIN)) {
          store.get("device", subject.substring(7));
        }
      } else if (!subject.equals("owner")) throw new IllegalArgumentException();
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
