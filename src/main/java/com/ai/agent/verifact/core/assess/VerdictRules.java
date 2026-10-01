package com.ai.agent.verifact.core.assess;

import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Grounding;

/**
 * When a model's verdict may stand. Verdicts are decided from sources' own words, never the model's memory:
 * SUPPORTED/PARTLY need a supporting excerpt, CONTRADICTED a contradicting one, MISLEADING either; otherwise
 * the claim is INSUFFICIENT_EVIDENCE.
 */
public final class VerdictRules {

    private VerdictRules() {
    }

    public static Verdict gate(Verdict verdict, SourceExcerpt supporting, SourceExcerpt contradicting) {
        return switch (verdict) {
            case SUPPORTED, PARTLY_SUPPORTED -> supporting != null ? verdict : Verdict.INSUFFICIENT_EVIDENCE;
            case CONTRADICTED -> contradicting != null ? verdict : Verdict.INSUFFICIENT_EVIDENCE;
            case MISLEADING -> supporting != null || contradicting != null ? verdict : Verdict.INSUFFICIENT_EVIDENCE;
            case INSUFFICIENT_EVIDENCE -> verdict;
        };
    }

    /** A flag about a claim (e.g. outdated, misattributed) also needs a source's own words, either way. */
    public static boolean flagBacked(SourceExcerpt supporting, SourceExcerpt contradicting) {
        return supporting != null || contradicting != null;
    }

    /** One source backs the claim and a different one contradicts it. */
    public static boolean sourcesConflict(SourceExcerpt supporting, SourceExcerpt contradicting) {
        return supporting != null && contradicting != null && !supporting.sourceId().equals(contradicting.sourceId());
    }

    /**
     * The model's explanation, kept only if what it states (numbers, names) is in the claim or the cited excerpts.
     *
     * @return the explanation, or null when it's missing, blank or not grounded
     */
    public static String groundedExplanation(String explanation, String claim, SourceExcerpt supporting, SourceExcerpt contradicting) {
        if (explanation == null || explanation.isBlank()) {
            return null;
        }
        String material = Grounding.material(claim, supporting == null ? "" : supporting.excerpt(),
                contradicting == null ? "" : contradicting.excerpt());
        return Grounding.supported(explanation, material) ? explanation : null;
    }
}
