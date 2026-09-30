package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.legal.CaseIntelligence.Basis;
import com.ai.agent.verifact.legal.CaseIntelligence.Conflict;
import com.ai.agent.verifact.legal.CaseIntelligence.Fact;
import com.ai.agent.verifact.legal.CaseIntelligence.Issue;
import com.ai.agent.verifact.legal.CaseIntelligence.Jurisdiction;
import com.ai.agent.verifact.legal.CaseIntelligence.LegalSource;
import com.ai.agent.verifact.legal.CaseIntelligence.MissingInformation;
import com.ai.agent.verifact.legal.CaseIntelligence.SourceNote;
import com.ai.agent.verifact.legal.CaseIntelligence.TimelineEvent;
import com.ai.agent.verifact.legal.LegalOutputs.CaseIntake;
import com.ai.agent.verifact.legal.LegalOutputs.IssueSources;
import com.ai.agent.verifact.legal.LegalOutputs.IssueToResearch;
import com.ai.agent.verifact.legal.LegalOutputs.JurisdictionGuess;
import com.ai.agent.verifact.legal.LegalOutputs.MissingItem;
import com.ai.agent.verifact.legal.LegalOutputs.SourceReview;
import com.ai.agent.verifact.legal.LegalOutputs.StatedConflict;
import com.ai.agent.verifact.legal.LegalOutputs.StatedEvent;
import com.ai.agent.verifact.legal.LegalOutputs.StatedFact;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.ai.agent.verifact.legal.Grounding.material;
import static com.ai.agent.verifact.legal.Grounding.words;

/**
 * LegalFact Case Intelligence (ADR-12, legalfact.md). Two model calls, like VeriFact:
 *
 * <pre>
 * description ─► [LLM 1] intake: areas, jurisdiction, facts, timeline, conflicts, issues, missing info
 *             ─► checks: quotes must be in the description, statements must match their quotes, dates
 *                must appear as written, the state must be named in its quote, no figures or case
 *                names the description lacks, advice-like wording removed
 *             ─► search official domains only (federal + curated state) per issue
 *             ─► [LLM 2] which retrieved sources bear on which issue ─► citations and figures checked
 * </pre>
 *
 * Nothing is stored and case text is never logged. Case facts are always USER_STATED: web search
 * can verify what the law says, not what happened to the user.
 */
