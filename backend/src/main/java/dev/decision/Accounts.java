package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/** Public authentication endpoints. No business data is accessed before authentication. */
@Component
class Accounts {
  private static final int ITERATIONS = 600_000;
  private static final String HASH_FORMAT = "pbkdf2-sha256$600000$";
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Semaphore passwordSlots = new Semaphore(4);
  private final Auth auth;
  private final Store s;

  Accounts(Auth auth, Store store) {
    this.auth = auth;
    this.s = store;
  }

  private record Account(String id, String username, String role, String hash) {
    ObjectNode publicView() {
      return Json.obj("id", id, "username", username, "role", role);
    }
  }

  ObjectNode current() {
    return s.db.queryForObject(
        "SELECT id::text,username,role FROM accounts WHERE id=?::uuid",
        (row, i) ->
            Json.obj(
                "id", row.getString(1), "username", row.getString(2), "role", row.getString(3)),
        AccountScope.id());
  }

  private Account find(String username) {
    List<Account> rows =
        s.db.query(
            "SELECT id::text,username,role,password_hash FROM accounts WHERE username=?",
            (row, i) ->
                new Account(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
            username);
    return rows.isEmpty() ? null : rows.get(0);
  }

  ObjectNode register(JsonNode body, String rawKey, String address) {
    String key = key(rawKey);
    reserve("register", address, 10);
    requirePair(body);
    String name = name(body.path("username"));
    String password = body.path("password").textValue();
    if (name == null || !validPassword(password))
      throw new ApiError(
          400,
          "INVALID_CREDENTIAL_FORMAT",
          "用户名限 3–32 位英文字母、数字、下划线、点或短横线，首位不能为点或短横线；密码需 12–128 个字符");
    if (name.equals("owner")) throw unavailable();
    Account known = find(name);
    String encoded = null;
    if (known == null) {
      byte[] salt = new byte[16];
      RANDOM.nextBytes(salt);
      encoded =
          HASH_FORMAT
              + Base64.getEncoder().encodeToString(salt)
              + "$"
              + Base64.getEncoder().encodeToString(derive(password, salt));
    } else if (!passwordMatches(password, known.hash)) {
      throw unavailable();
    }
    // Finish potentially expensive derivation before taking the write lock. Recheck for a
    // concurrent winner inside it; the unique username and the cached response commit together.
    String newHash = encoded;
    String scope = "auth:register";
    String fingerprint = fingerprint(scope, key, name, password);
    return s.write(
        () -> {
          Account account = find(name);
          if (account != null) {
            ObjectNode previous = cached(account, scope, key, fingerprint, false);
            if (previous != null) return previous;
            throw unavailable();
          }
          if (newHash == null) throw unavailable();
          account = new Account(Json.id(), name, "member", newHash);
          s.db.update(
              "INSERT INTO accounts(id,username,role,password_hash) VALUES (?::uuid,?,'member',?)",
              account.id,
              name,
              newHash);
          s.db.update(
              "INSERT INTO context_version(id,revision,account_id) VALUES (1,0,?::uuid)",
              account.id);
          s.db.update(
              "INSERT INTO chat_memory_state(id,epoch,account_id) VALUES (1,0,?::uuid)",
              account.id);
          return issue(account, scope, key, 201, fingerprint);
        });
  }

  ObjectNode login(JsonNode body, String rawKey, String address) {
    String key = key(rawKey);
    reserve("login", address, 60);
    String failureBucket = auth.sign("failed-login:" + address);
    if (Boolean.TRUE.equals(
        s.db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM login_attempts WHERE address_hash=? AND failures>=10"
                + " AND window_start > now()-interval '5 minutes')",
            Boolean.class,
            failureBucket))) throw limited();

    boolean legacy = body.isObject() && body.size() == 1 && body.has("accessCode");
    String username;
    String password;
    if (legacy) {
      username = "owner";
      password = body.path("accessCode").textValue();
    } else {
      requirePair(body);
      username = name(body.path("username"));
      password = body.path("password").textValue();
    }
    Account account = username == null ? null : find(username);
    boolean valid;
    if ("owner".equals(username)) {
      valid =
          password != null
              && password.length() <= 500
              && account != null
              && auth.codeMatches(password);
    } else {
      // Unknown but well-formed usernames pay the same KDF cost as an incorrect member password.
      valid =
          validPassword(password)
              && passwordMatches(password, account == null ? null : account.hash)
              && account != null;
    }
    if (!valid) {
      increment(failureBucket, 10);
      throw new ApiError(401, "UNAUTHORIZED", "账号或凭据不正确");
    }
    String scope =
        account.id.equals(AccountScope.ADMIN) ? "anonymous:/api/v1/auth/login" : "auth:login";
    String fingerprint =
        fingerprint(legacy ? "access-code" : "password-login", key, username, password);
    // V9 removes the old unkeyed digest from successful access-code login records.
    // Only this freshly authenticated legacy route can consume the migration marker.
    return s.write(
        () -> {
          ObjectNode previous = cached(account, scope, key, fingerprint, legacy);
          return previous != null ? previous : issue(account, scope, key, 200, fingerprint);
        });
  }

