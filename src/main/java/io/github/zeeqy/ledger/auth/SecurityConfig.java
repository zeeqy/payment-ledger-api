package io.github.zeeqy.ledger.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.zeeqy.ledger.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/** Stateless JWT (HS256) auth. Roles: USER, MERCHANT, ADMIN, carried in the "role" claim. */
@Configuration
public class SecurityConfig {

    private final SecretKey key;

    public SecurityConfig(@Value("${ledger.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JsonMapper json, RateLimitFilter rateLimit) throws Exception {
        http.csrf(c -> c.disable()) // no cookies, bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/auth/**")
                        .permitAll()
                        .requestMatchers("/docs", "/docs/**", "/swagger-ui/**", "/openapi.json/**", "/actuator/health")
                        .permitAll()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/payments/*/refunds")
                        .hasRole("MERCHANT")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint((req, res, e) -> writeProblem(
                                json, req, res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", e.getMessage()))
                        .accessDeniedHandler((req, res, e) -> writeProblem(
                                json, req, res, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role cannot do this")))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> writeProblem(
                                json, req, res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Bearer token required"))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(
                                json, req, res, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role cannot do this")))
                .addFilterAfter(rateLimit, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    static void writeProblem(
            JsonMapper json,
            HttpServletRequest req,
            HttpServletResponse res,
            HttpStatus status,
            String code,
            String detail)
            throws IOException {
        res.setStatus(status.value());
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(Problems.PROBLEM_JSON.toString());
        res.getWriter().write(json.writeValueAsString(Problems.body(status, code, detail, req.getRequestURI())));
    }

    private static JwtAuthenticationConverter roleConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }

    /** The filter runs inside the security chain only; stop Boot from also registering it as a servlet filter. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
