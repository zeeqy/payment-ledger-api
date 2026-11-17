package io.github.zeeqy.ledger.risk;

import io.github.zeeqy.ledger.ledger.LedgerService.EntryKind;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Optional call to project 04 (fraud-detection-api) {@code POST /predict}. Off when RISK_URL is empty. The model was
 * trained on PaySim, whose amounts are not MYR, so the score is a demo signal, not a calibrated MYR risk.
 */
@Component
public class RiskClient {

    public enum Outcome {
        ALLOW,
        FLAG,
        UNAVAILABLE
    }

    public record Verdict(Outcome outcome, double probability, String modelVersion, String error) {}

    private final String url;
    private final boolean failOpen;
    private final ZoneId zone;
    private final RestClient http;

    public RiskClient(
            @Value("${ledger.risk.url:}") String url,
            @Value("${ledger.risk.timeout-ms}") long timeoutMs,
            @Value("${ledger.risk.fail-open}") boolean failOpen,
            @Value("${ledger.limits.zone}") String zone) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.failOpen = failOpen;
        this.zone = ZoneId.of(zone);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return !url.isBlank();
    }

    public boolean failOpen() {
        return failOpen;
    }

    public Verdict assess(EntryKind kind, long amountSen, long fromBalanceSen, long toBalanceSen) {
        Map<String, Object> body = Map.of(
                "type", kind == EntryKind.PAYMENT ? "PAYMENT" : "TRANSFER",
                "amount", amountSen / 100.0,
                "oldbalance_orig", Math.max(0, fromBalanceSen) / 100.0,
                "oldbalance_dest", Math.max(0, toBalanceSen) / 100.0,
                "hour", LocalTime.now(zone).getHour());
        try {
            JsonNode res =
                    http.post().uri(url + "/predict").body(body).retrieve().body(JsonNode.class);
            if (res == null) {
                return new Verdict(Outcome.UNAVAILABLE, 0, null, "empty response");
            }
            Outcome outcome = "flag".equals(res.path("decision").asString()) ? Outcome.FLAG : Outcome.ALLOW;
            return new Verdict(
                    outcome,
                    res.path("fraud_probability").asDouble(),
                    res.path("model_version").asString(),
                    null);
        } catch (RuntimeException e) {
            return new Verdict(Outcome.UNAVAILABLE, 0, null, e.getClass().getSimpleName());
        }
    }
}
