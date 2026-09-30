package com.ai.agent.verifact.research;

import java.util.List;

/**
 * A published work as the scholarly indexes describe it. The abstract is used for checking only:
 * it is never stored or returned whole (abstracts can be copyrighted), only short verbatim quotes.
 *
 * @param notices     editorial notices attached to the work, e.g. "retraction", "expression_of_concern", "correction"
 * @param abstractText null when no index has one
 */
public record ScholarlyWork(String doi, String title, List<String> authors, Integer year, String venue,
                            String publisher, Integer citedByCount, boolean retracted, List<String> notices,
                            String abstractText) {

    public String url() {
        return doi == null ? null : "https://doi.org/" + doi;
    }

    ScholarlyWork withIndexData(boolean isRetracted, String abstractFromIndex, Integer cites, String venueFromIndex) {
        return new ScholarlyWork(doi, title, authors, year, venue != null ? venue : venueFromIndex, publisher,
                cites != null ? cites : citedByCount, retracted || isRetracted, notices,
                abstractText != null ? abstractText : abstractFromIndex);
    }
}
