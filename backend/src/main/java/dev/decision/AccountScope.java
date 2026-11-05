package dev.decision;

import java.util.UUID;

/**
 * Bound by authenticated HTTP dispatch or by a database-owned worker job, never by a request body.
 */
final class AccountScope implements AutoCloseable {
  static final String ADMIN = "00000000-0000-0000-0000-000000000001";
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private final String previous;

  private AccountScope(String accountId) {
    previous = CURRENT.get();
    CURRENT.set(UUID.fromString(accountId).toString());
  }

  static AccountScope enter(String accountId) {
    return new AccountScope(accountId);
  }

  static String id() {
    String id = CURRENT.get();
    if (id == null) throw new IllegalStateException("Account scope required");
    return id;
  }

  static boolean admin() {
    return ADMIN.equals(id());
  }

  static void requireAdmin() {
    if (!admin()) throw new ApiError(403, "FORBIDDEN", "此功能仅供管理员使用");
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
