package com.ai.agent.verifact.news;

import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.verification.Verdict;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NewsFact's automated check of one article: typed claims, each with a verdict backed by a source's
 * own words, a quote check done in code, date/context issues, and conflicts between sources. The
 * editor's decisions live separately in {@link NewsReview}.
 *
 * @param articleUrl null for pasted text
 * @param articleDate as the article states it, if it does
 */
public record NewsCheck(UUID id, Instant createdAt, String articleUrl, String articleTitle, String articleDate,
                        List<NewsClaim> claims, List<Evidence> sources, Map<Verdict, Integer> verdictCounts,
                        List<String> limitations, String notice, String searchProvider, long durationMs) {

    public enum ClaimType { FACT, STATISTIC, QUOTE, DATE_TIME, ATTRIBUTION }

    public enum ContextIssue { NONE, OUTDATED, OLD_EVENT_AS_NEW, MISSING_CONTEXT, MISATTRIBUTED }

    public enum QuoteStatus {
        /** Not a quote. */
        NOT_A_QUOTE,
        /** The quoted words appear word for word in a retrieved source. */
        FOUND_VERBATIM,
        /** Not found word for word in the retrieved sources: check the original recording or transcript. */
        NOT_LOCATED
    }

    /** A source's own words for or against a claim (verbatim, checked in code). */
    public record SourceExcerpt(String sourceId, String excerpt) {}

    /**
     * @param articleQuote the article's exact words
     * @param quoteSource  where the quoted words were found (FOUND_VERBATIM only)
     * @param sourcesConflict true when one source backs the claim and another contradicts it
     */
    public record NewsClaim(String id, ClaimType type, String articleQuote, String claim, String speaker,
                            String quotedWords, Verdict verdict, SourceExcerpt supporting, SourceExcerpt contradicting,
                            ContextIssue contextIssue, QuoteStatus quoteStatus, SourceExcerpt quoteSource,
                            boolean sourcesConflict, String explanation) {}
}
