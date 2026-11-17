package io.github.zeeqy.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The database itself refuses unbalanced entries, overdrafts and edits, even if application code is buggy. */
class SchemaGuardsTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    UUID newAccount(boolean allowNegative) {
        return jdbc.sql("INSERT INTO accounts (kind, allow_negative) VALUES ('SYSTEM', ?) RETURNING id")
                .param(allowNegative)
                .query(UUID.class)
                .single();
    }

    UUID newEntry() {
        return jdbc.sql("INSERT INTO journal_entries (kind) VALUES ('TRANSFER') RETURNING id")
                .query(UUID.class)
                .single();
    }

    void post(UUID entry, UUID account, long sen) {
        jdbc.sql("INSERT INTO postings (entry_id, account_id, amount_sen) VALUES (?, ?, ?)")
                .params(entry, account, sen)
                .update();
    }

    long balance(UUID account) {
        return jdbc.sql("SELECT balance_sen FROM accounts WHERE id = ?")
                .param(account)
                .query(Long.class)
                .single();
    }

    @Test
    void balancedEntryUpdatesCachedBalances() {
        UUID a = newAccount(true);
        UUID b = newAccount(false);
        tx.executeWithoutResult(s -> {
            UUID e = newEntry();
            post(e, a, -1_000);
            post(e, b, 1_000);
        });
        assertThat(balance(a)).isEqualTo(-1_000);
        assertThat(balance(b)).isEqualTo(1_000);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        UUID a = newAccount(true);
        UUID b = newAccount(true);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    UUID e = newEntry();
                    post(e, a, -1_000);
                    post(e, b, 999);
                }))
                .hasMessageContaining("unbalanced");
        assertThat(balance(a)).isZero();
    }

    @Test
    void overdraftIsRejected() {
        UUID a = newAccount(false);
        UUID b = newAccount(false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    UUID e = newEntry();
                    post(e, a, -1);
                    post(e, b, 1);
                }))
                .hasMessageContaining("ck_no_overdraft");
    }

    @Test
    void postingsAreAppendOnly() {
        UUID a = newAccount(true);
        UUID b = newAccount(true);
        tx.executeWithoutResult(s -> {
            UUID e = newEntry();
            post(e, a, -5);
            post(e, b, 5);
        });
        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount_sen = 1 WHERE account_id = ?")
                        .param(a)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings WHERE account_id = ?")
                        .param(a)
                        .update())
                .hasMessageContaining("append-only");
    }
}
