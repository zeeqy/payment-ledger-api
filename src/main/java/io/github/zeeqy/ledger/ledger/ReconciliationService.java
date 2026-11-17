package io.github.zeeqy.ledger.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recomputes the ledger's invariants from raw postings. Any non-zero number here is an incident. */
@Service
public class ReconciliationService {

    public record Report(
            boolean ok,
            long totalPostingsSen,
            long unbalancedEntries,
            long accountsWithWrongBalance,
            long overdrawnAccounts,
            long entries,
            long postings) {}

    private final JdbcClient jdbc;

    public ReconciliationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** REPEATABLE READ so every query sees the same snapshot while transfers keep running. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Report run() {
        long total = one("SELECT coalesce(sum(amount_sen), 0) FROM postings");
        long unbalanced = one(
                "SELECT count(*) FROM (SELECT entry_id FROM postings GROUP BY entry_id HAVING sum(amount_sen) <> 0) x");
        long wrongBalance = one("""
                SELECT count(*) FROM accounts a
                WHERE a.balance_sen <> (SELECT coalesce(sum(p.amount_sen), 0) FROM postings p WHERE p.account_id = a.id)""");
        long overdrawn = one("SELECT count(*) FROM accounts WHERE NOT allow_negative AND balance_sen < 0");
        long entries = one("SELECT count(*) FROM journal_entries");
        long postings = one("SELECT count(*) FROM postings");
        boolean ok = total == 0 && unbalanced == 0 && wrongBalance == 0 && overdrawn == 0;
        return new Report(ok, total, unbalanced, wrongBalance, overdrawn, entries, postings);
    }

    private long one(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
