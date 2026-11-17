package io.github.zeeqy.ledger.proof;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import io.github.zeeqy.ledger.ledger.ReconciliationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proof 1: many parallel requests over real HTTP against one wallet. */
class ConcurrencyTest extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    /** Fires {@code n} requests at the same instant (latch) and returns their responses. */
    <T> List<T> burst(int n, IntFunction<T> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> task = () -> {
                    start.await();
                    return request.apply(idx);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    @Test
    void hundredParallelTransfersOutOfOneWalletNeverOverdraw() throws Exception {
        Session ali = newUser(10_000); // RM 100.00
        List<Session> friends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            friends.add(newUser(0));
        }

        // 100 x RM 1.50 = RM 150.00 requested against RM 100.00.
        List<Resp> responses = burst(
                100,
                i -> postIdem(
                        "/transfers",
                        ali.token(),
                        Map.of("toWalletId", friends.get(i % 10).accountId(), "amountSen", 150)));

        long ok = responses.stream().filter(r -> r.status() == 201).count();
        long refused = responses.stream()
                .filter(r -> r.status() == 422 && r.code().equals("INSUFFICIENT_FUNDS"))
                .count();
        assertThat(ok).isEqualTo(66); // floor(10000 / 150)
        assertThat(refused).isEqualTo(34);
        assertThat(ok + refused).as("no 5xx, no deadlocks").isEqualTo(100);

        assertThat(balance(ali)).isEqualTo(10_000 - 66 * 150);
        long received = friends.stream().mapToLong(this::balance).sum();
        assertThat(received).isEqualTo(66 * 150);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        Session a = newUser(100_000);
        Session b = newUser(100_000);

        // A->B and B->A at the same time: without ordered locking this is the classic deadlock.
        List<Resp> responses = burst(
                100,
                i -> i % 2 == 0
                        ? postIdem("/transfers", a.token(), Map.of("toWalletId", b.accountId(), "amountSen", 100))
                        : postIdem("/transfers", b.token(), Map.of("toWalletId", a.accountId(), "amountSen", 100)));

        assertThat(responses)
                .allSatisfy(r -> assertThat(r.status()).as(r.body().toString()).isEqualTo(201));
        assertThat(balance(a)).isEqualTo(100_000);
        assertThat(balance(b)).isEqualTo(100_000);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void parallelRefundsCannotExceedThePayment() throws Exception {
        Session ali = newUser(10_000);
        Session shop = newMerchant(null);
        String paymentId = postIdem("/payments", ali.token(), Map.of("merchantId", shop.userId(), "amountSen", 1_000))
                .body()
                .path("entryId")
                .asString();

        List<Resp> responses =
                burst(20, i -> postIdem("/payments/" + paymentId + "/refunds", shop.token(), Map.of("amountSen", 300)));

        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(3);
        assertThat(responses.stream().filter(r -> "REFUND_EXCEEDS_PAYMENT".equals(r.code())))
                .hasSize(17);
        assertThat(balance(shop)).isEqualTo(100);
    }

    @Test
    void sameIdempotencyKeySentFiveTimesInParallelPostsExactlyOnce() throws Exception {
        Session ali = newUser(5_000);
        Session mei = newUser(0);
        String key = "pay-rent-2026-11";
        long entriesBefore = data.count("SELECT count(*) FROM postings WHERE account_id = ?", ali.accountId());

        List<Resp> responses = burst(
                5,
                i -> call(
                        org.springframework.http.HttpMethod.POST,
                        "/transfers",
                        ali.token(),
                        key,
                        Map.of("toWalletId", mei.accountId(), "amountSen", 1_000)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(responses.stream().map(Resp::body).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> "true".equals(r.headers().getFirst("Idempotent-Replayed"))))
                .hasSize(4);
        assertThat(data.count("SELECT count(*) FROM postings WHERE account_id = ?", ali.accountId()) - entriesBefore)
                .as("exactly one posting on the sender")
                .isEqualTo(1);
        assertThat(balance(ali)).isEqualTo(4_000);
        assertThat(balance(mei)).isEqualTo(1_000);
    }
}
