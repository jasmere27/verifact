package com.ai.agent.verifact.verification;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

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

    public record ClaimVerdict(
            @JsonPropertyDescription("The claim's ID exactly as given, e.g. C1") String claimId,
            @JsonPropertyDescription("Exactly one of: SUPPORTED, PARTLY_SUPPORTED, MISLEADING, CONTRADICTED, INSUFFICIENT_EVIDENCE")
            String verdict,
            @JsonPropertyDescription("IDs of evidence items that support the claim, e.g. [\"E1\"]") List<String> supportingEvidenceIds,
            @JsonPropertyDescription("IDs of evidence items that contradict the claim") List<String> contradictingEvidenceIds,
            @JsonPropertyDescription("At most two plain-language sentences") String explanation) {}
}
