package io.github.zeeqy.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zeeqy.ledger.TestData;
import io.github.zeeqy.ledger.TestcontainersConfiguration;
import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.payments.OperationsService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestcontainersConfiguration.class, TestData.class})
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idem;

    @Autowired
    OperationsService ops;

    @Autowired
    TestData data;

    @Test
    void sameKeyRunsOnceAndReplaysTheSameBody() {
        UUID ali = data.funded(0);
        String fp = idem.fingerprint("POST", "/topups", Map.of("amountSen", 500));

        var first = idem.execute(ali, "k1", fp, 201, "/topups", () -> ops.topUp(ali, 500));
        var second = idem.execute(ali, "k1", fp, 201, "/topups", () -> ops.topUp(ali, 500));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(ops.wallet(ali).balanceSen()).isEqualTo(500);
    }

    @Test
    void keysAreScopedPerUser() {
        UUID ali = data.funded(0);
        UUID mei = data.funded(0);
        String fp = idem.fingerprint("POST", "/topups", Map.of("amountSen", 100));
        idem.execute(ali, "shared", fp, 201, null, () -> ops.topUp(ali, 100));
        var forMei = idem.execute(mei, "shared", fp, 201, null, () -> ops.topUp(mei, 100));
        assertThat(forMei.replayed()).isFalse();
        assertThat(ops.wallet(mei).balanceSen()).isEqualTo(100);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        UUID ali = data.funded(0);
        idem.execute(
                ali,
                "k2",
                idem.fingerprint("POST", "/topups", Map.of("amountSen", 1)),
                201,
                null,
                () -> ops.topUp(ali, 1));
        String other = idem.fingerprint("POST", "/topups", Map.of("amountSen", 2));

        assertThatThrownBy(() -> idem.execute(ali, "k2", other, 201, null, () -> ops.topUp(ali, 2)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different request");
        assertThat(ops.wallet(ali).balanceSen()).isEqualTo(1);
    }

    @Test
    void businessErrorIsStoredAndReplayedWithoutSideEffects() {
        UUID ali = data.funded(100);
        UUID mei = data.funded(0);
        UUID meiWallet = ops.wallet(mei).walletId();
        String fp = idem.fingerprint("POST", "/transfers", Map.of("amountSen", 999));
        long auditBefore = data.count("SELECT count(*) FROM audit_log WHERE actor_id = ?", ali);

        var first = idem.execute(ali, "k3", fp, 201, "/transfers", () -> ops.transfer(ali, meiWallet, 999, null));
        var again = idem.execute(ali, "k3", fp, 201, "/transfers", () -> ops.transfer(ali, meiWallet, 999, null));

        assertThat(first.status()).isEqualTo(422);
        assertThat(first.body()).contains("INSUFFICIENT_FUNDS").contains("\"instance\":\"/transfers\"");
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(ops.wallet(ali).balanceSen()).isEqualTo(100);
        assertThat(data.count("SELECT count(*) FROM audit_log WHERE actor_id = ?", ali))
                .isEqualTo(auditBefore);
    }

    @Test
    void missingKeyIsA400() {
        assertThatThrownBy(() -> idem.execute(UUID.randomUUID(), " ", "fp", 201, null, () -> null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
}
