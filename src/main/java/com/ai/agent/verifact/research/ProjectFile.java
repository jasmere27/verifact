package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.UUID;

/**
 * A file in a capstone project (ADR-23): a chapter draft (existing draft analysis) or a research paper
 * ({@link PaperAnalysis}). The original file is never stored, only its extracted text and the analysis.
 *
 * @param label the student's label, e.g. "Chapter 2" or "Reyes 2023"
 */
public record ProjectFile(UUID id, Kind kind, String label, String fileName, DocumentExtractor.Kind docKind, int pages, int chars,
                          boolean truncated, Instant uploadedAt, String text, Draft draft, PaperAnalysis paper) {

    public enum Kind { DRAFT, PAPER }

    /**
     * What the project page and next steps need, without the text.
     *
     * @param needsCitation drafts: statements that may need a citation
     * @param match         papers: how the paper matched an index record
     * @param matchedKey    papers: the record's key (DOI) when it can be saved to the library
     * @param title         papers: the verified record's title, else null
     */
    public record Summary(UUID id, Kind kind, String label, String fileName, Instant uploadedAt, int pages, Integer needsCitation,
                          int referenceEntries, PaperAnalysis.MatchStatus match, String matchedKey, String title, int findings) {}

    Summary summary() {
        if (kind == Kind.DRAFT) {
            return new Summary(id, kind, label, fileName, uploadedAt, pages, draft == null || draft.needsCitation() == null ? 0 : draft.needsCitation().size(),
                    draft == null ? 0 : draft.referenceEntries(), null, null, null, 0);
        }
        PaperAnalysis.Match m = paper == null ? null : paper.match();
        boolean usable = m != null && m.source() != null
                && (m.status() == PaperAnalysis.MatchStatus.VERIFIED || m.status() == PaperAnalysis.MatchStatus.POSSIBLE);
        return new Summary(id, kind, label, fileName, uploadedAt, pages, null, 0, m == null ? null : m.status(),
                usable ? m.source().key() : null, m != null && m.status() == PaperAnalysis.MatchStatus.VERIFIED && m.source() != null ? m.source().title() : null,
                paper == null || paper.findings() == null ? 0 : paper.findings().size());
    }
}
