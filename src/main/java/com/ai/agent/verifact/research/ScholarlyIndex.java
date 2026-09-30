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

    class ScholarlyIndexUnavailableException extends RuntimeException {
        public ScholarlyIndexUnavailableException(String message) {
            super(message);
        }
    }
}
