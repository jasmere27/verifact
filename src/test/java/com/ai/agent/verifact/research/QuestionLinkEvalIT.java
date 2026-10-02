package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.LinkStance;
import com.ai.agent.verifact.research.ResearchProject.LinkSuggestion;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Live check of source ↔ question link suggestions (ADR-24) with the real model; abstracts come from a fake index so
 * the right answers are known. Costs well under a cent:
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... ./mvnw test -Dtest=QuestionLinkEvalIT</pre>
 * Checks that real quotes survive the verbatim checks, an off-topic source is never linked, and stances follow the
 * findings.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class QuestionLinkEvalIT {

    static final Map<String, String> ABSTRACTS = Map.of(
            "10.9/pro", "This quasi-experimental study involved 120 Grade 11 students in two public senior high schools in Cebu. "
                    + "Students taught mathematics through a flipped classroom model obtained significantly higher posttest scores in "
                    + "general mathematics than those taught through lecture. The results suggest that flipped instruction improves "
                    + "mathematics achievement in senior high school.",
            "10.9/con", "We compared flipped and traditional instruction in eight junior high school mathematics classes in Indonesia. "
                    + "After one semester, there was no significant difference in mathematics achievement between the two groups, "
                    + "although flipped classes reported more time spent on problem solving in class.",
            "10.9/method", "This paper reports the development and validation of the Mathematics Engagement Scale, a 24-item "
                    + "questionnaire measuring behavioral, emotional and cognitive engagement of secondary students in mathematics "
                    + "lessons. Confirmatory factor analysis with 650 students supported the three-factor structure.",
            "10.9/off", "Using survey data from 900 adults in Metro Manila, this study examines the adoption of mobile banking "
                    + "applications. Perceived usefulness and trust predicted intention to use mobile banking.");

    @Autowired
    private QuestionLinkService service;
    @MockitoBean
    private ScholarlyIndex index;

    @Test
    void suggestsGroundedLinksAndSkipsTheOffTopicSource() {
        when(index.work(anyString())).thenReturn(Optional.empty());
        ABSTRACTS.forEach((k, a) -> when(index.work(k)).thenReturn(Optional.of(ResearchDiscoveryServiceTest.work(k, title(k), a, "PH"))));
        List<LibraryItem> library = ABSTRACTS.keySet().stream().sorted().map(k -> new LibraryItem(k, Folder.RRS,
                ResearchDiscoveryService.toSource(ResearchDiscoveryServiceTest.work(k, title(k), ABSTRACTS.get(k), "PH"), null, null, null, "PH"),
                ReadingStatus.TO_READ, null, null, null, List.of(), Instant.EPOCH)).toList();
        ProjectData p = new ProjectData("Flipped classroom and Grade 11 mathematics achievement", "Education", "PH",
                List.of(new Question("q1", "Does the flipped classroom improve Grade 11 students' mathematics achievement?",
                                "The flipped classroom improves mathematics achievement."),
                        new Question("q2", "How engaged are Grade 11 students during flipped mathematics lessons?")),
                library, List.of(), null, null, null);

        long start = System.currentTimeMillis();
        List<String> limitations = new ArrayList<>();
        List<LinkSuggestion> out = service.suggest(p, limitations);
        System.out.println("Link suggestions in " + (System.currentTimeMillis() - start) + " ms, limitations " + limitations);
        out.forEach(s -> System.out.println("  " + s.key() + " -> " + s.questionId() + " " + s.role() + " " + s.stance() + " | " + s.how()
                + " | \"" + s.quote() + "\""));

        assertThat(out).noneMatch(s -> s.key().equals("10.9/off"));
        assertThat(out).anyMatch(s -> s.key().equals("10.9/pro") && s.questionId().equals("q1") && s.stance() == LinkStance.SUPPORTS);
        assertThat(out).noneMatch(s -> s.key().equals("10.9/con") && s.stance() == LinkStance.SUPPORTS);
        assertThat(out).anyMatch(s -> s.key().equals("10.9/method") && s.questionId().equals("q2"));
    }

    private static String title(String key) {
        return switch (key) {
            case "10.9/pro" -> "Flipped classroom and general mathematics achievement of Grade 11 students in Cebu";
            case "10.9/con" -> "Flipped versus traditional instruction in Indonesian junior high mathematics";
            case "10.9/method" -> "Development and validation of the Mathematics Engagement Scale";
            default -> "Mobile banking adoption in Metro Manila";
        };
    }
}
