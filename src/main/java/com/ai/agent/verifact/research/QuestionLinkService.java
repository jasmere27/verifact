package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.LinkRole;
import com.ai.agent.verifact.research.ResearchProject.LinkStance;
import com.ai.agent.verifact.research.ResearchProject.LinkSuggestion;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.StudentOutputs.LinkProposal;
import com.ai.agent.verifact.research.StudentOutputs.LinkReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Suggests which library sources help answer which research questions (ADR-24). The model reads the sources'
 * abstracts against the questions; code keeps a suggestion only if its quote is verbatim in that source's abstract
 * and its sentence is grounded in the quote, title and question. A stance (supports / contradicts the expected answer)
 * needs a FINDING and a question with an expected answer. The student accepts or rejects each one; nothing is linked
 * automatically.
 */
@Service
public class QuestionLinkService {

    private static final Logger log = LoggerFactory.getLogger(QuestionLinkService.class);

    static final int MAX_SOURCES = 24;
    static final int BATCH = 6;
    static final int MAX_PER_SOURCE = 3;
    static final int MAX_ABSTRACT_CHARS = 900;
    static final int MIN_QUOTE_WORDS = 8;

    private final LlmClient llm;
    private final ScholarlyIndex index;

    public QuestionLinkService(LlmClient llm, ScholarlyIndex index) {
        this.llm = llm;
        this.index = index;
    }

    record Item(String id, LibraryItem saved, String abstractText) {}

