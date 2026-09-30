package com.ai.agent.verifact.legal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A legal content audit of one page: each statement of law the page makes, compared with official
 * sources. Flags are for attorney/editor review, never a legal opinion that the page is wrong.
 *
 * @param statusCounts number of statements per status, for a one-line summary
 */
public record ContentAudit(String url, String pageTitle, Instant checkedAt, String jurisdiction,
                           List<AuditedStatement> statements, Map<Status, Integer> statusCounts,
                           List<CaseIntelligence.LegalSource> sources, List<String> limitations, String notice,
                           long durationMs) {

    public enum Status {
        /** Official sources say the same thing. */
        CONSISTENT_WITH_SOURCES,
        /** An official source states a different current rule, figure or date: likely out of date. */
        POTENTIALLY_OUTDATED,
        /** Official sources contradict it or don't back what it asserts. */
        POTENTIALLY_UNSUPPORTED,
        /** Partly matches, or depends on details: a human should look. */
        REQUIRES_REVIEW,
        /** No retrieved official source addresses it. */
        UNABLE_TO_VERIFY
    }

    /**
     * @param pageQuote  the page's exact words
     * @param sourceSays what the cited official source says, grounded in its excerpt; null if not usable
     * @param note       short neutral explanation (AI interpretation); null if it failed the checks
     */
    public record AuditedStatement(String id, String pageQuote, String statement, Status status,
                                   List<String> sourceIds, String sourceSays, String note) {}
}
