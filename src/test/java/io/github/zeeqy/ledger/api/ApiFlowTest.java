package io.github.zeeqy.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** End-to-end over real HTTP: auth, validation, error format, and a full money flow. */
class ApiFlowTest extends IntegrationTest {

    @Test
    void fullFlowTopUpTransferPayRefundReverseAndReconcile() {
        Session ali = newUser(20_000);
        Session mei = newUser(0);
        Session shop = newMerchant(null);

        Resp transfer = postIdem(
                "/transfers", ali.token(), Map.of("toWalletId", mei.accountId(), "amountSen", 5_000, "note", "rent"));
        assertThat(transfer.status()).isEqualTo(201);
        assertThat(transfer.body().path("balanceAfterSen").asLong()).isEqualTo(15_000);

        Resp pay = postIdem(
                "/payments",
                ali.token(),
                Map.of("merchantId", shop.userId(), "amountSen", 2_990, "reference", "INV-7"));
        assertThat(pay.status()).isEqualTo(201);
        String paymentId = pay.body().path("entryId").asString();

        Resp refund = postIdem("/payments/" + paymentId + "/refunds", shop.token(), Map.of("amountSen", 990));
        assertThat(refund.status()).isEqualTo(201);
        assertThat(refund.body().path("balanceAfterSen").asLong()).isEqualTo(2_000);

        String admin = adminToken();
        Resp reversal = postIdem(
                "/admin/entries/" + transfer.body().path("entryId").asString() + "/reversal",
                admin,
                Map.of("reason", "sent to wrong person"));
        assertThat(reversal.status()).isEqualTo(201);

        assertThat(balance(ali)).isEqualTo(20_000 - 2_990 + 990);
        assertThat(balance(mei)).isZero();
        assertThat(get("/wallets/me/statement?limit=10", ali.token()).body().size())
                .isEqualTo(5); // topup, transfer, payment, refund, reversal

        Resp recon = get("/admin/reconciliation", admin);
        assertThat(recon.body().path("ok").asBoolean()).isTrue();
        assertThat(recon.body().path("totalPostingsSen").asLong()).isZero();
    }

    @Test
    void replayedRequestReturnsSameBodyAndHeader() {
        Session ali = newUser(0);
        String key = UUID.randomUUID().toString();
        Resp first = call(HttpMethod.POST, "/wallets/me/topups", ali.token(), key, Map.of("amountSen", 700));
        Resp second = call(HttpMethod.POST, "/wallets/me/topups", ali.token(), key, Map.of("amountSen", 700));
        Resp changed = call(HttpMethod.POST, "/wallets/me/topups", ali.token(), key, Map.of("amountSen", 701));

        assertThat(first.headers().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(ali)).isEqualTo(700);
    }

    @Test
    void everyErrorUsesTheProblemJsonShape() {
        Session ali = newUser(100);

        Resp noToken = get("/wallets/me", null);
        Resp badToken = get("/wallets/me", "not-a-jwt");
        Resp notAdmin = get("/admin/reconciliation", ali.token());
        Resp noKey = post("/wallets/me/topups", ali.token(), Map.of("amountSen", 1));
        Resp negative = postIdem("/wallets/me/topups", ali.token(), Map.of("amountSen", -5));
        Resp decimal = postIdem("/wallets/me/topups", ali.token(), "{\"amountSen\": 12.5}");
        Resp unknownField = postIdem("/wallets/me/topups", ali.token(), "{\"amountSen\": 1, \"currency\": \"USD\"}");
        Resp broke = postIdem(
                "/transfers", ali.token(), Map.of("toWalletId", newUser(0).accountId(), "amountSen", 101));
        Resp badLogin = post("/auth/login", null, Map.of("email", "nobody@x.my", "password", "whatever-123"));
        Resp noRoute = get("/nope", ali.token());

        assertProblem(noToken, 401, "UNAUTHENTICATED");
        assertProblem(badToken, 401, "UNAUTHENTICATED");
        assertProblem(notAdmin, 403, "FORBIDDEN");
        assertProblem(noKey, 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertProblem(negative, 400, "VALIDATION_FAILED");
        assertThat(negative.body().path("errors").path("amountSen").asString()).isNotBlank();
        assertProblem(decimal, 400, "MALFORMED_REQUEST");
        assertProblem(unknownField, 400, "MALFORMED_REQUEST");
        assertProblem(broke, 422, "INSUFFICIENT_FUNDS");
        assertProblem(badLogin, 401, "BAD_CREDENTIALS");
        assertProblem(noRoute, 404, "NOT_FOUND");
        assertThat(balance(ali)).isEqualTo(100);
    }

    @Test
    void usersCannotRefundAndMerchantsOnlyTheirOwn() {
        Session ali = newUser(1_000);
        Session shopA = newMerchant(null);
        Session shopB = newMerchant(null);
        String paymentId = postIdem("/payments", ali.token(), Map.of("merchantId", shopA.userId(), "amountSen", 500))
                .body()
                .path("entryId")
                .asString();

        assertProblem(
                postIdem("/payments/" + paymentId + "/refunds", ali.token(), Map.of("amountSen", 1)), 403, "FORBIDDEN");
        assertProblem(
                postIdem("/payments/" + paymentId + "/refunds", shopB.token(), Map.of("amountSen", 1)),
                404,
                "PAYMENT_NOT_FOUND");
    }

    @Test
    void loginWorksAndDuplicateEmailIsRejected() {
        String email = "siti" + UUID.randomUUID() + "@test.my";
        assertThat(post("/auth/register", null, Map.of("email", email, "password", "panjang-sekali"))
                        .status())
                .isEqualTo(201);
        assertProblem(
                post("/auth/register", null, Map.of("email", email.toUpperCase(), "password", "panjang-sekali")),
                409,
                "EMAIL_TAKEN");
        Resp login = post("/auth/login", null, Map.of("email", email, "password", "panjang-sekali"));
        assertThat(login.status()).isEqualTo(200);
        assertThat(get("/wallets/me", login.body().path("accessToken").asString())
                        .code())
                .isEqualTo("NO_ACCOUNT");
    }

    @Test
    void openApiDocsArePublic() {
        Resp spec = get("/openapi.json", null);
        assertThat(spec.status()).isEqualTo(200);
        assertThat(spec.body().path("paths").has("/transfers")).isTrue();
        assertThat(http().get()
                                .uri("/docs")
                                .retrieve()
                                .toEntity(String.class)
                                .getStatusCode()
                                .is2xxSuccessful()
                        || http().get()
                                .uri("/docs")
                                .retrieve()
                                .toEntity(String.class)
                                .getStatusCode()
                                .is3xxRedirection())
                .isTrue();
    }

    private static void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(status);
        assertThat(r.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as(String.valueOf(r.headers().getContentType()))
                .isTrue();
        assertThat(r.code()).isEqualTo(code);
        assertThat(r.body().path("status").asInt()).isEqualTo(status);
        assertThat(r.body().has("title")).isTrue();
        assertThat(r.body().has("detail")).isTrue();
    }
}
