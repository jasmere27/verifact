package com.ai.agent.verifact.research;

import java.util.List;

/**
 * Student Research Mode: sources found for a topic (or a paragraph/claim) in one category. Every
 * source is a real record from a scholarly index; the model only plans searches and explains
 * relevance, and an explanation is shown only with a verbatim quote from the source's abstract.
 */
public record Discovery(Category category, String topic, List<String> searches, List<FoundSource> sources,
                        List<Lead> leads, List<String> limitations, String notice, long durationMs) {

    public enum Category {
        /** Review of Related Literature: reviews and conceptual work on the topic. */
        RRL,
        /** Review of Related Studies: empirical studies on the topic. */
        RRS,
        /** Studies with at least one author at an institution in the chosen country. */
        LOCAL,
        /** Studies with no author in the chosen country. */
        FOREIGN,
        THEORIES,
        CONCEPTS,
        METHODS,
        /** Last five years. */
        RECENT,
        /** Sources for a pasted paragraph or claim. */
        FOR_TEXT,
        SUPPORTING,
        CONTRADICTING
    }

    public enum Verification { VERIFIED, UNVERIFIED }

    public enum Stance { SUPPORTS, CONTRADICTS }

    /**
     * @param key            stable id for saving: the DOI, else the OpenAlex id
     * @param countries      authors' institution countries (ISO codes)
     * @param local          an author is in the workspace's country
     * @param relevance      why it's relevant (AI interpretation), only with {@code relevanceQuote}
     * @param relevanceQuote verbatim from the abstract (≤300 chars)
     * @param stance         for SUPPORTING/CONTRADICTING searches only
     */
    public record FoundSource(String key, String doi, String url, String title, List<String> authors, Integer year,
                              String venue, String type, List<String> countries, boolean local, boolean retracted,
                              Integer citedByCount, boolean hasAbstract, String relevance, String relevanceQuote,
                              Stance stance, Verification verification) {}

    /**
     * A theory, concept or method name the model suggested. VERIFIED only when a found source's title
     * or abstract names it; otherwise it is shown as an unverified suggestion with no sources.
     */
    public record Lead(String name, Verification verification, List<String> sourceKeys) {}
}
