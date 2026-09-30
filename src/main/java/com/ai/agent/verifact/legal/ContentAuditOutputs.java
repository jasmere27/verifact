package com.ai.agent.verifact.legal;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Shapes the model returns for a legal content audit; validated by {@link ContentAuditService}. */
public final class ContentAuditOutputs {

    private ContentAuditOutputs() {
    }

    public record StatementExtraction(
            @JsonPropertyDescription("US state the page's legal statements are about, if the page says so; empty if unclear")
            String state,
            List<LegalStatement> statements) {}

    public record LegalStatement(
            @JsonPropertyDescription("The page's exact words stating the rule, copied verbatim")
            String quote,
            @JsonPropertyDescription("The rule restated so it stands alone (who, what, amount/deadline, jurisdiction)")
            String statement,
            @JsonPropertyDescription("1-2 keyword queries to find the official statute, regulation or agency page on it")
            List<String> searchQueries) {}

    public record StatementReview(List<StatementCheck> statements) {}

    public record StatementCheck(
            @JsonPropertyDescription("The statement ID exactly as given, e.g. S1") String statementId,
            @JsonPropertyDescription("Exactly one of: CONSISTENT_WITH_SOURCES, POTENTIALLY_OUTDATED, POTENTIALLY_UNSUPPORTED, REQUIRES_REVIEW, UNABLE_TO_VERIFY")
            String status,
            @JsonPropertyDescription("IDs of the sources the status is based on, e.g. [\"E2\"]") List<String> sourceIds,
            @JsonPropertyDescription("What the cited source's excerpt says on this point, using only the excerpt")
            String sourceSays,
            @JsonPropertyDescription("For POTENTIALLY_OUTDATED or POTENTIALLY_UNSUPPORTED only: the exact words from one cited excerpt that conflict with the statement, copied verbatim. Empty otherwise")
            String conflictingExcerpt,
            @JsonPropertyDescription("One neutral sentence explaining the status for an editor. No legal advice")
            String note) {}
}
