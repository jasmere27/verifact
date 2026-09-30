package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.Discovery.FoundSource;
import com.ai.agent.verifact.research.Discovery.Verification;
import com.ai.agent.verifact.research.Insights.Coverage;
import com.ai.agent.verifact.research.Insights.Gap;
import com.ai.agent.verifact.research.Insights.GapKind;
import com.ai.agent.verifact.research.Insights.Relation;
import com.ai.agent.verifact.research.Insights.RelationKind;
import com.ai.agent.verifact.research.Insights.Role;
import com.ai.agent.verifact.research.Insights.Variable;
import com.ai.agent.verifact.research.ResearchWorkspace.SavedSource;
import com.ai.agent.verifact.research.StudentOutputs.GapNote;
import com.ai.agent.verifact.research.StudentOutputs.InsightDraft;
import com.ai.agent.verifact.research.StudentOutputs.RelationNote;
import com.ai.agent.verifact.research.StudentOutputs.RelationReview;
import com.ai.agent.verifact.research.StudentOutputs.VariableNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Phase 3 of Student Research Mode: coverage counted in code, then the model's reading of the saved
 * sources' abstracts (possible gaps, how each study relates, framework variables). Every item is tied to
 * saved sources: gaps must name them, relations need a verbatim abstract quote, and a variable is
 * VERIFIED only when a saved source names it.
 */
@Service
public class ResearchInsightsService {

    private static final Logger log = LoggerFactory.getLogger(ResearchInsightsService.class);

    static final int MAX_SOURCES = 20;
    static final int MIN_SOURCES = 3;
    static final int MAX_ABSTRACT_CHARS = 700;
    static final int RELATION_BATCH = 5;

    static final String NOTICE = "Gaps, relations and framework variables are an AI reading of the abstracts of the sources you saved, "
            + "not established facts about the field. Read the studies and confirm with your adviser before writing them up.";

    private final LlmClient llm;
    private final ScholarlyIndex index;
    private final Clock clock;

    public ResearchInsightsService(LlmClient llm, ScholarlyIndex index, Clock clock) {
        this.llm = llm;
        this.index = index;
        this.clock = clock;
    }

    record Item(String id, SavedSource saved, String abstractText) {}

    public Insights generate(ResearchWorkspace ws) {
        List<SavedSource> saved = ws.sources() == null ? List.of() : ws.sources().stream()
                .filter(s -> !s.source().retracted()).limit(MAX_SOURCES).toList();
        List<String> limitations = new ArrayList<>();
        Coverage coverage = coverage(ws, java.time.LocalDate.now(clock).getYear());

        List<Item> items = abstracts(saved, limitations);
        if (items.size() < MIN_SOURCES) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Save at least " + MIN_SOURCES + " sources that have abstracts first; insights come from what those abstracts say.");
        }
        if (ws.sources().size() > MAX_SOURCES) {
            limitations.add("Based on the first " + MAX_SOURCES + " saved sources.");
        }
        Map<String, Item> byId = new LinkedHashMap<>();
        items.forEach(i -> byId.put(i.id(), i));

        String topic = ws.topic();
        String coverageText = coverageText(coverage);
        String allMaterial = Grounding.material(topic, coverageText,
                String.join(" ", items.stream().map(i -> i.saved().source().title() + " " + i.abstractText()).toList()));

