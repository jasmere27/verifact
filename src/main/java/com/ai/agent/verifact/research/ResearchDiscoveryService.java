package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.Discovery.Category;
import com.ai.agent.verifact.research.Discovery.FoundSource;
import com.ai.agent.verifact.research.Discovery.Lead;
import com.ai.agent.verifact.research.Discovery.Stance;
import com.ai.agent.verifact.research.Discovery.Verification;
import com.ai.agent.verifact.research.DiscoveryOutputs.RelevanceReview;
import com.ai.agent.verifact.research.DiscoveryOutputs.SearchPlan;
import com.ai.agent.verifact.research.DiscoveryOutputs.WorkNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.ai.agent.verifact.evidence.Grounding.material;
import static com.ai.agent.verifact.evidence.Grounding.words;

/**
 * Student Research Mode discovery (ADR-15). Two model calls around a scholarly-index search:
 *
 * <pre>
 * topic (+ text) ─► [LLM 1] search queries (+ theory/concept/method names to look for)
 *                ─► OpenAlex search: filters by country, year, type; local/foreign decided from affiliations
 *                ─► [LLM 2] relevance (and stance for claims) per work, from its abstract only
 *                ─► checks: explanations need a verbatim, relevant abstract quote; stances too
 * </pre>
 *
 * Nothing in a result comes from the model's memory: every source is an index record, and suggested
 * theory/concept names are VERIFIED only when a found source names them.
 */
