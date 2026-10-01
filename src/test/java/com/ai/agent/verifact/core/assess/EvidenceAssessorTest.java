package com.ai.agent.verifact.core.assess;

import com.ai.agent.verifact.evidence.Evidence;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAssessorTest {

    static final String CLAIM = "Ridership fell 12 percent last year";
    static final List<Evidence> SOURCES = List.of(
            new Evidence("E1", "https://e1.example/a", "e1.example", "Council passes budget",
                    "The council voted 7-2 to approve the $4.2 billion transit budget on Tuesday.", null, null, null),
            new Evidence("E2", "https://e2.example/a", "e2.example", "Ridership report",
                    "Ridership fell 8 percent last year according to the annual report.", null, null, null));

    @Test
    void aBackedVerdictKeepsItsExcerptsAndFlagsAConflict() {
        ClaimFinding f = EvidenceAssessor.assess(CLAIM, new ReviewCandidate("partly supported",
                "E1", "The council voted 7-2 to approve the $4.2 billion transit budget",
                "e2", "Ridership fell 8 percent last year according to the annual report",
                "  The annual report gives   8 percent. "), SOURCES, 300);

        assertThat(f.verdict()).isEqualTo(Verdict.PARTLY_SUPPORTED);
        assertThat(f.supporting().sourceId()).isEqualTo("E1");
        assertThat(f.contradicting().sourceId()).isEqualTo("E2");
        assertThat(f.sourcesConflict()).isTrue();
        assertThat(f.flagsBacked()).isTrue();
        assertThat(f.explanation()).isEqualTo("The annual report gives 8 percent.");
    }

    @Test
    void paraphrasedOrMissingEvidenceLeavesTheClaimUnsettled() {
        ClaimFinding paraphrased = EvidenceAssessor.assess(CLAIM, new ReviewCandidate("CONTRADICTED", "", "",
                "E2", "the report says ridership dropped by eight percent", "Ridership dropped 8 percent."),
                SOURCES, 300);
        ClaimFinding none = EvidenceAssessor.assess(CLAIM, null, SOURCES, 300);

        assertThat(paraphrased.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(paraphrased.flagsBacked()).isFalse();
        assertThat(paraphrased.explanation()).isNull(); // "8" isn't in the claim or any accepted excerpt
        assertThat(none).isEqualTo(new ClaimFinding(Verdict.INSUFFICIENT_EVIDENCE, null, null, false, false, null));
    }

    @Test
    void answersAreIndexedByNormalisedClaimIdFirstOneWins() {
        record Answer(String id, String text) {}
        Map<String, Answer> byId = EvidenceAssessor.byClaimId(Arrays.asList(new Answer(" c1 ", "first"), new Answer("C1", "second"),
                new Answer(null, "no id"), null), Answer::id);

        assertThat(byId).containsOnlyKeys("C1");
        assertThat(byId.get("C1").text()).isEqualTo("first");
        assertThat(EvidenceAssessor.byClaimId(null, Answer::id)).isEmpty();
    }
}
