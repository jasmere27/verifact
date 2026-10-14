package com.ai.agent.verifact.video;

import java.util.List;

/** Finds public videos by text. Implementations must fail loudly ({@link VideoSearchException}) rather than return nothing on an outage. */
public interface VideoSearch {

    boolean enabled();

    List<FoundVideo> search(String query, int max);

    /** Only videos uploaded after {@code publishedAfter} (null = any date). */
    default List<FoundVideo> search(String query, int max, java.time.Instant publishedAfter) {
        return search(query, max);
    }

    class VideoSearchException extends RuntimeException {
        private final boolean quotaExceeded;

        public VideoSearchException(String message, boolean quotaExceeded) {
            super(message);
            this.quotaExceeded = quotaExceeded;
        }

        public boolean quotaExceeded() {
            return quotaExceeded;
        }
    }
}
