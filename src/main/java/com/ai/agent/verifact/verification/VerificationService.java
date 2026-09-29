package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.fetch.FetchFailedException;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.fetch.UnsafeUrlException;
import com.ai.agent.verifact.fetch.UrlGuard;
import com.ai.agent.verifact.model.InputType;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import com.ai.agent.verifact.verification.ModelOutputs.Assessment;
import com.ai.agent.verifact.verification.ModelOutputs.ClaimExtraction;
import com.ai.agent.verifact.verification.ModelOutputs.ClaimVerdict;
import com.ai.agent.verifact.verification.ModelOutputs.ExtractedClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The verification pipeline (ADR-3). The backend controls every step and the model gets no tools:
 *
 * <pre>
 * content ─► [LLM 1] extract claims + queries ─► search (bounded) ─► evidence E1..En
 *         ─► [LLM 2] per-claim verdict citing evidence IDs ─► validate ─► persist
 * </pre>
 *
 * At most two model calls and {@value #MAX_SEARCHES} searches per verification. Citations are
 * checked against retrieved evidence, and evidence strength and the overall verdict are computed
 * here from what was cited, so the model can't overstate certainty.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    static final int MAX_CLAIMS = 3;
    static final int MAX_SEARCHES = 4;
    static final int RESULTS_PER_SEARCH = 4;
    static final int MAX_EVIDENCE = 10;
    static final int MAX_SNIPPET_CHARS = 600;
    static final int CHECKED_TEXT_EXCERPT_CHARS = 1500;

    private final LlmClient llm;
    private final SearchProvider searchProvider;
    private final SafeUrlFetcher safeUrlFetcher;
    private final VerificationStore store;
    private final Clock clock;
    private final int maxContentChars;

    public VerificationService(LlmClient llm, SearchProvider searchProvider, SafeUrlFetcher safeUrlFetcher,
                               VerificationStore store, Clock clock,
                               @Value("${app.ai.max-content-chars:20000}") int maxContentChars) {
        this.llm = llm;
        this.searchProvider = searchProvider;
        this.safeUrlFetcher = safeUrlFetcher;
        this.store = store;
        this.clock = clock;
        this.maxContentChars = maxContentChars;
    }

    // ---------------------------------------------------------------- entry points

    /** Text or a single link. */
    public VerificationResult verifyText(String input) {
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please provide a claim, article text, or link to check.");
        }
        String trimmed = input.trim();
        if (!UrlGuard.looksLikeUrl(trimmed)) {
            return run(InputType.TEXT, trimmed, trimmed, "text submitted by a user");
        }
        SafeUrlFetcher.FetchedPage page;
        try {
            page = safeUrlFetcher.fetch(trimmed);
        } catch (UnsafeUrlException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VeriFact can't open that link: " + e.getMessage() + ".");
        } catch (FetchFailedException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
        String host = URI.create(page.url()).getHost();
        return run(InputType.URL, trimmed, page.title() + "\n\n" + page.text(), "a web page from the site " + host);
    }

    public VerificationResult verifyImageText(String fileName, String ocrText) {
        return run(InputType.IMAGE, displayName(fileName, "Uploaded image"), ocrText,
                "text extracted by OCR from an image a user uploaded");
    }

    public VerificationResult verifyAudioTranscript(String fileName, String transcript) {
        return run(InputType.AUDIO, displayName(fileName, "Uploaded audio"), transcript,
                "a transcript of audio a user uploaded");
    }

    // ---------------------------------------------------------------- pipeline

    VerificationResult run(InputType inputType, String displayInput, String rawContent, String sourceDescription) {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        String content = truncate(rawContent.trim(), maxContentChars);

        // 1. Claims and queries
        String nonce = nonce();
        ClaimExtraction extraction = llm.generate(
                VerificationPrompts.withNonce(VerificationPrompts.EXTRACTION_SYSTEM, nonce),
                VerificationPrompts.extractionUser(nonce, sourceDescription, content, today),
                ClaimExtraction.class);
        List<ExtractedClaim> claims = cleanClaims(extraction);
        if (claims.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "VeriFact couldn't find a specific factual claim to check. "
                            + "Try stating it directly, e.g. \"The Eiffel Tower is 330 metres tall.\"");
        }

        // 2. Evidence
        List<Evidence> evidence = gatherEvidence(claims, now);
        List<String> claimTexts = claims.stream().map(ExtractedClaim::claim).toList();

        // 3. Assessment (skipped when there is nothing to weigh)
        List<ClaimAssessment> assessments;
        String summary;
        List<String> limitations = new ArrayList<>();
        if (evidence.isEmpty()) {
            assessments = new ArrayList<>();
            for (int i = 0; i < claimTexts.size(); i++) {
                assessments.add(new ClaimAssessment("C" + (i + 1), claimTexts.get(i),
                        Verdict.INSUFFICIENT_EVIDENCE, EvidenceStrength.LIMITED,
                        "No relevant sources were found for this claim.", List.of(), List.of()));
            }
            summary = "VeriFact couldn't find sources that address this, so it can't be confirmed or refuted.";
            limitations.add("The web search returned no relevant results.");
        } else {
            String assessmentNonce = nonce();
            Assessment assessment = llm.generate(
                    VerificationPrompts.withNonce(VerificationPrompts.ASSESSMENT_SYSTEM, assessmentNonce),
                    VerificationPrompts.assessmentUser(assessmentNonce, claimTexts, evidence, today),
                    Assessment.class);
            assessments = validate(claimTexts, assessment, evidence, limitations);
            summary = cleanText(assessment.summary(), 400);
            if (summary.isBlank()) {
                summary = "See the individual claims below.";
            }
            if (assessment.limitations() != null) {
                assessment.limitations().stream()
                        .map(l -> cleanText(l, 300))
                        .filter(l -> !l.isBlank())
                        .limit(5)
                        .forEach(limitations::add);
            }
        }

        OverallVerdict overall = OverallVerdict.of(assessments.stream().map(ClaimAssessment::verdict).toList());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        VerificationResult result = new VerificationResult(
                UUID.randomUUID(), now, inputType, truncate(displayInput, 2000),
                truncate(content, CHECKED_TEXT_EXCERPT_CHARS), overall, summary, assessments, evidence,
                List.copyOf(new LinkedHashSet<>(limitations)), searchProvider.name(), durationMs);

        log.info("Verification done id={} inputType={} claims={} evidence={} overall={} durationMs={}",
                result.id(), inputType, assessments.size(), evidence.size(), overall, durationMs);
        store.save(result);
        return result;
    }

    private List<ExtractedClaim> cleanClaims(ClaimExtraction extraction) {
        List<ExtractedClaim> claims = new ArrayList<>();
        if (extraction == null || extraction.claims() == null) {
            return claims;
        }
        for (ExtractedClaim c : extraction.claims()) {
            String text = cleanText(c == null ? null : c.claim(), 500);
            if (text.isBlank()) {
                continue;
            }
            List<String> queries = new ArrayList<>();
            if (c.searchQueries() != null) {
                c.searchQueries().stream()
                        .map(q -> cleanText(q, 200))
                        .filter(q -> !q.isBlank())
                        .limit(2)
                        .forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(truncate(text, 200));
            }
            claims.add(new ExtractedClaim(text, queries));
            if (claims.size() == MAX_CLAIMS) {
                break;
            }
        }
        return claims;
    }

    /** Runs each claim's first query, then second queries, within the search budget. */
    private List<Evidence> gatherEvidence(List<ExtractedClaim> claims, Instant now) {
        List<String> queries = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (ExtractedClaim claim : claims) {
                if (claim.searchQueries().size() > round && queries.size() < MAX_SEARCHES) {
                    queries.add(claim.searchQueries().get(round));
                }
            }
        }

        Map<String, Evidence> byUrl = new LinkedHashMap<>();
        int failures = 0;
        for (String query : queries) {
            List<SearchResult> results;
            try {
                results = searchProvider.search(query);
            } catch (SearchUnavailableException e) {
                log.warn("Search failed via {}: {}", searchProvider.name(), e.getMessage());
                failures++;
                continue;
            }
            int taken = 0;
            for (SearchResult r : results) {
                if (byUrl.size() >= MAX_EVIDENCE || taken >= RESULTS_PER_SEARCH) {
                    break;
                }
                String key = normalizeUrl(r.url());
                String domain = domain(r.url());
                if (key == null || domain == null || byUrl.containsKey(key)) {
                    continue;
                }
                String id = "E" + (byUrl.size() + 1);
                byUrl.put(key, new Evidence(id, r.url(), domain, cleanText(r.title(), 300),
                        cleanText(r.snippet(), MAX_SNIPPET_CHARS), emptyToNull(cleanText(r.publishedDate(), 40)), now));
                taken++;
            }
        }
        if (failures == queries.size()) {
            // Without search the only alternative is the model's memory; refuse instead.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Web search is unavailable right now, so VeriFact can't gather evidence. Please try again later.");
        }
        return new ArrayList<>(byUrl.values());
    }

    /**
     * Enforces the evidence rules on the model's answer: unknown citations are dropped, verdicts
     * without matching citations are downgraded, and strength is computed from cited domains.
     */
    List<ClaimAssessment> validate(List<String> claimTexts, Assessment assessment, List<Evidence> evidence,
                                   List<String> limitations) {
        Map<String, Evidence> evidenceById = new LinkedHashMap<>();
        evidence.forEach(e -> evidenceById.put(e.id(), e));

        Map<String, ClaimVerdict> verdictsByClaim = new LinkedHashMap<>();
        if (assessment != null && assessment.claims() != null) {
            for (ClaimVerdict v : assessment.claims()) {
                if (v != null && v.claimId() != null) {
                    verdictsByClaim.putIfAbsent(v.claimId().trim().toUpperCase(Locale.ROOT), v);
                }
            }
        }

        List<ClaimAssessment> out = new ArrayList<>();
        for (int i = 0; i < claimTexts.size(); i++) {
            String claimId = "C" + (i + 1);
            ClaimVerdict v = verdictsByClaim.get(claimId);
            if (v == null) {
                out.add(new ClaimAssessment(claimId, claimTexts.get(i), Verdict.INSUFFICIENT_EVIDENCE,
                        EvidenceStrength.LIMITED, "This claim could not be assessed.", List.of(), List.of()));
                continue;
            }
            List<String> supporting = knownIds(v.supportingEvidenceIds(), evidenceById);
            List<String> contradicting = knownIds(v.contradictingEvidenceIds(), evidenceById);
            contradicting.removeAll(supporting); // an item can't count both ways
            Verdict verdict = Verdict.parse(v.verdict());
            String explanation = cleanText(v.explanation(), 500);

            boolean backed = switch (verdict) {
                case SUPPORTED, PARTLY_SUPPORTED -> !supporting.isEmpty();
                case CONTRADICTED -> !contradicting.isEmpty();
                case MISLEADING -> !supporting.isEmpty() || !contradicting.isEmpty();
                case INSUFFICIENT_EVIDENCE -> true;
            };
            if (!backed) {
                verdict = Verdict.INSUFFICIENT_EVIDENCE;
                explanation = "The sources found don't clearly establish this claim either way.";
            }
            if (explanation.isBlank()) {
                explanation = "See the cited sources.";
            }

            EvidenceStrength strength = strength(verdict, supporting, contradicting, evidenceById);
            boolean disputed = (verdict == Verdict.SUPPORTED && !contradicting.isEmpty())
                    || (verdict == Verdict.CONTRADICTED && !supporting.isEmpty());
            if (disputed) {
                limitations.add("Sources disagree about claim " + claimId + ".");
            }
            out.add(new ClaimAssessment(claimId, claimTexts.get(i), verdict, strength, explanation,
                    List.copyOf(supporting), List.copyOf(contradicting)));
        }
        return out;
    }

    static EvidenceStrength strength(Verdict verdict, List<String> supporting, List<String> contradicting,
                                     Map<String, Evidence> evidenceById) {
        List<String> direction = switch (verdict) {
            case SUPPORTED, PARTLY_SUPPORTED -> supporting;
            case CONTRADICTED -> contradicting;
            case MISLEADING -> {
                List<String> both = new ArrayList<>(supporting);
                both.addAll(contradicting);
                yield both;
            }
            case INSUFFICIENT_EVIDENCE -> List.of();
        };
        Set<String> domains = new LinkedHashSet<>();
        for (String id : direction) {
            domains.add(evidenceById.get(id).domain());
        }
        EvidenceStrength strength = domains.size() >= 3 ? EvidenceStrength.STRONG
                : domains.size() == 2 ? EvidenceStrength.MODERATE
                : EvidenceStrength.LIMITED;
        boolean disputed = (verdict == Verdict.SUPPORTED && !contradicting.isEmpty())
                || (verdict == Verdict.CONTRADICTED && !supporting.isEmpty());
        if (disputed && strength == EvidenceStrength.STRONG) {
            strength = EvidenceStrength.MODERATE;
        }
        return strength;
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> knownIds(List<String> ids, Map<String, Evidence> evidenceById) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String normalized = id.trim().toUpperCase(Locale.ROOT);
            if (evidenceById.containsKey(normalized) && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    static String domain(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null; // only web links can be shown as sources
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return domain(url) + path + query;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cleanText(String text, int max) {
        if (text == null) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").trim(), max);
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String displayName(String fileName, String fallback) {
        return fileName == null || fileName.isBlank() ? fallback : cleanText(fileName, 200);
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
