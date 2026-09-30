package com.ai.agent.verifact.evidence;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRetrieverTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    /** Ignores the domain restriction, like a provider that can't restrict. */
    private final List<List<String>> domainsSeen = new ArrayList<>();
    private List<SearchResult> answer = List.of();

    private final SearchProvider provider = new SearchProvider() {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public List<SearchResult> search(String query) {
            return answer;
        }

        @Override
        public List<SearchResult> search(String query, List<String> includeDomains) {
            domainsSeen.add(includeDomains);
            return answer;
        }
    };

    private static SearchResult hit(String url) {
        return new SearchResult("T", url, "S", null);
    }

    @Test
    void anAllowListIsPassedToTheProviderAndEnforcedOnResults() {
        answer = List.of(hit("https://www.dol.gov/agencies/whd/overtime"), hit("https://lawblog.example/overtime"),
                hit("https://ecfr.gov/current/title-29/part-541"), hit("https://evil-dol.gov.example/x"));

        List<Evidence> evidence = new EvidenceRetriever(provider).retrieve(List.of(List.of("overtime")),
                new EvidenceRetriever.Options(2, 10, null, List.of("dol.gov", "ecfr.gov")), NOW);

        assertThat(domainsSeen).containsExactly(List.of("dol.gov", "ecfr.gov"));
        assertThat(evidence).extracting(Evidence::domain).containsExactly("dol.gov", "ecfr.gov");
        assertThat(evidence).extracting(Evidence::id).containsExactly("E1", "E2");
    }

    @Test
    void withoutAnAllowListEverySiteCounts() {
        answer = List.of(hit("https://www.dol.gov/x"), hit("https://lawblog.example/y"));

        List<Evidence> evidence = new EvidenceRetriever(provider).retrieve(List.of(List.of("q")),
                new EvidenceRetriever.Options(2, 10, null, List.of()), NOW);

        assertThat(evidence).hasSize(2);
    }

    @Test
    void refusesWhenEverySearchFails() {
        SearchProvider down = new SearchProvider() {
            @Override
            public String name() {
                return "down";
            }

            @Override
            public List<SearchResult> search(String query) {
                throw new SearchUnavailableException("HTTP 500");
            }
        };

        assertThatThrownBy(() -> new EvidenceRetriever(down).retrieve(List.of(List.of("q")),
                new EvidenceRetriever.Options(2, 10, null, List.of()), NOW))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
