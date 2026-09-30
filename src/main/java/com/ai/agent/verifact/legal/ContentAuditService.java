package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.fetch.FetchFailedException;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.fetch.UnsafeUrlException;
import com.ai.agent.verifact.legal.ContentAudit.AuditedStatement;
import com.ai.agent.verifact.legal.ContentAudit.Status;
import com.ai.agent.verifact.legal.ContentAuditOutputs.LegalStatement;
import com.ai.agent.verifact.legal.ContentAuditOutputs.StatementCheck;
import com.ai.agent.verifact.legal.ContentAuditOutputs.StatementExtraction;
import com.ai.agent.verifact.legal.ContentAuditOutputs.StatementReview;
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
 * Legal content audit (experiment E2, legalfact.md): does a legal web page still match official
 * sources? Two model calls:
 *
 * <pre>
 * page ─► [LLM 1] statements of law, each with the page's exact words and search queries
 *      ─► search official domains (federal + curated state) per statement
 *      ─► [LLM 2] status per statement citing sources ─► checks ─► report
 * </pre>
 *
 * The checks keep flags honest: quotes must be on the page, a flag needs a cited source whose own
 * excerpt backs what we say it says (figures included), anything unsupported falls back to
 * REQUIRES_REVIEW or UNABLE_TO_VERIFY, and advice-like wording is removed. Flags are for an editor or
 * attorney to confirm, never a finding that the page is wrong.
 */
@Service
public class ContentAuditService {

    private static final Logger log = LoggerFactory.getLogger(ContentAuditService.class);

    static final int MAX_STATEMENTS = 8;
    static final int MAX_SEARCHES = 8;
    static final int MAX_SOURCES = 12;

    static final String NOTICE = "Automated comparison with official sources, for editorial and attorney review. "
            + "Not legal advice or a legal opinion: flags are potential issues to confirm, and \"consistent\" is not "
            + "a guarantee that the page is complete or current.";

    private final LlmClient llm;
    private final EvidenceRetriever evidenceRetriever;
    private final SafeUrlFetcher fetcher;
    private final Clock clock;
    private final int maxContentChars;

    public ContentAuditService(LlmClient llm, EvidenceRetriever evidenceRetriever, SafeUrlFetcher fetcher, Clock clock,
                               @Value("${app.ai.max-content-chars:20000}") int maxContentChars) {
        this.llm = llm;
        this.evidenceRetriever = evidenceRetriever;
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxContentChars = maxContentChars;
    }

