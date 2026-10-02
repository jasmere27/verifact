package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.LinkRole;
import com.ai.agent.verifact.research.ResearchProject.LinkStance;
import com.ai.agent.verifact.research.ResearchProject.LinkSuggestion;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import com.ai.agent.verifact.research.StudentOutputs.LinkProposal;
import com.ai.agent.verifact.research.StudentOutputs.LinkReview;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionLinkServiceTest {

    static final Map<String, String> ABSTRACTS = Map.of(
            "10.1/a", "This quasi-experimental study found that the flipped classroom improved mathematics achievement of Grade 10 students in Cebu.",
            "10.1/b", "Flipped instruction in Indonesian junior high schools showed no significant difference in mathematics achievement scores.",
            "10.1/c", "We validated a 20-item questionnaire measuring student engagement in blended mathematics courses with 412 respondents.");

    private final ResearchDiscoveryServiceTest.FakeIndex index = new ResearchDiscoveryServiceTest.FakeIndex() {
        @Override
        public Optional<DiscoveredWork> work(String key) {
            String abs = ABSTRACTS.get(key);
            return abs == null ? Optional.empty() : Optional.of(ResearchDiscoveryServiceTest.work(key, "Study " + key, abs, "PH"));
        }
    };
    private final ScriptedLlm llm = new ScriptedLlm();
    private final QuestionLinkService service = new QuestionLinkService(llm, index);

    static final Question RQ1 = new Question("q1", "Does the flipped classroom improve students' mathematics achievement?",
            "The flipped classroom improves mathematics achievement.");
    static final Question RQ2 = new Question("q2", "How engaged are students in flipped mathematics lessons?");

    static LibraryItem item(String key, String... questionIds) {
        DiscoveredWork w = ResearchDiscoveryServiceTest.work(key, "Study " + key, ABSTRACTS.get(key), "PH");
        return new LibraryItem(key, Folder.RRS, ResearchDiscoveryService.toSource(w, null, null, null, "PH"), ReadingStatus.TO_READ,
                null, null, null, List.of(questionIds), Instant.EPOCH);
    }

    static ProjectData project(List<String> dismissed, LibraryItem... items) {
        return new ProjectData("Flipped classroom and mathematics achievement", "Education", "PH", List.of(RQ1, RQ2), List.of(items),
                List.of(), null, null, null, List.of(), dismissed);
    }

    @Test
    void suggestionsNeedAVerbatimQuoteAGroundedSentenceAndANewPair() {
        llm.answer(LinkReview.class, u -> new LinkReview(List.of(
                new LinkProposal("S1", "Q1", "FINDING", "SUPPORTS", "Found higher mathematics achievement after flipped lessons in Cebu.",
                        "the flipped classroom improved mathematics achievement of Grade 10 students in Cebu"),
                new LinkProposal("S2", "q1", "finding", "CONTRADICTS", "Found no significant difference in mathematics achievement.",
                        "showed no significant difference in mathematics achievement scores"),
                // Paraphrased quote → dropped.
                new LinkProposal("S2", "Q2", "BACKGROUND", null, "Describes flipped instruction.", "flipped teaching made no difference to grades at all"),
                // Number not in the quote, title or question → dropped.
                new LinkProposal("S3", "Q2", "METHOD", "SUPPORTS", "A 35-item engagement questionnaire you could adapt.",
                        "We validated a 20-item questionnaire measuring student engagement in blended mathematics courses"),
                new LinkProposal("S3", "Q2", "METHOD", "SUPPORTS", "A validated engagement questionnaire you could adapt.",
                        "We validated a 20-item questionnaire measuring student engagement in blended mathematics courses"),
                new LinkProposal("S9", "Q1", "FINDING", null, "Unknown study.", "the flipped classroom improved mathematics achievement of Grade 10"),
                new LinkProposal("S1", "Q7", "FINDING", null, "Unknown question.", "the flipped classroom improved mathematics achievement of Grade 10"))));

        List<String> limitations = new ArrayList<>();
        List<LinkSuggestion> out = service.suggest(project(List.of(), item("10.1/a"), item("10.1/b"), item("10.1/c")), limitations);

        assertThat(out).extracting(LinkSuggestion::key, LinkSuggestion::questionId, LinkSuggestion::role, LinkSuggestion::stance)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("10.1/a", "q1", LinkRole.FINDING, LinkStance.SUPPORTS),
                        org.assertj.core.groups.Tuple.tuple("10.1/b", "q1", LinkRole.FINDING, LinkStance.CONTRADICTS),
                        // No expected answer for RQ2, and not a finding: no stance.
                        org.assertj.core.groups.Tuple.tuple("10.1/c", "q2", LinkRole.METHOD, null));
        assertThat(out.get(0).quote()).isEqualTo("the flipped classroom improved mathematics achievement of Grade 10 students in Cebu");
        assertThat(limitations).isEmpty();
        // The questions and expected answers are inside the nonce-delimited data block.
        assertThat(llm.userMessages.get(0)).contains("Q1 | Does the flipped classroom", "expected answer: The flipped classroom improves");
    }

    @Test
    void linkedAndRejectedPairsAreNotSuggestedAgain() {
        llm.answer(LinkReview.class, u -> new LinkReview(List.of(
                new LinkProposal("S1", "Q1", "FINDING", "SUPPORTS", "Found higher mathematics achievement in Cebu.",
                        "the flipped classroom improved mathematics achievement of Grade 10 students in Cebu"),
                new LinkProposal("S2", "Q1", "FINDING", "CONTRADICTS", "Found no significant difference in mathematics achievement.",
                        "showed no significant difference in mathematics achievement scores"))));

        // 10.1/a is already linked to q1; 10.1/b ↔ q1 was rejected before.
        List<LinkSuggestion> out = service.suggest(project(List.of(ProjectData.pair("10.1/b", "q1")), item("10.1/a", "q1"), item("10.1/b")),
                new ArrayList<>());

        assertThat(out).isEmpty();
    }

    @Test
    void refusesWithoutQuestionsOrAnythingNewToRead() {
        ProjectData noQuestions = new ProjectData("Topic", null, null, List.of(), List.of(item("10.1/a")), List.of(), null, null, null);
        assertThatThrownBy(() -> service.suggest(noQuestions, new ArrayList<>()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        ProjectData allLinked = project(List.of(), item("10.1/a", "q1", "q2"));
        assertThatThrownBy(() -> service.suggest(allLinked, new ArrayList<>()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("nothing new"));
        assertThat(llm.userMessages).isEmpty();
    }
}
