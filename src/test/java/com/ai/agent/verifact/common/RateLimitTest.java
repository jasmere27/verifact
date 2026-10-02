package com.ai.agent.verifact.common;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitTest {

    /** Clock whose time the test controls. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void limiterAllowsUpToLimitThenReportsRetryAfter() {
        MutableClock clock = new MutableClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(1), clock);

        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isEqualTo(60);
        assertThat(limiter.tryAcquire("b")).as("other keys are independent").isZero();

        clock.advance(Duration.ofSeconds(45));
        assertThat(limiter.tryAcquire("a")).isEqualTo(15);

        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire("a")).as("window reset").isZero();
    }

    @Test
    void filterReturns429ProblemAfterPerIpLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, 100, 100, false, new JsonMapper());

        assertThat(call(filter, "/api/v1/isFakeNews", "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/isFakeNews", "1.1.1.1", null).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = call(filter, "/api/v1/isFakeNews", "1.1.1.1", null);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getContentType()).isEqualTo("application/problem+json");
        assertThat(limited.getContentAsString()).contains("too quickly");

        assertThat(call(filter, "/api/v1/isFakeNews", "2.2.2.2", null).getStatus()).isEqualTo(200);
    }

    @Test
    void globalLimitCapsAllClients() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(100, 100, 2, false, new JsonMapper());
        call(filter, "/api/v1/analyzeImage", "1.1.1.1", null);
        call(filter, "/api/v1/analyzeAudio", "2.2.2.2", null);
        MockHttpServletResponse limited = call(filter, "/api/v1/isFakeNews", "3.3.3.3", null);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("daily capacity");
    }

    @Test
    void unlimitedPathsAreNotCounted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 1, 1, false, new JsonMapper());
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/actuator/health", "1.1.1.1", null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() throws Exception {
        RateLimitFilter untrusted = new RateLimitFilter(1, 100, 100, false, new JsonMapper());
        call(untrusted, "/api/v1/isFakeNews", "9.9.9.9", "1.1.1.1");
        // A spoofed header must not give the same socket address a fresh quota.
        assertThat(call(untrusted, "/api/v1/isFakeNews", "9.9.9.9", "5.5.5.5").getStatus()).isEqualTo(429);
    }

    @Test
    void trustedForwardedForUsesRightMostEntry() {
        RateLimitFilter trusted = new RateLimitFilter(1, 100, 100, true, new JsonMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/isFakeNews");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7");
        assertThat(trusted.clientIp(request)).isEqualTo("7.7.7.7");
    }

    @Test
    void ipv6ClientsAreGroupedByPrefix() {
        assertThat(RateLimitFilter.ipv6Prefix("2001:db8:1:2:aaaa::1"))
                .isEqualTo(RateLimitFilter.ipv6Prefix("2001:db8:1:2:bbbb::9"))
                .isNotEqualTo(RateLimitFilter.ipv6Prefix("2001:db8:1:3::1"));
        assertThat(RateLimitFilter.ipv6Prefix("1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void limiterMemoryIsBounded() {
        MutableClock clock = new MutableClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(10, Duration.ofDays(1), clock);
        for (int i = 0; i < FixedWindowRateLimiter.MAX_KEYS + 5_000; i++) {
            limiter.tryAcquire("k" + i);
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(FixedWindowRateLimiter.MAX_KEYS);
        assertThat(limiter.tryAcquire("brand-new")).as("fails closed when full").isPositive();
        assertThat(limiter.tryAcquire("k1")).as("known keys still work").isZero();

        clock.advance(Duration.ofDays(1).plusMinutes(2));
        assertThat(limiter.tryAcquire("after-expiry")).as("expired windows are cleaned up").isZero();
        assertThat(limiter.trackedKeys()).isLessThan(10);
    }

    @Test
    void feedbackHasItsOwnLimitAndDoesNotUseCheckQuota() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 100, 100, false, 2, new JsonMapper());
        String fb = "/api/v2/verifications/6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f/feedback";
        assertThat(call(filter, fb, "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, fb, "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, fb, "1.1.1.1", null).getStatus()).isEqualTo(429);
        assertThat(call(filter, "/api/v2/verifications", "1.1.1.1", null).getStatus())
                .as("check quota untouched").isEqualTo(200);
    }

    @Test
    void draftUploadsAndInsightsCountLikeChecksButRemovingADraftIsALightWrite() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 100, 100, false, 10, new JsonMapper());
        String ws = "/api/v2/research/workspaces/6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f";

        assertThat(call(filter, ws + "/insights", "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, ws + "/draft", "1.1.1.1", null).getStatus()).isEqualTo(429);

        MockHttpServletRequest delete = new MockHttpServletRequest("DELETE", ws + "/draft");
        delete.setRequestURI(ws + "/draft");
        delete.setRemoteAddr("1.1.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(delete, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void suggestingQuestionLinksCountsLikeACheckAndReviewingOneIsALightWrite() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 100, 100, false, 1, new JsonMapper());
        String project = "/api/v2/me/projects/6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f";

        assertThat(call(filter, project + "/links/suggest", "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v2/verifications", "1.1.1.1", null).getStatus()).isEqualTo(429);
        assertThat(call(filter, project + "/links/review", "1.1.1.1", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, project + "/links/review", "1.1.1.1", null).getStatus()).isEqualTo(429);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String path, String ip, String forwardedFor)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(ip);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