@Service
public class CaseIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CaseIntelligenceService.class);

    static final int MAX_FACTS = 12;
    static final int MAX_EVENTS = 12;
    static final int MAX_CONFLICTS = 4;
    static final int MAX_ISSUES = 4;
    static final int MAX_MISSING = 8;
    static final int MAX_SEARCHES = 6;
    static final int MAX_SOURCES = 10;
    static final int MAX_NOTES_PER_ISSUE = 4;
    /** Past this, the second model call is skipped so the whole analysis fits the client's wait. */
    static final Duration SOURCE_MATCHING_BUDGET = Duration.ofSeconds(75);

    static final String NOTICE = "AI assistance, not legal advice. Professional review required. LegalFact organises "
            + "the information provided and points to official sources that may be relevant; it does not assess "
            + "whether anyone has a claim.";

    private static final Pattern APPROXIMATE = Pattern.compile(
            "(?i)\\b(about|around|approximately|roughly|early|mid|late|last|ago|sometime|maybe|probably|or so|a few|several)\\b");
    private static final Pattern US = Pattern.compile(
            "(?i)^(us|usa|u\\.s\\.a?\\.?|united states(?: of america)?|america)$");
    /** Model notes claiming federal law doesn't apply in a state: wrong, and not to be shown. */
    private static final Pattern FEDERAL_EXCLUSION = Pattern.compile(
            "(?i)(federal[^.]*\\b(exclud|omit|not (?:included|applicable|relevant|used)|different jurisdiction|another jurisdiction|outside)"
                    + "|different jurisdiction|another jurisdiction)");
    private static final Set<String> STOPWORDS = Set.of(
            "person", "says", "said", "that", "they", "their", "them", "were", "with", "from", "this", "have", "been",
            "also", "about", "after", "before", "when", "then", "there", "which", "what", "would", "could", "states");

    /** Jurisdiction plus, when the model named a state the quote doesn't support, a note saying so. */
    record JurisdictionResult(Jurisdiction jurisdiction, String note) {}

    private final LlmClient llm;
    private final EvidenceRetriever evidenceRetriever;
    private final Clock clock;

    public CaseIntelligenceService(LlmClient llm, EvidenceRetriever evidenceRetriever, Clock clock) {
        this.llm = llm;
        this.evidenceRetriever = evidenceRetriever;
        this.clock = clock;
    }

    public CaseIntelligence analyze(String description, VerificationProgress progress) {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        String text = description.trim();
        String input = material(text);

        // 1. Intake: organise the description
        progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
        String nonce = nonce();
        CaseIntake intake = llm.generate(LegalPrompts.withNonce(LegalPrompts.INTAKE_SYSTEM, nonce),
                LegalPrompts.intakeUser(nonce, text, today), CaseIntake.class);
        if (intake == null) {
            intake = new CaseIntake(null, null, null, null, null, null, null, null);
        }
        List<String> uncertainties = new ArrayList<>();
        JurisdictionResult jr = jurisdiction(intake.jurisdiction(), text, input);
        Jurisdiction jurisdiction = jr.jurisdiction();
        if (jr.note() != null) {
            uncertainties.add(jr.note());
        }
        boolean outsideUs = jurisdiction.status() == Jurisdiction.Status.OUTSIDE_US;
        List<Fact> facts = facts(intake.facts(), input);
        List<TimelineEvent> timeline = timeline(intake.timeline(), input);
        List<Conflict> conflicts = conflicts(intake.conflicts(), input);
        List<PracticeArea> practiceAreas = practiceAreas(intake.practiceAreas());
        String summary = emptyToNull(modelText(intake.summary(), 800, input));
        // Outside the US, topics and "missing" items would be law from the model's memory: leave them out.
        List<IssueToResearch> issueDrafts = outsideUs ? List.of() : issues(intake.issues(), input);
        List<MissingInformation> missing = outsideUs ? List.of() : missing(intake.missingInformation(), input);
        if (facts.isEmpty() && issueDrafts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "LegalFact couldn't find a legal situation to organise. Describe what happened, when, and where.");
        }
        progress.claims(issueDrafts.stream().map(IssueToResearch::topic).toList());

        // 2. Official sources for each issue
        List<Evidence> evidence = List.of();
        if (outsideUs) {
            uncertainties.add("LegalFact currently covers US law only, so no legal topics or sources are shown.");
        } else if (!issueDrafts.isEmpty()) {
            if (jurisdiction.status() == Jurisdiction.Status.UNCERTAIN) {
                uncertainties.add("The state isn't clear from the description, so only federal sources were searched.");
            } else if (!LegalSources.hasStateSources(jurisdiction.state())) {
                uncertainties.add("State sources for " + jurisdiction.stateName()
                        + " aren't covered yet, so only federal sources were searched.");
            }
            progress.stage(VerificationProgress.Stage.SEARCHING);
            evidence = rankAndNumber(evidenceRetriever.retrieve(
                    issueDrafts.stream().map(IssueToResearch::searchQueries).toList(),
                    new EvidenceRetriever.Options(MAX_SEARCHES, MAX_SOURCES, null,
                            LegalSources.allowedDomains(jurisdiction.state())), now));
            progress.sources(evidence.size(), evidence.stream().map(Evidence::domain).distinct().limit(8).toList());
        }

        // 3. Which sources bear on which issue
        Map<String, List<SourceNote>> notesByIssue = new LinkedHashMap<>();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        if (!evidence.isEmpty() && elapsed.compareTo(SOURCE_MATCHING_BUDGET) > 0) {
            uncertainties.add("Sources were retrieved but there wasn't time to match them to topics; see the full list.");
        } else if (!evidence.isEmpty()) {
            progress.stage(VerificationProgress.Stage.ASSESSING);
            String sourcesNonce = nonce();
            List<LegalPrompts.IssueLine> issueLines = new ArrayList<>();
            for (int i = 0; i < issueDrafts.size(); i++) {
                issueLines.add(new LegalPrompts.IssueLine("I" + (i + 1), issueDrafts.get(i).topic()));
            }
            SourceReview review = llm.generate(LegalPrompts.withNonce(LegalPrompts.SOURCES_SYSTEM, sourcesNonce),
                    LegalPrompts.sourcesUser(sourcesNonce, describe(jurisdiction), issueLines,
                            evidence.stream().map(e -> new LegalPrompts.SourceLine(e.id(),
                                    LegalSourceType.classify(e.url(), e.domain()).name(), e.domain(), e.title(),
                                    e.snippet())).toList()),
                    SourceReview.class);
            notesByIssue = notes(review, evidence);
            if (review != null && review.uncertainties() != null) {
                String everything = input + material(evidence.stream().map(e -> e.title() + " " + e.snippet()).toList()
                        .toArray(String[]::new));
                review.uncertainties().stream()
                        .map(u -> modelText(u, 300, everything))
                        .filter(u -> !u.isBlank() && !FEDERAL_EXCLUSION.matcher(u).find())
                        .limit(5)
                        .forEach(uncertainties::add);
            }
        } else if (!issueDrafts.isEmpty() && !outsideUs) {
            uncertainties.add("No official sources addressing these topics were found.");
        }

        List<Issue> issues = new ArrayList<>();
        for (int i = 0; i < issueDrafts.size(); i++) {
            IssueToResearch draft = issueDrafts.get(i);
            String id = "I" + (i + 1);
            issues.add(new Issue(id, draft.topic(), emptyToNull(draft.note()), Basis.AI_INTERPRETATION,
                    notesByIssue.getOrDefault(id, List.of())));
        }
        List<LegalSource> sources = evidence.stream()
                .map(e -> new LegalSource(e.id(), e.url(), e.domain(), e.title(), e.snippet(), e.publishedDate(),
                        e.retrievedAt(), LegalSourceType.classify(e.url(), e.domain())))
                .toList();

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        // Counts only: the description and everything derived from it stay out of the logs.
        log.info("Case intelligence done jurisdiction={} areas={} facts={} events={} conflicts={} issues={} sources={} durationMs={}",
                jurisdiction.status(), practiceAreas, facts.size(), timeline.size(), conflicts.size(), issues.size(),
                sources.size(), durationMs);
        return new CaseIntelligence(now, practiceAreas, jurisdiction, summary, facts, timeline, conflicts, issues,
                missing, List.copyOf(new LinkedHashSet<>(uncertainties)), sources, notice(jurisdiction),
                evidenceRetriever.providerName(), durationMs);
    }

    static String notice(Jurisdiction jurisdiction) {
        return NOTICE + (jurisdiction.status() == Jurisdiction.Status.IDENTIFIED
                ? " Consult a licensed attorney in " + jurisdiction.stateName() + "."
                : " Consult a licensed attorney in the relevant jurisdiction.");
    }

    // ---------------------------------------------------------------- intake checks

    /**
     * Facts survive only if their quote is really in the description. A statement that strays from
     * its quote (or from the description) is replaced by the quote itself.
     */
    private static List<Fact> facts(List<StatedFact> stated, String input) {
        List<Fact> out = new ArrayList<>();
        if (stated == null) {
            return out;
        }
        for (StatedFact f : stated) {
            if (f == null || !quoted(f.quote(), input) || AdviceLanguage.isAdvice(f.quote())) {
                continue; // an advice-like "fact" is an injected instruction, not something that happened
            }
            String quote = clean(f.quote(), 300);
            out.add(new Fact(faithful(f.statement(), quote, input), quote, supportedDate(f.date(), input),
                    Basis.USER_STATED));
            if (out.size() == MAX_FACTS) {
                break;
            }
        }
        return out;
    }

    private static List<TimelineEvent> timeline(List<StatedEvent> stated, String input) {
        List<TimelineEvent> out = new ArrayList<>();
        if (stated == null) {
            return out;
        }
        for (StatedEvent e : stated) {
            if (e == null || !quoted(e.quote(), input) || AdviceLanguage.isAdvice(e.quote())) {
                continue;
            }
            String quote = clean(e.quote(), 300);
            String date = supportedDate(e.date(), input);
            boolean approximate = e.approximate()
                    || (date != null && APPROXIMATE.matcher(date).find())
                    || APPROXIMATE.matcher(quote).find();
            out.add(new TimelineEvent(date, approximate, faithful(e.event(), quote, input), quote, Basis.USER_STATED));
            if (out.size() == MAX_EVENTS) {
                break;
            }
        }
        return out;
    }

    private static List<Conflict> conflicts(List<StatedConflict> stated, String input) {
        List<Conflict> out = new ArrayList<>();
        if (stated == null) {
            return out;
        }
        for (StatedConflict c : stated) {
            if (c == null || c.quotes() == null) {
                continue;
            }
            List<String> quotes = c.quotes().stream().filter(q -> quoted(q, input)).map(q -> clean(q, 300))
                    .distinct().limit(4).toList();
            String what = modelText(c.description(), 250, input);
            if (quotes.size() >= 2 && !what.isBlank()) {
                out.add(new Conflict(what, quotes, Basis.AI_INTERPRETATION));
            }
            if (out.size() == MAX_CONFLICTS) {
                break;
            }
        }
        return out;
    }

    /**
     * The model's restatement if it stays with the person's words: no advice, no figures the
     * description lacks, and most of its content words taken from the description. Else the quote.
     */
    static String faithful(String statement, String quote, String input) {
        String s = clean(statement, 300);
        List<String> content = Arrays.stream(words(s).split(" "))
                .filter(w -> w.length() >= 4 && !STOPWORDS.contains(w))
                .toList();
        long found = content.stream().filter(w -> input.contains(" " + w + " ")).count();
        boolean ok = !s.isBlank() && !AdviceLanguage.isAdvice(s) && Grounding.supported(s, input)
                && (content.isEmpty() || found >= Math.ceil(content.size() * 0.6));
        return ok ? s : "The person says: \"" + quote + "\"";
    }

    /**
     * A date is kept only if the description contains it as written ("March 3", "about two weeks
     * later"); otherwise the model made it up or made a vague date precise.
     */
    static String supportedDate(String date, String input) {
        String d = clean(date, 60);
        String w = words(d);
        return w.isEmpty() || !input.contains(" " + w + " ") ? null : d;
    }

    /**
     * Identified only when the quoted basis is in the description and names the state (full name,
     * or its capitalised code as in "Austin, TX"). A city alone isn't enough: the state is then
     * uncertain, and a note says what the description mentions.
     */
    static JurisdictionResult jurisdiction(JurisdictionGuess guess, String text, String input) {
        Jurisdiction uncertain = new Jurisdiction(Jurisdiction.Status.UNCERTAIN, null, null, null, null);
        if (guess == null) {
            return new JurisdictionResult(uncertain, null);
        }
        String country = clean(guess.country(), 60);
        String quote = clean(guess.basisQuote(), 200);
        String q = words(quote);
        boolean quoted = q.length() >= 4 && input.contains(" " + q + " "); // a place name can be short
        boolean us = country.isBlank() || US.matcher(country).matches();
        if (!us && quoted) {
            return new JurisdictionResult(new Jurisdiction(Jurisdiction.Status.OUTSIDE_US, country, null, null, quote), null);
        }
        String state = UsStates.code(guess.state());
        if (state == null || !quoted) {
            return new JurisdictionResult(new Jurisdiction(Jurisdiction.Status.UNCERTAIN,
                    us && quoted ? "US" : null, null, null, null), null);
        }
        String name = UsStates.NAMES.get(state);
        boolean named = (" " + words(quote) + " ").contains(" " + words(name) + " ")
                || Pattern.compile("\\b" + state + "\\b").matcher(quote).find();
        if (!named) {
            return new JurisdictionResult(new Jurisdiction(Jurisdiction.Status.UNCERTAIN, "US", null, null, quote),
                    "The description mentions \"" + quote + "\" but doesn't name the state; confirm it (possibly "
                            + name + ").");
        }
        return new JurisdictionResult(new Jurisdiction(Jurisdiction.Status.IDENTIFIED, "US", state, name, quote), null);
    }

    private static List<PracticeArea> practiceAreas(List<String> labels) {
        Set<PracticeArea> areas = new LinkedHashSet<>();
        if (labels != null) {
            labels.stream().limit(3).map(PracticeArea::parse).forEach(areas::add);
        }
        if (areas.size() > 1) {
            areas.remove(PracticeArea.OTHER);
        }
        return areas.isEmpty() ? List.of(PracticeArea.OTHER) : List.copyOf(areas).subList(0, Math.min(2, areas.size()));
    }

    private static List<IssueToResearch> issues(List<IssueToResearch> drafts, String input) {
        List<IssueToResearch> out = new ArrayList<>();
        if (drafts == null) {
            return out;
        }
        for (IssueToResearch d : drafts) {
            String topic = d == null ? "" : clean(d.topic(), 150);
            if (topic.isBlank() || AdviceLanguage.isAdvice(topic) || !Grounding.supported(topic, input)) {
                continue; // a "topic" that is a conclusion, or cites law the description doesn't mention
            }
            List<String> queries = new ArrayList<>();
            if (d.searchQueries() != null) {
                d.searchQueries().stream().map(EvidenceRetriever::cleanQuery).filter(q -> !q.isBlank()).limit(2)
                        .forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(EvidenceRetriever.cleanQuery(topic));
            }
            out.add(new IssueToResearch(topic, modelText(d.note(), 300, input), queries));
            if (out.size() == MAX_ISSUES) {
                break;
            }
        }
        return out;
    }

    private static List<MissingInformation> missing(List<MissingItem> items, String input) {
        List<MissingInformation> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MissingItem m : items) {
            String item = m == null ? "" : clean(m.item(), 200);
            if (item.isBlank() || AdviceLanguage.isAdvice(item) || !Grounding.supported(item, input)) {
                continue;
            }
            out.add(new MissingInformation(item, emptyToNull(modelText(m.whyItMatters(), 250, input))));
            if (out.size() == MAX_MISSING) {
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- source checks

    /**
     * Official sources first by kind (statutes, regulations, guidance), renumbered E1..En. Government
     * sites often serve one document under differently-cased URLs (FinalPay.pdf, finalpay.pdf); those
     * count once.
     */
    static List<Evidence> rankAndNumber(List<Evidence> evidence) {
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence e : evidence) {
            unique.putIfAbsent(e.url().toLowerCase(Locale.ROOT).replaceFirst("^https?://(www\\.)?", ""), e);
        }
        List<Evidence> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt(e -> LegalSourceType.classify(e.url(), e.domain()).ordinal()));
        List<Evidence> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Evidence e = sorted.get(i);
            out.add(new Evidence("E" + (i + 1), e.url(), e.domain(), e.title(), e.snippet(), e.publishedDate(),
                    e.retrievedAt(), e.sourceType()));
        }
        return out;
    }

    /** Unknown source IDs are dropped; a note that adds figures the source doesn't contain is blanked. */
    static Map<String, List<SourceNote>> notes(SourceReview review, List<Evidence> evidence) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        evidence.forEach(e -> byId.put(e.id(), e));
        Map<String, List<SourceNote>> out = new LinkedHashMap<>();
        if (review == null || review.issues() == null) {
            return out;
        }
        for (IssueSources is : review.issues()) {
            if (is == null || is.issueId() == null || is.sources() == null) {
                continue;
            }
            String issueId = is.issueId().trim().toUpperCase(Locale.ROOT);
            List<SourceNote> notes = out.computeIfAbsent(issueId, k -> new ArrayList<>());
            for (LegalOutputs.SourceNote n : is.sources()) {
                String sourceId = n == null || n.sourceId() == null ? "" : n.sourceId().trim().toUpperCase(Locale.ROOT);
                Evidence e = byId.get(sourceId);
                if (e == null || notes.size() >= MAX_NOTES_PER_ISSUE
                        || notes.stream().anyMatch(existing -> existing.sourceId().equals(sourceId))) {
                    continue;
                }
                notes.add(new SourceNote(sourceId, grounded(n.whatItSays(), e), Basis.SOURCE_BACKED,
                        grounded(n.relevance(), e), Basis.AI_INTERPRETATION));
            }
        }
        return out;
    }

    /**
     * The model's words about a source, or null if they add advice or any figure, deadline or case name
     * that the source's own title and excerpt don't contain.
     */
    static String grounded(String text, Evidence source) {
        String t = AdviceLanguage.strip(clean(text, 400));
        return t.isBlank() || !Grounding.supported(t, material(source.title(), source.snippet())) ? null : t;
    }

    // ---------------------------------------------------------------- helpers

    /** Model-written text with advice-like and ungrounded sentences removed (possibly empty). */
    private static String modelText(String text, int max, String material) {
        return Grounding.supportedSentences(AdviceLanguage.strip(clean(text, max)), material);
    }

    /** At least three words and 15 characters, found verbatim (ignoring punctuation) in the description. */
    private static boolean quoted(String quote, String input) {
        String q = words(quote == null ? "" : quote);
        return q.length() >= 15 && q.split(" ").length >= 3 && input.contains(" " + q + " ");
    }

    private static String describe(Jurisdiction j) {
        return switch (j.status()) {
            case IDENTIFIED -> "United States: federal law applies, plus " + j.stateName() + " state law";
            case OUTSIDE_US -> j.country();
            case UNCERTAIN -> "United States, state not known: federal law applies";
        };
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
