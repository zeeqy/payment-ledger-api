package io.github.zeeqy.ledger.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import io.github.zeeqy.ledger.StubServer;
import io.github.zeeqy.ledger.StubServer.Reply;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Stands in for project 04's fraud API: flags transfers >= RM 1,000, errors on exactly RM 7.77. */
class RiskHookTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final StubServer MODEL = new StubServer(r -> {
        double amount = JSON.readTree(r.body()).path("amount").asDouble();
        if (amount == 7.77) {
            return new Reply(500, "{}");
        }
        String decision = amount >= 1_000 ? "flag" : "allow";
        return new Reply(
                200,
                "{\"fraud_probability\":" + (amount >= 1_000 ? 0.97 : 0.01) + ",\"decision\":\"" + decision
                        + "\",\"model_version\":\"stub-1\"}");
    });

    @DynamicPropertySource
    static void riskUrl(DynamicPropertyRegistry registry) {
        registry.add("ledger.risk.url", () -> MODEL.url(""));
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void flaggedTransferIsDeclinedAndTheDeclineIsAudited() {
        Session ali = newUser(200_000);
        Session mei = newUser(0);

        Resp ok = postIdem("/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 50_000));
        Resp flagged = postIdem("/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 100_000));

        assertThat(ok.status()).isEqualTo(201);
        assertThat(flagged.status()).isEqualTo(422);
        assertThat(flagged.code()).isEqualTo("RISK_DECLINED");
        assertThat(balance(ali)).isEqualTo(150_000);
        assertThat(data.count(
                        "SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'RISK_DECLINED'", ali.userId()))
                .isEqualTo(1);

        var sent = JSON.readTree(MODEL.received.getLast().body());
        assertThat(sent.path("type").asString()).isEqualTo("TRANSFER");
        assertThat(sent.path("amount").asDouble()).isEqualTo(1_000.0);
        assertThat(sent.path("oldbalance_orig").asDouble()).isEqualTo(1_500.0);
    }

    @Test
    void modelOutageFailsOpenByDefaultAndIsAudited() {
        Session ali = newUser(10_000);
        Resp r = postIdem(
                "/transfers", ali.token(), Map.of("toWalletId", newUser(0).accountId(), "amountSen", 777));
        assertThat(r.status()).isEqualTo(201);
        assertThat(data.count(
                        "SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'RISK_UNAVAILABLE'",
                        ali.userId()))
                .isEqualTo(1);
    }
}
