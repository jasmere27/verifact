package com.ai.agent.verifact.news;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Shapes the model returns for NewsFact; validated by {@link NewsCheckService}. Public for the schema generator. */
public final class NewsOutputs {

    private NewsOutputs() {
    }

    /** Step 1: the checkable claims in the article. */
    public record ClaimExtraction(
            @JsonPropertyDescription("The article's date of publication if the text states it, as written; empty otherwise")
            String articleDate,
            List<ArticleClaim> claims) {}

    public record ArticleClaim(
            @JsonPropertyDescription("Exactly one of: FACT, STATISTIC, QUOTE, DATE_TIME, ATTRIBUTION") String type,
            @JsonPropertyDescription("The sentence or clause from the article, copied verbatim") String quote,
            @JsonPropertyDescription("The claim restated so it stands alone (who, what, when, where)") String claim,
            @JsonPropertyDescription("QUOTE and ATTRIBUTION only: who the article says said it, as written; empty otherwise") String speaker,
            @JsonPropertyDescription("QUOTE only: the exact words inside the quotation marks, copied verbatim; empty otherwise") String quotedWords,
            @JsonPropertyDescription("1-2 short keyword web search queries to verify the claim (for quotes, include the speaker)")
            List<String> searchQueries) {}

    /** Step 2: verdicts from retrieved sources, with the sources' own words. */
    public record ClaimReviews(List<ClaimReview> claims) {}

    public record ClaimReview(
            @JsonPropertyDescription("The claim ID exactly as given, e.g. C1") String claimId,
            @JsonPropertyDescription("Exactly one of: SUPPORTED, PARTLY_SUPPORTED, MISLEADING, CONTRADICTED, INSUFFICIENT_EVIDENCE")
            String verdict,
            @JsonPropertyDescription("A source ID that supports the claim, e.g. E2; empty if none") String supportingSourceId,
            @JsonPropertyDescription("Exact words from that source's excerpt that support the claim, copied verbatim; empty if none")
            String supportingExcerpt,
            @JsonPropertyDescription("A source ID that contradicts or complicates the claim; empty if none") String contradictingSourceId,
            @JsonPropertyDescription("Exact words from that source's excerpt that contradict or complicate the claim, copied verbatim; empty if none")
            String contradictingExcerpt,
            @JsonPropertyDescription("Exactly one of: NONE, OUTDATED, OLD_EVENT_AS_NEW, MISSING_CONTEXT, MISATTRIBUTED")
            String contextIssue,
            @JsonPropertyDescription("At most two plain sentences for the editor, referring to what the sources say") String explanation) {}

    public record VideoReview(List<VideoNote> videos) {}

    public record VideoNote(
            @JsonPropertyDescription("The video's id, e.g. V2") String videoId,
            @JsonPropertyDescription("True only if the title/description are about this claim's event or subject") boolean relevant,
            @JsonPropertyDescription("Exactly one of: SUPPORTS, CONTRADICTS, CONTEXT") String stance,
            @JsonPropertyDescription("Exactly one of: NEWS_REPORT, OFFICIAL, EYEWITNESS, OTHER (from the channel name and description)")
            String kind,
            @JsonPropertyDescription("One plain sentence on how the video relates to the claim") String why,
            @JsonPropertyDescription("Words copied verbatim from the video's title or description that your sentence rests on") String quote,
            @JsonPropertyDescription("Up to 3 claims the video's title/description makes, each copied verbatim") List<String> claimsMade,
            @JsonPropertyDescription("A chapter label from the video's CHAPTERS list that covers the claim, copied exactly; or null")
            String chapter) {}
}
