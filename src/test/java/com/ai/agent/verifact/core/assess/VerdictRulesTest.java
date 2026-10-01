package com.ai.agent.verifact.core.assess;

import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictRulesTest {

    static final SourceExcerpt FOR = new SourceExcerpt("E1", "Officials said ridership fell 12 percent last year");
    static final SourceExcerpt AGAINST = new SourceExcerpt("E2", "Ridership fell 8 percent last year");

    @Test
    void aVerdictStandsOnlyWithTheExcerptItNeeds() {
        assertThat(VerdictRules.gate(Verdict.SUPPORTED, FOR, null)).isEqualTo(Verdict.SUPPORTED);
        assertThat(VerdictRules.gate(Verdict.SUPPORTED, null, AGAINST)).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(VerdictRules.gate(Verdict.PARTLY_SUPPORTED, null, null)).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(VerdictRules.gate(Verdict.CONTRADICTED, null, AGAINST)).isEqualTo(Verdict.CONTRADICTED);
        assertThat(VerdictRules.gate(Verdict.CONTRADICTED, FOR, null)).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(VerdictRules.gate(Verdict.MISLEADING, null, AGAINST)).isEqualTo(Verdict.MISLEADING);
        assertThat(VerdictRules.gate(Verdict.MISLEADING, null, null)).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(VerdictRules.gate(Verdict.INSUFFICIENT_EVIDENCE, FOR, AGAINST)).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void flagsNeedAnExcerptAndConflictsNeedTwoDifferentSources() {
        assertThat(VerdictRules.flagBacked(null, null)).isFalse();
        assertThat(VerdictRules.flagBacked(null, AGAINST)).isTrue();
        assertThat(VerdictRules.sourcesConflict(FOR, AGAINST)).isTrue();
        assertThat(VerdictRules.sourcesConflict(FOR, new SourceExcerpt("E1", "other words from the same source"))).isFalse();
        assertThat(VerdictRules.sourcesConflict(FOR, null)).isFalse();
    }

    @Test
    void explanationsMustOnlyStateWhatTheClaimOrExcerptsSay() {
        assertThat(VerdictRules.groundedExplanation("The annual report gives 8 percent.", "Ridership fell 12 percent", FOR, AGAINST))
                .isEqualTo("The annual report gives 8 percent.");
        // A number nobody cited is not allowed into the explanation.
        assertThat(VerdictRules.groundedExplanation("Ridership actually fell 30 percent.", "Ridership fell 12 percent", FOR, AGAINST)).isNull();
        assertThat(VerdictRules.groundedExplanation(" ", "claim", FOR, null)).isNull();
        assertThat(VerdictRules.groundedExplanation(null, "claim", FOR, null)).isNull();
    }
}
