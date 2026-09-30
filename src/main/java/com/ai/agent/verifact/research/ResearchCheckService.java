package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.ResearchCheck.CheckedClaim;
import com.ai.agent.verifact.research.ResearchCheck.CheckedReference;
import com.ai.agent.verifact.research.ResearchCheck.ConflictingWork;
import com.ai.agent.verifact.research.ResearchCheck.ReferenceStatus;
import com.ai.agent.verifact.research.ResearchCheck.Support;
import com.ai.agent.verifact.research.ResearchCheck.WorkSummary;
import com.ai.agent.verifact.research.ResearchOutputs.CitationExtraction;
import com.ai.agent.verifact.research.ResearchOutputs.CitedClaim;
import com.ai.agent.verifact.research.ResearchOutputs.ClaimReview;
import com.ai.agent.verifact.research.ResearchOutputs.Conflict;
import com.ai.agent.verifact.research.ResearchOutputs.ExtractedReference;
import com.ai.agent.verifact.research.ResearchOutputs.SupportReview;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ai.agent.verifact.evidence.Grounding.material;
import static com.ai.agent.verifact.evidence.Grounding.words;

/**
 * ResearchFact citation check (ADR-13). Two model calls, scholarly indexes in between:
 *
 * <pre>
 * text ─► [LLM 1] references (as written) + the claims that cite them
 *      ─► resolve each reference: Crossref (DOI or bibliographic match) + OpenAlex (retraction, abstract)
 *      ─► related works with abstracts per claim (OpenAlex search, capped)
 *      ─► [LLM 2] does each cited abstract support its claim; do related abstracts conflict?
 *      ─► checks: verbatim quotes from the right abstract, else NEEDS_REVIEW / dropped
 * </pre>
 *
 * Existence, match and retraction status are decided in code from index data, never by the model.
 * "Not found" and "lookup failed" are kept apart: an outage must never make a reference look fake.
 */
@Service
public class ResearchCheckService {

    private static final Logger log = LoggerFactory.getLogger(ResearchCheckService.class);

    static final int MAX_REFERENCES = 12;
    static final int MAX_CLAIMS = 8;
    static final int MAX_RELATED_SEARCHES = 4;
    static final int RELATED_PER_SEARCH = 3;
    static final int MAX_ABSTRACT_CHARS = 1500;
    static final int MAX_QUOTE_CHARS = 300;
    static final double TITLE_MATCH = 0.75;

    static final String NOTICE = "Automated check against Crossref and OpenAlex records and abstracts. An abstract "
            + "doesn't contain everything a paper says: confirm important points in the full text. \"Not found\" can "
            + "mean a work isn't indexed (books, reports, some preprints), not only that it doesn't exist.";

    private static final Pattern DOI = Pattern.compile("(?i)\\b(10\\.\\d{4,9}/[^\\s\"<>]+)");
    private static final Pattern YEAR = Pattern.compile("\\b(1[89]\\d{2}|20\\d{2})\\b");
    private static final Set<String> TITLE_STOPWORDS = Set.of("the", "a", "an", "of", "and", "in", "on", "for", "to", "with", "by", "at", "from");

    private final LlmClient llm;
    private final ScholarlyIndex index;
    private final Clock clock;

    public ResearchCheckService(LlmClient llm, ScholarlyIndex index, Clock clock) {
        this.llm = llm;
        this.index = index;
        this.clock = clock;
    }

    public ResearchCheck check(String text, VerificationProgress progress) {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        String input = material(text);
        List<String> limitations = new ArrayList<>();

        // 1. References and the claims citing them
        progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
        String nonce = nonce();
        CitationExtraction extraction = llm.generate(ResearchPrompts.withNonce(ResearchPrompts.EXTRACT_SYSTEM, nonce),
                ResearchPrompts.extractUser(nonce, text.trim(), LocalDate.ofInstant(now, clock.getZone())),
                CitationExtraction.class);
        List<ExtractedReference> refs = references(extraction, text, input);
        if (refs.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ResearchFact couldn't find any citations. Paste text that cites research: in-text citations with a "
                            + "reference list, or DOIs.");
        }
        Set<String> refIds = new HashSet<>(refs.stream().map(ExtractedReference::id).toList());
        List<CitedClaim> claims = claims(extraction, input, refIds);
        progress.claims(claims.stream().map(CitedClaim::claim).toList());