        List<Gap> gaps = new ArrayList<>();
        List<Variable> framework = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<InsightDraft> draft = pool.submit(() -> {
                String nonce = nonce();
                return llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.INSIGHT_SYSTEM, nonce),
                        data(nonce, topic, ws.field(), coverageText, items), InsightDraft.class);
            });
            List<Future<RelationReview>> batches = new ArrayList<>();
            for (int from = 0; from < items.size(); from += RELATION_BATCH) {
                List<Item> batch = items.subList(from, Math.min(from + RELATION_BATCH, items.size()));
                batches.add(pool.submit(() -> {
                    String nonce = nonce();
                    return llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.RELATION_SYSTEM, nonce),
                            data(nonce, topic, ws.field(), null, batch), RelationReview.class);
                }));
            }

            try {
                InsightDraft d = draft.get();
                gaps = gaps(d, byId, allMaterial);
                framework = framework(d, items);
            } catch (ExecutionException e) {
                log.warn("Insight draft failed: {}", e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
                limitations.add("Gaps and framework variables couldn't be generated this time; try again.");
            }
            int failed = 0;
            for (Future<RelationReview> f : batches) {
                try {
                    relations.addAll(relations(f.get(), byId, topic));
                } catch (ExecutionException e) {
                    failed++;
                }
            }
            if (failed > 0) {
                limitations.add("How some studies relate to yours couldn't be checked this time.");
            }
            if (failed == batches.size() && gaps.isEmpty() && framework.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The analysis service is unavailable right now. Please try again shortly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Generating insights was interrupted. Please try again.");
        }
        log.info("Insights done sources={} gaps={} relations={} variables={}", items.size(), gaps.size(), relations.size(), framework.size());
        return new Insights(clock.instant(), items.size(), coverage, gaps, relations, framework, limitations, NOTICE);
    }

    static Coverage coverage(ResearchWorkspace ws, int thisYear) {
        List<FoundSource> s = ws.sources() == null ? List.of() : ws.sources().stream().map(SavedSource::source).toList();
        Integer oldest = s.stream().map(FoundSource::year).filter(Objects::nonNull).min(Integer::compare).orElse(null);
        Integer newest = s.stream().map(FoundSource::year).filter(Objects::nonNull).max(Integer::compare).orElse(null);
        int local = ws.country() == null ? 0 : (int) s.stream().filter(FoundSource::local).count();
        int foreign = ws.country() == null ? 0 : (int) s.stream().filter(x -> !x.local() && x.countries() != null && !x.countries().isEmpty()).count();
        return new Coverage(s.size(), local, foreign, (int) s.stream().filter(FoundSource::hasAbstract).count(), oldest, newest,
                (int) s.stream().filter(x -> x.year() != null && x.year() >= thisYear - 5).count(),
                (int) s.stream().filter(x -> "review".equals(x.type())).count(),
                (int) s.stream().filter(FoundSource::retracted).count());
    }

    static String coverageText(Coverage c) {
        return "Saved sources: " + c.total() + "; local: " + c.local() + "; foreign: " + c.foreign() + "; with abstracts: " + c.withAbstract()
                + "; years: " + (c.oldestYear() == null ? "unknown" : c.oldestYear() + "-" + c.newestYear())
                + "; from the last 5 years: " + c.lastFiveYears() + "; reviews: " + c.reviews() + ".";
    }

    /** Saved sources with abstracts, re-fetched (saved records don't keep abstracts), a few at a time. */
    private List<Item> abstracts(List<SavedSource> saved, List<String> limitations) {
        Semaphore slots = new Semaphore(4);
        List<Future<Optional<DiscoveredWork>>> lookups = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SavedSource s : saved) {
                lookups.add(pool.submit(() -> {
                    if (!s.source().hasAbstract()) {
                        return Optional.empty();
                    }
                    slots.acquire();
                    try {
                        return index.work(s.key());
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        List<Item> items = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < saved.size(); i++) {
            try {
                Optional<DiscoveredWork> w = lookups.get(i).get();
                String abs = w.map(x -> x.work().abstractText()).orElse(null);
                if (abs != null && !abs.isBlank()) {
                    items.add(new Item("S" + (items.size() + 1), saved.get(i), abs));
                }
            } catch (ExecutionException e) {
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Generating insights was interrupted. Please try again.");
            }
        }
        if (failed > 0) {
            limitations.add(failed + " saved source(s) couldn't be re-read from OpenAlex and were left out.");
        }
        return items;
    }

    private static String data(String nonce, String topic, String field, String coverageText, List<Item> items) {
        StringBuilder out = new StringBuilder("<<<DATA_").append(nonce).append(">>>\nStudent's topic: ").append(topic).append('\n');
        if (field != null) {
            out.append("Field: ").append(field).append('\n');
        }
        if (coverageText != null) {
            out.append(coverageText).append('\n');
        }
        out.append("\nSAVED SOURCES\n");
        for (Item i : items) {
            FoundSource s = i.saved().source();
            String a = i.abstractText();
            out.append(i.id()).append(" | ").append(s.title()).append(" (").append(s.year() == null ? "n.d." : s.year()).append(")");
            if (s.countries() != null && !s.countries().isEmpty()) {
                out.append(" | countries: ").append(String.join(", ", s.countries()));
            }
            if (s.type() != null) {
                out.append(" | ").append(s.type());
            }
            out.append("\n  abstract: ").append(a.length() > MAX_ABSTRACT_CHARS ? a.substring(0, MAX_ABSTRACT_CHARS) : a).append('\n');
        }
        return out.append("<<<END_DATA_").append(nonce).append(">>>").toString();
    }

    static List<Gap> gaps(InsightDraft d, Map<String, Item> byId, String material) {
        List<Gap> out = new ArrayList<>();
        if (d == null || d.gaps() == null) {
            return out;
        }
        for (GapNote g : d.gaps()) {
            if (g == null || g.statement() == null || g.sourceIds() == null) {
                continue;
            }
            List<String> basis = g.sourceIds().stream().filter(Objects::nonNull)
                    .map(x -> byId.get(x.trim().toUpperCase(Locale.ROOT))).filter(Objects::nonNull)
                    .map(i -> i.saved().key()).distinct().toList();
            String statement = shorten(g.statement(), 320);
            if (basis.isEmpty() || statement == null || !Grounding.supported(statement, material)) {
                continue;
            }
            out.add(new Gap(statement, parse(GapKind.class, g.kind(), GapKind.OTHER), basis));
            if (out.size() == 5) {
                break;
            }
        }
        return out;
    }

    static List<Variable> framework(InsightDraft d, List<Item> items) {
        List<Variable> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (d == null || d.variables() == null) {
            return out;
        }
        for (VariableNote v : d.variables()) {
            String name = v == null ? null : cap(v.name(), 60);
            if (name == null || !seen.add(Grounding.words(name))) {
                continue;
            }
            List<String> keys = items.stream().filter(i -> names(i, name)).map(i -> i.saved().key()).toList();
            out.add(new Variable(name, parse(Role.class, v.role(), Role.CONTEXT), keys,
                    keys.isEmpty() ? Verification.UNVERIFIED : Verification.VERIFIED));
            if (out.size() == 6) {
                break;
            }
        }
        return out;
    }

    private static final Set<String> FILLER = Set.of("the", "of", "and", "in", "on", "for", "to", "a", "an", "with", "s");

    /** Every content word of the variable (plural "s" ignored) appears in the source's title or abstract. */
    static boolean names(Item i, String variable) {
        String material = Grounding.material(i.saved().source().title(), i.abstractText());
        List<String> words = java.util.Arrays.stream(Grounding.words(variable).split(" "))
                .filter(w -> w.length() >= 3 && !FILLER.contains(w)).toList();
        return !words.isEmpty() && words.stream().allMatch(w -> material.contains(" " + w + " ")
                || (w.endsWith("s") && material.contains(" " + w.substring(0, w.length() - 1) + " "))
                || material.contains(" " + w + "s "));
    }

    static List<Relation> relations(RelationReview r, Map<String, Item> byId, String topic) {
        List<Relation> out = new ArrayList<>();
        if (r == null || r.works() == null) {
            return out;
        }
        Set<String> done = new LinkedHashSet<>();
        for (RelationNote n : r.works()) {
            Item i = n == null || n.workId() == null ? null : byId.get(n.workId().trim().toUpperCase(Locale.ROOT));
            if (i == null || !done.add(i.id())) {
                continue;
            }
            RelationKind kind = parse(RelationKind.class, n.kind(), null);
            String quote = ResearchCheckService.verbatim(n.quote(), i.abstractText(), topic);
            String how = cap(n.how(), 300);
            if (kind == null || quote == null || how == null
                    || !Grounding.supported(how, Grounding.material(topic, quote, i.saved().source().title()))) {
                continue;
            }
            out.add(new Relation(i.saved().key(), i.saved().source().title(), kind, how, quote));
        }
        return out;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Cut at a word boundary with an ellipsis, never mid-word. */
    static String shorten(String s, int max) {
        String t = cap(s, Integer.MAX_VALUE);
        if (t == null || t.length() <= max) {
            return t;
        }
        int cut = t.lastIndexOf(' ', max - 1);
        return t.substring(0, cut > max / 2 ? cut : max - 1).replaceAll("[,;:\\s]+$", "") + "…";
    }

    private static String cap(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
