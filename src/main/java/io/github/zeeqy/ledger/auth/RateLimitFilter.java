package io.github.zeeqy.ledger.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Token bucket per authenticated user (per IP for anonymous calls such as login). Runs after JWT authentication so
 * the key is the user, not the IP: many users behind one mobile carrier NAT don't throttle each other.
 *
 * <p>Buckets live in this JVM's memory, so with N instances the effective limit is N times higher. A shared store
 * (Redis via bucket4j-redis) fixes that; see docs/DESIGN.md.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long perMinute;
    private final JsonMapper json;

    public RateLimitFilter(@Value("${ledger.rate-limit.requests-per-minute}") long perMinute, JsonMapper json) {
        this.perMinute = perMinute;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/openapi.json")
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String key = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())
                ? "user:" + auth.getName()
                : "ip:" + req.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(
                key,
                k -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(perMinute)
                                .refillGreedy(perMinute, Duration.ofMinutes(1))
                                .build())
                        .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        res.setHeader("X-RateLimit-Limit", String.valueOf(perMinute));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        if (probe.isConsumed()) {
            chain.doFilter(req, res);
            return;
        }
        long retryAfter =
                Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        SecurityConfig.writeProblem(
                json,
                req,
                res,
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Limit is " + perMinute + " requests/minute; retry in " + retryAfter + "s");
    }
}