  private static void requirePair(JsonNode body) {
    if (!body.isObject() || body.size() != 2 || !body.has("username") || !body.has("password"))
      throw new ApiError(400, "INVALID_REQUEST", "请提交用户名与密码；访问码登录仅提交 accessCode");
  }

  private static String name(JsonNode value) {
    String raw = value.textValue();
    return raw != null && USERNAME.matcher(raw).matches() ? raw.toLowerCase(Locale.ROOT) : null;
  }

  private static boolean validPassword(String value) {
    if (value == null || value.length() > 256) return false;
    int n = value.codePointCount(0, value.length());
    return n >= 12 && n <= 128;
  }

  private static String key(String raw) {
    if (raw == null)
      throw new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "创建请求需要 Idempotency-Key UUID");
    return Json.uuid(raw).toLowerCase(Locale.ROOT);
  }

  private String fingerprint(String scope, String key, String username, String credential) {
    return auth.sign("credential-request:" + scope + ":" + key + ":" + username + ":" + credential);
  }

  private ObjectNode cached(
      Account account, String scope, String key, String hash, boolean legacy) {
    List<Map<String, Object>> rows =
        s.db.queryForList(
            "SELECT request_hash,response FROM idempotency_keys WHERE account_id=?::uuid AND"
                + " scope=? AND key=?::uuid",
            account.id,
            scope,
            key);
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.get(0);
    if (!hash.equals(row.get("request_hash"))) {
      if (!legacy || !"legacy-access-code-verified".equals(row.get("request_hash")))
        throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能提交不同内容");
      s.db.update(
          "UPDATE idempotency_keys SET request_hash=? WHERE account_id=?::uuid AND scope=? AND"
              + " key=?::uuid",
          hash,
          account.id,
          scope,
          key);
    }
    ObjectNode response = (ObjectNode) Json.parse(auth.decrypt((String) row.get("response")));
    response.set("account", account.publicView());
    return response;
  }

  private ObjectNode issue(
      Account account, String scope, String key, int status, String fingerprint) {
    Instant expires = Instant.now().plus(Duration.ofHours(24));
    ObjectNode result =
        Json.obj(
            "accessToken",
            auth.token("account:" + account.id, expires),
            "expiresAt",
            expires.toString(),
            "account",
            account.publicView());
    s.db.update(
        "INSERT INTO idempotency_keys(account_id,scope,key,request_hash,status,response)"
            + " VALUES (?::uuid,?,?::uuid,?,?,?)",
        account.id,
        scope,
        key,
        fingerprint,
        status,
        auth.encrypt(result.toString()));
    return result;
  }

  private byte[] derive(String password, byte[] salt) {
    if (!passwordSlots.tryAcquire()) throw limited();
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Password hashing unavailable", ex);
    } finally {
      spec.clearPassword();
      passwordSlots.release();
    }
  }

  private boolean passwordMatches(String password, String stored) {
    if (stored == null) {
      derive(password, new byte[16]);
      return false;
    }
    if (!stored.startsWith(HASH_FORMAT))
      throw new IllegalStateException("Unsupported password hash");
    String[] parts = stored.substring(HASH_FORMAT.length()).split("\\$", -1);
    byte[] candidate = derive(password, Base64.getDecoder().decode(parts[0]));
    return MessageDigest.isEqual(candidate, Base64.getDecoder().decode(parts[1]));
  }

  private void reserve(String action, String address, int limit) {
    s.db.update("DELETE FROM login_attempts WHERE window_start <= now()-interval '5 minutes'");
    if (!increment(auth.sign("auth-limit:" + action + ":" + address), limit)) throw limited();
  }

  private boolean increment(String bucket, int limit) {
    // The conditional UPSERT reserves a slot before doing password work, across instances.
    return s.db.update(
            "INSERT INTO login_attempts(address_hash,failures,window_start) VALUES (?,1,now())"
                + " ON CONFLICT(address_hash) DO UPDATE SET failures=login_attempts.failures+1"
                + " WHERE login_attempts.failures < ?",
            bucket,
            limit)
        == 1;
  }

  private static ApiError unavailable() {
    return new ApiError(409, "ACCOUNT_UNAVAILABLE", "该账号名不可用");
  }

  private static ApiError limited() {
    return new ApiError(429, "LOGIN_RATE_LIMIT", "尝试过多，请稍后重试", true);
  }
}
