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

    /** Step 1 for images: what the image shows, plus the same claims-and-queries as {@link ClaimExtraction}. */
    public record ImageExtraction(
            @JsonPropertyDescription("The legible text in the image, top to bottom, verbatim and in its original language, up to about 1500 characters. Empty if none")
            String visibleText,
            @JsonPropertyDescription("Exactly one of: SOCIAL_MEDIA_POST, NEWS_HEADLINE, ARTICLE, CHART, MEME, PHOTO, DOCUMENT, OTHER")
            String imageKind,
            @JsonPropertyDescription("The account, person, outlet or organisation the image presents as its author or source, exactly as written in the image. Empty if none is written")
            String shownSource,
            @JsonPropertyDescription("A date or time written in the image, exactly as written. Empty if none")
            String shownDate,
            @JsonPropertyDescription("One or two neutral sentences on what the image shows. Do not judge whether it is true or genuine")
            String description,
            List<ExtractedClaim> claims) {}

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
