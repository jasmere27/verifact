package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchProject.Action;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.NextStep;
import com.ai.agent.verifact.research.ResearchProject.Priority;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** "What should I do next?" and progress are rules over the project's real contents (ADR-21). */
class ProjectAdvisorTest {

    private static final int YEAR = 2026;
    private static final Question RQ1 = new Question("q1", "How does flipped learning affect mathematics achievement?");
    private static final Question RQ2 = new Question("q2", "How do students perceive flipped learning?");

    private static LibraryItem item(String key, Folder folder, int year, boolean local, boolean retracted, List<String> rqs) {
        Discovery.FoundSource s = new Discovery.FoundSource(key, key, "https://doi.org/" + key, "Title " + key, List.of("A. Reyes"), year,
                "J", "article", List.of(local ? "PH" : "US"), local, retracted, 3, true, null, null, null, Discovery.Verification.VERIFIED);
        return new LibraryItem(key, folder, s, ReadingStatus.READ, null, null, null, rqs, Instant.EPOCH);
    }

    private static ProjectData project(List<Question> qs, List<LibraryItem> lib) {
        return new ProjectData("Flipped classroom and mathematics achievement", "Education", "PH", qs, lib, List.of(), null, null, null);
    }

    private static List<String> ids(List<NextStep> steps) {
        return steps.stream().map(NextStep::id).toList();
    }

    @Test
    void aNewProjectIsToldToWriteQuestionsAndFindFirstSources() {
        List<NextStep> steps = ProjectAdvisor.nextSteps(project(List.of(), List.of()), YEAR);

        assertThat(ids(steps)).containsExactly("questions", "start-library");
        assertThat(steps).allSatisfy(s -> assertThat(s.priority()).isEqualTo(Priority.HIGH));
        assertThat(steps.get(1).action()).isEqualTo(Action.FIND_SOURCES);
        assertThat(steps.get(1).category()).isEqualTo(Discovery.Category.RRL);
        assertThat(ProjectAdvisor.progress(project(List.of(), List.of()), YEAR).percent()).isBetween(1, 20);
    }

    @Test
    void questionsWithFewLinkedSourcesGetTargetedSearches() {
        List<LibraryItem> lib = List.of(item("10.1/a", Folder.RRS, 2024, true, false, List.of("q1")),
                item("10.1/b", Folder.RRS, 2023, false, false, List.of("q1")),
                item("10.1/c", Folder.RRL, 2022, false, false, List.of("q1")));

        List<NextStep> steps = ProjectAdvisor.nextSteps(project(List.of(RQ1, RQ2), lib), YEAR);

        assertThat(ids(steps)).contains("coverage-q2").doesNotContain("coverage-q1");
        NextStep rq2 = steps.stream().filter(s -> s.id().equals("coverage-q2")).findFirst().orElseThrow();
        assertThat(rq2.title()).isEqualTo("RQ2 has no supporting literature yet");
        assertThat(rq2.priority()).isEqualTo(Priority.HIGH);
        assertThat(rq2.action()).isEqualTo(Action.FIND_SOURCES);
        assertThat(rq2.category()).isEqualTo(Discovery.Category.FOR_TEXT);
        assertThat(rq2.questionId()).isEqualTo("q2");
    }

    @Test
    void retractedSourcesComeFirstAndNameTheSources() {
        List<LibraryItem> lib = List.of(item("10.1/bad", Folder.RRS, 2020, true, true, List.of("q1")));

        List<NextStep> steps = ProjectAdvisor.nextSteps(project(List.of(RQ1), lib), YEAR);

        assertThat(steps.get(0).id()).isEqualTo("retracted");
        assertThat(steps.get(0).basis()).containsExactly("10.1/bad");
    }

    @Test
    void missingLocalForeignRecentTheoryAndGapAreFlaggedFromTheLibrary() {
        List<LibraryItem> lib = new ArrayList<>();
        IntStream.range(0, 9).forEach(i -> lib.add(item("10.1/" + i, Folder.RRS, 2012, false, false, List.of("q1"))));

        List<NextStep> steps = ProjectAdvisor.nextSteps(project(List.of(RQ1), lib), YEAR);
        List<String> all = ids(steps);

        assertThat(all).contains("local", "recent", "theory", "gap").doesNotContain("foreign");
        assertThat(steps.stream().filter(s -> s.id().equals("recent")).findFirst().orElseThrow().basis()).containsExactly("newest saved: 2012");
        assertThat(steps).hasSizeLessThanOrEqualTo(ProjectAdvisor.MAX_STEPS);
        // HIGH before MEDIUM before LOW
        assertThat(steps).isSortedAccordingTo(java.util.Comparator.comparing(NextStep::priority));
    }

    @Test
    void draftStatementsNeedingCitationsAreAHighPriorityStep() {
        Draft draft = new Draft("chapter2.docx", DocumentExtractor.Kind.DOCX, 10, 5000, false, Instant.EPOCH, "text", "s", List.of(),
                List.of(new Draft.Statement("Flipped learning always works.", "no citation"),
                        new Draft.Statement("Most schools use it.", "no citation")), 4, 6, "x", List.of());
        ProjectData p = new ProjectData("Topic here", null, null, List.of(RQ1), List.of(), List.of(), null, draft, null);

        List<NextStep> steps = ProjectAdvisor.nextSteps(p, YEAR);

        assertThat(steps).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo("draft-claims");
            assertThat(s.title()).isEqualTo("2 statements in your draft may need citations");
            assertThat(s.priority()).isEqualTo(Priority.HIGH);
        });
        assertThat(ids(ProjectAdvisor.nextSteps(p, YEAR))).contains("citations");
    }

    @Test
    void progressCountsWhatIsDone() {
        List<LibraryItem> lib = new ArrayList<>();
        IntStream.range(0, 10).forEach(i -> lib.add(item("10.1/" + i, i == 0 ? Folder.THEORY : Folder.RRS, 2024, i % 2 == 0, false, List.of("q1"))));
        ProjectData p = new ProjectData("Topic here", null, "PH", List.of(RQ1), lib,
                List.of(new ResearchProject.GapNote("g1", "Few PH studies on senior high.", List.of("10.1/1"))), null, null, null);

        ResearchProject.Progress progress = ProjectAdvisor.progress(p, YEAR);

        assertThat(progress.milestones()).filteredOn(ResearchProject.Milestone::done).extracting(ResearchProject.Milestone::id)
                .containsExactly("topic", "questions", "library", "coverage", "localForeign", "framework", "gap");
        assertThat(progress.percent()).isEqualTo(Math.round(100f * 7 / 9));
    }
}
