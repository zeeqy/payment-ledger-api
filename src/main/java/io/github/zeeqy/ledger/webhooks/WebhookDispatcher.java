package io.github.zeeqy.ledger.webhooks;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers outbox events. At-least-once: a merchant may see the same event twice (e.g. we crash after their 200 but
 * before marking it delivered), so payloads carry a stable event id for deduplication.
 *
 * <p>Claiming uses {@code FOR UPDATE SKIP LOCKED} plus a short lease (next_attempt_at pushed forward), so several API
 * instances can run dispatchers without sending the same event concurrently, and no DB transaction stays open during
 * the HTTP call.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH = 20;

    record Claimed(long id, String eventType, String payload, int attempts, String url, String secret) {}

    private final JdbcClient jdbc;
    private final RestClient http;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public WebhookDispatcher(
            JdbcClient jdbc,
            @Value("${ledger.webhooks.max-attempts}") int maxAttempts,
            @Value("${ledger.webhooks.base-backoff-ms}") long baseBackoffMs) {
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${ledger.webhooks.poll-ms}")
    public void tick() {
        try {
            List<Claimed> batch = claim();
            batch.forEach(this::deliver);
        } catch (RuntimeException e) {
            log.warn("Webhook dispatch tick failed: {}", e.toString());
        }
    }

    /** Auto-commit statement: claims due events and leases them for 60s. */
    List<Claimed> claim() {
        return jdbc.sql("""
                        UPDATE outbox_events o
                        SET attempts = o.attempts + 1, next_attempt_at = now() + interval '60 seconds'
                        FROM merchants m
                        WHERE o.id IN (SELECT id FROM outbox_events
                                       WHERE status = 'PENDING' AND next_attempt_at <= now()
                                       ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                          AND m.user_id = o.merchant_id
                        RETURNING o.id, o.event_type, o.payload::text AS payload, o.attempts,
                                  m.webhook_url, m.webhook_secret""")
                .param(BATCH)
                .query((rs, n) -> new Claimed(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getString("webhook_url"),
                        rs.getString("webhook_secret")))
                .list();
    }

    void deliver(Claimed e) {
        String error;
        try {
            long ts = Instant.now().getEpochSecond();
            var res = http.post()
                    .uri(e.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSigner.HEADER, WebhookSigner.sign(e.secret(), ts, e.payload()))
                    .header("Ledger-Event-Type", e.eventType())
                    .header("Ledger-Delivery-Attempt", String.valueOf(e.attempts()))
                    .body(e.payload())
                    .retrieve()
                    .onStatus(s -> true, (req, r) -> {})
                    .toBodilessEntity();
            if (res.getStatusCode().is2xxSuccessful()) {
                jdbc.sql(
                                "UPDATE outbox_events SET status = 'DELIVERED', delivered_at = now(), last_error = NULL WHERE id = ?")
                        .param(e.id())
                        .update();
                return;
            }
            error = "HTTP " + res.getStatusCode().value();
        } catch (RuntimeException ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        if (e.attempts() >= maxAttempts) {
            log.warn("Webhook {} dead after {} attempts: {}", e.id(), e.attempts(), error);
            jdbc.sql("UPDATE outbox_events SET status = 'DEAD', last_error = ? WHERE id = ?")
                    .params(error, e.id())
                    .update();
            return;
        }
        long delay = WebhookSigner.backoffMs(e.attempts(), baseBackoffMs);
        long jitter = ThreadLocalRandom.current().nextLong(delay / 10 + 1); // spread retries, avoid thundering herd
        jdbc.sql("""
                        UPDATE outbox_events SET next_attempt_at = now() + (? * interval '1 millisecond'), last_error = ?
                        WHERE id = ?""").params(delay + jitter, error, e.id()).update();
    }

    /** For tests and the admin view. */
    public record Status(String status, int attempts, String lastError) {}

    public List<Status> statusOf(UUID merchantId) {
        return jdbc.sql("SELECT status, attempts, last_error FROM outbox_events WHERE merchant_id = ? ORDER BY id")
                .param(merchantId)
                .query((rs, n) -> new Status(rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error")))
                .list();
    }
}
