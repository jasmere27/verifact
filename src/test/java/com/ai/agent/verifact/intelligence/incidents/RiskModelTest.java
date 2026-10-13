package com.ai.agent.verifact.intelligence.incidents;

import com.ai.agent.verifact.evidence.SourceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RiskModelTest {

    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void everyPointIsExplainedAndTheScoreIsBounded() {
        RiskModel.Assessment a = RiskModel.assess(new RiskModel.Inputs("HIGH", SourceType.NEWS, 6, NOW, "HIGH",
                RiskModel.EvidenceState.SUPPORTED), NOW);
        assertThat(a.score()).isEqualTo(100);
        assertThat(a.priority()).isEqualTo(RiskModel.Priority.CRITICAL);
        assertThat(a.factors()).allSatisfy(f -> assertThat(f.reason()).isNotBlank());
        assertThat(a.factors().stream().mapToInt(RiskModel.Factor::points).sum()).isEqualTo(a.score());
        assertThat(a.method()).contains("not a probability");
    }

    @Test
    void contradictedOldSingleSiteClaimsRankLow() {
        RiskModel.Assessment a = RiskModel.assess(new RiskModel.Inputs("LOW", SourceType.OTHER, 1, NOW.minus(Duration.ofDays(30)), "LOW",
                RiskModel.EvidenceState.CONTRADICTED), NOW);
        assertThat(a.priority()).isEqualTo(RiskModel.Priority.LOW);
        assertThat(a.factors()).filteredOn(f -> f.name().equals("Observed spread"))
                .singleElement().satisfies(f -> assertThat(f.points()).isZero());
        RiskModel.Assessment spread = RiskModel.assess(new RiskModel.Inputs("LOW", SourceType.OTHER, 3, NOW, "LOW", null), NOW);
        assertThat(spread.factors()).filteredOn(f -> f.name().equals("Observed spread")).singleElement()
                .satisfies(f -> assertThat(f.reason()).contains("3 different sites").contains("no audience or engagement estimate"));
    }

    @Test
    void similarClaimsLinkAndDifferentOnesDont() {
        assertThat(Similarity.jaccard("Acme Foods is recalling peanut butter over salmonella",
                "Acme Foods recalls its peanut butter because of salmonella")).isGreaterThanOrEqualTo(IncidentService.LINK_SIMILARITY);
        assertThat(Similarity.jaccard("Acme Foods is recalling peanut butter over salmonella",
                "Acme Foods CEO accused of tax fraud")).isLessThan(IncidentService.LINK_SIMILARITY);
    }
}
