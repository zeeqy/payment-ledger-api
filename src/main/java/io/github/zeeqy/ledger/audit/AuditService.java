package io.github.zeeqy.ledger.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Append-only record of who did what. Written in the same transaction as the action it describes. */
@Service
public class AuditService {

    public record AuditEntry(
            long id,
            UUID actorId,
            String action,
            String entityType,
            String entityId,
            String details,
            String ip,
            String createdAt) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate independent;

    public AuditService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.independent = new TransactionTemplate(txManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Commits on its own, so the record survives even when the surrounding operation is refused and rolled back. */
    public void recordIndependently(
            UUID actorId, String action, String entityType, Object entityId, Map<String, ?> details) {
        independent.executeWithoutResult(s -> record(actorId, action, entityType, entityId, details));
    }

    public void record(UUID actorId, String action, String entityType, Object entityId, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details, ip)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?)""")
                .params(
                        actorId,
                        action,
                        entityType,
                        entityId == null ? null : entityId.toString(),
                        json.writeValueAsString(details),
                        clientIp())
                .update();
    }

    public List<AuditEntry> recent(UUID actorId, int limit) {
        return jdbc.sql("""
                        SELECT id, actor_id, action, entity_type, entity_id, details::text AS details, ip,
                               created_at::text AS created_at
                        FROM audit_log WHERE (CAST(? AS uuid) IS NULL OR actor_id = ?)
                        ORDER BY id DESC LIMIT ?""")
                .params(actorId, actorId, limit)
                .query((rs, n) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("details"),
                        rs.getString("ip"),
                        rs.getString("created_at")))
                .list();
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
