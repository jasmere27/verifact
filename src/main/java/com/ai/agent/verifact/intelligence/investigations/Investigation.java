package com.ai.agent.verifact.intelligence.investigations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Investigation read models. A result separates four kinds of statement: the allegation as submitted
 * ({@code ClaimResult.claim}/{@code originalWords}), verified source words ({@link Excerpt}: checked word for word
 * against what was retrieved), AI interpretation ({@code aiExplanation}, labelled), and unresolved questions.
 */
public final class Investigation {

    private Investigation() {
    }

    /** Nuanced outcomes; nothing is forced into true/false. UNDER_INVESTIGATION is shown before the first result. */
    public enum Assessment { SUPPORTED, CONTRADICTED, MIXED, INSUFFICIENT_EVIDENCE, MISSING_CONTEXT, UNDER_INVESTIGATION }

    /** A source's own words, verified to appear in the retrieved text of that source. */
    public record Excerpt(String sourceId, String text, String url, String domain, String title) {}

    /**
     * @param originalWords the submitted text's own words for the claim
     * @param verdict       the underlying evidence verdict (SUPPORTED, PARTLY_SUPPORTED, MISLEADING, CONTRADICTED, INSUFFICIENT_EVIDENCE)
     * @param aiExplanation the model's explanation, kept only when grounded in the claim and excerpts
     */
    public record ClaimResult(String id, String type, String claim, String originalWords, String speaker, Assessment assessment,
                              String verdict, Excerpt supporting, Excerpt contradicting, boolean sourcesConflict, String contextIssue,
                              String quoteStatus, Excerpt quoteSource, String aiExplanation) {}

    /** @param direction relative to the claims that cite it: SUPPORTS, CONTRADICTS, MIXED or CONTEXT (retrieved, not cited) */
    public record Source(String id, String url, String domain, String title, String publishedDate, Instant retrievedAt,
                         String sourceType, String snippet, String direction, List<String> claimIds) {}

    public record TimelineEntry(String date, String label, String sourceId) {}

    public record Result(int version, Instant createdAt, String inputUrl, String inputTitle, List<ClaimResult> claims,
                         List<Source> sources, List<TimelineEntry> chronology, List<String> unresolved, List<String> limitations,
                         String method, String searchProvider, long durationMs) {}

    public record ClaimChange(String claim, String before, String after) {}

    public record Changes(int previousVersion, List<ClaimChange> claims, List<String> newSources, List<String> droppedSources,
                          String summary) {}

    public record VersionSummary(int version, Instant createdAt, int claims, int sources, Changes changes) {}

    public record Summary(UUID id, String title, String status, int version, UUID incidentId, String incidentTitle,
                          String headline, Instant createdAt, Instant updatedAt, Instant lastReviewedAt) {}

    /** @param headline the main claim's assessment, or null before the first result */
    public record Detail(Summary summary, String input, String errorMessage, String analystNotes, Result result,
                         Changes changes, List<VersionSummary> versions) {}

    public record Page(List<Summary> items, int page, int size, long total) {}
}
