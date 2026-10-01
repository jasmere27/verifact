package com.ai.agent.verifact.core.assess;

import com.ai.agent.verifact.core.claims.ClaimGrounder;
import com.ai.agent.verifact.core.provenance.CitationValidator;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a model's assessment of a claim into a finding the evidence backs. The model judges; the code decides
 * what of that judgement may be shown.
 */
public final class EvidenceAssessor {

    /** Shortest run of a source's words accepted as an excerpt. */
    static final int EXCERPT_MIN_WORDS = 5;
    static final int MAX_EXPLANATION_CHARS = 400;

    private EvidenceAssessor() {
    }

    /**
     * @param claim  the claim as checked
     * @param review the model's assessment, or null if it gave none (→ INSUFFICIENT_EVIDENCE)
     */
    public static ClaimFinding assess(String claim, ReviewCandidate review, List<Evidence> sources, int maxExcerptChars) {
        if (review == null) {
            return new ClaimFinding(Verdict.INSUFFICIENT_EVIDENCE, null, null, false, false, null);
        }
        Map<String, Evidence> byId = CitationValidator.byId(sources);
        SourceExcerpt supporting = CitationValidator.verbatim(review.supportingSourceId(), review.supportingExcerpt(), byId,
                EXCERPT_MIN_WORDS, maxExcerptChars);
        SourceExcerpt contradicting = CitationValidator.verbatim(review.contradictingSourceId(), review.contradictingExcerpt(),
                byId, EXCERPT_MIN_WORDS, maxExcerptChars);
        Verdict verdict = VerdictRules.gate(Verdict.parse(review.verdict()), supporting, contradicting);
        String explanation = VerdictRules.groundedExplanation(ClaimGrounder.clean(review.explanation(), MAX_EXPLANATION_CHARS),
                claim, supporting, contradicting);
        return new ClaimFinding(verdict, supporting, contradicting, VerdictRules.flagBacked(supporting, contradicting),
                VerdictRules.sourcesConflict(supporting, contradicting), explanation);
    }

    /** The model's answers by claim id ("c2" → "C2"); the first answer for an id wins, answers without an id are ignored. */
    public static <T> Map<String, T> byClaimId(List<T> answers, Function<T, String> claimId) {
        Map<String, T> out = new LinkedHashMap<>();
        if (answers == null) {
            return out;
        }
        for (T a : answers) {
            String id = a == null ? null : claimId.apply(a);
            if (id != null) {
                out.putIfAbsent(id.trim().toUpperCase(Locale.ROOT), a);
            }
        }
        return out;
    }
}
