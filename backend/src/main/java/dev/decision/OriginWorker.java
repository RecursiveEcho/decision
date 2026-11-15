package dev.decision;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in worker, isolated from Decision's business job queue. No production deployment is implied. */
@Component
class OriginWorker {
  final OriginChat chat;
  final Store s;
  final boolean enabled;
  final AtomicBoolean busy = new AtomicBoolean();
  final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "origin-generation"); t.setDaemon(true); return t;
  });

  OriginWorker(OriginChat chat, Store s, @Value("${app.origin-worker-enabled:false}") boolean enabled) {
    this.chat = chat; this.s = s; this.enabled = enabled;
  }

  @Scheduled(fixedDelay=1000)
  void tick() {
    if (!enabled || !busy.compareAndSet(false, true)) return;
    executor.execute(() -> {
      try {
        for (String account : s.db.queryForList("SELECT DISTINCT account_id::text FROM origin_records WHERE kind='generation' AND data->>'state'='running'", String.class))
          try (var ignored = AccountScope.enter(account)) { chat.recoverExpired(); }
        var rows = s.db.queryForList("SELECT account_id::text,id::text FROM origin_records WHERE kind='generation' AND data->>'state'='queued' ORDER BY seq LIMIT 1");
        if (!rows.isEmpty()) {
          try (var ignored = AccountScope.enter((String) rows.get(0).get("account_id"))) {
            chat.run((String) rows.get(0).get("id"));
          }
        }
      } catch (Exception failure) {
        org.slf4j.LoggerFactory.getLogger(OriginWorker.class).warn("Origin worker iteration failed; persisted state retained ({})", failure.getClass().getSimpleName());
      } finally { busy.set(false); }
    });
  }

  @PreDestroy
  void close() {
    chat.active.values().forEach(Upstream.Cancellation::cancel);
    executor.shutdownNow();
  }
}
