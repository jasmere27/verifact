package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.List;

/**
 * A student's uploaded draft in a workspace: the extracted text (the file itself is never stored) and
 * what we found in it. Statements are verbatim spans of the draft; the summary keeps only sentences
 * whose numbers appear in the draft.
 *
 * @param citationCheckText the reference list plus the draft's sentences with in-text citations, sized for
 *                          the existing citation check; null when no reference list was found
 */
public record Draft(String fileName, DocumentExtractor.Kind kind, int pages, int chars, boolean truncated, Instant uploadedAt,
                    String text, String summary, List<String> concepts, List<Statement> needsCitation,
                    int referenceEntries, int inTextCitations, String citationCheckText, List<String> limitations) {

    /** A sentence in the draft that makes a claim without a citation. {@code quote} is the draft's own words. */
    public record Statement(String quote, String why) {}
}
