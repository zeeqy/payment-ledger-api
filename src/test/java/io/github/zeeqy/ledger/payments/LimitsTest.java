package io.github.zeeqy.ledger.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Defaults: RM 5,000 per transaction and RM 5,000 outgoing per wallet per Malaysian day. */
class LimitsTest extends IntegrationTest {

    @Test
    void dailyOutgoingLimitCountsTransfersAndPayments() {
        Session ali = newUser(500_000);
        postIdem("/wallets/me/topups", ali.token(), Map.of("amountSen", 300_000));
        Session mei = newUser(0);
        Session shop = newMerchant(null);

        assertThat(postIdem("/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 300_000))
                        .status())
                .isEqualTo(201);
        assertThat(postIdem("/payments", ali.token(), Map.of("merchantId", shop.userId(), "amountSen", 199_999))
                        .status())
                .isEqualTo(201);
        Resp over = postIdem("/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 2));

        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(over.body().path("detail").asString()).contains("1 sen left");
        // Exactly hitting the limit is allowed; top-ups are not outgoing.
        assertThat(postIdem("/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 1))
                        .status())
                .isEqualTo(201);
        assertThat(balance(ali)).isEqualTo(800_000 - 500_000);
    }

    @Test
    void singleTransactionCap() {
        Session ali = newUser(500_000);
        postIdem("/wallets/me/topups", ali.token(), Map.of("amountSen", 100_000));
        Resp big = postIdem(
                "/transfers", ali.token(), Map.of("toWalletId", newUser(0).accountId(), "amountSen", 500_001));
        assertThat(big.code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
    }
}
