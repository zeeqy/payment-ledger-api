package io.github.zeeqy.ledger.api;

import io.github.zeeqy.ledger.auth.UserService;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Payment Ledger API").version("0.1.0").description("""
                                Mini e-wallet backend on a double-entry ledger. All amounts are integer **sen** \
                                (RM 12.34 = 1234). Money-moving POSTs require an `Idempotency-Key` header. \
                                Errors are `application/problem+json` with a stable `code`."""))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Creates the operator account from ADMIN_EMAIL / ADMIN_PASSWORD if both are set. */
    @Bean
    ApplicationRunner adminBootstrap(
            UserService users,
            @Value("${ledger.admin.email:}") String email,
            @Value("${ledger.admin.password:}") String password) {
        return args -> {
            if (!email.isBlank() && !password.isBlank()) {
                users.ensureAdmin(email, password);
            }
        };
    }
}
