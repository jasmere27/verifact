package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.PaperAnalysis.Aspect;
import com.ai.agent.verifact.research.PaperAnalysis.MatchStatus;
import com.ai.agent.verifact.research.StudentOutputs.MethodNote;
import com.ai.agent.verifact.research.StudentOutputs.PaperNote;
import com.ai.agent.verifact.research.StudentOutputs.PaperReading;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A student's uploaded paper (ADR-23): only claims backed by the paper's own words survive; the record match is checked. */
class PaperAnalysisServiceTest {

    private static final String TITLE = "Flipped Classroom and Mathematics Achievement of Grade 11 Students";
    private static final String PAPER = TITLE + "\nMaria Reyes and Jose Santos\nhttps://doi.org/10.1234/flip.2023.7\n\n"
            + "Abstract. This quasi-experimental study involved 120 Grade 11 students from two public senior high schools in Laguna. "
            + "Students in the flipped classroom scored significantly higher on the post-test than those in the lecture group (p < .05). "
            + "Data were collected using a 40-item mathematics achievement test validated by three experts. "
            + "An independent samples t-test was used to compare the groups. "
            + "The study was limited to two schools in one province, so the results may not apply to other settings.";

    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<String> doiLookups = new java.util.ArrayList<>();
    private final ResearchDiscoveryServiceTest.FakeIndex index = new ResearchDiscoveryServiceTest.FakeIndex() {
        @Override
        public Optional<ScholarlyWork> byDoi(String doi) {
            doiLookups.add(doi);
            return "10.1234/flip.2023.7".equals(doi) ? Optional.of(scholarly(TITLE, doi)) : Optional.empty();
        }

        @Override
        public List<ScholarlyWork> byReference(String text, int rows) {
            return List.of(scholarly(TITLE, "10.1234/flip.2023.7"));
        }

        @Override
        public Optional<DiscoveredWork> work(String key) {
            return Optional.of(ResearchDiscoveryServiceTest.work(key, TITLE, "abstract", "PH"));
        }
    };
    private final PaperAnalysisService service =
            new PaperAnalysisService(llm, index, Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));

    private static ScholarlyWork scholarly(String title, String doi) {
        return new ScholarlyWork(doi, title, List.of("Reyes, M."), 2023, "Journal", null, 3, false, List.of(), null);
    }

    private static DocumentExtractor.Extracted doc(String text) {
        return new DocumentExtractor.Extracted(DocumentExtractor.Kind.PDF, text, 8, false);
    }

    private void reading(PaperReading r) {
        llm.answer(PaperReading.class, m -> r);
    }

    @Test
    void findingsMethodAndLimitationsKeepOnlyThePapersOwnWords() {
        reading(new PaperReading(TITLE, List.of("Maria Reyes"), 2023, null,
                "The study compared 120 Grade 11 students taught with a flipped classroom or lectures. Flipped students scored higher. "
                        + "About 95% of teachers now use it.",
                List.of(new PaperNote("Flipped classroom students scored higher on the post-test.",
                                "Students in the flipped classroom scored significantly higher on the post-test than those in the lecture group"),
                        new PaperNote("Flipped learning doubled scores everywhere.", "flipped learning doubled scores in every school we studied"),
                        new PaperNote("Scores rose by 35 points.", "Students in the flipped classroom scored significantly higher on the post-test")),
                List.of(new MethodNote("DESIGN", "A quasi-experiment with 120 students.", "This quasi-experimental study involved 120 Grade 11 students"),
                        new MethodNote("INSTRUMENTS", "A 40-item test checked by experts.", "Data were collected using a 40-item mathematics achievement test"),
                        new MethodNote("MAGIC", "Not an aspect.", "An independent samples t-test was used to compare the groups")),
                List.of(new PaperNote("Only two schools in one province.", "The study was limited to two schools in one province"))));

        PaperAnalysis a = service.analyze(doc(PAPER), "PH");

        // Fabricated quote dropped; a number (35) not in its quote rejected.
        assertThat(a.findings()).extracting(PaperAnalysis.Quoted::statement)
                .containsExactly("Flipped classroom students scored higher on the post-test.");
        assertThat(a.findings().get(0).quote()).startsWith("Students in the flipped classroom scored significantly higher");
        assertThat(a.method()).extracting(PaperAnalysis.MethodPart::aspect).containsExactly(Aspect.DESIGN, Aspect.INSTRUMENTS);
        assertThat(a.authorLimitations()).hasSize(1);
        // The summary sentence with a number that isn't in the paper (95%) is removed.
        assertThat(a.plainSummary()).contains("120 Grade 11 students").doesNotContain("95");
    }

    @Test
    void aDoiPrintedInThePaperWithItsTitleOnThePageIsVerified() {
        reading(new PaperReading(TITLE, List.of(), null, null, null, List.of(), List.of(), List.of()));

        PaperAnalysis a = service.analyze(doc(PAPER), "PH");

        assertThat(a.match().status()).isEqualTo(MatchStatus.VERIFIED);
        assertThat(a.match().source().key()).isEqualTo("10.1234/flip.2023.7");
        assertThat(a.match().basis()).contains("DOI printed in the paper");
    }

    @Test
    void aDoiOnlyTheModelSuppliesIsIgnoredAndTheTitleIsUsed() {
        String noDoi = PAPER.replace("https://doi.org/10.1234/flip.2023.7\n", "");
        reading(new PaperReading(TITLE, List.of("Maria Reyes"), 2023, "10.9999/made.up", null, List.of(), List.of(), List.of()));

        PaperAnalysis a = service.analyze(doc(noDoi), "PH");

        assertThat(a.match().status()).isEqualTo(MatchStatus.VERIFIED);
        assertThat(a.match().basis()).contains("Title on the first page");
        assertThat(doiLookups).isEmpty();
    }

    @Test
    void aTitleTheModelInventsIsNotLookedUp() {
        String noDoi = PAPER.replace("https://doi.org/10.1234/flip.2023.7\n", "");
        reading(new PaperReading("A Completely Different Study Of Something", List.of(), null, null, null, List.of(), List.of(), List.of()));

        PaperAnalysis a = service.analyze(doc(noDoi), "PH");

        assertThat(a.match().status()).isEqualTo(MatchStatus.NOT_FOUND);
        assertThat(a.match().source()).isNull();
    }

    @Test
    void anIndexOutageIsNotReportedAsNotFound() {
        ResearchDiscoveryServiceTest.FakeIndex down = new ResearchDiscoveryServiceTest.FakeIndex() {
            @Override
            public Optional<ScholarlyWork> byDoi(String doi) {
                throw new ScholarlyIndex.ScholarlyIndexUnavailableException("down");
            }
        };
        llm.answer(PaperReading.class, m -> new PaperReading(TITLE, List.of(), null, null, null, List.of(), List.of(), List.of()));

        PaperAnalysis a = new PaperAnalysisService(llm, down, Clock.systemUTC()).analyze(doc(PAPER), null);

        assertThat(a.match().status()).isEqualTo(MatchStatus.LOOKUP_FAILED);
        assertThat(a.findings()).isEmpty();
        assertThat(a.limitations()).anyMatch(l -> l.contains("No findings"));
    }

    @Test
    void shortTitlesCountOnlyAsTheirOwnLineNearTheTop() {
        assertThat(PaperAnalysisService.titleOnPage("Deep learning", "Deep learning\nYann LeCun, Yoshua Bengio\nAbstract ...")).isTrue();
        assertThat(PaperAnalysisService.titleOnPage("Deep learning", "We review how deep learning changed vision research.")).isFalse();
        assertThat(PaperAnalysisService.titleOnPage(TITLE, "Header\n" + TITLE.toUpperCase() + "\nAuthors")).isTrue();
    }
}
