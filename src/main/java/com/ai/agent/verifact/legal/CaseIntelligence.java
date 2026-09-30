package com.ai.agent.verifact.legal;

import java.time.Instant;
import java.util.List;

/**
 * LegalFact's Case Intelligence report: the user's description, organised, with official sources
 * that may be relevant. Legal information for professional review, never legal advice. Not stored.
 * Every statement says where it comes from ({@link Basis}).
 */
public record CaseIntelligence(Instant createdAt, List<PracticeArea> practiceAreas, Jurisdiction jurisdiction,
                               String summary, List<Fact> keyFacts, List<TimelineEvent> timeline,
                               List<Conflict> conflicts, List<Issue> issues,
                               List<MissingInformation> missingInformation,
                               List<String> uncertainties, List<LegalSource> sources, String notice,
                               String searchProvider, long durationMs) {

    /** Where a statement comes from, shown next to it. */
    public enum Basis {
        /** The user's own account. Not verified. */
        USER_STATED,
        /** What a retrieved official source says (about the law, not about the user's situation). */
        SOURCE_BACKED,
        /** Written by the model to organise the information. */
        AI_INTERPRETATION
    }

    public record Jurisdiction(Status status, String country, String state, String stateName, String basisQuote) {

        public enum Status { IDENTIFIED, UNCERTAIN, OUTSIDE_US }
    }

    /** @param date as the user wrote it; null if not given or not found in their text */
    public record Fact(String statement, String userQuote, String date, Basis basis) {}

    /** @param date as the user wrote it; null = "Date not provided" */
    public record TimelineEvent(String date, boolean approximate, String event, String userQuote, Basis basis) {}

    public record Issue(String id, String topic, String note, Basis basis, List<SourceNote> sources) {}

    /** Where the person's account is inconsistent: a question for the reviewer to ask, not a finding. */
    public record Conflict(String description, List<String> userQuotes, Basis basis) {}

    /**
     * @param whatItSays     the model's summary of the source (SOURCE_BACKED); null if it failed the checks
     *                       (the UI shows the excerpt)
     * @param relevance      why a professional might consult it (AI_INTERPRETATION); null if it failed the checks
     */
    public record SourceNote(String sourceId, String whatItSays, Basis whatItSaysBasis, String relevance,
                             Basis relevanceBasis) {}

    public record MissingInformation(String item, String whyItMatters) {}

    public record LegalSource(String id, String url, String domain, String title, String excerpt,
                              String publishedDate, Instant retrievedAt, LegalSourceType type) {}
}
