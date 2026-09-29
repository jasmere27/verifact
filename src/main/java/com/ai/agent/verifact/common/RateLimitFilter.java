package com.ai.agent.verifact.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * Caps how often the expensive verification endpoints (each one costs an LLM call plus web
 * searches) can be hit: per client IP per minute and per day, plus a global daily ceiling
 * that bounds total AI spend. In-memory, so limits reset on restart and are per instance,
 * which is fine for a single backend instance.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/isFakeNews",
            "/api/v1/analyzeImage",
            "/api/v1/analyzeAudio");

    private final FixedWindowRateLimiter perIpMinute;
    private final FixedWindowRateLimiter perIpDay;
    private final FixedWindowRateLimiter globalDay;
    private final boolean trustForwardedFor;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Value("${app.rate-limit.per-ip-per-minute:5}") int perIpPerMinute,
                           @Value("${app.rate-limit.per-ip-per-day:50}") int perIpPerDay,
                           @Value("${app.rate-limit.global-per-day:1000}") int globalPerDay,
                           @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
                           ObjectMapper objectMapper) {
        Clock clock = Clock.systemUTC();
        this.perIpMinute = new FixedWindowRateLimiter(perIpPerMinute, Duration.ofMinutes(1), clock);
        this.perIpDay = new FixedWindowRateLimiter(perIpPerDay, Duration.ofDays(1), clock);
        this.globalDay = new FixedWindowRateLimiter(globalPerDay, Duration.ofDays(1), clock);
        this.trustForwardedFor = trustForwardedFor;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientIp = clientIp(request);

        long retryAfter = perIpMinute.tryAcquire(clientIp);
        if (retryAfter == 0) {
            retryAfter = perIpDay.tryAcquire(clientIp);
        }
        if (retryAfter > 0) {
            reject(response, retryAfter, "You're sending checks too quickly. Please wait a moment and try again.");
            return;
        }
        long globalRetryAfter = globalDay.tryAcquire("global");
        if (globalRetryAfter > 0) {
            log.warn("Global daily verification limit reached");
            reject(response, globalRetryAfter, "VeriFact has reached its daily capacity. Please try again later.");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Behind a reverse proxy (Render, etc.) the socket address is the proxy. The proxy appends
     * the address it saw to X-Forwarded-For, so the right-most entry is the one a client can't
     * forge. Only honoured when explicitly enabled.
     */
    String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] parts = forwardedFor.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds, String message) throws IOException {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.TOO_MANY_REQUESTS, message);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