    /** Fetches the page safely (SSRF-guarded), then audits it. */
    public ContentAudit audit(String url, String stateHint) {
        SafeUrlFetcher.FetchedPage page;
        try {
            page = fetcher.fetch(url);
        } catch (UnsafeUrlException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LegalFact can't open that link: " + e.getMessage() + ".");
        } catch (FetchFailedException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
        return auditText(page.url(), page.title(), page.text(), stateHint);
    }

    /** @param stateHint state the page is about, if known (e.g. "CA"); otherwise taken from the page when it names one */
    public ContentAudit auditText(String url, String title, String text, String stateHint) {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        String pageText = text.length() > maxContentChars ? text.substring(0, maxContentChars) : text;
        String page = material(title == null ? "" : title, pageText);
        List<String> limitations = new ArrayList<>();

        // 1. Statements of law on the page
        String nonce = nonce();
        StatementExtraction extraction = llm.generate(
                LegalPrompts.withNonce(ContentAuditPrompts.EXTRACT_SYSTEM, nonce),
                ContentAuditPrompts.extractUser(nonce, clean(title, 200), pageText, today), StatementExtraction.class);
        String state = state(stateHint, extraction == null ? null : extraction.state(), page);
        String jurisdiction = state == null ? "United States (federal law; state not identified)"
                : "United States: federal law, plus " + UsStates.NAMES.get(state) + " state law";
        if (state == null) {
            limitations.add("The page's state wasn't identified, so only federal sources were searched.");
        } else if (!LegalSources.hasStateSources(state)) {
            limitations.add("State sources for " + UsStates.NAMES.get(state) + " aren't covered yet; only federal sources were searched.");
        }
        List<LegalStatement> statements = statements(extraction, page);
        if (statements.isEmpty()) {
            limitations.add("No specific statements of law were found on the page.");
            return result(url, title, now, jurisdiction, List.of(), List.of(), limitations, startedAt);
        }

        // 2. Official sources per statement
        List<Evidence> evidence = CaseIntelligenceService.rankAndNumber(evidenceRetriever.retrieve(
                statements.stream().map(LegalStatement::searchQueries).toList(),
                new EvidenceRetriever.Options(MAX_SEARCHES, MAX_SOURCES, null, LegalSources.allowedDomains(state)), now));

        // 3. Compare
        Map<String, StatementCheck> checks = new LinkedHashMap<>();
        if (evidence.isEmpty()) {
            limitations.add("No official sources addressing these statements were found.");
        } else {
            String reviewNonce = nonce();
            List<ContentAuditPrompts.StatementLine> lines = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                lines.add(new ContentAuditPrompts.StatementLine("S" + (i + 1), statements.get(i).statement()));
            }
            StatementReview review = llm.generate(LegalPrompts.withNonce(ContentAuditPrompts.REVIEW_SYSTEM, reviewNonce),
                    ContentAuditPrompts.reviewUser(reviewNonce, jurisdiction, today, lines,
                            evidence.stream().map(e -> new LegalPrompts.SourceLine(e.id(),
                                    LegalSourceType.classify(e.url(), e.domain()).name(), e.domain(), e.title(),
                                    e.snippet())).toList()),
                    StatementReview.class);
            if (review != null && review.statements() != null) {
                for (StatementCheck c : review.statements()) {
                    if (c != null && c.statementId() != null) {
                        checks.putIfAbsent(c.statementId().trim().toUpperCase(Locale.ROOT), c);
                    }
                }
            }
        }

        List<AuditedStatement> audited = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            String id = "S" + (i + 1);
            audited.add(validate(id, statements.get(i), checks.get(id), evidence));
        }
        return result(url, title, now, jurisdiction, audited, evidence, limitations, startedAt);
    }

    /**
     * Enforces the audit rules on one statement: citations must exist; a flag or "consistent" needs a
     * source summary grounded in the cited excerpts, otherwise it becomes REQUIRES_REVIEW; no sources
     * means UNABLE_TO_VERIFY.
     */
    static AuditedStatement validate(String id, LegalStatement statement, StatementCheck check, List<Evidence> evidence) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        evidence.forEach(e -> byId.put(e.id(), e));
        Status status = parse(check == null ? null : check.status());
        List<String> ids = new ArrayList<>();
        if (check != null && check.sourceIds() != null) {
            for (String raw : check.sourceIds()) {
                String sid = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
                if (byId.containsKey(sid) && !ids.contains(sid)) {
                    ids.add(sid);
                }
            }
        }
        if (ids.isEmpty()) {
            return new AuditedStatement(id, statement.quote(), statement.statement(), Status.UNABLE_TO_VERIFY, List.of(),
                    null, null);
        }
        String cited = material(ids.stream().map(byId::get).map(e -> e.title() + " " + e.snippet()).toArray(String[]::new));
        String sourceSays = grounded(check.sourceSays(), cited);
        String note = grounded(check.note(), cited + material(statement.quote()));
        if (status == Status.UNABLE_TO_VERIFY) {
            return new AuditedStatement(id, statement.quote(), statement.statement(), status, List.of(), null, note);
        }
        if (sourceSays == null) {
            status = Status.REQUIRES_REVIEW; // can't show what the source says, so can't assert a status
        }
        if ((status == Status.POTENTIALLY_OUTDATED || status == Status.POTENTIALLY_UNSUPPORTED)
                && !quotesACitedSource(check.conflictingExcerpt(), ids, byId)) {
            // A flag must point at the source's own conflicting words; silence or paraphrase isn't enough.
            status = Status.REQUIRES_REVIEW;
        }
        return new AuditedStatement(id, statement.quote(), statement.statement(), status, ids, sourceSays, note);
    }

    /** The conflicting words, at least five, appear verbatim in one cited source's title or excerpt. */
    static boolean quotesACitedSource(String excerpt, List<String> ids, Map<String, Evidence> byId) {
        String q = words(excerpt == null ? "" : excerpt);
        if (q.split(" ").length < 5) {
            return false;
        }
        return ids.stream().map(byId::get)
                .anyMatch(e -> material(e.title(), e.snippet()).contains(" " + q + " "));
    }

    private static String grounded(String text, String material) {
        String t = AdviceLanguage.strip(clean(text, 400));
        return t.isBlank() || !Grounding.supported(t, material) ? null : t;
    }

    /** Statements whose quote is really on the page; restatements that add figures fall back to the quote. */
    private static List<LegalStatement> statements(StatementExtraction extraction, String page) {
        List<LegalStatement> out = new ArrayList<>();
        if (extraction == null || extraction.statements() == null) {
            return out;
        }
        for (LegalStatement s : extraction.statements()) {
            if (s == null || s.quote() == null) {
                continue;
            }
            String quote = clean(s.quote(), 400);
            String q = words(quote);
            if (q.length() < 15 || q.split(" ").length < 3 || !page.contains(" " + q + " ")) {
                continue;
            }
            String statement = clean(s.statement(), 400);
            if (statement.isBlank() || !Grounding.supported(statement, page) || AdviceLanguage.isAdvice(statement)) {
                statement = quote;
            }
            List<String> queries = new ArrayList<>();
            if (s.searchQueries() != null) {
                s.searchQueries().stream().map(EvidenceRetriever::cleanQuery).filter(x -> !x.isBlank()).limit(2)
                        .forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(EvidenceRetriever.cleanQuery(statement));
            }
            out.add(new LegalStatement(quote, statement, queries));
            if (out.size() == MAX_STATEMENTS) {
                break;
            }
        }
        return out;
    }

    /** The caller's hint wins; otherwise the model's guess, only if the page itself names that state. */
    static String state(String hint, String guess, String page) {
        String fromHint = UsStates.code(hint);
        if (fromHint != null) {
            return fromHint;
        }
        String code = UsStates.code(guess);
        return code != null && page.contains(" " + words(UsStates.NAMES.get(code)) + " ") ? code : null;
    }

    private static Status parse(String value) {
        if (value == null) {
            return Status.UNABLE_TO_VERIFY;
        }
        try {
            return Status.valueOf(value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
        } catch (IllegalArgumentException e) {
            return Status.REQUIRES_REVIEW;
        }
    }

    private ContentAudit result(String url, String title, Instant now, String jurisdiction,
                                List<AuditedStatement> statements, List<Evidence> evidence, List<String> limitations,
                                long startedAt) {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0);
        }
        statements.forEach(s -> counts.merge(s.status(), 1, Integer::sum));
        List<CaseIntelligence.LegalSource> sources = evidence.stream()
                .map(e -> new CaseIntelligence.LegalSource(e.id(), e.url(), e.domain(), e.title(), e.snippet(),
                        e.publishedDate(), e.retrievedAt(), LegalSourceType.classify(e.url(), e.domain())))
                .toList();
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Content audit done statements={} counts={} sources={} durationMs={}",
                statements.size(), counts, sources.size(), durationMs);
        return new ContentAudit(url, clean(title, 300), now, jurisdiction, statements, counts, sources,
                List.copyOf(limitations), NOTICE, durationMs);
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
