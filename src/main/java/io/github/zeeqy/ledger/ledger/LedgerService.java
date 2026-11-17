package io.github.zeeqy.ledger.ledger;

import io.github.zeeqy.ledger.common.ApiException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that writes money. Callers describe an entry as a list of legs that sum to zero; this class locks
 * the accounts, checks funds and appends the entry and its postings. Balances move via the DB trigger.
 */
@Service
public class LedgerService {

    public static final String FUNDING_ACCOUNT = "SYS_FUNDING";

    public enum EntryKind {
        TOPUP,
        TRANSFER,
        PAYMENT,
        REFUND,
        REVERSAL
    }

    /** One side of an entry. Positive increases the account's balance. */
    public record Leg(UUID accountId, long amountSen) {}

    public record LockedAccount(UUID id, UUID ownerId, String kind, long balanceSen, boolean allowNegative) {}

    private final JdbcClient jdbc;
    private volatile UUID fundingAccountId;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID fundingAccountId() {
        if (fundingAccountId == null) {
            fundingAccountId = jdbc.sql("SELECT id FROM accounts WHERE code = ?")
                    .param(FUNDING_ACCOUNT)
                    .query(UUID.class)
                    .single();
        }
        return fundingAccountId;
    }

    /**
     * Locks accounts with SELECT ... FOR UPDATE, always in ascending id order. A fixed global order means two
     * transfers A->B and B->A can never each hold one lock and wait for the other (no deadlock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, LockedAccount> lock(Collection<UUID> accountIds) {
        Map<UUID, LockedAccount> locked = new LinkedHashMap<>();
        for (UUID id : new TreeSet<>(accountIds)) {
            LockedAccount account = jdbc.sql("""
                            SELECT id, owner_id, kind, balance_sen, allow_negative
                            FROM accounts WHERE id = ? FOR UPDATE""")
                    .param(id)
                    .query((rs, n) -> new LockedAccount(
                            rs.getObject("id", UUID.class),
                            rs.getObject("owner_id", UUID.class),
                            rs.getString("kind"),
                            rs.getLong("balance_sen"),
                            rs.getBoolean("allow_negative")))
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found"));
            locked.put(id, account);
        }
        return locked;
    }

    /** Appends a balanced entry. Must run inside the caller's transaction. Returns the new entry id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(EntryKind kind, String description, UUID relatedEntryId, UUID initiatedBy, List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("an entry needs at least two legs");
        }
        if (legs.stream().anyMatch(l -> l.amountSen() == 0)) {
            throw new IllegalArgumentException("zero-amount leg");
        }
        long sum = legs.stream().mapToLong(Leg::amountSen).reduce(0L, Math::addExact);
        if (sum != 0) {
            throw new IllegalArgumentException("legs sum to " + sum + ", expected 0");
        }

        Map<UUID, Long> netByAccount =
                legs.stream().collect(Collectors.groupingBy(Leg::accountId, Collectors.summingLong(Leg::amountSen)));
        Map<UUID, LockedAccount> locked = lock(netByAccount.keySet());
        netByAccount.forEach((accountId, net) -> {
            LockedAccount a = locked.get(accountId);
            if (!a.allowNegative() && Math.addExact(a.balanceSen(), net) < 0) {
                throw ApiException.unprocessable(
                        "INSUFFICIENT_FUNDS", "Balance is " + a.balanceSen() + " sen, needs " + (-net) + " sen");
            }
        });

        UUID entryId = jdbc.sql("""
                        INSERT INTO journal_entries (kind, description, related_entry_id, initiated_by)
                        VALUES (?, ?, ?, ?) RETURNING id""")
                .params(kind.name(), description, relatedEntryId, initiatedBy)
                .query(UUID.class)
                .single();
        for (Leg leg : legs) {
            jdbc.sql("INSERT INTO postings (entry_id, account_id, amount_sen) VALUES (?, ?, ?)")
                    .params(entryId, leg.accountId(), leg.amountSen())
                    .update();
        }
        return entryId;
    }

    public long balance(UUID accountId) {
        return jdbc.sql("SELECT balance_sen FROM accounts WHERE id = ?")
                .param(accountId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    /** Balance recomputed from scratch from postings; must always equal {@link #balance(UUID)}. */
    public long derivedBalance(UUID accountId) {
        return jdbc.sql("SELECT coalesce(sum(amount_sen), 0) FROM postings WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    public List<Leg> postingsOf(UUID entryId) {
        return jdbc.sql("SELECT account_id, amount_sen FROM postings WHERE entry_id = ? ORDER BY id")
                .param(entryId)
                .query((rs, n) -> new Leg(rs.getObject("account_id", UUID.class), rs.getLong("amount_sen")))
                .list();
    }
}