    /** @param limitations filled with what was left out, for the student */
    List<LinkSuggestion> suggest(ProjectData p, List<String> limitations) {
        if (p.questions().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add your research questions first; links are suggested against them.");
        }
        Set<String> taken = new HashSet<>(p.dismissed());
        p.library().forEach(i -> i.questionIds().forEach(q -> taken.add(ProjectData.pair(i.key(), q))));
        // Sources with the fewest links first: they're the ones the student most needs help placing.
        List<LibraryItem> candidates = p.library().stream()
                .filter(i -> !i.source().retracted() && i.source().hasAbstract())
                .filter(i -> p.questions().stream().anyMatch(q -> !taken.contains(ProjectData.pair(i.key(), q.id()))))
                .sorted(Comparator.comparingInt(i -> i.questionIds().size()))
                .toList();
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, p.library().isEmpty()
                    ? "Save some sources to your library first."
                    : "There's nothing new to suggest: every source with an abstract is already linked or was reviewed.");
        }
        if (candidates.size() > MAX_SOURCES) {
            limitations.add("Looked at " + MAX_SOURCES + " of " + candidates.size() + " sources (the least linked first). Run it again after reviewing these.");
            candidates = candidates.subList(0, MAX_SOURCES);
        }
        List<Item> items = abstracts(candidates, limitations);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The research database couldn't be reached to re-read your sources. Please try again shortly.");
        }
        Map<String, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        Map<String, Question> questions = new HashMap<>();
        for (int i = 0; i < p.questions().size(); i++) {
            questions.put("Q" + (i + 1), p.questions().get(i));
        }

        List<Future<LinkReview>> batches = new ArrayList<>();
        List<LinkSuggestion> out = new ArrayList<>();
        int failed = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 0; from < items.size(); from += BATCH) {
                List<Item> batch = items.subList(from, Math.min(from + BATCH, items.size()));
                batches.add(pool.submit(() -> {
                    String nonce = UUID.randomUUID().toString().replace("-", "");
                    return llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.LINK_SYSTEM, nonce),
                            data(nonce, p, batch), LinkReview.class);
                }));
            }
            for (Future<LinkReview> f : batches) {
                try {
                    out.addAll(check(f.get(), byId, questions, taken));
                } catch (ExecutionException e) {
                    failed++;
                    log.warn("Link batch failed: {}", e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Suggesting links was interrupted. Please try again.");
        }
        if (failed == batches.size()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The analysis service is unavailable right now. Please try again shortly.");
        }
        if (failed > 0) {
            limitations.add("Some sources couldn't be read this time; run it again to include them.");
        }
        log.info("Link suggestions sources={} suggestions={}", items.size(), out.size());
        return out;
    }

    /** Code checks on the model's proposals: known ids, new pairs, verbatim quote, grounded sentence, valid stance. */
    static List<LinkSuggestion> check(LinkReview review, Map<String, Item> byId, Map<String, Question> questions, Set<String> taken) {
        List<LinkSuggestion> out = new ArrayList<>();
        if (review == null || review.links() == null) {
            return out;
        }
        Map<String, Integer> perSource = new HashMap<>();
        for (LinkProposal l : review.links()) {
            if (l == null || l.workId() == null || l.questionId() == null) {
                continue;
            }
            Item item = byId.get(l.workId().trim().toUpperCase(Locale.ROOT));
            Question q = questions.get(l.questionId().trim().toUpperCase(Locale.ROOT));
            LinkRole role = parse(LinkRole.class, l.role());
            if (item == null || q == null || role == null || perSource.getOrDefault(item.id(), 0) >= MAX_PER_SOURCE
                    || !taken.add(ProjectData.pair(item.saved().key(), q.id()))) {
                continue;
            }
            String quote = l.quote() == null ? null : Grounding.findSpan(l.quote(), item.abstractText(), MIN_QUOTE_WORDS);
            String how = ResearchInsightsService.shorten(l.how(), 300);
            String title = item.saved().source().title();
            if (quote == null || how == null || !Grounding.supported(how, Grounding.material(q.text(), q.hypothesis(), quote, title))) {
                taken.remove(ProjectData.pair(item.saved().key(), q.id()));
                continue;
            }
            LinkStance stance = role == LinkRole.FINDING && q.hypothesis() != null ? parse(LinkStance.class, l.stance()) : null;
            out.add(new LinkSuggestion(item.saved().key(), title, q.id(), role, stance, how, quote));
            perSource.merge(item.id(), 1, Integer::sum);
        }
        return out;
    }

    /** Library records don't keep abstracts (copyright); re-read them from the index, a few at a time. */
    private List<Item> abstracts(List<LibraryItem> saved, List<String> limitations) {
        Semaphore slots = new Semaphore(4);
        List<Future<Optional<DiscoveredWork>>> lookups = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (LibraryItem s : saved) {
                lookups.add(pool.submit(() -> {
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
                String abs = lookups.get(i).get().map(w -> w.work().abstractText()).orElse(null);
                if (abs != null && !abs.isBlank()) {
                    items.add(new Item("S" + (items.size() + 1), saved.get(i), abs));
                }
            } catch (ExecutionException e) {
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Suggesting links was interrupted. Please try again.");
            }
        }
        if (failed > 0) {
            limitations.add(failed + " source(s) couldn't be re-read from the research database and were left out.");
        }
        return items;
    }

    static String data(String nonce, ProjectData p, List<Item> items) {
        StringBuilder out = new StringBuilder("<<<DATA_").append(nonce).append(">>>\nStudent's topic: ").append(p.title()).append('\n');
        if (p.field() != null) {
            out.append("Field: ").append(p.field()).append('\n');
        }
        out.append("\nRESEARCH QUESTIONS\n");
        for (int i = 0; i < p.questions().size(); i++) {
            Question q = p.questions().get(i);
            out.append('Q').append(i + 1).append(" | ").append(q.text()).append('\n');
            if (q.hypothesis() != null) {
                out.append("  expected answer: ").append(q.hypothesis()).append('\n');
            }
        }
        out.append("\nSAVED STUDIES\n");
        for (Item i : items) {
            Discovery.FoundSource s = i.saved().source();
            String a = i.abstractText();
            out.append(i.id()).append(" | ").append(s.title()).append(" (").append(s.year() == null ? "n.d." : s.year()).append(")\n")
                    .append("  abstract: ").append(a.length() > MAX_ABSTRACT_CHARS ? a.substring(0, MAX_ABSTRACT_CHARS) : a).append('\n');
        }
        return out.append("<<<END_DATA_").append(nonce).append(">>>").toString();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
