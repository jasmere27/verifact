package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.Discovery.Category;
import com.ai.agent.verifact.research.ProjectFile.Kind;
import com.ai.agent.verifact.research.ResearchProject.Action;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.Milestone;
import com.ai.agent.verifact.research.ResearchProject.NextStep;
import com.ai.agent.verifact.research.ResearchProject.Priority;
import com.ai.agent.verifact.research.ResearchProject.Progress;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "What should I do next?" and the progress checklist, computed in code from what the project actually
 * contains (ADR-21). Every step states the facts it rests on; nothing here calls a model or names a source,
 * so it can't invent anything. Finding real sources is the student's next click (the action), which runs the
 * normal index search.
 */
final class ProjectAdvisor {

    /** Sources per research question before it counts as covered. */
    static final int SOURCES_PER_QUESTION = 3;
    /** A literature review usually needs at least this many sources. */
    static final int LIBRARY_TARGET = 10;
    static final int RECENT_YEARS = 5;
    static final int MAX_STEPS = 6;

    private ProjectAdvisor() {
    }

    static String label(List<Question> questions, String id) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).id().equals(id)) {
                return "RQ" + (i + 1);
            }
        }
        return "a research question";
    }

    static long linked(List<LibraryItem> library, String questionId) {
        return library.stream().filter(i -> i.questionIds() != null && i.questionIds().contains(questionId)).count();
    }

    static boolean recent(LibraryItem item, int year) {
        Integer y = item.source() == null ? null : item.source().year();
        return y != null && y > year - RECENT_YEARS;
    }

    static Progress progress(ProjectData p, List<ProjectFile.Summary> files, int year) {
        List<ProjectFile.Summary> drafts = drafts(files);
        List<Milestone> m = new ArrayList<>();
        List<LibraryItem> lib = p.library();
        m.add(new Milestone("topic", "Topic or working title", p.title() != null && !p.title().isBlank(), null));
        m.add(new Milestone("questions", "Research questions written", !p.questions().isEmpty(),
                p.questions().isEmpty() ? "None yet" : p.questions().size() + " question" + (p.questions().size() == 1 ? "" : "s")));
        m.add(new Milestone("library", "At least " + LIBRARY_TARGET + " sources saved", lib.size() >= LIBRARY_TARGET, lib.size() + " saved"));
        long covered = p.questions().stream().filter(q -> linked(lib, q.id()) >= SOURCES_PER_QUESTION).count();
        m.add(new Milestone("coverage", "Every question has " + SOURCES_PER_QUESTION + "+ sources",
                !p.questions().isEmpty() && covered == p.questions().size(),
                p.questions().isEmpty() ? "Add questions first" : covered + " of " + p.questions().size() + " covered"));
        if (p.country() != null) {
            long local = lib.stream().filter(i -> i.source().local()).count();
            long foreign = lib.stream().filter(i -> foreign(i)).count();
            m.add(new Milestone("localForeign", "Local and foreign studies", local > 0 && foreign > 0, local + " local · " + foreign + " foreign"));
        }
        long theories = lib.stream().filter(i -> i.folder() == Folder.THEORY).count();
        m.add(new Milestone("framework", "Theory or framework chosen", theories > 0, theories + " saved"));
        m.add(new Milestone("gap", "Research gap stated in your words", !p.gaps().isEmpty(), p.gaps().size() + " written"));
        m.add(new Milestone("draft", "Draft uploaded", !drafts.isEmpty(),
                drafts.isEmpty() ? null : drafts.size() + " draft" + (drafts.size() == 1 ? "" : "s")));
        int uncited = uncited(drafts);
        m.add(new Milestone("cited", "Draft claims have citations", !drafts.isEmpty() && uncited == 0,
                drafts.isEmpty() ? "Upload a draft" : uncited + " statement" + (uncited == 1 ? "" : "s") + " may need one"));
        int done = (int) m.stream().filter(Milestone::done).count();
        return new Progress(Math.round(100f * done / m.size()), m);
    }

    private static boolean foreign(LibraryItem i) {
        return !i.source().local() && i.source().countries() != null && !i.source().countries().isEmpty();
    }

    static List<NextStep> nextSteps(ProjectData p, List<ProjectFile.Summary> files, int year) {
        List<NextStep> steps = new ArrayList<>();
        List<ProjectFile.Summary> drafts = drafts(files);
        List<LibraryItem> lib = p.library();

        List<String> retracted = lib.stream().filter(i -> i.source().retracted()).map(LibraryItem::key).toList();
        if (!retracted.isEmpty()) {
            steps.add(step("retracted", Priority.HIGH, plural(retracted.size(), "saved source has", "saved sources have") + " been retracted",
                    "Retracted papers shouldn't be cited as evidence. Remove them or cite them only as retracted.",
                    Action.REVIEW_RETRACTED, null, null, retracted));
        }
        if (p.questions().isEmpty()) {
            steps.add(step("questions", Priority.HIGH, "Write your research questions",
                    "Your questions decide which literature you need. Everything else (coverage, gaps, next steps) is checked against them.",
                    Action.ADD_QUESTIONS, null, null, List.of()));
        }
        if (lib.isEmpty()) {
            steps.add(step("start-library", Priority.HIGH, "Find your first sources",
                    "Start with reviews of your topic (for your RRL), then studies that collected data (for your RRS).",
                    Action.FIND_SOURCES, Category.RRL, null, List.of()));
        }
        for (Question q : p.questions()) {
            long n = linked(lib, q.id());
            if (n < SOURCES_PER_QUESTION) {
                String rq = label(p.questions(), q.id());
                steps.add(step("coverage-" + q.id(), n == 0 ? Priority.HIGH : Priority.MEDIUM,
                        n == 0 ? rq + " has no studies yet" : rq + " has only " + n + " " + (n == 1 ? "study" : "studies"),
                        "Aim for at least " + SOURCES_PER_QUESTION + " studies about “" + shorten(q.text()) + "”.",
                        Action.FIND_SOURCES, Category.FOR_TEXT, q.id(), List.of(n + " linked")));
            }
        }
        int uncited = uncited(drafts);
        if (uncited > 0) {
            ProjectFile.Summary worst = drafts.stream().filter(d -> d.needsCitation() != null && d.needsCitation() > 0)
                    .max(Comparator.comparing(ProjectFile.Summary::needsCitation)).orElseThrow();
            steps.add(new NextStep("draft-claims", Priority.HIGH, plural(uncited, "statement", "statements") + " in your "
                    + (drafts.size() == 1 ? "draft" : "drafts") + " may need citations",
                    "Each one makes a claim without a source. Find a source for it, or rephrase it as your own reasoning.",
                    Action.REVIEW_DRAFT_CLAIMS, null, null, worst.id().toString(),
                    drafts.stream().filter(d -> d.needsCitation() != null && d.needsCitation() > 0).map(ProjectAdvisor::name).toList()));
        }
        Set<String> saved = new HashSet<>(lib.stream().map(i -> i.key().toLowerCase(java.util.Locale.ROOT)).toList());
        List<ProjectFile.Summary> unsaved = files.stream()
                .filter(f -> f.kind() == Kind.PAPER && f.matchedKey() != null && !saved.contains(f.matchedKey().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        if (!unsaved.isEmpty()) {
            steps.add(new NextStep("papers", Priority.MEDIUM, plural(unsaved.size(), "uploaded paper isn't", "uploaded papers aren't") + " in your library yet",
                    "Add them so they count towards your questions, gaps and reference list.",
                    Action.OPEN_FILES, null, null, unsaved.get(0).id().toString(), unsaved.stream().map(ProjectAdvisor::name).toList()));
        }
        if (!p.suggestions().isEmpty()) {
            steps.add(step("review-links", Priority.MEDIUM, plural(p.suggestions().size(), "suggested link", "suggested links") + " to review",
                    "Sources that may help answer your research questions, each with the abstract's own words. Accept the ones that fit.",
                    Action.REVIEW_LINKS, null, null, p.suggestions().stream().map(ResearchProject.LinkSuggestion::title).distinct().limit(5).toList()));
        }
        List<String> unlinked = lib.stream().filter(i -> i.questionIds() == null || i.questionIds().isEmpty()).map(LibraryItem::key).toList();
        if (!p.questions().isEmpty() && unlinked.size() >= 3 && p.suggestions().isEmpty()) {
            steps.add(step("link", Priority.MEDIUM, plural(unlinked.size(), "saved source isn't", "saved sources aren't") + " linked to a research question",
                    "Linking shows which questions are well supported and which still need literature. Get suggestions, then accept the ones that fit.",
                    Action.LINK_SOURCES, null, null, unlinked));
        }
        if (lib.size() >= LIBRARY_TARGET - 2 && p.gaps().isEmpty()) {
            steps.add(step("gap", Priority.MEDIUM, "Your literature is growing, but your research gap isn't stated yet",
                    "Write what the existing studies don't cover that your study will. Generate insights for possible gaps to consider, then put it in your own words.",
                    p.insights() == null ? Action.GENERATE_INSIGHTS : Action.WRITE_GAP, null, null, List.of(lib.size() + " sources saved")));
        }
        if (lib.size() >= 3) {
            if (p.country() != null && lib.stream().noneMatch(i -> i.source().local())) {
                steps.add(step("local", Priority.MEDIUM, "No local studies yet",
                        "Panels usually expect studies from your own country. These are found by author affiliation.",
                        Action.FIND_SOURCES, Category.LOCAL, null, List.of("0 of " + lib.size() + " sources local")));
            }
            if (p.country() != null && lib.stream().noneMatch(ProjectAdvisor::foreign)) {
                steps.add(step("foreign", Priority.MEDIUM, "No foreign studies yet",
                        "Foreign studies show how your topic has been studied elsewhere.",
                        Action.FIND_SOURCES, Category.FOREIGN, null, List.of("0 of " + lib.size() + " sources foreign")));
            }
            if (lib.stream().noneMatch(i -> recent(i, year))) {
                steps.add(step("recent", Priority.MEDIUM, "Nothing from the last " + RECENT_YEARS + " years",
                        "Recent studies show your topic is still relevant and what's changed.",
                        Action.FIND_SOURCES, Category.RECENT, null, List.of("newest saved: " + newest(lib))));
            }
        }
        if (lib.size() >= 5 && lib.stream().noneMatch(i -> i.folder() == Folder.THEORY)) {
            steps.add(step("theory", Priority.MEDIUM, "No theory or framework saved",
                    "Most capstones need a theoretical or conceptual framework. Find theories used by studies like yours.",
                    Action.FIND_SOURCES, Category.THEORIES, null, List.of()));
        }
        ProjectFile.Summary withRefs = drafts.stream().filter(d -> d.referenceEntries() > 0).findFirst().orElse(null);
        if (withRefs != null) {
            steps.add(new NextStep("citations", Priority.LOW, "Check the " + plural(withRefs.referenceEntries(), "reference", "references")
                    + " in " + name(withRefs),
                    "Confirms each one exists, isn't retracted, and that the cited paper's abstract supports what you wrote.",
                    Action.CHECK_CITATIONS, null, null, withRefs.id().toString(), List.of(name(withRefs))));
        }
        if (drafts.isEmpty() && lib.size() >= 5) {
            steps.add(step("draft", Priority.LOW, "Upload your draft",
                    "Get statements that may need citations and a check of your reference list.",
                    Action.UPLOAD_DRAFT, null, null, List.of()));
        }
        long toRead = lib.stream().filter(i -> i.status() == null || i.status() == ReadingStatus.TO_READ).count();
        if (toRead >= 5) {
            steps.add(step("read", Priority.LOW, plural((int) toRead, "source is", "sources are") + " still marked “to read”",
                    "Read them and add key findings, so your RRL is built on what the studies actually say.",
                    Action.OPEN_LIBRARY, null, null, List.of()));
        }
        if (p.insights() != null && lib.size() - p.insights().basedOnSources() >= 5) {
            steps.add(step("refresh-insights", Priority.LOW, "Refresh your gaps and framework insights",
                    "You've saved " + (lib.size() - p.insights().basedOnSources()) + " sources since they were generated.",
                    Action.GENERATE_INSIGHTS, null, null, List.of()));
        }
        return steps.stream().sorted(Comparator.comparing(NextStep::priority)).limit(MAX_STEPS).toList();
    }

    private static NextStep step(String id, Priority priority, String title, String detail, Action action, Category category,
                                 String questionId, List<String> basis) {
        return new NextStep(id, priority, title, detail, action, category, questionId, null, basis);
    }

    private static List<ProjectFile.Summary> drafts(List<ProjectFile.Summary> files) {
        return files == null ? List.of() : files.stream().filter(f -> f.kind() == Kind.DRAFT).toList();
    }

    private static int uncited(List<ProjectFile.Summary> drafts) {
        return drafts.stream().mapToInt(d -> d.needsCitation() == null ? 0 : d.needsCitation()).sum();
    }

    private static String name(ProjectFile.Summary f) {
        return f.label() != null ? f.label() : f.title() != null ? f.title() : f.fileName() != null ? f.fileName() : "your file";
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() > 90 ? t.substring(0, 89).stripTrailing() + "…" : t;
    }

    private static String newest(List<LibraryItem> lib) {
        return lib.stream().map(i -> i.source().year()).filter(y -> y != null).max(Integer::compare).map(String::valueOf).orElse("unknown year");
    }
}
