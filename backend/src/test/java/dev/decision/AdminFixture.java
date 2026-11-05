package dev.decision;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Legacy single-owner domain fixtures call services directly, outside HTTP authentication. */
abstract class AdminFixture {
  private AccountScope scope;

  @BeforeEach
  void bindTestOwner() {
    scope = AccountScope.enter(AccountScope.ADMIN);
  }

  @AfterEach
  void releaseTestOwner() {
    scope.close();
  }

  static <T> Callable<T> asAdmin(Callable<T> action) {
    return () -> {
      try (var owner = AccountScope.enter(AccountScope.ADMIN)) {
        return action.call();
      }
    };
  }
}