        // 2. Resolve every reference in the scholarly indexes
        progress.stage(VerificationProgress.Stage.SEARCHING);
        Map<String, Resolved> resolved = new LinkedHashMap<>();
        for (ExtractedReference r : refs) {
            resolved.put(r.id(), resolve(r, text));
        }
        if (resolved.values().stream().anyMatch(x -> x.status() == ReferenceStatus.LOOKUP_FAILED)) {
            limitations.add("Some references couldn't be looked up because a scholarly index was unavailable; they are "
                    + "marked \"lookup failed\", not \"not found\".");
        }

        // 3. Related research per claim (abstracts only), excluding what the text already cites
        Set<String> citedDois = new HashSet<>();
        resolved.values().forEach(x -> {
            if (x.work() != null && x.work().doi() != null) {
                citedDois.add(x.work().doi());
            }
        });
        Map<String, ScholarlyWork> related = new LinkedHashMap<>();
        int searches = 0;
        for (CitedClaim c : claims) {
            if (searches == MAX_RELATED_SEARCHES || c.searchQuery() == null || c.searchQuery().isBlank()) {
                continue;
            }
            searches++;
            try {
                for (ScholarlyWork w : index.related(clean(c.searchQuery(), 200), RELATED_PER_SEARCH)) {
                    if (w.abstractText() != null && (w.doi() == null || !citedDois.contains(w.doi()))
                            && related.values().stream().noneMatch(x -> x.doi() != null && x.doi().equals(w.doi()))) {
                        related.put("P" + (related.size() + 1), w);
                    }
                }
            } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
                log.warn("Related-work search failed: {}", e.getMessage());
            }
        }
        progress.sources((int) resolved.values().stream().filter(x -> x.work() != null).count(),
                resolved.values().stream().map(Resolved::work).filter(w -> w != null && w.venue() != null)
                        .map(ScholarlyWork::venue).distinct().limit(8).toList());

        // 4. Support and conflicts, from abstracts only
        Map<String, ClaimReview> reviews = new LinkedHashMap<>();
        List<ResearchPrompts.ClaimBlock> blocks = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            List<ResearchPrompts.AbstractLine> cited = new ArrayList<>();
            for (String rid : claims.get(i).referenceIds()) {
                ScholarlyWork w = resolved.get(rid).work();
                if (w != null && w.abstractText() != null) {
                    cited.add(new ResearchPrompts.AbstractLine(rid, w.title(), w.year(), truncate(w.abstractText())));
                }
            }
            if (!cited.isEmpty()) {
                blocks.add(new ResearchPrompts.ClaimBlock("C" + (i + 1), claims.get(i).claim(), cited));
            }
        }
        if (!blocks.isEmpty()) {
            progress.stage(VerificationProgress.Stage.ASSESSING);
            String reviewNonce = nonce();
            SupportReview review = llm.generate(ResearchPrompts.withNonce(ResearchPrompts.REVIEW_SYSTEM, reviewNonce),
                    ResearchPrompts.reviewUser(reviewNonce, blocks, related.entrySet().stream()
                            .map(e -> new ResearchPrompts.AbstractLine(e.getKey(), e.getValue().title(), e.getValue().year(),
                                    truncate(e.getValue().abstractText()))).toList()),
                    SupportReview.class);
            if (review != null && review.claims() != null) {
                for (ClaimReview r : review.claims()) {
                    if (r != null && r.claimId() != null) {
                        reviews.putIfAbsent(r.claimId().trim().toUpperCase(Locale.ROOT), r);
                    }
                }
            }
        }

        List<CheckedClaim> checkedClaims = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            String id = "C" + (i + 1);
            checkedClaims.add(validate(id, claims.get(i), reviews.get(id), resolved, related));
        }
        List<CheckedReference> checkedRefs = refs.stream().map(r -> {
            Resolved x = resolved.get(r.id());
            return new CheckedReference(r.id(), r.text(), x.status(), x.differences(), summary(x.work()));
        }).toList();

        Map<ReferenceStatus, Integer> refCounts = new EnumMap<>(ReferenceStatus.class);
        for (ReferenceStatus s : ReferenceStatus.values()) {
            refCounts.put(s, 0);
        }
        checkedRefs.forEach(r -> refCounts.merge(r.status(), 1, Integer::sum));
        Map<Support, Integer> supportCounts = new EnumMap<>(Support.class);
        for (Support s : Support.values()) {
            supportCounts.put(s, 0);
        }
        checkedClaims.forEach(c -> supportCounts.merge(c.support(), 1, Integer::sum));
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        // Counts only: the user's text stays out of the logs.
        log.info("Research check done references={} claims={} related={} refCounts={} support={} durationMs={}",
                checkedRefs.size(), checkedClaims.size(), related.size(), refCounts, supportCounts, durationMs);
        return new ResearchCheck(now, checkedRefs, checkedClaims, refCounts, supportCounts, List.copyOf(limitations),
                NOTICE, durationMs);
    }

    // ---------------------------------------------------------------- references

    record Resolved(ReferenceStatus status, List<String> differences, ScholarlyWork work) {}

    /**
     * DOI first (only a DOI actually present in the text; a model-supplied one could be invented), else a
     * bibliographic match that must clear the title-similarity bar. Retraction overrides everything.
     */
    Resolved resolve(ExtractedReference r, String text) {
        try {
            String doi = doiInText(r, text);
            ScholarlyWork work = null;
            if (doi != null) {
                work = index.byDoi(doi).orElse(null);
                if (work == null) {
                    return new Resolved(ReferenceStatus.NOT_FOUND, List.of("The DOI " + doi + " isn't registered with Crossref or DataCite."), null);
                }
            } else {
                for (ScholarlyWork candidate : index.byReference(r.text(), 3)) {
                    if (matches(r, candidate)) {
                        work = candidate.doi() == null ? candidate : index.byDoi(candidate.doi()).orElse(candidate);
                        break;
                    }
                }
                if (work == null) {
                    return new Resolved(ReferenceStatus.NOT_FOUND, List.of("No matching record in Crossref."), null);
                }
            }
            List<String> differences = differences(r, work);
            ReferenceStatus status = work.retracted() ? ReferenceStatus.RETRACTED
                    : differences.isEmpty() ? ReferenceStatus.VERIFIED : ReferenceStatus.FOUND_WITH_DIFFERENCES;
            return new Resolved(status, differences, work);
        } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
            log.warn("Reference lookup failed: {}", e.getMessage());
            return new Resolved(ReferenceStatus.LOOKUP_FAILED, List.of(), null);
        }
    }

    static String doiInText(ExtractedReference r, String text) {
        for (String candidate : new String[]{r.doi(), r.text()}) {
            if (candidate == null) {
                continue;
            }
            Matcher m = DOI.matcher(candidate);
            if (m.find()) {
                String doi = m.group(1).replaceAll("[.,;:)\\]]+$", "").toLowerCase(Locale.ROOT);
                if (text.toLowerCase(Locale.ROOT).contains(doi)) {
                    return doi;
                }
            }
        }
        return null;
    }

    /** A bibliographic candidate counts only if its title matches the reference's title (or text). */
    static boolean matches(ExtractedReference r, ScholarlyWork w) {
        if (w.title() == null) {
            return false;
        }
        if (r.title() != null && !r.title().isBlank()) {
            return titleSimilarity(r.title(), w.title()) >= TITLE_MATCH;
        }
        List<String> titleWords = contentWords(w.title());
        if (titleWords.size() < 3) {
            return false;
        }
        String ref = " " + words(r.text()) + " ";
        long found = titleWords.stream().filter(t -> ref.contains(" " + t + " ")).count();
        return found >= Math.ceil(titleWords.size() * 0.8);
    }

    static List<String> differences(ExtractedReference r, ScholarlyWork w) {
        List<String> out = new ArrayList<>();
        Integer year = year(r.year());
        if (year != null && w.year() != null && Math.abs(year - w.year()) > 1) {
            out.add("Year: " + year + " in the text, " + w.year() + " in the record.");
        }
        String surname = r.firstAuthor() == null ? "" : words(r.firstAuthor());
        if (!surname.isBlank() && !w.authors().isEmpty()) {
            String first = " " + words(w.authors().get(0)) + " ";
            if (!first.contains(" " + surname.split(" ")[surname.split(" ").length - 1] + " ")) {
                out.add("First author: \"" + clean(r.firstAuthor(), 60) + "\" in the text, \"" + w.authors().get(0) + "\" in the record.");
            }
        }
        if (r.title() != null && !r.title().isBlank() && w.title() != null && titleSimilarity(r.title(), w.title()) < TITLE_MATCH) {
            out.add("Title differs from the record: \"" + clean(w.title(), 160) + "\".");
        }
        return out;
    }

    static double titleSimilarity(String a, String b) {
        Set<String> x = new HashSet<>(contentWords(a));
        Set<String> y = new HashSet<>(contentWords(b));
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static List<String> contentWords(String s) {
        return Arrays.stream(words(s == null ? "" : s).split(" "))
                .filter(t -> !t.isBlank() && !TITLE_STOPWORDS.contains(t)).toList();
    }

    private static Integer year(String s) {
        Matcher m = YEAR.matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    // ---------------------------------------------------------------- claims

    private static List<ExtractedReference> references(CitationExtraction extraction, String text, String input) {
        List<ExtractedReference> out = new ArrayList<>();
        if (extraction == null || extraction.references() == null) {
            return out;
        }
        for (ExtractedReference r : extraction.references()) {
            if (r == null || r.text() == null || r.id() == null) {
                continue;
            }
            String t = clean(r.text(), 500);
            String w = words(t);
            boolean hasDoi = doiInText(r, text) != null;
            if (!hasDoi && (w.length() < 6 || !input.contains(" " + w + " "))) {
                continue; // not actually in the text
            }
            out.add(new ExtractedReference(r.id().trim().toUpperCase(Locale.ROOT), t, r.doi(), clean(r.title(), 300),
                    clean(r.firstAuthor(), 80), clean(r.year(), 10)));
            if (out.size() == MAX_REFERENCES) {
                break;
            }
        }
        return out;
    }

    private static List<CitedClaim> claims(CitationExtraction extraction, String input, Set<String> refIds) {
        List<CitedClaim> out = new ArrayList<>();
        if (extraction == null || extraction.claims() == null) {
            return out;
        }
        for (CitedClaim c : extraction.claims()) {
            if (c == null || c.quote() == null || c.referenceIds() == null) {
                continue;
            }
            String q = words(c.quote());
            List<String> ids = c.referenceIds().stream().filter(x -> x != null)
                    .map(x -> x.trim().toUpperCase(Locale.ROOT)).filter(refIds::contains).distinct().toList();
            if (q.split(" ").length < 4 || !input.contains(" " + q + " ") || ids.isEmpty()) {
                continue;
            }
            String quote = clean(c.quote(), 400);
            String claim = clean(c.claim(), 400);
            if (claim.isBlank() || !Grounding.supported(claim, input)) {
                claim = quote;
            }
            out.add(new CitedClaim(quote, claim, ids, clean(c.searchQuery(), 200)));
            if (out.size() == MAX_CLAIMS) {
                break;
            }
        }
        return out;
    }

    /**
     * Support comes from code when there's nothing to judge (citation not found, no abstract); otherwise
     * the model's verdict stands only with a verbatim quote from an abstract of a work the claim cites.
     */
    static CheckedClaim validate(String id, CitedClaim c, ClaimReview review, Map<String, Resolved> resolved,
                                 Map<String, ScholarlyWork> related) {
        List<ScholarlyWork> found = c.referenceIds().stream().map(resolved::get).map(Resolved::work)
                .filter(w -> w != null).toList();
        if (found.isEmpty()) {
            return new CheckedClaim(id, c.quote(), c.claim(), c.referenceIds(), Support.CITATION_PROBLEM, null, null,
                    "The cited reference couldn't be found, so support can't be checked.", List.of());
        }
        if (found.stream().allMatch(w -> w.abstractText() == null)) {
            return new CheckedClaim(id, c.quote(), c.claim(), c.referenceIds(), Support.NO_ABSTRACT, null, null,
                    "No abstract is available for the cited work; check the full text.", List.of());
        }
        Support support = parse(review == null ? null : review.support());
        String from = review == null || review.evidenceFrom() == null ? null : review.evidenceFrom().trim().toUpperCase(Locale.ROOT);
        String quote = null;
        if (support != Support.NOT_ADDRESSED_IN_ABSTRACT) {
            ScholarlyWork source = from != null && c.referenceIds().contains(from) && resolved.get(from).work() != null
                    ? resolved.get(from).work() : null;
            quote = source == null ? null : verbatim(review.evidenceQuote(), source.abstractText());
            if (quote == null) {
                support = Support.NEEDS_REVIEW;
                from = null;
            }
        } else {
            from = null;
        }
        String material = material(c.claim(), quote == null ? "" : quote);
        String note = review == null ? null : Grounding.supported(clean(review.note(), 300), material)
                ? emptyToNull(clean(review.note(), 300)) : null;
        List<ConflictingWork> conflicting = new ArrayList<>();
        if (review != null && review.conflicts() != null) {
            for (Conflict k : review.conflicts()) {
                ScholarlyWork w = k == null || k.paperId() == null ? null : related.get(k.paperId().trim().toUpperCase(Locale.ROOT));
                String q = w == null ? null : verbatim(k.quote(), w.abstractText());
                if (q != null && conflicting.size() < 3) {
                    String kNote = clean(k.note(), 300);
                    conflicting.add(new ConflictingWork(summary(w), q,
                            Grounding.supported(kNote, material(c.claim(), q)) ? emptyToNull(kNote) : null));
                }
            }
        }
        return new CheckedClaim(id, c.quote(), c.claim(), c.referenceIds(), support, from, quote, note, conflicting);
    }

    /** The quote, if at least five words of it appear verbatim in the abstract; capped for display. */
    static String verbatim(String quote, String abstractText) {
        if (quote == null || abstractText == null) {
            return null;
        }
        String q = words(quote);
        if (q.split(" ").length < 5 || !material(abstractText).contains(" " + q + " ")) {
            return null;
        }
        String c = clean(quote, MAX_QUOTE_CHARS);
        return c.length() == MAX_QUOTE_CHARS ? c + "…" : c;
    }

    private static Support parse(String s) {
        if (s == null) {
            return Support.NOT_ADDRESSED_IN_ABSTRACT;
        }
        try {
            Support v = Support.valueOf(s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
            return switch (v) {
                case SUPPORTED, PARTIALLY_SUPPORTED, OVERSTATED, CONTRADICTED, NOT_ADDRESSED_IN_ABSTRACT -> v;
                default -> Support.NEEDS_REVIEW; // the model doesn't get to set code-decided statuses
            };
        } catch (IllegalArgumentException e) {
            return Support.NEEDS_REVIEW;
        }
    }

    static WorkSummary summary(ScholarlyWork w) {
        if (w == null) {
            return null;
        }
        List<String> authors = w.authors().size() > 3
                ? List.of(w.authors().get(0), w.authors().get(1), w.authors().get(2), "et al.") : w.authors();
        return new WorkSummary(w.doi(), w.url(), w.title(), authors, w.year(), w.venue(), w.publisher(), w.citedByCount(),
                w.notices(), w.abstractText() != null);
    }

    private static String truncate(String s) {
        return s.length() > MAX_ABSTRACT_CHARS ? s.substring(0, MAX_ABSTRACT_CHARS) : s;
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
