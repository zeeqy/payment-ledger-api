package io.github.zeeqy.ledger.api;

import io.github.zeeqy.ledger.common.Problems;
import io.github.zeeqy.ledger.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Wraps a money-moving controller action so it runs at most once per Idempotency-Key. */
@Component
class Idempotent {

    static final String HEADER = "Idempotency-Key";

    private final IdempotencyService service;

    Idempotent(IdempotencyService service) {
        this.service = service;
    }

    ResponseEntity<String> run(Jwt jwt, String key, HttpServletRequest req, Object body, Supplier<?> op) {
        String path = req.getRequestURI();
        String fingerprint = service.fingerprint(req.getMethod(), path, body);
        var stored = service.execute(UUID.fromString(jwt.getSubject()), key, fingerprint, 201, path, op);
        return ResponseEntity.status(stored.status())
                .contentType(new MediaType(
                        stored.status() >= 400 ? Problems.PROBLEM_JSON : MediaType.APPLICATION_JSON,
                        StandardCharsets.UTF_8))
                .header("Idempotent-Replayed", String.valueOf(stored.replayed()))
                .body(stored.body());
    }

    static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
