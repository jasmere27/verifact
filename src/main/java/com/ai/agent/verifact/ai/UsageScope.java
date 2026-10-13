package com.ai.agent.verifact.ai;

import java.util.function.Supplier;

/**
 * Counts the model calls, tokens and web searches made by the current thread while a scope is open, so a
 * feature can record what one action cost (VeriFact Intelligence usage metering). Nothing is counted when no
 * scope is open, which is the case for every other product.
 */
public final class UsageScope {

    /** What one scope consumed. */
    public record Totals(int llmCalls, long promptTokens, long completionTokens, int searchCalls) {}

    private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();

    private UsageScope() {
    }

    /** Runs {@code work} with a fresh scope; {@code sink} receives the totals even when the work throws. */
    public static <T> T measure(Supplier<T> work, java.util.function.Consumer<Totals> sink) {
        long[] previous = CURRENT.get();
        long[] counters = new long[4];
        CURRENT.set(counters);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
                for (int i = 0; i < counters.length; i++) {
                    previous[i] += counters[i];
                }
            }
            sink.accept(new Totals((int) counters[0], counters[1], counters[2], (int) counters[3]));
        }
    }

    static void llmCall(long promptTokens, long completionTokens) {
        long[] c = CURRENT.get();
        if (c != null) {
            c[0]++;
            c[1] += Math.max(0, promptTokens);
            c[2] += Math.max(0, completionTokens);
        }
    }

    public static void searchCall() {
        long[] c = CURRENT.get();
        if (c != null) {
            c[3]++;
        }
    }
}
