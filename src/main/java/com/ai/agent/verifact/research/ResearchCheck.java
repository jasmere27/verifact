package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ResearchFact's citation-integrity report: does each reference exist and match, is it retracted,
 * does the cited paper's abstract support the claim, and does related research conflict. Nothing is
 * stored; abstracts appear only as short verbatim quotes with links.
 */
public record ResearchCheck(Instant createdAt, List<CheckedReference> references, List<CheckedClaim> claims,
                            Map<ReferenceStatus, Integer> referenceCounts, Map<Support, Integer> supportCounts,
                            List<String> limitations, String notice, long durationMs) {

    public enum ReferenceStatus {
        /** Found in Crossref, details match what the text says. */
        VERIFIED,
        /** Found, but the year, first author or title differs from the text. */
        FOUND_WITH_DIFFERENCES,
        /** Found, and a retraction (or removal/withdrawal) notice is attached. */
        RETRACTED,
        /** No matching record: possibly fabricated, or not indexed (books, reports, some preprints). */
        NOT_FOUND,
        /** The lookup itself failed; says nothing about the reference. */
        LOOKUP_FAILED
    }

    public enum Support {
        SUPPORTED,
        /** The claim has several parts and the abstract supports only some. */
        PARTIALLY_SUPPORTED,
        /** The abstract reports something related but weaker or narrower than the claim. */
        OVERSTATED,
        CONTRADICTED, NOT_ADDRESSED_IN_ABSTRACT,
        /** The cited work was found but no index has its abstract. */
        NO_ABSTRACT,
        /** The cited reference wasn't found (or the lookup failed), so support can't be checked. */
        CITATION_PROBLEM,
        /** The model's verdict couldn't be backed by a verbatim quote from the abstract. */
        NEEDS_REVIEW
    }

    /**
     * @param differences what differs between the text and the record, e.g. "year: 2019 in the text, 2021 in the record"
     * @param work        null when not found or the lookup failed
     */
    public record CheckedReference(String id, String textAsWritten, ReferenceStatus status, List<String> differences,
                                   WorkSummary work) {}

    /** @param notices editorial notices, e.g. "retraction", "correction", "expression_of_concern" */
    public record WorkSummary(String doi, String url, String title, List<String> authors, Integer year, String venue,
                              String publisher, Integer citedByCount, List<String> notices, boolean hasAbstract) {}

    /**
     * @param evidenceQuote verbatim from the cited abstract (≤300 chars), or null
     * @param evidenceFrom  the reference ID the quote comes from
     */
    public record CheckedClaim(String id, String quote, String claim, List<String> referenceIds, Support support,
                               String evidenceFrom, String evidenceQuote, String note, List<ConflictingWork> conflicting) {}

    /** @param quote verbatim from that work's abstract (≤300 chars) */
    public record ConflictingWork(WorkSummary work, String quote, String note) {}
}
