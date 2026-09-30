package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.research.Discovery.Verification;
import com.ai.agent.verifact.research.Insights.GapKind;
import com.ai.agent.verifact.research.Insights.RelationKind;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import com.ai.agent.verifact.research.ResearchWorkspace.SavedSource;
import com.ai.agent.verifact.research.StudentOutputs.GapNote;
import com.ai.agent.verifact.research.StudentOutputs.InsightDraft;
import com.ai.agent.verifact.research.StudentOutputs.RelationNote;
import com.ai.agent.verifact.research.StudentOutputs.RelationReview;
import com.ai.agent.verifact.research.StudentOutputs.VariableNote;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchInsightsServiceTest {

    static final String TOPIC = "Effect of flipped classroom on Grade 11 students' mathematics achievement";
    static final Map<String, String> ABSTRACTS = Map.of(
            "10.1/a", "This quasi-experimental study found that the flipped classroom improved mathematics achievement of Grade 10 students in Cebu.",
            "10.1/b", "A survey of 300 university students in Japan linked flipped learning to higher self-efficacy in statistics courses.",
            "10.1/c", "Flipped instruction in Indonesian junior high schools showed no significant difference in mathematics achievement.");

    private final ResearchDiscoveryServiceTest.FakeIndex index = new ResearchDiscoveryServiceTest.FakeIndex() {
        @Override
        public Optional<DiscoveredWork> work(String key) {
            String abs = ABSTRACTS.get(key);
            return abs == null ? Optional.empty() : Optional.of(ResearchDiscoveryServiceTest.work(key, "Study " + key, abs, "PH"));
        }
    };
    private final ScriptedLlm llm = new ScriptedLlm();
    private final ResearchInsightsService service = new ResearchInsightsService(llm, index,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

    static ResearchWorkspace workspace(String... keys) {
        List<SavedSource> saved = new ArrayList<>();
        for (String k : keys) {
            DiscoveredWork w = ResearchDiscoveryServiceTest.work(k, "Study " + k, ABSTRACTS.get(k), k.endsWith("a") ? "PH" : "JP");
            saved.add(new SavedSource(k, Folder.RRS, ResearchDiscoveryService.toSource(w, null, null, null, "PH"), null, Instant.EPOCH));
        }
        return new ResearchWorkspace(UUID.randomUUID(), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, TOPIC, "Education", "PH",
                saved, null, null, null);
    }

    @Test
    void everyInsightIsTiedToSavedSourcesAndCheckedAgainstTheirAbstracts() {
        llm.answer(InsightDraft.class, u -> new InsightDraft(
                List.of(new GapNote("None of the saved studies involve Grade 11 students; the closest is Grade 10 in Cebu.", "population", List.of("S1")),
                        new GapNote("No study covers public schools.", "SETTING", List.of()),
                        new GapNote("Only 1 of 45 studies was local.", "SETTING", List.of("S1", "S2")),
                        new GapNote("Studies in the saved set rarely examine self-efficacy with mathematics.", "VARIABLE", List.of("S9", "S2"))),
                List.of(new VariableNote("Flipped classroom", "INDEPENDENT"), new VariableNote("Mathematics achievement", "DEPENDENT"),
                        new VariableNote("Parental involvement", "MODERATOR"))));
        llm.answer(RelationReview.class, u -> new RelationReview(List.of(
                new RelationNote("S1", "SAME_FOCUS", "Same intervention and outcome with Grade 10 students in Cebu.",
                        "the flipped classroom improved mathematics achievement of Grade 10 students in Cebu"),
                new RelationNote("S2", "DIFFERENT_SETTING", "University students in Japan.", "flipped learning proves self-efficacy doubles"),
                new RelationNote("S3", "CONTRADICTS", "Found no significant difference in mathematics achievement.",
                        "showed no significant difference in mathematics achievement"))));

        Insights in = service.generate(workspace("10.1/a", "10.1/b", "10.1/c"));

        assertThat(in.coverage().total()).isEqualTo(3);
        assertThat(in.coverage().local()).isEqualTo(1);
        assertThat(in.coverage().foreign()).isEqualTo(2);
        // No basis → dropped; "45" isn't in any abstract or the coverage → dropped; unknown ids are ignored.
        assertThat(in.gaps()).extracting(Insights.Gap::kind).containsExactly(GapKind.POPULATION, GapKind.VARIABLE);
        assertThat(in.gaps().get(0).basis()).containsExactly("10.1/a");
        assertThat(in.gaps().get(1).basis()).containsExactly("10.1/b");
        // A made-up quote drops the relation.
        assertThat(in.relations()).extracting(Insights.Relation::key, Insights.Relation::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("10.1/a", RelationKind.SAME_FOCUS),
                org.assertj.core.groups.Tuple.tuple("10.1/c", RelationKind.CONTRADICTS));
        assertThat(in.framework()).extracting(Insights.Variable::name, Insights.Variable::verification).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Flipped classroom", Verification.VERIFIED),
                org.assertj.core.groups.Tuple.tuple("Mathematics achievement", Verification.VERIFIED),
                org.assertj.core.groups.Tuple.tuple("Parental involvement", Verification.UNVERIFIED));
        assertThat(in.framework().get(1).sourceKeys()).containsExactly("10.1/a", "10.1/c");
        assertThat(in.notice()).contains("not established facts");
        assertThat(llm.userMessages).allSatisfy(m -> assertThat(m).containsPattern("<<<DATA_[0-9a-f]{32}>>>"));
    }

    @Test
    void longStatementsAreShortenedAtAWordBoundary() {
        assertThat(ResearchInsightsService.shorten("one two three four five six", 15)).isEqualTo("one two three…");
        assertThat(ResearchInsightsService.shorten("short", 15)).isEqualTo("short");
    }

    @Test
    void needsAtLeastThreeSourcesWithAbstracts() {
        assertThatThrownBy(() -> service.generate(workspace("10.1/a", "10.1/b")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aFailedGapPassStillReturnsCheckedRelations() {
        llm.answer(InsightDraft.class, u -> {
            throw new IllegalStateException("down");
        });
        llm.answer(RelationReview.class, u -> new RelationReview(List.of(new RelationNote("S1", "SAME_FOCUS",
                "Same intervention and outcome.", "the flipped classroom improved mathematics achievement of Grade 10 students"))));

        Insights in = service.generate(workspace("10.1/a", "10.1/b", "10.1/c"));

        assertThat(in.gaps()).isEmpty();
        assertThat(in.relations()).hasSize(1);
        assertThat(in.limitations()).anySatisfy(l -> assertThat(l).contains("couldn't be generated"));
    }
}
