package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ProjectSetupService.Setup;
import com.ai.agent.verifact.research.ProjectSetupService.TitleSource;
import com.ai.agent.verifact.research.StudentOutputs.SetupQuestion;
import com.ai.agent.verifact.research.StudentOutputs.SetupReading;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSetupServiceTest {

    static final String PROPOSAL = """
            EFFECT OF THE FLIPPED CLASSROOM ON THE GENERAL MATHEMATICS ACHIEVEMENT OF GRADE 11 STUDENTS
            CHAPTER 1: THE PROBLEM AND ITS BACKGROUND
            Statement of the Problem
            1. Does the flipped classroom improve the General Mathematics achievement of Grade 11 students?
            2. How engaged are Grade 11 students during flipped mathematics lessons?
            Hypotheses
            Ho1: There is no significant difference in achievement between the two groups.
            The researchers expect that students in flipped lessons are more engaged than students in lecture lessons.
            """;

    @Test
    void keepsOnlyTheDocumentsOwnWords() {
        Setup s = ProjectSetupService.check(new SetupReading(
                "Effect of the Flipped Classroom on the General Mathematics Achievement of Grade 11 Students", null, "Education",
                List.of(new SetupQuestion("1. Does the flipped classroom improve the General Mathematics achievement of Grade 11 students?",
                                "There is no significant difference in achievement between the two groups."),
                        new SetupQuestion("How engaged are Grade 11 students during flipped mathematics lessons?",
                                "students in flipped lessons are more engaged than students in lecture lessons"),
                        // Invented by the model: not in the document.
                        new SetupQuestion("What is the effect of gamification on motivation?", null),
                        new SetupQuestion("2. How engaged are Grade 11 students during flipped mathematics lessons?", null))),
                PROPOSAL, "chapter1.docx");

        assertThat(s.titleSource()).isEqualTo(TitleSource.DOCUMENT);
        assertThat(s.title()).isEqualTo("EFFECT OF THE FLIPPED CLASSROOM ON THE GENERAL MATHEMATICS ACHIEVEMENT OF GRADE 11 STUDENTS");
        assertThat(s.questions()).extracting(ResearchProjectStore.QuestionInput::text).containsExactly(
                "Does the flipped classroom improve the General Mathematics achievement of Grade 11 students?",
                "How engaged are Grade 11 students during flipped mathematics lessons?");
        // A null hypothesis isn't an expected answer; a stated expectation is kept verbatim.
        assertThat(s.questions().get(0).hypothesis()).isNull();
        assertThat(s.questions().get(1).hypothesis()).isEqualTo("students in flipped lessons are more engaged than students in lecture lessons.");
        assertThat(s.field()).isEqualTo("Education");
    }

    @Test
    void aChapterHeadingIsNotATitleAndAMadeUpTitleNeedsTheDocumentsTerms() {
        String chapter = "CHAPTER 1\nINTRODUCTION\nTeachers in Cebu use the flipped classroom in General Mathematics for Grade 11 students.";
        Setup heading = ProjectSetupService.check(new SetupReading("Chapter 1: The Problem and Its Background",
                "Flipped classroom in Grade 11 General Mathematics in Cebu", null, List.of()), chapter, "My_Proposal.pdf");
        assertThat(heading.titleSource()).isEqualTo(TitleSource.SUGGESTED);
        assertThat(heading.title()).isEqualTo("Flipped classroom in Grade 11 General Mathematics in Cebu");

        Setup unrelated = ProjectSetupService.check(new SetupReading(null, "Blockchain adoption among rural cooperatives", null, null),
                chapter, "capstone_proposal-final.pdf");
        assertThat(unrelated.titleSource()).isEqualTo(TitleSource.NONE);
        assertThat(unrelated.title()).isEqualTo("capstone proposal final");
        assertThat(unrelated.questions()).isEmpty();
    }
}
