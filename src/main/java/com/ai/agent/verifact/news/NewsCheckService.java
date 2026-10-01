package com.ai.agent.verifact.news;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.core.assess.VerdictRules;
import com.ai.agent.verifact.core.provenance.CitationValidator;
import com.ai.agent.verifact.core.provenance.QuoteVerifier;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.evidence.Urls;
import com.ai.agent.verifact.fetch.FetchFailedException;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.fetch.UnsafeUrlException;
import com.ai.agent.verifact.fetch.UrlGuard;
import com.ai.agent.verifact.news.NewsCheck.ClaimType;
import com.ai.agent.verifact.news.NewsCheck.ContextIssue;
import com.ai.agent.verifact.news.NewsCheck.NewsClaim;
import com.ai.agent.verifact.news.NewsCheck.QuoteStatus;
import com.ai.agent.verifact.news.NewsOutputs.ArticleClaim;
import com.ai.agent.verifact.news.NewsOutputs.ClaimExtraction;
import com.ai.agent.verifact.news.NewsOutputs.ClaimReview;
import com.ai.agent.verifact.news.NewsOutputs.ClaimReviews;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.ai.agent.verifact.evidence.Grounding.material;
import static com.ai.agent.verifact.evidence.Grounding.words;

/**
 * NewsFact check (ADR-14). Two model calls, the shared web evidence retriever in between:
 *
 * <pre>
 * article (URL or text) ─► [LLM 1] typed claims with the article's exact words
 *                       ─► web search per claim (the article's own site excluded)
 *                       ─► quote check in code: are the quoted words in any source, word for word?
 *                       ─► [LLM 2] verdict + context issue per claim, with sources' verbatim excerpts
 *                       ─► checks: excerpts must be the sources' own words, else the verdict drops
 * </pre>
 */
@Service
public class NewsCheckService {

    private static final Logger log = LoggerFactory.getLogger(NewsCheckService.class);

    private static final java.util.concurrent.ExecutorService VIDEO_POOL = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    static final int MAX_CLAIMS = 10;
    static final int MAX_SEARCHES = 10;
    static final int MAX_SOURCES = 16;
    static final int MAX_EXCERPT_CHARS = 300;

    static final String NOTICE = "Automated first pass for an editor: every verdict shows the source's own words, "
            + "and quotes are checked word for word against the sources found. \"Not located\" means not found in "
            + "these sources, not that a quote is fake. Confirm with primary sources before publishing a correction.";

    private final LlmClient llm;
    private final NewsVideoService videoService;
    private final EvidenceRetriever retriever;
    private final SafeUrlFetcher fetcher;
    private final Clock clock;
    private final int maxContentChars;

    public NewsCheckService(LlmClient llm, EvidenceRetriever retriever, SafeUrlFetcher fetcher, Clock clock,
                            @Value("${app.ai.max-content-chars:20000}") int maxContentChars, NewsVideoService videoService) {
        this.llm = llm;
        this.videoService = videoService;
        this.retriever = retriever;
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxContentChars = maxContentChars;
    }

    public NewsCheck check(String input, VerificationProgress progress) {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        String trimmed = input.trim();
        String url = null;
        String title = "";
        String text = trimmed;
        if (UrlGuard.looksLikeUrl(trimmed)) {
            progress.stage(VerificationProgress.Stage.READING_INPUT);
            SafeUrlFetcher.FetchedPage page;
            try {
                page = fetcher.fetch(trimmed);
            } catch (UnsafeUrlException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NewsFact can't open that link: " + e.getMessage() + ".");
            } catch (FetchFailedException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
            }
            url = page.url();
            title = page.title() == null ? "" : page.title();
            text = page.text();
        }
        if (text.length() > maxContentChars) {
            text = text.substring(0, maxContentChars);
        }
        String article = material(title, text);
        List<String> limitations = new ArrayList<>();

        // 1. Claims
        progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
        String nonce = nonce();
        ClaimExtraction extraction = llm.generate(NewsPrompts.withNonce(NewsPrompts.EXTRACT_SYSTEM, nonce),
                NewsPrompts.extractUser(nonce, clean(title, 300), text, today), ClaimExtraction.class);
        List<ArticleClaim> claims = claims(extraction, article);
        if (claims.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "NewsFact couldn't find checkable claims in that article. Try the full article text or its link.");
        }
        String articleDate = extraction.articleDate() == null ? null
                : article.contains(" " + words(extraction.articleDate()) + " ") && !words(extraction.articleDate()).isEmpty()
                ? clean(extraction.articleDate(), 60) : null;
        progress.claims(claims.stream().map(ArticleClaim::claim).toList());

