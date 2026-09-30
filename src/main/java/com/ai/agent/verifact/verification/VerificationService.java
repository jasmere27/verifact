package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.ai.ImageInput;
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
import com.ai.agent.verifact.verification.ModelOutputs.ImageExtraction;
import com.google.common.net.InternetDomainName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The verification pipeline (ADR-3). The backend controls every step and the model gets no tools:
 *
 * <pre>
 * content ─► [LLM 1] extract claims + queries ─► search (bounded) ─► evidence E1..En
 *            (for an image, LLM 1 reads the image itself; OCR is the fallback)
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
    static final int MAX_SOCIAL = 2;

    /** Search operators that could let content steer where evidence comes from (e.g. site:attacker.example). */
    private static final Pattern SEARCH_OPERATOR = Pattern.compile(
            "(?i)\\b(?:site|inurl|allinurl|intitle|allintitle|intext|allintext|filetype|ext|related|cache|link|info|source|before|after|daterange):\\S*");
    private static final Pattern EXCLUSION_TERM = Pattern.compile("(^|\\s)-\\S+");
    private static final Pattern EVIDENCE_REF = Pattern.compile("\\bE\\d+\\b");
    /** A vision failure slower than this is not retried with OCR (see {@link #verifyImage}). */
    static final Duration VISION_FALLBACK_BUDGET = Duration.ofSeconds(30);

    /**
     * Words that would turn the model's neutral reading of an image into a judgement shown under
     * VeriFact's name. The image is untrusted and can steer the model, so such fields are dropped.
     */
    private static final Pattern JUDGEMENT_WORDS = Pattern.compile(
            "(?i)(verif|confirm|authentic|genuine|fake|hoax|debunk|fact.?check|accurate|legit|\\btrue\\b|\\bfalse\\b|100\\s*%|reliable|trustworth)");

    private static final String OCR_SOURCE_DESCRIPTION = "text extracted by OCR from an image a user uploaded";

    private static final Pattern QUOTE_ATTRIBUTION = Pattern.compile(
            "(?i)\\b(said|says|stated|claimed|wrote|tweeted|quoted)\\b|[\"“”]");

    static final String IMAGE_LIMITATION =
            "VeriFact checks what the image says. It can't tell whether the image itself was edited or taken out of context.";
    private static final String NOT_ESTABLISHED = "The sources found don't clearly establish this claim either way.";

    /** Result of checking the model's assessment against the evidence rules. */
    record Validation(List<ClaimAssessment> claims, boolean anyDowngraded) {}

    private final LlmClient llm;
    private final SearchProvider searchProvider;
    private final SafeUrlFetcher safeUrlFetcher;
    private final VerificationStore store;
    private final Clock clock;
    private final int maxContentChars;
    private final Duration reuseWindow;

    public VerificationService(LlmClient llm, SearchProvider searchProvider, SafeUrlFetcher safeUrlFetcher,
                               VerificationStore store, Clock clock,
                               @Value("${app.ai.max-content-chars:20000}") int maxContentChars,
                               @Value("${app.reuse.ttl-hours:24}") long reuseTtlHours) {
        this.llm = llm;
        this.searchProvider = searchProvider;
        this.safeUrlFetcher = safeUrlFetcher;
        this.store = store;
        this.clock = clock;
        this.maxContentChars = maxContentChars;
        this.reuseWindow = Duration.ofHours(reuseTtlHours);
    }

    // ---------------------------------------------------------------- entry points

    /** Text or a single link. */
    public VerificationResult verifyText(String input) {
        return verifyText(input, VerificationProgress.NONE);
    }

    public VerificationResult verifyText(String input, VerificationProgress progress) {
        return verifyText(input, progress, false);
    }

    /**
     * @param refresh true to always run a new check; otherwise a report for the same text or link
     *                from the last {@code app.reuse.ttl-hours} is returned instantly (free, and
     *                consistent for everyone checking the same viral claim)
     */
    public VerificationResult verifyText(String input, VerificationProgress progress, boolean refresh) {
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please provide a claim, article text, or link to check.");
        }
        String trimmed = input.trim();
        boolean isLink = UrlGuard.looksLikeUrl(trimmed);
        String inputHash = inputHash(trimmed, isLink);
        if (!refresh && inputHash != null) {
            var recent = store.findRecent(inputHash, clock.instant().minus(reuseWindow));
            if (recent.isPresent()) {
                log.info("Reusing report id={} for a repeated input", recent.get().id());
                return recent.get();
            }
        }
        if (!isLink) {
            return run(InputType.TEXT, trimmed, trimmed, "text submitted by a user", null, progress, inputHash);
        }
        progress.stage(VerificationProgress.Stage.READING_INPUT);
        SafeUrlFetcher.FetchedPage page;
        try {
            page = safeUrlFetcher.fetch(trimmed);
        } catch (UnsafeUrlException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VeriFact can't open that link: " + e.getMessage() + ".");
        } catch (FetchFailedException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
        String host = URI.create(page.url()).getHost();
        return run(InputType.URL, trimmed, page.title() + "\n\n" + page.text(), "a web page from the site " + host,
                page.url(), progress, inputHash);
    }

    /**
     * An uploaded image. With {@code image} set, a vision model reads it (text, visible context and
     * claims in one call); if that step fails, or {@code image} is null (vision disabled), the image's
     * OCR text is checked like pasted text instead.
     *
     * @param image   the upload prepared for the model, or null to use OCR only
     * @param ocrText runs OCR on the upload; only called when falling back
     */
    public VerificationResult verifyImage(String fileName, ImageInput image, Supplier<String> ocrText,
                                          VerificationProgress progress) {
        long startedAt = System.nanoTime();
        if (image != null) {
            Instant visionStartedAt = clock.instant();
            progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
            ImageExtraction extraction = null;
            try {
                extraction = llm.generateWithImage(VerificationPrompts.IMAGE_EXTRACTION_SYSTEM,
                        VerificationPrompts.imageExtractionUser(today()), image, ImageExtraction.class);
            } catch (ApiException e) {
                // Fall back only on a quick failure (model rejects images, unreadable output). A slow one is
                // usually a timeout or outage: retrying on the OCR path would blow the time budget and the
                // user's 120 s wait, and likely fail the same way.
                Duration spent = Duration.between(visionStartedAt, clock.instant());
                if (e.getStatus() != HttpStatus.BAD_GATEWAY || spent.compareTo(VISION_FALLBACK_BUDGET) > 0) {
                    throw e;
                }
                // Cause type only: a parse error's message can contain the model's transcription of the image.
                log.warn("Vision step failed after {} ms (cause={}), falling back to OCR", spent.toMillis(),
                        e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
            }
            if (extraction != null) {
                List<ExtractedClaim> claims = requireClaims(dropRestatements(cleanClaims(extraction.claims())));
                String visibleText = truncate(extraction.visibleText() == null ? "" : extraction.visibleText().trim(),
                        maxContentChars);
                return complete(startedAt, InputType.IMAGE, displayName(fileName, "Uploaded image"), visibleText,
                        imageContext(extraction), claims, null, progress, null);
            }
        }
        // Timed from the start, so a failed vision attempt counts towards the reported duration.
        return run(startedAt, InputType.IMAGE, displayName(fileName, "Uploaded image"), ocrText.get(),
                OCR_SOURCE_DESCRIPTION, null, progress, null);
    }

    public VerificationResult verifyImageText(String fileName, String ocrText) {
        return verifyImageText(fileName, ocrText, VerificationProgress.NONE);
    }

    public VerificationResult verifyImageText(String fileName, String ocrText, VerificationProgress progress) {
        return run(InputType.IMAGE, displayName(fileName, "Uploaded image"), ocrText,
                OCR_SOURCE_DESCRIPTION, null, progress, null);
    }

    public VerificationResult verifyAudioTranscript(String fileName, String transcript) {
        return verifyAudioTranscript(fileName, transcript, VerificationProgress.NONE);
    }

    public VerificationResult verifyAudioTranscript(String fileName, String transcript, VerificationProgress progress) {
        return run(InputType.AUDIO, displayName(fileName, "Uploaded audio"), transcript,
                "a transcript of audio a user uploaded", null, progress, null);
    }

    // ---------------------------------------------------------------- pipeline

    /**
     * @param sourceUrl for link input, the page that was checked; it and its site are excluded from
     *                  the evidence so an article can't corroborate itself
     */
    VerificationResult run(InputType inputType, String displayInput, String rawContent, String sourceDescription,
                           String sourceUrl, VerificationProgress progress, String inputHash) {
        return run(System.nanoTime(), inputType, displayInput, rawContent, sourceDescription, sourceUrl, progress,
                inputHash);
    }

    private VerificationResult run(long startedAt, InputType inputType, String displayInput, String rawContent,
                                   String sourceDescription, String sourceUrl, VerificationProgress progress,
                                   String inputHash) {
        String content = truncate(rawContent.trim(), maxContentChars);

        // 1. Claims and queries
        progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
        String nonce = nonce();
        ClaimExtraction extraction = llm.generate(
                VerificationPrompts.withNonce(VerificationPrompts.EXTRACTION_SYSTEM, nonce),
                VerificationPrompts.extractionUser(nonce, sourceDescription, content, today()),
                ClaimExtraction.class);
        List<ExtractedClaim> claims = requireClaims(cleanClaims(extraction == null ? null : extraction.claims()));
        return complete(startedAt, inputType, displayInput, content, null, claims, sourceUrl, progress, inputHash);
    }

    /** Steps 2 and 3, shared by every input type once claims are known: evidence, assessment, validation, save. */
    private VerificationResult complete(long startedAt, InputType inputType, String displayInput, String content,
                                        ImageContext imageContext, List<ExtractedClaim> claims, String sourceUrl,
                                        VerificationProgress progress, String inputHash) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());

        // 2. Evidence
        List<String> claimTexts = claims.stream().map(ExtractedClaim::claim).toList();
        progress.claims(claimTexts);
        progress.stage(VerificationProgress.Stage.SEARCHING);
        List<Evidence> evidence = gatherEvidence(claims, now, sourceUrl);
        progress.sources(evidence.size(), evidence.stream().map(Evidence::domain).distinct().limit(8).toList());

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
            progress.stage(VerificationProgress.Stage.ASSESSING);
            String assessmentNonce = nonce();
            Assessment assessment = llm.generate(
                    VerificationPrompts.withNonce(VerificationPrompts.ASSESSMENT_SYSTEM, assessmentNonce),
                    VerificationPrompts.assessmentUser(assessmentNonce, claimTexts, evidence, today),
                    Assessment.class);
            Validation validation = validate(claimTexts, assessment, evidence, limitations);
            assessments = validation.claims();
            summary = cleanText(assessment.summary(), 400);
            boolean allInsufficient = assessments.stream().allMatch(a -> a.verdict() == Verdict.INSUFFICIENT_EVIDENCE);
            if (allInsufficient) {
                // Never ship a model-written conclusion the evidence didn't back.
                summary = "The sources found don't clearly confirm or refute this.";
            } else if (validation.anyDowngraded()) {
                summary = "Some claims couldn't be established from the sources found. See each claim below.";
            } else if (summary.isBlank()) {
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

        if (inputType == InputType.IMAGE) {
            limitations.add(IMAGE_LIMITATION);
        }

        OverallVerdict overall = OverallVerdict.of(assessments.stream().map(ClaimAssessment::verdict).toList());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        VerificationResult result = new VerificationResult(
                UUID.randomUUID(), now, inputType, truncate(displayInput, 2000),
                truncate(content, CHECKED_TEXT_EXCERPT_CHARS), overall, summary, assessments, evidence,
                List.copyOf(new LinkedHashSet<>(limitations)), searchProvider.name(), durationMs, imageContext);

        log.info("Verification done id={} inputType={} claims={} evidence={} overall={} durationMs={}",
                result.id(), inputType, assessments.size(), evidence.size(), overall, durationMs);
        store.save(result, inputHash);
        return result;
    }

    /**
     * Vision models sometimes return a statement twice: "X" and "physicists confirm X". Checking both
     * turns a clear verdict into MIXED (the attribution usually can't be sourced). Keeps one: the
     * attributed version for real quotes ("[name] said X"), where who said it is the point, else plain X.
     */
    static List<ExtractedClaim> dropRestatements(List<ExtractedClaim> claims) {
        List<ExtractedClaim> kept = new ArrayList<>(claims);
        for (ExtractedClaim inner : claims) {
            for (ExtractedClaim wrapper : claims) {
                String in = " " + words(inner.claim()) + " ";
                String out = " " + words(wrapper.claim()) + " ";
                if (inner == wrapper || in.isBlank() || out.length() <= in.length() || !out.contains(in)) {
                    continue;
                }
                kept.remove(QUOTE_ATTRIBUTION.matcher(wrapper.claim()).find() ? inner : wrapper);
            }
        }
        return kept;
    }

    private static String words(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static List<ExtractedClaim> requireClaims(List<ExtractedClaim> claims) {
        if (claims.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "VeriFact couldn't find a specific factual claim to check. "
                            + "Try stating it directly, e.g. \"The Eiffel Tower is 330 metres tall.\"");
        }
        return claims;
    }

    private List<ExtractedClaim> cleanClaims(List<ExtractedClaim> extracted) {
        List<ExtractedClaim> claims = new ArrayList<>();
        if (extracted == null) {
            return claims;
        }
        for (ExtractedClaim c : extracted) {
            String text = cleanText(c == null ? null : c.claim(), 500);
            if (text.isBlank()) {
                continue;
            }
            List<String> queries = new ArrayList<>();
            if (c.searchQueries() != null) {
                c.searchQueries().stream()
                        .map(VerificationService::cleanQuery)
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

    /** Plain keywords only: search operators and exclusion terms are removed. */
    static String cleanQuery(String query) {
        String q = cleanText(query, 200);
        q = SEARCH_OPERATOR.matcher(q).replaceAll(" ");
        q = EXCLUSION_TERM.matcher(q).replaceAll(" ");
        return q.replaceAll("\\s+", " ").trim();
    }

    /**
     * Runs each claim's first query, then second queries, within the search budget. Each claim gets
     * a fair share of evidence slots, and the checked page's own site is excluded.
     */
    private List<Evidence> gatherEvidence(List<ExtractedClaim> claims, Instant now, String sourceUrl) {
        List<String> queries = new ArrayList<>();
        List<Integer> queryClaim = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < claims.size(); i++) {
                ExtractedClaim claim = claims.get(i);
                if (claim.searchQueries().size() > round && queries.size() < MAX_SEARCHES) {
                    queries.add(claim.searchQueries().get(round));
                    queryClaim.add(i);
                }
            }
        }
        int perClaimCap = (MAX_EVIDENCE + claims.size() - 1) / claims.size();
        int[] perClaim = new int[claims.size()];
        String excludedSite = sourceUrl == null ? null : registrableDomain(domain(sourceUrl));

        Map<String, Evidence> byUrl = new LinkedHashMap<>();
        int failures = 0;
        int socialCount = 0;
        for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            int claimIndex = queryClaim.get(qi);
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
                if (byUrl.size() >= MAX_EVIDENCE || taken >= RESULTS_PER_SEARCH || perClaim[claimIndex] >= perClaimCap) {
                    break;
                }
                String key = normalizeUrl(r.url());
                String domain = domain(r.url());
                if (key == null || domain == null || byUrl.containsKey(key)) {
                    continue;
                }
                if (excludedSite != null && excludedSite.equals(registrableDomain(domain))) {
                    continue; // the checked page (or its own site) can't be evidence for itself
                }
                SourceType type = SourceType.classify(r.url(), domain, registrableDomain(domain));
                if (type == SourceType.SOCIAL && socialCount >= MAX_SOCIAL) {
                    continue; // user-generated content is kept to a minimum
                }
                if (type == SourceType.SOCIAL) {
                    socialCount++;
                }
                String id = "E" + (byUrl.size() + 1);
                byUrl.put(key, new Evidence(id, r.url(), domain, cleanText(r.title(), 300),
                        cleanText(r.snippet(), MAX_SNIPPET_CHARS), emptyToNull(cleanText(r.publishedDate(), 40)), now,
                        type));
                taken++;
                perClaim[claimIndex]++;
            }
        }
        if (failures == queries.size()) {
            // Without search the only alternative is the model's memory; refuse instead.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Web search is unavailable right now, so VeriFact can't gather evidence. Please try again later.");
        }
        return rankAndNumber(new ArrayList<>(byUrl.values()));
    }

    /** Orders evidence by source type (fact-checkers first, social last) and renumbers E1..En. */
    static List<Evidence> rankAndNumber(List<Evidence> evidence) {
        List<Evidence> sorted = new ArrayList<>(evidence);
        sorted.sort(java.util.Comparator.comparingInt(
                e -> e.sourceType() == null ? SourceType.OTHER.ordinal() : e.sourceType().ordinal()));
        List<Evidence> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Evidence e = sorted.get(i);
            out.add(new Evidence("E" + (i + 1), e.url(), e.domain(), e.title(), e.snippet(), e.publishedDate(),
                    e.retrievedAt(), e.sourceType()));
        }
        return out;
    }

    /**
     * Enforces the evidence rules on the model's answer: unknown citations are dropped, verdicts
     * without matching citations are downgraded, and strength is computed from cited domains.
     */
    Validation validate(List<String> claimTexts, Assessment assessment, List<Evidence> evidence,
                        List<String> limitations) {
        boolean anyDowngraded = false;
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

            // Social media / forum posts can be cited, but never be the only basis for a verdict.
            boolean backed = switch (verdict) {
                // MISLEADING = accurate facts, false impression: needs a source for the facts and an explanation.
                case SUPPORTED, PARTLY_SUPPORTED -> hasNonSocial(supporting, evidenceById);
                case MISLEADING -> hasNonSocial(supporting, evidenceById) && !explanation.isBlank();
                case CONTRADICTED -> hasNonSocial(contradicting, evidenceById);
                case INSUFFICIENT_EVIDENCE -> true;
            };
            if (!backed) {
                verdict = Verdict.INSUFFICIENT_EVIDENCE;
                explanation = NOT_ESTABLISHED;
                anyDowngraded = true;
            }
            if (explanation.isBlank() || refersToUnknownEvidence(explanation, evidenceById)) {
                explanation = verdict == Verdict.INSUFFICIENT_EVIDENCE ? NOT_ESTABLISHED : "See the cited sources.";
            }

            EvidenceStrength strength = strength(verdict, supporting, contradicting, evidenceById);
            boolean disputed = isDisputed(verdict, supporting, contradicting);
            if (disputed) {
                limitations.add("Sources disagree about claim " + claimId + ".");
            }
            out.add(new ClaimAssessment(claimId, claimTexts.get(i), verdict, strength, explanation,
                    List.copyOf(supporting), List.copyOf(contradicting)));
        }
        return new Validation(out, anyDowngraded);
    }

    private static boolean hasNonSocial(List<String> ids, Map<String, Evidence> evidenceById) {
        return ids.stream().anyMatch(id -> !evidenceById.get(id).isSocial());
    }

    static boolean isDisputed(Verdict verdict, List<String> supporting, List<String> contradicting) {
        return switch (verdict) {
            case SUPPORTED, PARTLY_SUPPORTED, MISLEADING -> !contradicting.isEmpty();
            case CONTRADICTED -> !supporting.isEmpty();
            case INSUFFICIENT_EVIDENCE -> false;
        };
    }

    private static boolean refersToUnknownEvidence(String explanation, Map<String, Evidence> evidenceById) {
        Matcher m = EVIDENCE_REF.matcher(explanation);
        while (m.find()) {
            if (!evidenceById.containsKey(m.group())) {
                return true;
            }
        }
        return false;
    }

    static EvidenceStrength strength(Verdict verdict, List<String> supporting, List<String> contradicting,
                                     Map<String, Evidence> evidenceById) {
        List<String> direction = switch (verdict) {
            case SUPPORTED, PARTLY_SUPPORTED, MISLEADING -> supporting;
            case CONTRADICTED -> contradicting;
            case INSUFFICIENT_EVIDENCE -> List.of();
        };
        // Independent sources = distinct registrable domains (a.blogspot.com and b.blogspot.com are separate
        // sites, but news.bbc.co.uk and www.bbc.co.uk are one).
        Set<String> domains = new LinkedHashSet<>();
        for (String id : direction) {
            Evidence e = evidenceById.get(id);
            if (!e.isSocial()) { // user-generated posts don't count as independent sources
                domains.add(registrableDomain(e.domain()));
            }
        }
        EvidenceStrength strength = domains.size() >= 3 ? EvidenceStrength.STRONG
                : domains.size() == 2 ? EvidenceStrength.MODERATE
                : EvidenceStrength.LIMITED;
        if (isDisputed(verdict, supporting, contradicting) && strength == EvidenceStrength.STRONG) {
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

    /**
     * The site that owns a host, per the public suffix list: news.bbc.co.uk → bbc.co.uk. Hosts on
     * shared-hosting suffixes (e.g. blogspot.com) stay distinct per site. IPs and unknowns pass through.
     */
    static String registrableDomain(String host) {
        if (host == null) {
            return null;
        }
        try {
            InternetDomainName name = InternetDomainName.from(host);
            if (name.isUnderPublicSuffix()) {
                return name.topPrivateDomain().toString();
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // IP literal or invalid name: fall through
        }
        return host;
    }

    /**
     * Identity of an input for reuse: SHA-256 over the normalised link, or over the text in lower
     * case with whitespace collapsed and surrounding quotes/end punctuation removed. So
     * "The earth is flat." and "the  earth is flat" share a report.
     */
    static String inputHash(String input, boolean isLink) {
        String key;
        if (isLink) {
            String url = normalizeUrl(input);
            if (url == null) {
                return null;
            }
            key = "url:" + url;
        } else {
            String text = input.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                    .replaceAll("^[\"'“”‘’\\s]+|[\"'“”‘’.!?\\s]+$", "");
            if (text.isEmpty()) {
                return null;
            }
            key = "text:" + text;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    /**
     * The model's reading of the image, normalised. It's shown to readers as context, so it is
     * length-capped, and a field that passes judgement (e.g. "verified by Reuters") is dropped.
     */
    static ImageContext imageContext(ImageExtraction extraction) {
        return new ImageContext(ImageContext.Kind.parse(extraction.imageKind()),
                neutral(cleanText(extraction.shownSource(), 120)),
                neutral(cleanText(extraction.shownDate(), 60)),
                neutral(cleanText(extraction.description(), 400)));
    }

    private static String neutral(String text) {
        return text.isBlank() || JUDGEMENT_WORDS.matcher(text).find() || text.toLowerCase(Locale.ROOT).contains("verifact")
                ? null : text;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
