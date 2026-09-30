package com.ai.agent.verifact.research;

import java.util.List;
import java.util.Optional;

/**
 * Scholarly metadata lookups (Crossref + OpenAlex). The only way ResearchFact reaches external
 * indexes; the model never does. Implementations call fixed hosts, never user-supplied URLs.
 */
public interface ScholarlyIndex {

    /** @throws ScholarlyIndexUnavailableException if the lookup itself failed (not the same as "not found") */
    Optional<ScholarlyWork> byDoi(String doi);

    /** Candidate works for a free-text reference ("Smith J, 2020, Title, Journal"), best first. */
    List<ScholarlyWork> byReference(String referenceText, int rows);

    /** Works with abstracts related to a query, for "does other research conflict?". */
    List<ScholarlyWork> related(String query, int rows);

    /**
     * Discovery search (Student Research Mode).
     *
     * @param countryCode only works with an author at an institution in this country (e.g. "PH"); null for any
     * @param fromYear    earliest publication year; null for any
     * @param type        OpenAlex work type (e.g. "review", "article"); null for any
     * @param byCitations order by citation count instead of relevance
     */
    /** One work by its DOI or OpenAlex id ("https://openalex.org/W123"), for re-verifying saved sources. */
    default Optional<DiscoveredWork> work(String key) {
        return Optional.empty();
    }

    default List<DiscoveredWork> discover(String query, String countryCode, Integer fromYear, String type, boolean byCitations,
                                          int rows) {
        return List.of();
    }

    class ScholarlyIndexUnavailableException extends RuntimeException {
        public ScholarlyIndexUnavailableException(String message) {
            super(message);
        }
    }
}