        // Supporting videos: searched alongside the sources and verdicts; a failure here never fails the check.
        List<NewsVideoService.ClaimInput> videoClaims = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            videoClaims.add(new NewsVideoService.ClaimInput("C" + (i + 1), claims.get(i).claim()));
        }
        java.util.concurrent.CompletableFuture<NewsVideos> videos = videoService == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> videoService.find(videoClaims), VIDEO_POOL);

        // 2. Sources (the article's own site can't confirm itself)
        progress.stage(VerificationProgress.Stage.SEARCHING);
        String excludedSite = url == null ? null : Urls.registrableDomain(Urls.domain(url));
        List<Evidence> sources = retriever.retrieve(claims.stream().map(ArticleClaim::searchQueries).toList(),
                new EvidenceRetriever.Options(MAX_SEARCHES, MAX_SOURCES, excludedSite, List.of()), now);
        progress.sources(sources.size(), sources.stream().map(Evidence::domain).distinct().limit(8).toList());
        if (sources.isEmpty()) {
            limitations.add("The web search found no sources for these claims.");
        }

        // 3. Verdicts, with the sources' own words
        Map<String, ClaimReview> reviews = new LinkedHashMap<>();
        if (!sources.isEmpty()) {
            progress.stage(VerificationProgress.Stage.ASSESSING);
            String reviewNonce = nonce();
            List<NewsPrompts.ClaimLine> lines = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) {
                lines.add(new NewsPrompts.ClaimLine("C" + (i + 1), claims.get(i).type(), claims.get(i).claim()));
            }
            ClaimReviews review = llm.generate(NewsPrompts.withNonce(NewsPrompts.REVIEW_SYSTEM, reviewNonce),
                    NewsPrompts.reviewUser(reviewNonce, today, articleDate, lines, sources), ClaimReviews.class);
            if (review != null && review.claims() != null) {
                for (ClaimReview r : review.claims()) {
                    if (r != null && r.claimId() != null) {
                        reviews.putIfAbsent(r.claimId().trim().toUpperCase(Locale.ROOT), r);
                    }
                }
            }
        }

        List<NewsClaim> checked = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            String id = "C" + (i + 1);
            checked.add(validate(id, claims.get(i), reviews.get(id), sources));
        }
        if (checked.stream().anyMatch(c -> c.quoteStatus() == QuoteStatus.NOT_LOCATED)) {
            limitations.add("Some quotes weren't found word for word in the sources searched; check the original "
                    + "recording, transcript or statement.");
        }
        Map<Verdict, Integer> counts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) {
            counts.put(v, 0);
        }
        checked.forEach(c -> counts.merge(c.verdict(), 1, Integer::sum));
        NewsVideos foundVideos;
        try {
            foundVideos = videos.get(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            videos.cancel(true);
            log.warn("Supporting videos unavailable: {}", e.getClass().getSimpleName());
            foundVideos = NewsVideos.none("Video search didn't finish in time; the rest of the check is complete.");
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        // Counts only: article text stays out of the logs.
        log.info("News check done claims={} sources={} verdicts={} durationMs={}", checked.size(), sources.size(), counts, durationMs);
        return new NewsCheck(UUID.randomUUID(), now, url, emptyToNull(clean(title, 300)), articleDate, checked, sources,
                counts, List.copyOf(limitations), NOTICE, retriever.providerName(), durationMs, foundVideos);
    }

    /** Claims whose sentence (and quoted words) are really in the article; speakers only as the article names them. */
    private static List<ArticleClaim> claims(ClaimExtraction extraction, String article) {
        List<ArticleClaim> out = new ArrayList<>();
        if (extraction == null || extraction.claims() == null) {
            return out;
        }
        for (ArticleClaim c : extraction.claims()) {
            if (c == null || c.quote() == null) {
                continue;
            }
            String q = words(c.quote());
            if (q.split(" ").length < 4 || !article.contains(" " + q + " ")) {
                continue;
            }
            ClaimType type = type(c.type());
            String quotedWords = clean(c.quotedWords(), 400);
            if (type == ClaimType.QUOTE && (words(quotedWords).split(" ").length < 3
                    || !article.contains(" " + words(quotedWords) + " "))) {
                type = ClaimType.ATTRIBUTION; // no verifiable quoted words in the article
                quotedWords = "";
            }
            String speaker = clean(c.speaker(), 120);
            if (!speaker.isEmpty() && !article.contains(" " + words(speaker) + " ")) {
                speaker = "";
            }
            String claim = clean(c.claim(), 400);
            if (claim.isBlank() || !Grounding.supported(claim, article)) {
                claim = clean(c.quote(), 400);
            }
            List<String> queries = new ArrayList<>();
            if (c.searchQueries() != null) {
                c.searchQueries().stream().map(EvidenceRetriever::cleanQuery).filter(x -> !x.isBlank()).limit(2).forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(EvidenceRetriever.cleanQuery(claim));
            }
            out.add(new ArticleClaim(type.name(), clean(c.quote(), 400), claim, speaker, quotedWords, queries));
            if (out.size() == MAX_CLAIMS) {
                break;
            }
        }
        return out;
    }

    /**
     * The model's verdict stands only when the excerpt it relies on is the source's own words:
     * SUPPORTED/PARTLY need a supporting excerpt, CONTRADICTED a contradicting one, MISLEADING either;
     * otherwise INSUFFICIENT_EVIDENCE. The quote check is independent of the model.
     */
    static NewsClaim validate(String id, ArticleClaim c, ClaimReview r, List<Evidence> sources) {
        ClaimType type = ClaimType.valueOf(c.type());
        QuoteStatus quoteStatus = QuoteStatus.NOT_A_QUOTE;
        SourceExcerpt quoteSource = null;
        if (type == ClaimType.QUOTE) {
            quoteSource = QuoteVerifier.locate(c.quotedWords(), sources, 3, MAX_EXCERPT_CHARS);
            quoteStatus = quoteSource != null ? QuoteStatus.FOUND_VERBATIM : QuoteStatus.NOT_LOCATED;
        }
        Map<String, Evidence> byId = CitationValidator.byId(sources);
        SourceExcerpt supporting = r == null ? null : excerpt(r.supportingSourceId(), r.supportingExcerpt(), byId);
        SourceExcerpt contradicting = r == null ? null : excerpt(r.contradictingSourceId(), r.contradictingExcerpt(), byId);
        Verdict verdict = r == null ? Verdict.INSUFFICIENT_EVIDENCE : Verdict.parse(r.verdict());
        verdict = VerdictRules.gate(verdict, supporting, contradicting);
        ContextIssue context = contextIssue(r == null ? null : r.contextIssue());
        if (!VerdictRules.flagBacked(supporting, contradicting)) {
            context = ContextIssue.NONE;
        }
        String explanation = r == null ? null : VerdictRules.groundedExplanation(clean(r.explanation(), 400), c.claim(),
                supporting, contradicting);
        return new NewsClaim(id, type, c.quote(), c.claim(), emptyToNull(c.speaker()), emptyToNull(c.quotedWords()),
                verdict, supporting, contradicting, context, quoteStatus, quoteSource,
                VerdictRules.sourcesConflict(supporting, contradicting), explanation);
    }

    private static SourceExcerpt excerpt(String sourceId, String excerpt, Map<String, Evidence> byId) {
        return CitationValidator.verbatim(sourceId, excerpt, byId, 5, MAX_EXCERPT_CHARS);
    }

    private static ClaimType type(String s) {
        try {
            return ClaimType.valueOf(s == null ? "FACT" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
        } catch (IllegalArgumentException e) {
            return ClaimType.FACT;
        }
    }

    private static ContextIssue contextIssue(String s) {
        try {
            return s == null ? ContextIssue.NONE : ContextIssue.valueOf(s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
        } catch (IllegalArgumentException e) {
            return ContextIssue.NONE;
        }
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
