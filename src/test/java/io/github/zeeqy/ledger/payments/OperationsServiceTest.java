package io.github.zeeqy.ledger.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zeeqy.ledger.IntegrationTest;
import io.github.zeeqy.ledger.common.ApiException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationsServiceTest extends IntegrationTest {

    @Autowired
    OperationsService ops;

    static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(code);
    }

    @Test
    void transferMovesMoneyBetweenWallets() {
        UUID ali = data.funded(10_000);
        UUID mei = data.funded(0);
        UUID meiWallet = ops.wallet(mei).walletId();

        var r = ops.transfer(ali, meiWallet, 3_000, "makan");

        assertThat(r.balanceAfterSen()).isEqualTo(7_000);
        assertThat(ops.wallet(mei).balanceSen()).isEqualTo(3_000);
        assertThat(ops.statement(mei, 10)).singleElement().satisfies(line -> {
            assertThat(line.amountSen()).isEqualTo(3_000);
            assertThat(line.counterpartyAccountId()).isEqualTo(ops.wallet(ali).walletId());
        });
    }

    @Test
    void transferRules() {
        UUID ali = data.funded(1_000);
        UUID merchant = data.merchant();
        UUID aliWallet = ops.wallet(ali).walletId();

        assertCode(() -> ops.transfer(ali, aliWallet, 1, null), "SAME_ACCOUNT");
        assertCode(() -> ops.transfer(ali, ops.wallet(merchant).walletId(), 1, null), "NOT_A_WALLET");
        assertCode(() -> ops.transfer(ali, UUID.randomUUID(), 1, null), "ACCOUNT_NOT_FOUND");
        assertCode(() -> ops.transfer(ali, ops.wallet(data.funded(0)).walletId(), 1_001, null), "INSUFFICIENT_FUNDS");
        assertCode(() -> ops.topUp(merchant, 100), "NO_WALLET");
        assertCode(() -> ops.createWallet(ali), "WALLET_EXISTS");
    }

    @Test
    void paymentThenPartialRefundsCappedAtPaymentAmount() {
        UUID ali = data.funded(10_000);
        UUID merchant = data.merchant();
        var payment = ops.pay(ali, merchant, 4_000, "ORDER-1");

        ops.refund(merchant, payment.entryId(), 1_500, "1 item out of stock");
        assertCode(() -> ops.refund(merchant, payment.entryId(), 2_501, null), "REFUND_EXCEEDS_PAYMENT");
        ops.refund(merchant, payment.entryId(), 2_500, null);

        assertThat(ops.wallet(ali).balanceSen()).isEqualTo(10_000);
        assertThat(ops.wallet(merchant).balanceSen()).isZero();
        assertCode(() -> ops.refund(merchant, payment.entryId(), 1, null), "REFUND_EXCEEDS_PAYMENT");
    }

    @Test
    void merchantCanOnlyRefundItsOwnPayments() {
        UUID ali = data.funded(1_000);
        UUID shopA = data.merchant();
        UUID shopB = data.merchant();
        var payment = ops.pay(ali, shopA, 500, null);

        assertCode(() -> ops.refund(shopB, payment.entryId(), 100, null), "PAYMENT_NOT_FOUND");
        assertCode(() -> ops.refund(ali, payment.entryId(), 100, null), "NOT_A_MERCHANT");
    }

    @Test
    void reversalMirrorsTheEntryOnce() {
        UUID admin = data.user("ADMIN");
        UUID ali = data.funded(5_000);
        UUID merchant = data.merchant();
        var payment = ops.pay(ali, merchant, 2_000, null);

        var reversal = ops.reverse(admin, payment.entryId(), "duplicate charge");

        assertThat(reversal.relatedEntryId()).isEqualTo(payment.entryId());
        assertThat(ops.wallet(ali).balanceSen()).isEqualTo(5_000);
        assertThat(ops.wallet(merchant).balanceSen()).isZero();
        assertCode(() -> ops.reverse(admin, payment.entryId(), "again"), "ALREADY_REVERSED");
        assertCode(() -> ops.reverse(admin, reversal.entryId(), "undo undo"), "NOT_REVERSIBLE");
        assertCode(() -> ops.refund(merchant, payment.entryId(), 1, null), "PAYMENT_REVERSED");
    }

    @Test
    void cannotReverseRefundedPaymentOrMoneyAlreadySpent() {
        UUID admin = data.user("ADMIN");
        UUID ali = data.funded(5_000);
        UUID mei = data.funded(0);
        UUID merchant = data.merchant();
        var payment = ops.pay(ali, merchant, 2_000, null);
        ops.refund(merchant, payment.entryId(), 500, null);
        assertCode(() -> ops.reverse(admin, payment.entryId(), "x"), "HAS_REFUNDS");

        var transfer = ops.transfer(ali, ops.wallet(mei).walletId(), 1_000, null);
        ops.transfer(mei, ops.wallet(ali).walletId(), 600, null); // Mei spends most of it
        assertCode(() -> ops.reverse(admin, transfer.entryId(), "fraud"), "INSUFFICIENT_FUNDS");
    }
}
