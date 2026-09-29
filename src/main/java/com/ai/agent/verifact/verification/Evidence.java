package com.ai.agent.verifact.verification;

import java.time.Instant;

/**
 * A search result retrieved by the backend during a verification. Only these may be cited.
 *
 * @param id            stable within one verification, e.g. "E1"
 * @param publishedDate as reported by the search provider, or null if unknown
 * @param sourceType    derived from the URL; null on reports stored before it existed
 */
public record Evidence(String id, String url, String domain, String title, String snippet,
                       String publishedDate, Instant retrievedAt, SourceType sourceType) {

    boolean isSocial() {
        return sourceType == SourceType.SOCIAL;
    }
}
