package io.github.zeeqy.ledger.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.zeeqy.ledger.IntegrationTest;
import io.github.zeeqy.ledger.StubServer;
import io.github.zeeqy.ledger.StubServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookDeliveryTest extends IntegrationTest {

    @Autowired
    WebhookDispatcher dispatcher;

    @Test
    void failingEndpointIsRetriedWithBackoffUntilDeliveredAndSignatureVerifies() {
        AtomicInteger calls = new AtomicInteger();
        try (var merchantEndpoint =
                new StubServer(r -> calls.incrementAndGet() <= 2 ? new Reply(503, "") : new Reply(200, "{}"))) {
            Session shop = newMerchant(merchantEndpoint.url("/hooks/ledger"));
            Session ali = newUser(5_000);

            Resp pay = postIdem(
                    "/payments",
                    ali.token(),
                    Map.of("merchantId", shop.userId(), "amountSen", 1_234, "reference", "A1"));
            assertThat(pay.status()).isEqualTo(201);

            await().atMost(Duration.ofSeconds(15))
                    .until(() -> dispatcher.statusOf(shop.userId()).stream()
                            .allMatch(s -> s.status().equals("DELIVERED")));

            var status = dispatcher.statusOf(shop.userId());
            assertThat(status)
                    .singleElement()
                    .satisfies(s -> assertThat(s.attempts()).isEqualTo(3));
            assertThat(merchantEndpoint.received).hasSize(3);
            var last = merchantEndpoint.received.getLast();
            assertThat(last.header("Ledger-Event-Type")).isEqualTo("payment.succeeded");
            assertThat(last.header("Ledger-Delivery-Attempt")).isEqualTo("3");
            assertThat(WebhookSigner.verify(
                            shop.webhookSecret(),
                            last.header(WebhookSigner.HEADER),
                            last.body(),
                            Instant.now().getEpochSecond(),
                            300))
                    .isTrue();
            var event = json.readTree(last.body());
            assertThat(event.path("data").path("paymentId").asString())
                    .isEqualTo(pay.body().path("entryId").asString());
            assertThat(event.path("data").path("amountSen").asLong()).isEqualTo(1_234);
            // Same event id on every attempt, so the merchant can deduplicate.
            assertThat(merchantEndpoint.received.stream()
                            .map(r -> json.readTree(r.body()).path("id").asString())
                            .distinct())
                    .hasSize(1);
        }
    }

    @Test
    void refusedPaymentQueuesNoWebhookAndRefundDoes() {
        try (var merchantEndpoint = new StubServer(r -> new Reply(200, "{}"))) {
            Session shop = newMerchant(merchantEndpoint.url("/hook"));
            Session ali = newUser(1_000);

            Resp refused = postIdem("/payments", ali.token(), Map.of("merchantId", shop.userId(), "amountSen", 5_000));
            assertThat(refused.code()).isEqualTo("INSUFFICIENT_FUNDS");

            String paymentId = postIdem("/payments", ali.token(), Map.of("merchantId", shop.userId(), "amountSen", 800))
                    .body()
                    .path("entryId")
                    .asString();
            postIdem("/payments/" + paymentId + "/refunds", shop.token(), Map.of("amountSen", 300));

            await().atMost(Duration.ofSeconds(10)).until(() -> merchantEndpoint.received.size() == 2);
            assertThat(merchantEndpoint.received.stream().map(r -> r.header("Ledger-Event-Type")))
                    .containsExactlyInAnyOrder("payment.succeeded", "refund.succeeded");
        }
    }

    @Test
    void endpointThatNeverRecoversEndsDead() {
        try (var merchantEndpoint = new StubServer(r -> new Reply(500, ""))) {
            Session shop = newMerchant(merchantEndpoint.url("/hook"));
            Session ali = newUser(1_000);
            postIdem("/payments", ali.token(), Map.of("merchantId", shop.userId(), "amountSen", 100));

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> dispatcher.statusOf(shop.userId()).getFirst().attempts() >= 2);
            // Skip the long tail of backoff: pretend 7 of 8 attempts already failed.
            data.count(
                    "WITH u AS (UPDATE outbox_events SET attempts = 7, next_attempt_at = now() WHERE merchant_id = ?"
                            + " RETURNING 1) SELECT count(*) FROM u",
                    shop.userId());

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> dispatcher
                            .statusOf(shop.userId())
                            .getFirst()
                            .status()
                            .equals("DEAD"));
            var s = dispatcher.statusOf(shop.userId()).getFirst();
            assertThat(s.attempts()).isEqualTo(8);
            assertThat(s.lastError()).isEqualTo("HTTP 500");
        }
    }
}
