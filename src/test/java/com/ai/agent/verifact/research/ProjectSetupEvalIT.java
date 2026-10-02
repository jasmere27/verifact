package com.ai.agent.verifact.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check of "Start from your Chapter 1" (ADR-25) with the real model, on a typical Philippine Chapter 1 with no
 * title page and a null hypothesis. Costs well under a cent:
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... ./mvnw test -Dtest=ProjectSetupEvalIT</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class ProjectSetupEvalIT {

    static final String CHAPTER_1 = """
            CHAPTER 1: THE PROBLEM AND ITS BACKGROUND

            Introduction

            Mathematics remains one of the most difficult subjects for Filipino learners. Many senior high school students
            struggle with General Mathematics because lessons are mostly delivered through lectures, leaving little class
            time for practice and feedback. The flipped classroom is an instructional approach in which students study
            lesson content at home through videos or readings, and class time is used for problem solving (Bergmann & Sams, 2012).

            This study aims to determine the effect of the flipped classroom on the General Mathematics achievement of
            Grade 11 students in a public senior high school in Cebu.

            Statement of the Problem

            1. Does the flipped classroom improve the General Mathematics achievement of Grade 11 students compared with lecture-based instruction?
            2. How engaged are Grade 11 students during flipped mathematics lessons?
            3. What challenges do students and teachers encounter when implementing the flipped classroom?

            Hypothesis

            Ho: There is no significant difference in the General Mathematics achievement of students taught through the
            flipped classroom and those taught through lecture-based instruction.
            """;

    @Autowired
    private ProjectSetupService service;

    @Test
    void readsTheQuestionsWordForWordAndSuggestsATitle() {
        long start = System.currentTimeMillis();
        ProjectSetupService.Setup s = service.read("Chapter1_final.docx",
                new DocumentExtractor.Extracted(DocumentExtractor.Kind.TXT, CHAPTER_1, 1, false));
        System.out.println("Setup in " + (System.currentTimeMillis() - start) + " ms: " + s.titleSource() + " | " + s.title() + " | " + s.field());
        s.questions().forEach(q -> System.out.println("  Q: " + q.text() + " | expected: " + q.hypothesis()));

        assertThat(s.titleSource()).isNotEqualTo(ProjectSetupService.TitleSource.DOCUMENT); // no title page, only a chapter heading
        assertThat(s.title()).doesNotContainIgnoringCase("chapter");
        assertThat(s.questions()).hasSize(3);
        assertThat(s.questions().get(0).text()).startsWith("Does the flipped classroom improve").endsWith("?");
        assertThat(s.questions()).allMatch(q -> q.hypothesis() == null); // the only hypothesis is a null hypothesis
    }
}
