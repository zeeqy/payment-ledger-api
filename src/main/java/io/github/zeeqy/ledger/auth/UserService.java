package io.github.zeeqy.ledger.auth;

import io.github.zeeqy.ledger.audit.AuditService;
import io.github.zeeqy.ledger.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    public record Token(String accessToken, String tokenType, long expiresInSeconds, UUID userId, String role) {}

    private record UserRow(UUID id, String passwordHash, String role) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final AuditService audit;
    private final Duration ttl;
    /** Compared against when the email is unknown, so login takes the same time either way. */
    private final String dummyHash;

    public UserService(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            JwtEncoder jwt,
            AuditService audit,
            @Value("${ledger.jwt.ttl-minutes}") long ttlMinutes) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.dummyHash = encoder.encode("timing-equaliser");
    }

    @Transactional
    public UUID register(String email, String password, String role) {
        try {
            UUID id = jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id")
                    .params(email.trim().toLowerCase(), encoder.encode(password), role)
                    .query(UUID.class)
                    .single();
            audit.record(id, "USER_REGISTERED", "user", id, Map.of("role", role));
            return id;
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
    }

    public Token login(String email, String password) {
        UserRow user = jdbc.sql("SELECT id, password_hash, role FROM users WHERE email = ?")
                .param(email.trim().toLowerCase())
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getString("role")))
                .optional()
                .orElse(null);
        boolean ok = encoder.matches(password, user == null ? dummyHash : user.passwordHash());
        if (user == null || !ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is wrong");
        }
        return issue(user.id(), user.role());
    }

    public Token issue(UUID userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("payment-ledger-api")
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        String token = jwt.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Token(token, "Bearer", ttl.toSeconds(), userId, role);
    }

    /** Creates the operator account from ADMIN_EMAIL / ADMIN_PASSWORD on startup, if set and missing. */
    @Transactional
    public void ensureAdmin(String email, String password) {
        boolean exists = jdbc.sql("SELECT exists(SELECT 1 FROM users WHERE email = ?)")
                .param(email.toLowerCase())
                .query(Boolean.class)
                .single();
        if (!exists) {
            register(email, password, "ADMIN");
        }
    }
}
