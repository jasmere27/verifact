package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.DocumentExtractor.Extracted;
import com.ai.agent.verifact.research.DocumentExtractor.Kind;
import com.ai.agent.verifact.research.StudentOutputs.DraftReading;
import com.ai.agent.verifact.research.StudentOutputs.Uncited;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftAnalysisServiceTest {

    static final String DRAFT = """
            Table of Contents
            Chapter 1 ........ 1
            References ........ 20

            Chapter 1: The Problem and Its Background
            Mathematics achievement among Filipino students remains low in international assessments.
            The flipped classroom has been linked to higher engagement in mathematics (Reyes & Cruz, 2021).
            Most senior high schools in the Philippines now use blended learning.
            This study aims to determine the effect of the flipped classroom on Grade 11 students' mathematics achievement.
            Studies show that students taught in flipped classes score higher on problem solving [3].
            Self-efficacy is a key concept in this study, together with mathematics achievement.

            Chapter 2: Methodology
            A quasi-experimental design with two intact classes of Grade 11 students will be used in this study to compare outcomes.

            References
            Reyes, A. M., & Cruz, J. (2021). Flipped classroom in Philippine senior high mathematics. Journal of Education, 12(3), 1-15. https://doi.org/10.1/abc
            Bergmann, J., & Sams, A. (2012). Flip your classroom. ISTE.

            Appendix A
            Questionnaire items
            """;

    private final ScriptedLlm llm = new ScriptedLlm();
    private final DraftAnalysisService service = new DraftAnalysisService(llm, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void referencesAreSplitAfterTheLastHeadingButNotFromTheTableOfContents() {
        String[] parts = DraftAnalysisService.split(DRAFT);

        assertThat(parts[1]).startsWith("Reyes, A. M.").contains("Bergmann").doesNotContain("Questionnaire");
        assertThat(parts[0]).contains("Chapter 2").doesNotContain("Bergmann");
        assertThat(DraftAnalysisService.referenceEntries(parts[1])).isEqualTo(2);
    }

    @Test
    void uncitedClaimsMustBeTheDraftsOwnWordsAndActuallyUncited() {
        llm.answer(DraftReading.class, u -> new DraftReading(
                "The draft studies the flipped classroom and mathematics achievement of Grade 11 students. It surveys 500 schools.",
                List.of("self-efficacy", "mathematics achievement", "growth mindset"),
                List.of(new Uncited("Most senior high schools in the Philippines now use blended learning.", "A trend claim."),
                        new Uncited("Mathematics achievement among Filipino students remains low in international assessments", "A finding."),
                        new Uncited("The flipped classroom has been linked to higher engagement in mathematics", "Claim."),
                        new Uncited("Studies show that students taught in flipped classes score higher on problem solving", "Claim."),
                        new Uncited("Most Filipino schools have abandoned lectures entirely.", "Invented."))));

        Draft d = service.analyze("thesis.docx", new Extracted(Kind.DOCX, DRAFT, 0, false));

        assertThat(d.needsCitation()).extracting(Draft.Statement::quote).containsExactly(
                "Most senior high schools in the Philippines now use blended learning",
                "Mathematics achievement among Filipino students remains low in international assessments");
        // "500 schools" isn't in the draft: that sentence is dropped from the summary.
        assertThat(d.summary()).contains("Grade 11").doesNotContain("500");
        assertThat(d.concepts()).containsExactly("self-efficacy", "mathematics achievement");
        assertThat(d.referenceEntries()).isEqualTo(2);
        assertThat(d.inTextCitations()).isEqualTo(2);
        assertThat(d.citationCheckText()).contains("(Reyes & Cruz, 2021)").contains("\n\nReferences\nReyes, A. M.")
                .doesNotContain("This study aims").hasSizeLessThanOrEqualTo(DraftAnalysisService.CHECK_CHARS);
        assertThat(llm.userMessages.get(0)).containsPattern("<<<DRAFT_[0-9a-f]{32}>>>").doesNotContain("Bergmann");
        assertThat(llm.systemPrompts.get(0)).contains("UNTRUSTED");
    }

    @Test
    void withoutAReferenceListThereIsNothingToCheck() {
        llm.answer(DraftReading.class, u -> new DraftReading(null, null, null));
        String body = DRAFT.substring(0, DRAFT.indexOf("\nReferences\nReyes"));

        Draft d = service.analyze("notes.txt", new Extracted(Kind.TXT, body, 0, true));

        assertThat(d.citationCheckText()).isNull();
        assertThat(d.limitations()).anySatisfy(l -> assertThat(l).contains("No reference list"))
                .anySatisfy(l -> assertThat(l).contains("Only the start"));
    }
}
