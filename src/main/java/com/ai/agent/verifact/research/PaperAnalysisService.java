package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.PaperAnalysis.Aspect;
import com.ai.agent.verifact.research.PaperAnalysis.Match;
import com.ai.agent.verifact.research.PaperAnalysis.MatchStatus;
import com.ai.agent.verifact.research.PaperAnalysis.MethodPart;
import com.ai.agent.verifact.research.PaperAnalysis.Quoted;
import com.ai.agent.verifact.research.StudentOutputs.MethodNote;
import com.ai.agent.verifact.research.StudentOutputs.PaperNote;
import com.ai.agent.verifact.research.StudentOutputs.PaperReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a research paper a student uploaded (ADR-23): one model pass for a plain-language explanation, findings,
 * method and the authors' limitations, then checks in code. Quotes must be in the file word for word, numbers in a
 * statement must be in its quote (or, for the summary, in the paper), and the paper is matched to an index record
 * by the DOI printed on it or by its title. Nothing here names other studies.
 */
@Service
public class PaperAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PaperAnalysisService.class);

    static final int MAX_MODEL_CHARS = 40_000;
    /** Where a paper's own title, authors and DOI are printed. */
    static final int FRONT_CHARS = 4_000;
    static final int MAX_QUOTE_CHARS = 400;
    static final int MAX_FINDINGS = 5;
    static final int MAX_LIMITATIONS = 3;
    static final String NOTICE = "Explanations and summaries are AI readings of the paper. Every finding, method detail and "
            + "limitation shown is backed by the paper's own words; read them in the paper before you cite it.";

    private static final Pattern DOI = Pattern.compile("\\b10\\.\\d{4,9}/[^\\s\"<>]+", Pattern.CASE_INSENSITIVE);

    private final LlmClient llm;
    private final ScholarlyIndex index;
    private final Clock clock;

    public PaperAnalysisService(LlmClient llm, ScholarlyIndex index, Clock clock) {
        this.llm = llm;
        this.index = index;
        this.clock = clock;
    }

    public PaperAnalysis analyze(DocumentExtractor.Extracted doc, String country) {
        String text = doc.text();
        List<String> limitations = new ArrayList<>();
        if (doc.truncated()) {
            limitations.add("Only the start of the file was read (limits: " + String.format(Locale.ROOT, "%,d", DocumentExtractor.MAX_CHARS)
                    + " characters, " + DocumentExtractor.MAX_PAGES + " PDF pages).");
        }
        String forModel = text.length() > MAX_MODEL_CHARS ? text.substring(0, MAX_MODEL_CHARS) : text;
        if (text.length() > MAX_MODEL_CHARS) {
            limitations.add("The explanation reads the first " + String.format(Locale.ROOT, "%,d", MAX_MODEL_CHARS)
                    + " characters, so results late in a long paper may be missed.");
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        PaperReading reading = llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.PAPER_SYSTEM, nonce),
                "<<<PAPER_" + nonce + ">>>\n" + forModel + "\n<<<END_PAPER_" + nonce + ">>>", PaperReading.class);

        String material = Grounding.material(forModel);
        String summary = reading == null ? "" : Grounding.supportedSentences(cap(reading.plainSummary(), 1500), material);
        List<Quoted> findings = quoted(reading == null ? null : reading.findings(), forModel, MAX_FINDINGS);
        List<MethodPart> method = method(reading == null ? null : reading.method(), forModel);
        List<Quoted> authorLimitations = quoted(reading == null ? null : reading.limitations(), forModel, MAX_LIMITATIONS);
        Match match = match(text, reading, country);
        if (findings.isEmpty()) {
            limitations.add("No findings could be tied to the paper's own words, so none are shown. Read its results and discussion.");
        }
        log.info("Paper analysed chars={} match={} findings={} method={} limitations={}",
                text.length(), match.status(), findings.size(), method.size(), authorLimitations.size());
        return new PaperAnalysis(clock.instant(), match, summary.isBlank() ? null : summary, findings, method, authorLimitations,
                limitations, NOTICE);
    }

    /** Statements kept only with a verbatim quote from the paper, and only if their numbers are in that quote. */
    static List<Quoted> quoted(List<PaperNote> notes, String paper, int max) {
        List<Quoted> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (notes == null) {
            return out;
        }
        for (PaperNote n : notes) {
            if (n == null) {
                continue;
            }
            String statement = cap(n.statement(), 400);
            String quote = span(n.quote(), paper, 8);
            if (statement == null || quote == null || !Grounding.supported(statement, Grounding.material(quote)) || !seen.add(quote)) {
                continue;
            }
            out.add(new Quoted(statement, quote));
            if (out.size() == max) {
                break;
            }
        }
        return out;
    }

    static List<MethodPart> method(List<MethodNote> notes, String paper) {
        List<MethodPart> out = new ArrayList<>();
        Set<Aspect> seen = new LinkedHashSet<>();
        if (notes == null) {
            return out;
        }
        for (MethodNote n : notes) {
            if (n == null || n.aspect() == null) {
                continue;
            }
            Aspect aspect;
            try {
                aspect = Aspect.valueOf(n.aspect().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String statement = cap(n.statement(), 300);
            String quote = span(n.quote(), paper, 6);
            if (statement == null || quote == null || !Grounding.supported(statement, Grounding.material(quote)) || !seen.add(aspect)) {
                continue;
            }
            out.add(new MethodPart(aspect, statement, quote));
        }
        out.sort((a, b) -> a.aspect().compareTo(b.aspect()));
        return out;
    }

    /**
     * The index record for this paper. VERIFIED only when the record's title is in the first page's text; a DOI
     * counts only if printed in the paper itself (never one the model supplied).
     */
    Match match(String text, PaperReading reading, String country) {
        String front = text.length() > FRONT_CHARS ? text.substring(0, FRONT_CHARS) : text;
        try {
            Matcher m = DOI.matcher(front);
            if (m.find()) {
                String doi = m.group().replaceAll("[.,;)\\]]+$", "");
                Optional<ScholarlyWork> work = index.byDoi(doi);
                if (work.isPresent()) {
                    boolean onPage = titleOnPage(work.get().title(), front);
                    return new Match(onPage ? MatchStatus.VERIFIED : MatchStatus.POSSIBLE, source(work.get(), country),
                            onPage ? "DOI printed in the paper; the title matches" : "DOI printed in the paper, but its record's title isn't on the first page: check it's this paper");
                }
            }
            String title = reading == null ? null : cap(reading.title(), 300);
            if (title == null || !titleOnPage(title, front)) {
                return new Match(MatchStatus.NOT_FOUND, null, "No DOI or title could be read from the first page.");
            }
            String query = title + (reading.authors() == null || reading.authors().isEmpty() ? "" : " " + reading.authors().get(0))
                    + (reading.year() == null ? "" : " " + reading.year());
            ScholarlyWork best = null;
            double score = 0;
            for (ScholarlyWork w : index.byReference(query, 3)) {
                double s = ResearchCheckService.titleSimilarity(title, w.title());
                if (s > score) {
                    score = s;
                    best = w;
                }
            }
            if (best == null || score < 0.75) {
                return new Match(MatchStatus.NOT_FOUND, null, "No matching record in Crossref/OpenAlex. Check the details from the paper itself.");
            }
            boolean onPage = score >= 0.9 && titleOnPage(best.title(), front);
            return new Match(onPage ? MatchStatus.VERIFIED : MatchStatus.POSSIBLE, source(best, country),
                    onPage ? "Title on the first page matches the record" : "A similar title was found: check it's this paper");
        } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
            return new Match(MatchStatus.LOOKUP_FAILED, null, "The research database couldn't be reached; try again later.");
        }
    }

    /** Full record (local/foreign, retraction) for saving, when the index has it by DOI. */
    private Discovery.FoundSource source(ScholarlyWork w, String country) {
        if (w.doi() == null) {
            return null;
        }
        return index.work(w.doi()).map(d -> ResearchDiscoveryService.toSource(d, null, null, null, country)).orElse(null);
    }

    /**
     * The title is printed on the first page: anywhere in its text if at least 3 words long, or (short titles like
     * "Deep learning") as a line of its own among the first lines, where titles are printed, not inside a sentence.
     */
    static boolean titleOnPage(String title, String front) {
        String t = Grounding.words(title);
        if (t.isBlank()) {
            return false;
        }
        if (t.split(" ").length >= 3) {
            return Grounding.material(front).contains(" " + t + " ");
        }
        return front.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(8).anyMatch(l -> Grounding.words(l).equals(t));
    }

    private static String span(String quote, String paper, int minWords) {
        String s = Grounding.findSpan(quote, paper, minWords);
        if (s == null) {
            return null;
        }
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > MAX_QUOTE_CHARS ? s.substring(0, MAX_QUOTE_CHARS) + "…" : s;
    }

    private static String cap(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
