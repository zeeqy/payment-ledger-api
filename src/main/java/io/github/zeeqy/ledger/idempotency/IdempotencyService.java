package io.github.zeeqy.ledger.idempotency;

import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.common.Problems;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Same Idempotency-Key + same request = same response, and the money moves once.
 *
 * <p>The key row is inserted in the SAME transaction as the money movement. If two identical requests race, the
 * second INSERT ... ON CONFLICT blocks on the first one's uncommitted row; when the first commits, the second sees the
 * stored response and replays it. If the first crashes and rolls back, the key disappears with it and a retry runs
 * normally. Business errors (4xx) are stored and replayed too (Stripe's semantics); the operation's partial writes are
 * rolled back to a savepoint first.
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int status, String body, boolean replayed) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate savepoint;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.savepoint = new TransactionTemplate(txManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public StoredResponse execute(
            UUID userId, String key, String fingerprint, int successStatus, String instance, Supplier<?> operation) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw ApiException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "Send a unique Idempotency-Key header (1-255 chars) on this endpoint");
        }
        return tx.execute(status -> {
            int inserted = jdbc.sql("""
                            INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status_code, response_body)
                            VALUES (?, ?, ?, 0, '{}') ON CONFLICT DO NOTHING""").params(userId, key, fingerprint).update();
            if (inserted == 0) {
                return replay(userId, key, fingerprint);
            }
            int code;
            String body;
            try {
                Object result = savepoint.execute(s -> operation.get());
                code = successStatus;
                body = json.writeValueAsString(result);
            } catch (ApiException e) {
                code = e.status().value();
                body = json.writeValueAsString(Problems.body(e, instance));
            }
            jdbc.sql("""
                            UPDATE idempotency_keys SET status_code = ?, response_body = ?::json
                            WHERE user_id = ? AND idem_key = ?""").params(code, body, userId, key).update();
            return new StoredResponse(code, body, false);
        });
    }

    private StoredResponse replay(UUID userId, String key, String fingerprint) {
        return jdbc.sql("""
                        SELECT request_hash, status_code, response_body::text AS body
                        FROM idempotency_keys WHERE user_id = ? AND idem_key = ?""")
                .params(userId, key)
                .query((rs, n) -> {
                    if (!rs.getString("request_hash").equals(fingerprint)) {
                        throw ApiException.unprocessable(
                                "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used with a different request");
                    }
                    return new StoredResponse(rs.getInt("status_code"), rs.getString("body"), true);
                })
                .single();
    }

    /** SHA-256 over method, path and the canonical JSON of the validated request body. */
    public String fingerprint(String method, String path, Object request) {
        String canonical = method + " " + path + "\n" + json.writeValueAsString(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