@Service
public class ResearchDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ResearchDiscoveryService.class);

    static final int MAX_QUERIES = 3;
    static final int MAX_NAMES = 4;
    static final int PER_SEARCH = 8;
    static final int MAX_SOURCES = 12;
    static final int MAX_ABSTRACT_CHARS = 1200;
    static final int REVIEW_BATCH = 4;

    static final String NOTICE = "Every source here is a real record from OpenAlex (with its DOI where one exists). "
            + "\"Why it's relevant\" is an AI reading of the abstract, shown only with the abstract's own words: read "
            + "the paper before citing it.";

    private final LlmClient llm;
    private final ScholarlyIndex index;
    private final Clock clock;

    public ResearchDiscoveryService(LlmClient llm, ScholarlyIndex index, Clock clock) {
        this.llm = llm;
        this.index = index;
        this.clock = clock;
    }

    /**
     * @param text        paragraph or claim, for FOR_TEXT / SUPPORTING / CONTRADICTING; else null
     * @param countryCode the student's country for LOCAL/FOREIGN (e.g. "PH"); null means no local split
     */
    public Discovery discover(Category category, String topic, String text, String countryCode) {
        long startedAt = System.nanoTime();
        String country = countryCode == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
        if (country != null && !country.matches("[A-Z]{2}")) {
            country = null;
        }
        if ((category == Category.LOCAL || category == Category.FOREIGN) && country == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose your country to separate local and foreign studies.");
        }
        boolean claimMode = category == Category.SUPPORTING || category == Category.CONTRADICTING;
        if ((claimMode || category == Category.FOR_TEXT) && (text == null || text.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paste the paragraph or claim to find studies for.");
        }
        List<String> limitations = new ArrayList<>();

        // 1. Plan searches
        String nonce = nonce();
        SearchPlan plan = llm.generateQuick(DiscoveryPrompts.withNonce(DiscoveryPrompts.PLAN_SYSTEM, nonce),
                DiscoveryPrompts.planUser(nonce, category.name(), clean(topic, 300), clean(text, 1500),
                        country == null ? null : Locale.of("", country).getDisplayCountry(Locale.ENGLISH)),
                SearchPlan.class);
        List<String> queries = queries(plan, topic, text);
        List<String> names = category == Category.THEORIES || category == Category.CONCEPTS || category == Category.METHODS
                ? names(plan) : List.of();

        // 2. Search the index (names get their own searches so each can be verified)
        Map<String, Hit> hits = new LinkedHashMap<>();
        Map<String, List<String>> keysByName = new LinkedHashMap<>();
        List<String> searches = new ArrayList<>();
        try {
            if (!names.isEmpty()) {
                String context = queries.get(0);
                for (String name : names) {
                    String q = name + " " + context;
                    searches.add(q);
                    List<String> keys = new ArrayList<>();
                    for (DiscoveredWork w : index.discover(q, null, null, null, true, 4)) {
                        String key = add(hits, w);
                        if (key != null && mentions(w, name)) {
                            keys.add(key);
                        }
                    }
                    keysByName.put(name, keys);
                }
            } else {
                for (String q : queries) {
                    searches.add(q);
                    for (DiscoveredWork w : search(category, q, country)) {
                        add(hits, w);
                    }
                }
            }
        } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
            log.warn("Discovery search failed: {}", e.getMessage());
            if (hits.isEmpty()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The research database is unavailable right now, so no sources can be found. Please try again shortly.");
            }
            limitations.add("Some searches failed; results may be incomplete.");
        }
        if (category == Category.FOREIGN && country != null) {
            String c = country;
            hits.values().removeIf(h -> h.work.countries().contains(c));
        }

        // 3. Relevance (and stance) from abstracts only
        List<Hit> candidates = new ArrayList<>(hits.values());
        Map<String, WorkNote> notes = new LinkedHashMap<>();
        List<Hit> withAbstracts = candidates.stream().filter(h -> h.work.work().abstractText() != null).limit(MAX_SOURCES + 4).toList();
        if (!withAbstracts.isEmpty()) {
            // Small batches in parallel: one long review took ~110 s in production; batches of 4 take ~15-25 s each.
            List<Future<RelevanceReview>> batches = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int from = 0; from < withAbstracts.size(); from += REVIEW_BATCH) {
                    List<Hit> batch = withAbstracts.subList(from, Math.min(from + REVIEW_BATCH, withAbstracts.size()));
                    batches.add(pool.submit(() -> review(batch, category, topic, claimMode ? text : null)));
                }
            }
            RuntimeException firstFailure = null;
            int failed = 0;
            for (Future<RelevanceReview> f : batches) {
                try {
                    RelevanceReview review = f.get();
                    if (review != null && review.works() != null) {
                        for (WorkNote n : review.works()) {
                            if (n != null && n.workId() != null) {
                                notes.putIfAbsent(n.workId().trim().toUpperCase(Locale.ROOT), n);
                            }
                        }
                    }
                } catch (ExecutionException e) {
                    failed++;
                    if (firstFailure == null) {
                        firstFailure = e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The search was interrupted. Please try again.");
                }
            }
            if (failed == batches.size() && firstFailure != null) {
                throw firstFailure;
            }
            if (failed > 0) {
                limitations.add("Relevance couldn't be checked for some sources; they're listed without an explanation.");
            }
        }

        String relevanceTo = claimMode || category == Category.FOR_TEXT ? clean(text, 1500) : clean(topic, 300);
        List<FoundSource> sources = new ArrayList<>();
        for (Hit h : candidates) {
            WorkNote n = notes.get(h.id);
            String abs = h.work.work().abstractText();
            String quote = n == null || abs == null ? null : ResearchCheckService.verbatim(n.quote(), abs, relevanceTo);
            String why = quote == null ? null : clean(n.why(), 300);
            if (why != null && (why.isBlank() || !Grounding.supported(why, material(relevanceTo, quote, h.work.work().title())))) {
                why = null;
                quote = null;
            }
            Stance stance = null;
            if (claimMode) {
                stance = stance(n == null ? null : n.stance());
                boolean wanted = category == Category.SUPPORTING ? stance == Stance.SUPPORTS : stance == Stance.CONTRADICTS;
                if (!wanted || quote == null) {
                    continue; // a claim search only lists studies whose own words back the stance
                }
            } else if (n != null && !n.relevant() && quote == null) {
                continue; // the model judged it off-topic and there's nothing to show for it
            } else if (names.isEmpty() && !onTopic(h.work, relevanceTo)) {
                continue; // not one distinctive topic word in its title or abstract, whatever the model said
            }
            sources.add(source(h, why, quote, stance, country));
        }
        // Explained sources first (they have abstract evidence), then the rest in search order.
        sources.sort((a, b) -> Boolean.compare(b.relevanceQuote() != null, a.relevanceQuote() != null));
        if (sources.size() > MAX_SOURCES) {
            sources = new ArrayList<>(sources.subList(0, MAX_SOURCES));
        }

        List<Lead> leads = new ArrayList<>();
        keysByName.forEach((name, keys) -> {
            List<String> shown = keys.stream().filter(k -> hits.containsKey(k)).distinct().limit(3).toList();
            leads.add(new Lead(name, shown.isEmpty() ? Verification.UNVERIFIED : Verification.VERIFIED, shown));
        });
        if (leads.stream().anyMatch(l -> l.verification() == Verification.UNVERIFIED)) {
            limitations.add("Suggestions marked Unverified weren't named by any source found: treat them as ideas to "
                    + "look up, not as established references.");
        }
        if (category == Category.LOCAL) {
            limitations.add("Local studies are those with an author at an institution in your country, per OpenAlex. "
                    + "Many local journals aren't indexed, so check your library and local databases too.");
        }
        if (sources.isEmpty() && limitations.isEmpty()) {
            limitations.add("No sources matched. Try a broader topic or different keywords.");
        }
        if (claimMode && sources.isEmpty()) {
            limitations.add("No study's abstract clearly " + (category == Category.SUPPORTING ? "supports" : "contradicts")
                    + " the claim. That doesn't mean none exists: try rephrasing the claim.");
        }

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Discovery done category={} searches={} found={} shown={} durationMs={}", category, searches.size(),
                hits.size(), sources.size(), durationMs);
        return new Discovery(category, clean(topic, 300), List.copyOf(searches), sources, leads, List.copyOf(limitations),
                NOTICE, durationMs);
    }

    private RelevanceReview review(List<Hit> batch, Category category, String topic, String claim) {
        String nonce = nonce();
        List<DiscoveryPrompts.WorkLine> lines = new ArrayList<>();
        for (Hit h : batch) {
            String a = h.work.work().abstractText();
            lines.add(new DiscoveryPrompts.WorkLine(h.id, h.work.work().title(), h.work.work().year(),
                    a.length() > MAX_ABSTRACT_CHARS ? a.substring(0, MAX_ABSTRACT_CHARS) : a));
        }
        return llm.generateQuick(DiscoveryPrompts.withNonce(DiscoveryPrompts.REVIEW_SYSTEM, nonce),
                DiscoveryPrompts.reviewUser(nonce, category.name(), clean(topic, 300), clean(claim, 1500), lines),
                RelevanceReview.class);
    }

    private List<DiscoveredWork> search(Category category, String q, String country) {
        int thisYear = LocalDate.now(clock).getYear();
        return switch (category) {
            case RRL -> merge(index.discover(q, null, null, "review", false, PER_SEARCH / 2),
                    index.discover(q, null, null, null, true, PER_SEARCH / 2));
            case RRS -> index.discover(q, null, thisYear - 10, "article", false, PER_SEARCH);
            case LOCAL -> index.discover(q, country, null, null, false, PER_SEARCH);
            case FOREIGN, FOR_TEXT, SUPPORTING, CONTRADICTING -> index.discover(q, null, null, null, false, PER_SEARCH);
            case RECENT -> index.discover(q, null, thisYear - 5, null, false, PER_SEARCH);
            case THEORIES, CONCEPTS, METHODS -> index.discover(q, null, null, null, true, PER_SEARCH);
        };
    }

    private static List<DiscoveredWork> merge(List<DiscoveredWork> a, List<DiscoveredWork> b) {
        List<DiscoveredWork> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private record Hit(String id, DiscoveredWork work) {}

    /** Adds a work under its stable key (DOI, else OpenAlex id); returns the key, or null if it has neither. */
    private static String add(Map<String, Hit> hits, DiscoveredWork w) {
        String key = key(w);
        if (key == null || w.work().title() == null) {
            return null;
        }
        hits.putIfAbsent(key, new Hit("W" + (hits.size() + 1), w));
        return key;
    }

    static String key(DiscoveredWork w) {
        if (w.work().doi() != null) {
            return w.work().doi();
        }
        return w.openAlexId();
    }

    /** A suggested name counts as verified for a work only if its title or abstract names it. */
    static boolean mentions(DiscoveredWork w, String name) {
        String n = words(name).replaceAll("\\b(the|theory|model|framework)\\b", " ").trim().replaceAll("\\s+", " ");
        if (n.length() < 3) {
            return false;
        }
        return material(w.work().title() == null ? "" : w.work().title(),
                w.work().abstractText() == null ? "" : w.work().abstractText()).contains(" " + n + " ");
    }

    private static FoundSource source(Hit h, String why, String quote, Stance stance, String country) {
        return toSource(h.work, why, quote, stance, country);
    }

    /** A found source as shown and saved: metadata only from the index record, never from the model. */
    static FoundSource toSource(DiscoveredWork d, String why, String quote, Stance stance, String country) {
        ScholarlyWork w = d.work();
        String url = w.url() != null ? w.url() : d.landingUrl() != null ? d.landingUrl() : d.openAlexId();
        List<String> authors = w.authors().size() > 6 ? List.of(w.authors().get(0), w.authors().get(1), w.authors().get(2),
                w.authors().get(3), w.authors().get(4), "et al.") : w.authors();
        return new FoundSource(key(d), w.doi(), url, w.title(), authors, w.year(), w.venue(), d.type(),
                d.countries(), country != null && d.countries().contains(country), w.retracted(), w.citedByCount(),
                w.abstractText() != null, why, quote, stance, Verification.VERIFIED);
    }

    /**
     * The model's queries, kept only if they share a distinctive word with the student's topic or text: a
     * confused or refusing plan ("please resubmit the topic…", seen in production) must never become a search.
     */
    static List<String> queries(SearchPlan plan, String topic, String text) {
        List<String> out = new ArrayList<>();
        Set<String> anchor = distinctive(topic + " " + (text == null ? "" : text));
        if (plan != null && plan.queries() != null) {
            plan.queries().stream().map(q -> clean(q, 150)).filter(q -> q.split(" ").length >= 2)
                    .filter(q -> distinctive(q).stream().anyMatch(anchor::contains)).distinct()
                    .limit(MAX_QUERIES).forEach(out::add);
        }
        if (out.isEmpty()) {
            out.add(clean(topic, 150));
        }
        return out;
    }

    /** Words too common in research titles to show that a paper is about this topic. */
    private static final Set<String> GENERIC = Set.of("effect", "effects", "impact", "influence", "role", "study", "studies",
            "student", "students", "learner", "learners", "school", "schools", "high", "senior", "junior", "grade", "level",
            "research", "analysis", "among", "using", "based", "case", "approach", "towards", "toward", "between", "their",
            "during", "within", "through", "from", "with", "into", "about", "this", "that", "what", "which", "does", "how",
            "and", "the", "for", "are", "was", "were", "has", "have", "its", "not", "new", "use", "on", "of", "in", "to", "a", "an");

    static Set<String> distinctive(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : words(text == null ? "" : text).split(" ")) {
            if (w.length() >= 3 && !GENERIC.contains(w) && !w.matches("\\d+")) {
                out.add(w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }

    static boolean onTopic(DiscoveredWork w, String relevanceTo) {
        Set<String> topicWords = distinctive(relevanceTo);
        if (topicWords.isEmpty()) {
            return true;
        }
        Set<String> workWords = distinctive((w.work().title() == null ? "" : w.work().title()) + " "
                + (w.work().abstractText() == null ? "" : w.work().abstractText()));
        return topicWords.stream().anyMatch(workWords::contains);
    }

    private static List<String> names(SearchPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan != null && plan.names() != null) {
            plan.names().stream().map(n -> clean(n, 80)).filter(n -> !n.isBlank()).distinct().limit(MAX_NAMES).forEach(out::add);
        }
        return out;
    }

    private static Stance stance(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.equals("SUPPORTS") ? Stance.SUPPORTS : v.equals("CONTRADICTS") ? Stance.CONTRADICTS : null;
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
