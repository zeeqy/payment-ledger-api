package io.github.zeeqy.ledger.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    static final String SECRET = "whsec_test";
    static final String BODY = "{\"id\":\"evt_1\",\"amountSen\":2990}";

    @Test
    void validSignatureVerifies() {
        String header = WebhookSigner.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(header).startsWith("t=1700000000,v1=");
        assertThat(WebhookSigner.verify(SECRET, header, BODY, 1_700_000_030L, 300))
                .isTrue();
    }

    @Test
    void tamperedBodyWrongSecretOrStaleTimestampFail() {
        String header = WebhookSigner.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY.replace("2990", "29900"), 1_700_000_000L, 300))
                .isFalse();
        assertThat(WebhookSigner.verify("whsec_other", header, BODY, 1_700_000_000L, 300))
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, header, BODY, 1_700_000_301L, 300))
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, "garbage", BODY, 1_700_000_000L, 300))
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, "t=abc,v1=00", BODY, 1_700_000_000L, 300))
                .isFalse();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(WebhookSigner.backoffMs(1, 2_000)).isEqualTo(2_000);
        assertThat(WebhookSigner.backoffMs(2, 2_000)).isEqualTo(4_000);
        assertThat(WebhookSigner.backoffMs(5, 2_000)).isEqualTo(32_000);
        assertThat(WebhookSigner.backoffMs(40, 2_000)).isEqualTo(3_600_000);
    }
}
