package io.github.zeeqy.ledger.webhooks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: the event row commits or rolls back together with the money movement, so a merchant is never
 * told about a payment that didn't happen, and never misses one that did. Delivery is done later by
 * {@link WebhookDispatcher}.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID merchantId, String eventType, Map<String, ?> data) {
        boolean hasEndpoint = jdbc.sql("SELECT webhook_url IS NOT NULL FROM merchants WHERE user_id = ?")
                .param(merchantId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!hasEndpoint) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + UUID.randomUUID());
        event.put("type", eventType);
        event.put("createdAt", Instant.now().toString());
        event.put("data", data);
        jdbc.sql("INSERT INTO outbox_events (merchant_id, event_type, payload) VALUES (?, ?, ?::jsonb)")
                .params(merchantId, eventType, json.writeValueAsString(event))
                .update();
    }
}
