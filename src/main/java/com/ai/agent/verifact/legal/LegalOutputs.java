package com.ai.agent.verifact.legal;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Shapes the model returns as JSON for LegalFact. Lenient on purpose: everything is validated and
 * normalised by {@link CaseIntelligenceService} before anyone sees it. Public for the schema generator.
 */
public final class LegalOutputs {

    private LegalOutputs() {
    }

    /** Step 1: structure the user's own description. Nothing here is verified. */
    public record CaseIntake(
            @JsonPropertyDescription("1-2 values from: EMPLOYMENT, HOUSING, FAMILY, CONSUMER, PERSONAL_INJURY, CRIMINAL, IMMIGRATION, DEBT_AND_BANKRUPTCY, WILLS_AND_ESTATES, BUSINESS_AND_CONTRACTS, REAL_ESTATE, INTELLECTUAL_PROPERTY, CIVIL_RIGHTS, OTHER")
            List<String> practiceAreas,
            JurisdictionGuess jurisdiction,
            @JsonPropertyDescription("2-4 neutral sentences restating what the person describes, attributed to them (\"The person says...\"). No legal conclusions")
            String summary,
            List<StatedFact> facts,
            List<StatedEvent> timeline,
            List<IssueToResearch> issues,
            List<MissingItem> missingInformation,
            List<StatedConflict> conflicts) {}

    public record StatedConflict(
            @JsonPropertyDescription("One neutral sentence naming what the description says inconsistently, e.g. \"Whether the person quit or was fired\"")
            String description,
            @JsonPropertyDescription("The exact words from the description for each version, copied verbatim (at least two)")
            List<String> quotes) {}

    public record JurisdictionGuess(
            @JsonPropertyDescription("Country as stated or clearly implied, e.g. \"US\". Empty if not stated") String country,
            @JsonPropertyDescription("US state name if exactly one state is clearly indicated. Empty if none, unclear, or several") String state,
            @JsonPropertyDescription("The exact words from the description that indicate the place. Empty if none") String basisQuote) {}

    public record StatedFact(
            @JsonPropertyDescription("One fact as the person states it, in neutral words") String statement,
            @JsonPropertyDescription("The exact words from the description this fact comes from, copied verbatim") String quote,
            @JsonPropertyDescription("The date or time as the person wrote it (e.g. \"March 3\", \"last spring\"). Empty if none") String date) {}

    public record StatedEvent(
            @JsonPropertyDescription("The date or time as the person wrote it. Empty if none") String date,
            @JsonPropertyDescription("True if the person's wording is approximate (about, around, early, last month...)") boolean approximate,
            @JsonPropertyDescription("What happened, in neutral words") String event,
            @JsonPropertyDescription("The exact words from the description, copied verbatim") String quote) {}

    public record IssueToResearch(
            @JsonPropertyDescription("A legal topic a professional may want to look at, as a neutral topic (e.g. \"Final paycheck timing\"). Not a conclusion")
            String topic,
            @JsonPropertyDescription("One neutral sentence on why the topic may be relevant to what the person describes")
            String note,
            @JsonPropertyDescription("1-2 short keyword queries to find official statutes, regulations or agency guidance on the topic for the jurisdiction")
            List<String> searchQueries) {}

    public record MissingItem(
            @JsonPropertyDescription("Information a professional would likely need that the description does not give") String item,
            @JsonPropertyDescription("One short sentence on what a reviewer would learn from it. Never say whether rules were met or violated")
            String whyItMatters) {}

    /** Step 2: which retrieved official sources bear on each issue. */
    public record SourceReview(List<IssueSources> issues, List<String> uncertainties) {}

    public record IssueSources(
            @JsonPropertyDescription("The issue ID exactly as given, e.g. I1") String issueId,
            List<SourceNote> sources) {}

    public record SourceNote(
            @JsonPropertyDescription("A source ID from the evidence list, e.g. E2") String sourceId,
            @JsonPropertyDescription("At most two sentences on what the source itself says, using only its title and excerpt")
            String whatItSays,
            @JsonPropertyDescription("One sentence on why a professional might consult it for this issue, in general terms")
            String relevance) {}
}
