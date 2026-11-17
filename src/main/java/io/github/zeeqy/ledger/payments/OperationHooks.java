package io.github.zeeqy.ledger.payments;

import io.github.zeeqy.ledger.audit.AuditService;
import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.ledger.LedgerService.EntryKind;
import io.github.zeeqy.ledger.risk.RiskClient;
import io.github.zeeqy.ledger.risk.RiskClient.Verdict;
import io.github.zeeqy.ledger.webhooks.Outbox;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Rules and side effects around money movement: risk screening, limits, merchant webhooks. */
@Component
public class OperationHooks {

    private final JdbcClient jdbc;
    private final RiskClient risk;
    private final AuditService audit;
    private final Outbox outbox;
    private final long dailyLimitSen;
    private final long maxSingleSen;
    private final ZoneId zone;

    public OperationHooks(
            JdbcClient jdbc,
            RiskClient risk,
            AuditService audit,
            Outbox outbox,
            @Value("${ledger.limits.daily-outgoing-sen}") long dailyLimitSen,
            @Value("${ledger.limits.max-single-amount-sen}") long maxSingleSen,
            @Value("${ledger.limits.zone}") String zone) {
        this.jdbc = jdbc;
        this.risk = risk;
        this.audit = audit;
        this.outbox = outbox;
        this.dailyLimitSen = dailyLimitSen;
        this.maxSingleSen = maxSingleSen;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Fraud screen (project 04's model). Runs BEFORE row locks are taken so a slow model call never holds a wallet
     * locked. Balances read here are unlocked snapshots, which is fine for a risk feature.
     */
    public void screen(UUID userId, UUID fromAccount, UUID toAccount, EntryKind kind, long amountSen) {
        if (amountSen > maxSingleSen) {
            throw ApiException.unprocessable(
                    "AMOUNT_ABOVE_LIMIT", "Single transaction limit is " + maxSingleSen + " sen");
        }
        if (!risk.enabled()) {
            return;
        }
        Verdict verdict = risk.assess(kind, amountSen, balance(fromAccount), balance(toAccount));
        switch (verdict.outcome()) {
            case ALLOW -> {}
            case FLAG -> {
                audit.recordIndependently(
                        userId,
                        "RISK_DECLINED",
                        "account",
                        fromAccount,
                        Map.of(
                                "amountSen",
                                amountSen,
                                "probability",
                                verdict.probability(),
                                "model",
                                verdict.modelVersion()));
                throw ApiException.unprocessable("RISK_DECLINED", "This transaction was declined by fraud screening");
            }
            case UNAVAILABLE -> {
                audit.recordIndependently(
                        userId,
                        "RISK_UNAVAILABLE",
                        "account",
                        fromAccount,
                        Map.of("failOpen", risk.failOpen(), "error", verdict.error()));
                if (!risk.failOpen()) {
                    throw new ApiException(
                            HttpStatus.SERVICE_UNAVAILABLE, "RISK_UNAVAILABLE", "Fraud screening is unavailable");
                }
            }
        }
    }

    /**
     * Daily outgoing limit per wallet (transfers + payments, Malaysian calendar day). Runs AFTER the wallet row is
     * locked, so two parallel transfers can't both squeeze under the limit.
     */
    public void beforeOutgoing(UUID userId, UUID fromAccount, EntryKind kind, long amountSen) {
        OffsetDateTime startOfDay = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        long usedToday =
                jdbc.sql("""
                        SELECT coalesce(-sum(p.amount_sen), 0) FROM postings p
                        JOIN journal_entries e ON e.id = p.entry_id
                        WHERE p.account_id = ? AND p.amount_sen < 0 AND p.created_at >= ?
                          AND e.kind IN ('TRANSFER', 'PAYMENT')""").params(fromAccount, startOfDay).query(Long.class).single();
        if (usedToday + amountSen > dailyLimitSen) {
            throw ApiException.unprocessable(
                    "DAILY_LIMIT_EXCEEDED",
                    "Daily limit is " + dailyLimitSen + " sen; " + (dailyLimitSen - usedToday) + " sen left today");
        }
    }

    /** Queues a merchant webhook in the same transaction as the money movement (transactional outbox). */
    public void afterMerchantEvent(UUID merchantId, String eventType, Map<String, ?> data) {
        outbox.enqueue(merchantId, eventType, data);
    }

    private long balance(UUID account) {
        return jdbc.sql("SELECT balance_sen FROM accounts WHERE id = ?")
                .param(account)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
