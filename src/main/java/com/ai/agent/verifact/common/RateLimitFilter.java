package com.ai.agent.verifact.common;

import tools.jackson.databind.json.JsonMapper;
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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
            "/api/v1/analyzeAudio",
            "/api/v2/verifications",
            "/api/v2/verifications/image",
            "/api/v2/verifications/audio",
            "/api/v2/verifications/stream",
            "/api/v2/verifications/image/stream",
            "/api/v2/verifications/audio/stream",
            "/api/v2/legal/case-intelligence",
            "/api/v2/legal/case-intelligence/stream",
            "/api/v2/research/check",
            "/api/v2/research/check/stream",
            "/api/v2/news/checks",
            "/api/v2/news/checks/stream",
            "/api/v2/research/discover");

    /**
     * Feedback and NewsFact review saves are cheap but spammable; limited separately so they never use
     * up check quota.
     */
    static final java.util.regex.Pattern FEEDBACK_PATH =
            java.util.regex.Pattern.compile("^/api/v2/(verifications/[^/]+/feedback|news/checks/[^/]+/review)$");
    /** ResearchFact workspace changes (create, save a source, edit, delete): same light limiter; reads are free. */
    static final java.util.regex.Pattern WORKSPACE_PATH =
            java.util.regex.Pattern.compile("^/api/v2/research/workspaces(/[^/]+(/sources|/draft)?)?$");
    /** Profile changes: the light limiter; reading your own account is free. */
    static final String ACCOUNT_PATH = "/api/v2/me";
    /** Uploading a draft and generating insights call the model: counted like checks. */
    static final java.util.regex.Pattern HEAVY_WORKSPACE_PATH =
            java.util.regex.Pattern.compile("^/api/v2/research/workspaces/[^/]+/(draft|insights)$");

    private final FixedWindowRateLimiter feedbackPerIpMinute;
    private final FixedWindowRateLimiter perIpMinute;
    private final FixedWindowRateLimiter perIpDay;
    private final FixedWindowRateLimiter globalDay;
    private final boolean trustForwardedFor;
    private final JsonMapper jsonMapper;

    /** Decodes and strips ";params" like Spring MVC's handler matching does. */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    static {
        PATH_HELPER.setUrlDecode(true);
        PATH_HELPER.setRemoveSemicolonContent(true);
    }

    public RateLimitFilter(int perIpPerMinute, int perIpPerDay, int globalPerDay, boolean trustForwardedFor,
                           JsonMapper jsonMapper) {
        this(perIpPerMinute, perIpPerDay, globalPerDay, trustForwardedFor, 10, jsonMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(@Value("${app.rate-limit.per-ip-per-minute:5}") int perIpPerMinute,
                           @Value("${app.rate-limit.per-ip-per-day:50}") int perIpPerDay,
                           @Value("${app.rate-limit.global-per-day:1000}") int globalPerDay,
                           @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
                           @Value("${app.rate-limit.feedback-per-ip-per-minute:10}") int feedbackPerIpPerMinute,
                           JsonMapper jsonMapper) {
        Clock clock = Clock.systemUTC();
        this.feedbackPerIpMinute = new FixedWindowRateLimiter(feedbackPerIpPerMinute, Duration.ofMinutes(1), clock);
        this.perIpMinute = new FixedWindowRateLimiter(perIpPerMinute, Duration.ofMinutes(1), clock);
        this.perIpDay = new FixedWindowRateLimiter(perIpPerDay, Duration.ofDays(1), clock);
        this.globalDay = new FixedWindowRateLimiter(globalPerDay, Duration.ofDays(1), clock);
        this.trustForwardedFor = trustForwardedFor;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalizedPath(request);
        return !isHeavy(request, path) && !isLightWrite(request, path);
    }

    private static boolean isHeavy(HttpServletRequest request, String path) {
        return LIMITED_PATHS.contains(path)
                || (HEAVY_WORKSPACE_PATH.matcher(path).matches() && "POST".equalsIgnoreCase(request.getMethod()));
    }

    private static boolean isLightWrite(HttpServletRequest request, String path) {
        return FEEDBACK_PATH.matcher(path).matches()
                || (ACCOUNT_PATH.equals(path) && !"GET".equalsIgnoreCase(request.getMethod()))
                || (WORKSPACE_PATH.matcher(path).matches() && !"GET".equalsIgnoreCase(request.getMethod()) && !isHeavy(request, path));
    }

    /**
     * The path as the controllers will see it. Matching the raw request URI would let variants
     * such as {@code /api/v1/isFakeNews;x} or percent-encoded paths reach a handler uncounted.
     */
    static String normalizedPath(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        path = path.replaceAll("/{2,}", "/").replace("/./", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientIp = clientIp(request);

        if (isLightWrite(request, normalizedPath(request))) {
            long wait = feedbackPerIpMinute.tryAcquire(clientIp);
            if (wait > 0) {
                reject(response, wait, "You're saving too quickly. Please wait a moment.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

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
     * forge. Only honoured when explicitly enabled. IPv6 clients are grouped by /64, since one
     * subscriber typically controls a whole /64 and could otherwise rotate addresses.
     */
    String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] parts = forwardedFor.split(",");
                ip = parts[parts.length - 1].trim();
            }
        }
        return ipv6Prefix(ip);
    }

    static String ipv6Prefix(String ip) {
        if (ip == null || !ip.contains(":")) {
            return ip;
        }
        try {
            // Only literal addresses reach here (they contain ':'), so no DNS lookup happens.
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) {
                return ip;
            }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                prefix.append(String.format("%02x%02x:", b[i], b[i + 1]));
            }
            return prefix.append(":/64").toString();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds, String message) throws IOException {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.TOO_MANY_REQUESTS, message);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
