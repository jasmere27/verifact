package com.ai.agent.verifact.verification;

import java.util.List;

/**
 * Shapes the model is asked to return as JSON. Kept lenient (strings, nullable lists) because
 * model output is validated and normalised by {@link VerificationService} before use.
 * Public so the JSON schema generator and Jackson can access them.
 */
public final class ModelOutputs {

    private ModelOutputs() {
    }

    /** Step 1: checkable claims found in the content, each with search queries. */
    public record ClaimExtraction(List<ExtractedClaim> claims) {}

    public record ExtractedClaim(String claim, List<String> searchQueries) {}

    /** Step 2: evidence-based assessment of the extracted claims. */
    public record Assessment(String summary, List<ClaimVerdict> claims, List<String> limitations) {}

    public record ClaimVerdict(String claimId, String verdict, List<String> supportingEvidenceIds,
                               List<String> contradictingEvidenceIds, String explanation) {}
}
