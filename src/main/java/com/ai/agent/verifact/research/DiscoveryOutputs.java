package com.ai.agent.verifact.research;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Shapes the model returns for discovery; validated by {@link ResearchDiscoveryService}. */
public final class DiscoveryOutputs {

    private DiscoveryOutputs() {
    }

    public record SearchPlan(
            @JsonPropertyDescription("1-3 short keyword search queries for scholarly databases (English), specific to the topic")
            List<String> queries,
            @JsonPropertyDescription("THEORIES/CONCEPTS/METHODS only: up to 4 well-established names (e.g. \"Technology Acceptance Model\"); empty otherwise")
            List<String> names) {}

    public record RelevanceReview(List<WorkNote> works) {}

    public record WorkNote(
            @JsonPropertyDescription("The work ID exactly as given, e.g. W3") String workId,
            @JsonPropertyDescription("True if the abstract is relevant to the topic (or, for a claim, addresses it)") boolean relevant,
            @JsonPropertyDescription("Claim searches only: SUPPORTS, CONTRADICTS or NEITHER; empty otherwise") String stance,
            @JsonPropertyDescription("One sentence on how this study relates to the student's topic or claim") String why,
            @JsonPropertyDescription("Exact words from the abstract that show the relevance or stance, copied verbatim (one sentence at most)")
            String quote) {}
}
