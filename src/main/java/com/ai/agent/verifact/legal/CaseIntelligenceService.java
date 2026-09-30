package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.legal.CaseIntelligence.Basis;
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
import com.ai.agent.verifact.legal.LegalOutputs.StatedEvent;
import com.ai.agent.verifact.legal.LegalOutputs.StatedFact;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LegalFact Case Intelligence (ADR-12, legalfact.md). Two model calls, like VeriFact:
 *
 * <pre>
 * description ─► [LLM 1] intake: areas, jurisdiction, facts, timeline, issues, missing info
 *             ─► checks: facts/events must quote the description, dates must appear in it,
 *                jurisdiction needs a quoted basis, advice-like wording removed
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
    static final int MAX_ISSUES = 4;
    static final int MAX_MISSING = 8;
    static final int MAX_SEARCHES = 6;
    static final int MAX_SOURCES = 10;
    static final int MAX_NOTES_PER_ISSUE = 4;

    static final String NOTICE = "AI assistance, not legal advice. LegalFact organises the information provided and "
            + "points to official sources that may be relevant. It does not assess whether anyone has a claim. "
            + "A licensed attorney should review it.";

    private static final Pattern APPROXIMATE = Pattern.compile(
            "(?i)\\b(about|around|approximately|roughly|early|mid|late|last|ago|sometime|maybe|probably|or so|a few|several)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern MONTH = Pattern.compile(
            "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b");
    private static final Pattern US = Pattern.compile(
            "(?i)^(us|usa|u\\.s\\.a?\\.?|united states(?: of america)?|america)$");

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
        String input = " " + words(text) + " ";

        // 1. Intake: organise the description
        progress.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
        String nonce = nonce();
        CaseIntake intake = llm.generate(LegalPrompts.withNonce(LegalPrompts.INTAKE_SYSTEM, nonce),
                LegalPrompts.intakeUser(nonce, text, today), CaseIntake.class);
        if (intake == null) {
            intake = new CaseIntake(null, null, null, null, null, null, null);
        }
        List<Fact> facts = facts(intake.facts(), input);
        List<TimelineEvent> timeline = timeline(intake.timeline(), input);
        Jurisdiction jurisdiction = jurisdiction(intake.jurisdiction(), input);
        List<PracticeArea> practiceAreas = practiceAreas(intake.practiceAreas());
        String summary = emptyToNull(AdviceLanguage.strip(clean(intake.summary(), 800)));
        List<IssueToResearch> issueDrafts = issues(intake.issues());
        List<MissingInformation> missing = missing(intake.missingInformation());
        if (facts.isEmpty() && issueDrafts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "LegalFact couldn't find a legal situation to organise. Describe what happened, when, and where.");
        }
        progress.claims(issueDrafts.stream().map(IssueToResearch::topic).toList());

        // 2. Official sources for each issue
        List<String> uncertainties = new ArrayList<>();
        List<Evidence> evidence = List.of();
        if (jurisdiction.status() == Jurisdiction.Status.OUTSIDE_US) {
            uncertainties.add("LegalFact currently covers US law only, so no sources were retrieved.");
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
        if (!evidence.isEmpty()) {
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
                review.uncertainties().stream()
                        .map(u -> AdviceLanguage.strip(clean(u, 300)))
                        .filter(u -> !u.isBlank())
                        .limit(5)
                        .forEach(uncertainties::add);
            }
        } else if (!issueDrafts.isEmpty() && jurisdiction.status() != Jurisdiction.Status.OUTSIDE_US) {
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
        log.info("Case intelligence done jurisdiction={} areas={} facts={} events={} issues={} sources={} durationMs={}",
                jurisdiction.status(), practiceAreas, facts.size(), timeline.size(), issues.size(), sources.size(),
                durationMs);
        return new CaseIntelligence(now, practiceAreas, jurisdiction, summary, facts, timeline, issues, missing,
                List.copyOf(new LinkedHashSet<>(uncertainties)), sources, NOTICE, evidenceRetriever.providerName(),
                durationMs);
    }

    // ---------------------------------------------------------------- intake checks

    /** Facts survive only if their quote is really in the description. */
    private static List<Fact> facts(List<StatedFact> stated, String input) {
        List<Fact> out = new ArrayList<>();
        if (stated == null) {
            return out;
        }
        for (StatedFact f : stated) {
            if (f == null || !quoted(f.quote(), input)) {
                continue;
            }
            String quote = clean(f.quote(), 300);
            String statement = clean(f.statement(), 300);
            if (statement.isBlank() || AdviceLanguage.isAdvice(statement)) {
                statement = "The person says: \"" + quote + "\"";
            }
            out.add(new Fact(statement, quote, supportedDate(f.date(), input), Basis.USER_STATED));
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
            if (e == null || !quoted(e.quote(), input)) {
                continue;
            }
            String quote = clean(e.quote(), 300);
            String event = clean(e.event(), 300);
            if (event.isBlank() || AdviceLanguage.isAdvice(event)) {
                event = quote;
            }
            String date = supportedDate(e.date(), input);
            boolean approximate = e.approximate()
                    || (date != null && APPROXIMATE.matcher(date).find())
                    || APPROXIMATE.matcher(quote).find();
            out.add(new TimelineEvent(date, approximate, event, quote, Basis.USER_STATED));
            if (out.size() == MAX_EVENTS) {
                break;
            }
        }
        return out;
    }

    /**
     * A date is kept only if the description contains it: every number and month name in it, or the
     * whole phrase for wording like "last spring". Otherwise the model made it up (or made it precise).
     */
    static String supportedDate(String date, String input) {
        String d = clean(date, 60);
        if (d.isBlank()) {
            return null;
        }
        boolean hasNumbersOrMonths = false;
        Matcher numbers = NUMBER.matcher(d);
        while (numbers.find()) {
            hasNumbersOrMonths = true;
            if (!input.contains(" " + numbers.group() + " ")) {
                return null;
            }
        }
        Matcher months = MONTH.matcher(d);
        while (months.find()) {
            hasNumbersOrMonths = true;
            if (!input.contains(" " + months.group().toLowerCase(Locale.ROOT))) {
                return null;
            }
        }
        if (!hasNumbersOrMonths && !input.contains(" " + words(d) + " ")) {
            return null;
        }
        return d;
    }

    /** Identified only with a quoted basis that is really in the description. US only for now. */
    static Jurisdiction jurisdiction(JurisdictionGuess guess, String input) {
        if (guess == null) {
            return new Jurisdiction(Jurisdiction.Status.UNCERTAIN, null, null, null, null);
        }
        String country = clean(guess.country(), 60);
        String quote = clean(guess.basisQuote(), 200);
        boolean quoted = quoted(quote, input);
        boolean us = country.isBlank() || US.matcher(country).matches();
        if (!us && quoted) {
            return new Jurisdiction(Jurisdiction.Status.OUTSIDE_US, country, null, null, quote);
        }
        String state = UsStates.code(guess.state());
        if (state != null && quoted) {
            return new Jurisdiction(Jurisdiction.Status.IDENTIFIED, "US", state, UsStates.NAMES.get(state), quote);
        }
        return new Jurisdiction(Jurisdiction.Status.UNCERTAIN, us && quoted ? "US" : null, null, null, null);
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

    private static List<IssueToResearch> issues(List<IssueToResearch> drafts) {
        List<IssueToResearch> out = new ArrayList<>();
        if (drafts == null) {
            return out;
        }
        for (IssueToResearch d : drafts) {
            String topic = d == null ? "" : clean(d.topic(), 150);
            if (topic.isBlank() || AdviceLanguage.isAdvice(topic)) {
                continue; // a "topic" that is really a conclusion is dropped
            }
            List<String> queries = new ArrayList<>();
            if (d.searchQueries() != null) {
                d.searchQueries().stream().map(EvidenceRetriever::cleanQuery).filter(q -> !q.isBlank()).limit(2)
                        .forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(EvidenceRetriever.cleanQuery(topic));
            }
            out.add(new IssueToResearch(topic, AdviceLanguage.strip(clean(d.note(), 300)), queries));
            if (out.size() == MAX_ISSUES) {
                break;
            }
        }
        return out;
    }

    private static List<MissingInformation> missing(List<MissingItem> items) {
        List<MissingInformation> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MissingItem m : items) {
            String item = m == null ? "" : clean(m.item(), 200);
            if (!item.isBlank()) {
                out.add(new MissingInformation(item, emptyToNull(AdviceLanguage.strip(clean(m.whyItMatters(), 250)))));
            }
            if (out.size() == MAX_MISSING) {
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- source checks

    /** Official sources first by kind (statutes, regulations, guidance), renumbered E1..En. */
    private static List<Evidence> rankAndNumber(List<Evidence> evidence) {
        List<Evidence> sorted = new ArrayList<>(evidence);
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
                notes.add(new SourceNote(sourceId, grounded(n.whatItSays(), e), grounded(n.relevance(), e),
                        Basis.SOURCE_BACKED));
            }
        }
        return out;
    }

    /**
     * The model's words about a source, or null if they add advice or any number (a section, a figure,
     * a deadline) that the source's own title, excerpt or URL doesn't contain.
     */
    static String grounded(String text, Evidence source) {
        String t = AdviceLanguage.strip(clean(text, 400));
        if (t.isBlank()) {
            return null;
        }
        String haystack = " " + words(source.title() + " " + source.snippet() + " " + source.url()) + " ";
        Matcher numbers = NUMBER.matcher(t);
        while (numbers.find()) {
            if (!haystack.contains(" " + numbers.group() + " ")) {
                return null;
            }
        }
        return t;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean quoted(String quote, String input) {
        String q = words(quote == null ? "" : quote);
        return q.length() >= 3 && input.contains(" " + q + " ");
    }

    private static String describe(Jurisdiction j) {
        return switch (j.status()) {
            case IDENTIFIED -> j.stateName() + ", United States";
            case OUTSIDE_US -> j.country();
            case UNCERTAIN -> "Uncertain (United States assumed only if stated; no state given)";
        };
    }

    /** Lower case, letters and digits only, single spaces: for "is this in the description" checks. */
    static String words(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
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
