package com.ai.agent.verifact.intelligence.investigations;

import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.SourceType;
import com.ai.agent.verifact.intelligence.incidents.RiskModel;
import com.ai.agent.verifact.intelligence.responses.ResponseStudioServiceAccess;
import com.ai.agent.verifact.news.NewsCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping evidence checks to nuanced assessments, evidence directions, chronology and version changes. */
class InvestigationMapperTest {

    static NewsCheck.NewsClaim claim(Verdict v, NewsCheck.ContextIssue context, boolean conflict, SourceExcerpt sup, SourceExcerpt con) {
        return new NewsCheck.NewsClaim("C1", NewsCheck.ClaimType.FACT, "words", "Acme recalled bars in 2026.", null, null, v, sup, con,
                context, NewsCheck.QuoteStatus.NOT_A_QUOTE, null, conflict, null);
    }

    static NewsCheck check(List<NewsCheck.NewsClaim> claims, List<Evidence> sources) {
        return new NewsCheck(UUID.randomUUID(), Instant.parse("2026-10-14T00:00:00Z"), null, null, null, claims, sources, Map.of(), List.of(),
                "", "fake", 1, null);
    }

    static Evidence source(String id, String date) {
        return new Evidence(id, "https://s" + id + ".example/a", "s" + id + ".example", "Title " + id, "Snippet " + id, date, Instant.now(), SourceType.NEWS);
    }

    @Test
    void verdictsBecomeNuancedAssessments() {
        assertThat(InvestigationMapper.assessment(claim(Verdict.SUPPORTED, NewsCheck.ContextIssue.NONE, false, null, null))).isEqualTo(Investigation.Assessment.SUPPORTED);
        assertThat(InvestigationMapper.assessment(claim(Verdict.SUPPORTED, NewsCheck.ContextIssue.OUTDATED, false, null, null))).isEqualTo(Investigation.Assessment.MISSING_CONTEXT);
        assertThat(InvestigationMapper.assessment(claim(Verdict.PARTLY_SUPPORTED, NewsCheck.ContextIssue.NONE, false, null, null))).isEqualTo(Investigation.Assessment.MIXED);
        assertThat(InvestigationMapper.assessment(claim(Verdict.SUPPORTED, NewsCheck.ContextIssue.NONE, true, null, null))).isEqualTo(Investigation.Assessment.MIXED);
        assertThat(InvestigationMapper.assessment(claim(Verdict.MISLEADING, NewsCheck.ContextIssue.NONE, false, null, null))).isEqualTo(Investigation.Assessment.MISSING_CONTEXT);
        assertThat(InvestigationMapper.assessment(claim(Verdict.INSUFFICIENT_EVIDENCE, NewsCheck.ContextIssue.NONE, false, null, null))).isEqualTo(Investigation.Assessment.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void sourcesGetDirectionsUnresolvedClaimsAreListedAndDatesOrdered() {
        Evidence e1 = source("E1", "2026-10-10");
        Evidence e2 = source("E2", "Mon, 05 Oct 2026 10:00:00 GMT");
        Evidence e3 = source("E3", null);
        Investigation.Result r = InvestigationMapper.map(check(List.of(
                claim(Verdict.SUPPORTED, NewsCheck.ContextIssue.NONE, true, new SourceExcerpt("E1", "a"), new SourceExcerpt("E2", "b"))),
                List.of(e1, e2, e3)), 1);
        assertThat(r.sources()).extracting(Investigation.Source::direction).containsExactly("SUPPORTS", "CONTRADICTS", "CONTEXT");
        assertThat(r.claims().getFirst().supporting().url()).isEqualTo(e1.url());
        assertThat(r.unresolved()).singleElement().asString().contains("disagree");
        assertThat(r.chronology()).extracting(Investigation.TimelineEntry::date).containsExactly("2026-10-05", "2026-10-10", "2026-10-14");
        assertThat(InvestigationMapper.evidenceState(r)).isEqualTo(RiskModel.EvidenceState.UNRESOLVED);
    }

    @Test
    void aNewVersionRecordsWhatChanged() {
        Investigation.Result v1 = InvestigationMapper.map(check(List.of(claim(Verdict.CONTRADICTED, NewsCheck.ContextIssue.NONE, false, null,
                new SourceExcerpt("E1", "x"))), List.of(source("E1", null))), 1);
        Investigation.Result v2 = InvestigationMapper.map(check(List.of(claim(Verdict.SUPPORTED, NewsCheck.ContextIssue.NONE, false,
                new SourceExcerpt("E2", "y"), null)), List.of(source("E2", null))), 2);
        Investigation.Changes c = InvestigationMapper.diff(v1, v2);
        assertThat(c.claims()).singleElement().satisfies(x -> {
            assertThat(x.before()).isEqualTo("CONTRADICTED");
            assertThat(x.after()).isEqualTo("SUPPORTED");
        });
        assertThat(c.newSources()).containsExactly("https://sE2.example/a");
        assertThat(c.droppedSources()).containsExactly("https://sE1.example/a");
        assertThat(c.summary()).startsWith("1 assessment changed");
        assertThat(InvestigationMapper.diff(null, v2)).isNull();
        assertThat(ResponseStudioServiceAccess.warning(v2)).contains("supports the main claim");
    }
}
