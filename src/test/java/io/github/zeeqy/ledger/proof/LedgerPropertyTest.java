package io.github.zeeqy.ledger.proof;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.ledger.ReconciliationService;
import io.github.zeeqy.ledger.payments.OperationsService;
import io.github.zeeqy.ledger.payments.Views.OperationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proof 2: model-based property test. Random sequences of top-ups, transfers, payments, refunds and reversals with
 * random amounts (including edge values) are run against the real service and DB and mirrored in a trivial in-memory
 * model. After every step: all postings sum to zero, every cached balance equals its postings, nothing is overdrawn,
 * and every balance equals the model's. Seeds are fixed so a failure is reproducible.
 */
class LedgerPropertyTest extends IntegrationTest {

    static final int STEPS = 80;

    @Autowired
    OperationsService ops;

    @Autowired
    ReconciliationService reconciliation;

    record Payment(UUID id, UUID customer, UUID merchant, long amount, long[] refunded, boolean[] reversed) {}

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void invariantsHoldForRandomOperationSequences(long seed) {
        Random rnd = new Random(seed);
        UUID admin = data.user("ADMIN");
        List<UUID> users = new ArrayList<>();
        List<UUID> merchants = new ArrayList<>();
        Map<UUID, Long> model = new HashMap<>(); // userId -> expected balance (sen)
        Map<UUID, UUID> walletOf = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID u = data.funded(0);
            users.add(u);
            model.put(u, 0L);
            walletOf.put(u, ops.wallet(u).walletId());
        }
        for (int i = 0; i < 2; i++) {
            UUID m = data.merchant();
            merchants.add(m);
            model.put(m, 0L);
        }
        List<Payment> payments = new ArrayList<>();
        List<OperationResult> transfers = new ArrayList<>();
        Map<UUID, long[]> transferLegs = new HashMap<>(); // entryId -> {from user index, to user index, amount}
        int succeeded = 0;

        for (int step = 0; step < STEPS; step++) {
            String context = "seed " + seed + " step " + step;
            UUID a = users.get(rnd.nextInt(users.size()));
            UUID b = users.get(rnd.nextInt(users.size()));
            long amount = amount(rnd);
            try {
                switch (rnd.nextInt(6)) {
                    case 0 -> {
                        ops.topUp(a, amount);
                        model.merge(a, amount, Long::sum);
                    }
                    case 1, 2 -> {
                        OperationResult r = ops.transfer(a, walletOf.get(b), amount, null);
                        model.merge(a, -amount, Long::sum);
                        model.merge(b, amount, Long::sum);
                        transfers.add(r);
                        transferLegs.put(r.entryId(), new long[] {users.indexOf(a), users.indexOf(b), amount});
                    }
                    case 3 -> {
                        UUID m = merchants.get(rnd.nextInt(merchants.size()));
                        OperationResult r = ops.pay(a, m, amount, null);
                        model.merge(a, -amount, Long::sum);
                        model.merge(m, amount, Long::sum);
                        payments.add(new Payment(r.entryId(), a, m, amount, new long[1], new boolean[1]));
                    }
                    case 4 -> {
                        if (payments.isEmpty()) {
                            continue;
                        }
                        Payment p = payments.get(rnd.nextInt(payments.size()));
                        long refund = Math.max(1, rnd.nextLong(p.amount() + 2)); // sometimes too much
                        ops.refund(p.merchant(), p.id(), refund, null);
                        p.refunded()[0] += refund;
                        model.merge(p.merchant(), -refund, Long::sum);
                        model.merge(p.customer(), refund, Long::sum);
                    }
                    default -> {
                        if (transfers.isEmpty()) {
                            continue;
                        }
                        OperationResult t = transfers.get(rnd.nextInt(transfers.size()));
                        ops.reverse(admin, t.entryId(), "property test");
                        long[] leg = transferLegs.get(t.entryId());
                        model.merge(users.get((int) leg[0]), leg[2], Long::sum);
                        model.merge(users.get((int) leg[1]), -leg[2], Long::sum);
                    }
                }
                succeeded++;
            } catch (ApiException expected) {
                // Refused operations (insufficient funds, self-transfer, over-refund, double reversal, limits) must
                // leave no trace; the checks below prove that.
            }

            var report = reconciliation.run();
            assertThat(report.totalPostingsSen()).as(context).isZero();
            assertThat(report.unbalancedEntries()).as(context).isZero();
            assertThat(report.accountsWithWrongBalance()).as(context).isZero();
            assertThat(report.overdrawnAccounts()).as(context).isZero();
            for (var e : model.entrySet()) {
                assertThat(ops.wallet(e.getKey()).balanceSen())
                        .as(context + " balance of " + e.getKey())
                        .isEqualTo(e.getValue());
            }
        }
        assertThat(succeeded).as("the sequence must actually move money").isGreaterThan(STEPS / 4);
    }

    /** Mostly small amounts, with edge values (1 sen, exactly a round ringgit, just above the per-txn cap). */
    static long amount(Random rnd) {
        return switch (rnd.nextInt(10)) {
            case 0 -> 1;
            case 1 -> 100;
            case 2 -> 500_001; // above the single-transaction cap: transfers/payments must refuse it
            default -> 1 + rnd.nextLong(20_000);
        };
    }
}
