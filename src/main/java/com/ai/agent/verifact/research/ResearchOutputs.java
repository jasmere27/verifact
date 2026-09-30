package com.ai.agent.verifact.research;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Shapes the model returns for ResearchFact; validated by {@link ResearchCheckService}. Public for the schema generator. */
public final class ResearchOutputs {

    private ResearchOutputs() {
    }

    /** Step 1: the references and the claims that cite them, as written in the text. */
    public record CitationExtraction(List<ExtractedReference> references, List<CitedClaim> claims) {}

    public record ExtractedReference(
            @JsonPropertyDescription("R1, R2, ... in order of first appearance") String id,
            @JsonPropertyDescription("The reference exactly as written in the text (reference-list entry, or the in-text citation if there is no list), copied verbatim")
            String text,
            @JsonPropertyDescription("The DOI if the text gives one, copied exactly; empty otherwise. Never invent a DOI") String doi,
            @JsonPropertyDescription("The cited work's title if the text gives it; empty otherwise") String title,
            @JsonPropertyDescription("First author's surname if given; empty otherwise") String firstAuthor,
            @JsonPropertyDescription("Publication year if given; empty otherwise") String year) {}

    public record CitedClaim(
            @JsonPropertyDescription("The sentence or clause making the claim, copied verbatim from the text") String quote,
            @JsonPropertyDescription("The claim restated so it stands alone") String claim,
            @JsonPropertyDescription("IDs of the references this claim cites, e.g. [\"R2\"]") List<String> referenceIds,
            @JsonPropertyDescription("A short keyword query to find other research on the claim") String searchQuery) {}

    /** Step 2: does each cited abstract support its claim; does related research conflict? */
    public record SupportReview(List<ClaimReview> claims) {}

    public record ClaimReview(
            @JsonPropertyDescription("The claim ID exactly as given, e.g. C1") String claimId,
            @JsonPropertyDescription("Exactly one of: SUPPORTED, PARTIALLY_SUPPORTED, CONTRADICTED, NOT_ADDRESSED_IN_ABSTRACT")
            String support,
            @JsonPropertyDescription("ID of the cited work whose abstract the verdict is based on, e.g. R2") String evidenceFrom,
            @JsonPropertyDescription("Exact words from that abstract that support or contradict the claim, copied verbatim (one sentence at most). Empty for NOT_ADDRESSED_IN_ABSTRACT")
            String evidenceQuote,
            @JsonPropertyDescription("One neutral sentence explaining the verdict") String note,
            List<Conflict> conflicts) {}

    public record Conflict(
            @JsonPropertyDescription("ID of a related work, e.g. P3") String paperId,
            @JsonPropertyDescription("Exact words from that work's abstract that conflict with the claim, copied verbatim") String quote,
            @JsonPropertyDescription("One neutral sentence on how it differs") String note) {}
}
