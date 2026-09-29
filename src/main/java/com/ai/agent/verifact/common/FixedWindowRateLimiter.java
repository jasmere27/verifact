package com.ai.agent.verifact.common;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe fixed-window counter keyed by an arbitrary string (e.g. client IP). */
class FixedWindowRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {}

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Records one request for {@code key}.
     *
     * @return 0 if allowed, otherwise the number of seconds until the window resets
     */
    long tryAcquire(String key) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
        }

        Window window = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (window.count() <= limit) {
            return 0;
        }
        long remainingMillis = window.startMillis() + windowMillis - now;
        return Math.max(1, (remainingMillis + 999) / 1000);
    }
}
