package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.List;

/**
 * What ResearchFact found in a research paper a student uploaded (ADR-23). The record match comes from the index
 * and is labelled VERIFIED only when that record's title appears at the start of the file; everything else is the
 * AI's reading of the paper, and every finding, method detail and stated limitation carries a verbatim quote from
 * the file (numbers in a statement must appear in its quote).
 *
 * @param plainSummary plain-language explanation (AI), sentences with numbers not in the paper removed
 */
public record PaperAnalysis(Instant analyzedAt, Match match, String plainSummary, List<Quoted> findings, List<MethodPart> method,
                            List<Quoted> authorLimitations, List<String> limitations, String notice) {

    public enum MatchStatus {
        /** An index record whose title appears at the start of the file. */
        VERIFIED,
        /** A close index record, but the file doesn't confirm it: check the details. */
        POSSIBLE,
        NOT_FOUND,
        /** The index couldn't be reached (not the same as not found). */
        LOOKUP_FAILED
    }

    /**
     * @param source the index record (null unless VERIFIED or POSSIBLE and the index has it)
     * @param basis  how it was matched, e.g. "DOI printed in the paper"
     */
    public record Match(MatchStatus status, Discovery.FoundSource source, String basis) {}

    /** A statement about the paper (AI wording) and the paper's own words it rests on. */
    public record Quoted(String statement, String quote) {}

    public enum Aspect { DESIGN, PARTICIPANTS, SETTING, INSTRUMENTS, ANALYSIS }

    public record MethodPart(Aspect aspect, String statement, String quote) {}
}
