package io.github.zeeqy.ledger;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Boots the real app on a random port against a real Postgres. Subclasses share one Spring context. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledger.rate-limit.requests-per-minute=1000000", "ledger.webhooks.poll-ms=100"})
@Import({TestcontainersConfiguration.class, TestData.class})
public abstract class IntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestData data;

    @Autowired
    protected JsonMapper json;

    public record Resp(int status, JsonNode body, HttpHeaders headers) {
        public String code() {
            return body.path("code").asString();
        }
    }

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> {})
                .build();
    }

    protected Resp call(HttpMethod method, String path, String token, String idemKey, Object body) {
        var spec = http().method(method).uri(path);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (idemKey != null) {
            spec = spec.header("Idempotency-Key", idemKey);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON)
                    .body(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
            return new Resp(res.getStatusCode().value(), node, res.getHeaders());
        });
    }

    protected Resp get(String path, String token) {
        return call(HttpMethod.GET, path, token, null, null);
    }

    protected Resp post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, null, body);
    }

    protected Resp postIdem(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, UUID.randomUUID().toString(), body);
    }

    /** Registers a user over HTTP, opens a wallet, optionally tops up. Returns the session. */
    protected Session newUser(long topUpSen) {
        String email = "u" + UUID.randomUUID() + "@test.my";
        Resp reg = post("/auth/register", null, Map.of("email", email, "password", "correct-horse-1"));
        String token = reg.body().path("accessToken").asString();
        Resp wallet = post("/wallets", token, null);
        if (topUpSen > 0) {
            postIdem("/wallets/me/topups", token, Map.of("amountSen", topUpSen));
        }
        return new Session(
                UUID.fromString(reg.body().path("userId").asString()),
                token,
                UUID.fromString(wallet.body().path("walletId").asString()));
    }

    protected Session newMerchant(String webhookUrl) {
        String email = "m" + UUID.randomUUID() + "@shop.my";
        var body = new java.util.HashMap<String, Object>(
                Map.of("email", email, "password", "correct-horse-1", "name", "Kedai Runcit"));
        if (webhookUrl != null) {
            body.put("webhookUrl", webhookUrl);
        }
        Resp reg = post("/auth/register/merchant", null, body);
        return new Session(
                UUID.fromString(reg.body().path("merchant").path("merchantId").asString()),
                reg.body().path("token").path("accessToken").asString(),
                UUID.fromString(reg.body().path("merchant").path("accountId").asString()),
                reg.body().path("merchant").path("webhookSecret").asString());
    }

    protected String adminToken() {
        UUID id = data.user("ADMIN");
        return data.token(id, "ADMIN");
    }

    protected long balance(Session s) {
        return get("/wallets/me", s.token()).body().path("balanceSen").asLong();
    }

    public record Session(UUID userId, String token, UUID accountId, String webhookSecret) {
        Session(UUID userId, String token, UUID accountId) {
            this(userId, token, accountId, null);
        }
    }
}
