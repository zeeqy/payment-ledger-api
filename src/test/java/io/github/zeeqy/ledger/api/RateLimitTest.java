package io.github.zeeqy.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zeeqy.ledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ledger.rate-limit.requests-per-minute=5")
class RateLimitTest extends IntegrationTest {

    @Test
    void limitIsPerUserNotPerIp() {
        String ali = data.token(data.funded(0), "USER");
        String mei = data.token(data.funded(0), "USER");

        for (int i = 0; i < 5; i++) {
            assertThat(get("/wallets/me", ali).status()).isEqualTo(200);
        }
        Resp limited = get("/wallets/me", ali);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.headers().getFirst("Retry-After")).isNotBlank();

        // Same IP (localhost), different user: not affected.
        assertThat(get("/wallets/me", mei).status()).isEqualTo(200);
    }
}
