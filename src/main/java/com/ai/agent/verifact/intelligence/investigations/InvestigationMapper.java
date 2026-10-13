package com.ai.agent.verifact.intelligence.investigations;

import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.intelligence.incidents.RiskModel;
import com.ai.agent.verifact.intelligence.incidents.Similarity;
import com.ai.agent.verifact.intelligence.investigations.Investigation.Assessment;
import com.ai.agent.verifact.news.NewsCheck;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps an evidence check to an investigation result, and compares versions. Pure functions. */
public final class InvestigationMapper {

    static final String METHOD = "Claims were extracted from the submitted text, then searched for on the web. Each assessment rests only on "
            + "the retrieved sources: an excerpt is shown only if its words were found in that source's retrieved text. Search ranking "
            + "and the AI's own knowledge are not treated as evidence. Assessments are automated and should be reviewed by a person "
            + "before any decision or public statement.";

    private InvestigationMapper() {
    }

    public static Investigation.Result map(NewsCheck check, int version) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        check.sources().forEach(e -> byId.put(e.id(), e));
        Map<String, Set<String>> supports = new LinkedHashMap<>();
        Map<String, Set<String>> contradicts = new LinkedHashMap<>();
        List<Investigation.ClaimResult> claims = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (NewsCheck.NewsClaim c : check.claims()) {
            Assessment a = assessment(c);
            if (c.supporting() != null) {
                supports.computeIfAbsent(c.supporting().sourceId(), k -> new LinkedHashSet<>()).add(c.id());
            }
            if (c.contradicting() != null) {
                contradicts.computeIfAbsent(c.contradicting().sourceId(), k -> new LinkedHashSet<>()).add(c.id());
            }
            claims.add(new Investigation.ClaimResult(c.id(), c.type().name(), c.claim(), c.articleQuote(), c.speaker(), a, c.verdict().name(),
                    excerpt(c.supporting(), byId), excerpt(c.contradicting(), byId), c.sourcesConflict(), c.contextIssue().name(),
                    c.quoteStatus().name(), excerpt(c.quoteSource(), byId), c.explanation()));
            if (a == Assessment.INSUFFICIENT_EVIDENCE) {
                unresolved.add("No retrieved source settles: \"" + c.claim() + "\"");
            } else if (a == Assessment.MIXED) {
                unresolved.add("Sources disagree or only partly confirm: \"" + c.claim() + "\"");
            }
            if (c.quoteStatus() == NewsCheck.QuoteStatus.NOT_LOCATED) {
                unresolved.add("The quoted words" + (c.speaker() == null ? "" : " attributed to " + c.speaker())
                        + " weren't found word for word in the sources searched.");
            }
        }
        List<Investigation.Source> sources = new ArrayList<>();
        for (Evidence e : check.sources()) {
            Set<String> s = supports.getOrDefault(e.id(), Set.of());
            Set<String> x = contradicts.getOrDefault(e.id(), Set.of());
            String direction = !s.isEmpty() && !x.isEmpty() ? "MIXED" : !s.isEmpty() ? "SUPPORTS" : !x.isEmpty() ? "CONTRADICTS" : "CONTEXT";
            List<String> claimIds = new ArrayList<>(s);
            x.stream().filter(id -> !claimIds.contains(id)).forEach(claimIds::add);
            sources.add(new Investigation.Source(e.id(), e.url(), e.domain(), e.title(), e.publishedDate(), e.retrievedAt(),
                    e.sourceType() == null ? "OTHER" : e.sourceType().name(), e.snippet(), direction, claimIds));
        }
        return new Investigation.Result(version, check.createdAt(), check.articleUrl(), check.articleTitle(), claims, sources,
                chronology(check), unresolved, check.limitations(), METHOD, check.searchProvider(), check.durationMs());
    }

    static Assessment assessment(NewsCheck.NewsClaim c) {
        if (c.sourcesConflict() || c.verdict() == Verdict.PARTLY_SUPPORTED) {
            return Assessment.MIXED;
        }
        boolean context = c.contextIssue() == NewsCheck.ContextIssue.OUTDATED || c.contextIssue() == NewsCheck.ContextIssue.OLD_EVENT_AS_NEW
                || c.contextIssue() == NewsCheck.ContextIssue.MISSING_CONTEXT || c.contextIssue() == NewsCheck.ContextIssue.MISATTRIBUTED;
        return switch (c.verdict()) {
            case SUPPORTED -> context ? Assessment.MISSING_CONTEXT : Assessment.SUPPORTED;
            case CONTRADICTED -> Assessment.CONTRADICTED;
            case MISLEADING -> Assessment.MISSING_CONTEXT;
            case PARTLY_SUPPORTED -> Assessment.MIXED;
            case INSUFFICIENT_EVIDENCE -> Assessment.INSUFFICIENT_EVIDENCE;
        };
    }

    /** The incident's evidence factor follows the main (first) claim. */
    public static RiskModel.EvidenceState evidenceState(Investigation.Result r) {
        if (r == null || r.claims().isEmpty()) {
            return RiskModel.EvidenceState.UNRESOLVED;
        }
        return switch (r.claims().getFirst().assessment()) {
            case SUPPORTED -> RiskModel.EvidenceState.SUPPORTED;
            case CONTRADICTED -> RiskModel.EvidenceState.CONTRADICTED;
            case MISSING_CONTEXT -> RiskModel.EvidenceState.MISSING_CONTEXT;
            default -> RiskModel.EvidenceState.UNRESOLVED;
        };
    }

    public static String headline(Investigation.Result r) {
        if (r == null || r.claims().isEmpty()) {
            return null;
        }
        return r.claims().getFirst().assessment().name();
    }

    private static Investigation.Excerpt excerpt(SourceExcerpt x, Map<String, Evidence> byId) {
        if (x == null) {
            return null;
        }
        Evidence e = byId.get(x.sourceId());
        return e == null ? null : new Investigation.Excerpt(x.sourceId(), x.excerpt(), e.url(), e.domain(), e.title());
    }

    /** Dated sources in order. Undated sources are left out of the chronology (they are still listed as sources). */
    static List<Investigation.TimelineEntry> chronology(NewsCheck check) {
        record Dated(LocalDate date, Investigation.TimelineEntry entry) {}
        List<Dated> dated = new ArrayList<>();
        for (Evidence e : check.sources()) {
            LocalDate d = parseDate(e.publishedDate());
            if (d != null) {
                dated.add(new Dated(d, new Investigation.TimelineEntry(d.toString(), (e.title() == null || e.title().isBlank() ? e.domain() : e.title())
                        + " (" + e.domain() + ")", e.id())));
            }
        }
        if (check.articleDate() != null) {
            LocalDate d = parseDate(check.articleDate());
            if (d != null) {
                dated.add(new Dated(d, new Investigation.TimelineEntry(d.toString(), "Submitted article's stated date", null)));
            }
        }
        dated.add(new Dated(LocalDate.ofInstant(check.createdAt(), ZoneOffset.UTC),
                new Investigation.TimelineEntry(LocalDate.ofInstant(check.createdAt(), ZoneOffset.UTC).toString(), "Evidence retrieved for this version", null)));
        return dated.stream().sorted(Comparator.comparing(Dated::date)).map(Dated::entry).toList();
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            return OffsetDateTime.parse(t).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return LocalDate.parse(t.length() >= 10 ? t.substring(0, 10) : t);
        } catch (DateTimeParseException ignored) {
            // next format
        }
        for (String pattern : List.of("MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "d MMM yyyy")) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    /** What changed since the previous version: assessments per (similar) claim, and sources gained or lost. */
    public static Investigation.Changes diff(Investigation.Result before, Investigation.Result after) {
        if (before == null) {
            return null;
        }
        List<Investigation.ClaimChange> changes = new ArrayList<>();
        for (Investigation.ClaimResult c : after.claims()) {
            Investigation.ClaimResult match = before.claims().stream()
                    .max(Comparator.comparingDouble(b -> Similarity.jaccard(b.claim(), c.claim())))
                    .filter(b -> Similarity.jaccard(b.claim(), c.claim()) >= 0.5).orElse(null);
            if (match == null) {
                changes.add(new Investigation.ClaimChange(c.claim(), null, c.assessment().name()));
            } else if (match.assessment() != c.assessment()) {
                changes.add(new Investigation.ClaimChange(c.claim(), match.assessment().name(), c.assessment().name()));
            }
        }
        Set<String> beforeUrls = new LinkedHashSet<>(before.sources().stream().map(Investigation.Source::url).toList());
        Set<String> afterUrls = new LinkedHashSet<>(after.sources().stream().map(Investigation.Source::url).toList());
        List<String> added = afterUrls.stream().filter(u -> !beforeUrls.contains(u)).toList();
        List<String> dropped = beforeUrls.stream().filter(u -> !afterUrls.contains(u)).toList();
        long assessmentChanges = changes.stream().filter(c -> c.before() != null).count();
        String summary = (assessmentChanges == 0 ? "No assessment changed" : assessmentChanges + (assessmentChanges == 1 ? " assessment changed" : " assessments changed"))
                + "; " + added.size() + " new source" + (added.size() == 1 ? "" : "s") + ", " + dropped.size() + " no longer found.";
        return new Investigation.Changes(before.version(), changes, added, dropped, summary);
    }
}
