package io.github.zeeqy.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zeeqy.ledger.TestcontainersConfiguration;
import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.ledger.LedgerService.EntryKind;
import io.github.zeeqy.ledger.ledger.LedgerService.Leg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class LedgerServiceTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    UUID wallet() {
        return jdbc.sql("INSERT INTO accounts (kind) VALUES ('WALLET') RETURNING id")
                .query(UUID.class)
                .single();
    }

    UUID postInTx(EntryKind kind, List<Leg> legs) {
        return tx.execute(s -> ledger.post(kind, null, null, null, legs));
    }

    @Test
    void topUpThenTransferMovesMoneyAndKeepsDerivedBalanceInSync() {
        UUID funding = ledger.fundingAccountId();
        UUID a = wallet();
        UUID b = wallet();
        postInTx(EntryKind.TOPUP, List.of(new Leg(funding, -10_000), new Leg(a, 10_000)));
        postInTx(EntryKind.TRANSFER, List.of(new Leg(a, -2_550), new Leg(b, 2_550)));

        assertThat(ledger.balance(a)).isEqualTo(7_450).isEqualTo(ledger.derivedBalance(a));
        assertThat(ledger.balance(b)).isEqualTo(2_550).isEqualTo(ledger.derivedBalance(b));
    }

    @Test
    void insufficientFundsIsABusinessErrorAndWritesNothing() {
        UUID a = wallet();
        UUID b = wallet();
        long entriesBefore = jdbc.sql("SELECT count(*) FROM journal_entries")
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> postInTx(EntryKind.TRANSFER, List.of(new Leg(a, -1), new Leg(b, 1))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries")
                        .query(Long.class)
                        .single())
                .isEqualTo(entriesBefore);
    }

    @Test
    void rejectsUnbalancedOrDegenerateLegsBeforeTouchingTheDatabase() {
        UUID a = wallet();
        UUID b = wallet();
        assertThatThrownBy(() -> postInTx(EntryKind.TRANSFER, List.of(new Leg(a, -5), new Leg(b, 4))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postInTx(EntryKind.TRANSFER, List.of(new Leg(a, 0), new Leg(b, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postInTx(EntryKind.TRANSFER, List.of(new Leg(a, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToRunOutsideATransaction() {
        UUID a = wallet();
        UUID b = wallet();
        assertThatThrownBy(
                        () -> ledger.post(EntryKind.TRANSFER, null, null, null, List.of(new Leg(a, -1), new Leg(b, 1))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unknownAccountIs404() {
        UUID a = wallet();
        assertThatThrownBy(() -> postInTx(EntryKind.TRANSFER, List.of(new Leg(a, -1), new Leg(UUID.randomUUID(), 1))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }
}
