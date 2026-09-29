package com.ai.agent.verifact.common;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe fixed-window counter keyed by an arbitrary string (e.g. client IP). */
class FixedWindowRateLimiter {

    /** Hard cap on tracked keys so a flood of distinct clients can't exhaust memory. */
    static final int MAX_KEYS = 100_000;
    private static final long CLEANUP_INTERVAL_MILLIS = 60_000;

    private record Window(long startMillis, int count) {}

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupMillis = new AtomicLong();

    FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Records one request for {@code key}.
     *
     * @return 0 if allowed, otherwise the number of seconds until the caller should retry
     */
    long tryAcquire(String key) {
        long now = clock.millis();
        cleanupIfDue(now);

        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            // Under a flood of distinct clients, fail closed for new ones rather than grow unbounded.
            return Math.max(1, CLEANUP_INTERVAL_MILLIS / 1000);
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

    int trackedKeys() {
        return windows.size();
    }

    /** Drops expired windows at most once per interval, so the scan never runs per request. */
    private void cleanupIfDue(long now) {
        long last = lastCleanupMillis.get();
        if (now - last >= CLEANUP_INTERVAL_MILLIS && lastCleanupMillis.compareAndSet(last, now)) {
            windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
        }
    }
}
