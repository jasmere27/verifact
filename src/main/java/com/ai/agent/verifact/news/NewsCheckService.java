package com.ai.agent.verifact.news;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.core.assess.ClaimFinding;
import com.ai.agent.verifact.core.assess.EvidenceAssessor;
import com.ai.agent.verifact.core.assess.ReviewCandidate;
import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.core.claims.ClaimCandidate;
import com.ai.agent.verifact.core.claims.ClaimGrounder;
import com.ai.agent.verifact.core.claims.ClaimProfile;
import com.ai.agent.verifact.core.provenance.QuoteVerifier;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.EvidenceRetriever;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.ai.agent.verifact.core.claims.ClaimGrounder.clean;
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

    static final ClaimProfile CLAIM_PROFILE = new ClaimProfile(
            java.util.Arrays.stream(ClaimType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()),
            ClaimType.FACT.name(), ClaimType.QUOTE.name(), ClaimType.ATTRIBUTION.name(), MAX_CLAIMS);

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
        Map<String, ClaimReview> reviews = Map.of();
        if (!sources.isEmpty()) {
            progress.stage(VerificationProgress.Stage.ASSESSING);
            String reviewNonce = nonce();
            List<NewsPrompts.ClaimLine> lines = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) {
                lines.add(new NewsPrompts.ClaimLine("C" + (i + 1), claims.get(i).type(), claims.get(i).claim()));
            }
            ClaimReviews review = llm.generate(NewsPrompts.withNonce(NewsPrompts.REVIEW_SYSTEM, reviewNonce),
                    NewsPrompts.reviewUser(reviewNonce, today, articleDate, lines, sources), ClaimReviews.class);
            reviews = EvidenceAssessor.byClaimId(review == null ? null : review.claims(), ClaimReview::claimId);
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
        if (extraction == null || extraction.claims() == null) {
            return new ArrayList<>();
        }
        List<ClaimCandidate> candidates = extraction.claims().stream().map(c -> c == null ? null
                : new ClaimCandidate(c.type(), c.quote(), c.claim(), c.speaker(), c.quotedWords(), c.searchQueries())).toList();
        return ClaimGrounder.ground(candidates, article, CLAIM_PROFILE).stream()
                .map(g -> new ArticleClaim(g.type(), g.quote(), g.claim(), g.speaker(), g.quotedWords(), g.searchQueries()))
                .toList();
    }

    /**
     * The model's verdict stands only when the excerpt it relies on is the source's own words (shared
     * {@link EvidenceAssessor} rules); context flags need an excerpt too. The quote check is independent of the model.
     */
    static NewsClaim validate(String id, ArticleClaim c, ClaimReview r, List<Evidence> sources) {
        ClaimType type = ClaimType.valueOf(c.type());
        QuoteStatus quoteStatus = QuoteStatus.NOT_A_QUOTE;
        SourceExcerpt quoteSource = null;
        if (type == ClaimType.QUOTE) {
            quoteSource = QuoteVerifier.locate(c.quotedWords(), sources, 3, MAX_EXCERPT_CHARS);
            quoteStatus = quoteSource != null ? QuoteStatus.FOUND_VERBATIM : QuoteStatus.NOT_LOCATED;
        }
        ClaimFinding f = EvidenceAssessor.assess(c.claim(), r == null ? null : new ReviewCandidate(r.verdict(),
                r.supportingSourceId(), r.supportingExcerpt(), r.contradictingSourceId(), r.contradictingExcerpt(),
                r.explanation()), sources, MAX_EXCERPT_CHARS);
        ContextIssue context = f.flagsBacked() ? contextIssue(r.contextIssue()) : ContextIssue.NONE;
        return new NewsClaim(id, type, c.quote(), c.claim(), emptyToNull(c.speaker()), emptyToNull(c.quotedWords()),
                f.verdict(), f.supporting(), f.contradicting(), context, quoteStatus, quoteSource, f.sourcesConflict(),
                f.explanation());
    }

    private static ContextIssue contextIssue(String s) {
        try {
            return s == null ? ContextIssue.NONE : ContextIssue.valueOf(s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
        } catch (IllegalArgumentException e) {
            return ContextIssue.NONE;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
