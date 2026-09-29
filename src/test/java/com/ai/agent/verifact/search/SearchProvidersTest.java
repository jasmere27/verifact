package com.ai.agent.verifact.search;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Verifies the exact HTTP contract with each provider, with no network access. */
class SearchProvidersTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    // ---------- Tavily ----------

    @Test
    void tavilySendsDocumentedRequestAndParsesResults() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TavilySearchProvider tavily = new TavilySearchProvider(builder, jsonMapper, "tvly-test", 5);

        server.expect(requestTo(TavilySearchProvider.ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tvly-test"))
                .andExpect(jsonPath("$.query").value("moon landing 1969"))
                .andExpect(jsonPath("$.max_results").value(5))
                .andExpect(jsonPath("$.search_depth").value("basic"))
                .andExpect(jsonPath("$.include_published_date").value(true))
                .andRespond(withSuccess("""
                        {"query":"moon landing 1969","results":[
                          {"title":"Apollo 11","url":"https://nasa.gov/apollo11","content":"First crewed landing.",
                           "score":0.9,"published_date":"2019-07-16"},
                          {"title":"No date","url":"https://example.org/x","content":"Snippet","score":0.5},
                          {"title":"Missing url","content":"dropped"}
                        ]}""", MediaType.APPLICATION_JSON));

        List<SearchResult> results = tavily.search("moon landing 1969");

        server.verify();
        assertThat(results).containsExactly(
                new SearchResult("Apollo 11", "https://nasa.gov/apollo11", "First crewed landing.", "2019-07-16"),
                new SearchResult("No date", "https://example.org/x", "Snippet", null));
    }

    @Test
    void tavilyHttpErrorsBecomeUnavailableWithoutLeakingTheKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TavilySearchProvider tavily = new TavilySearchProvider(builder, jsonMapper, "tvly-secret", 5);
        server.expect(requestTo(TavilySearchProvider.ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"detail\":\"tvly-secret over quota\"}"));

        assertThatThrownBy(() -> tavily.search("q"))
                .isInstanceOf(SearchUnavailableException.class)
                .hasMessageContaining("429")
                .hasMessageNotContaining("tvly-secret");
    }

    @Test
    void tavilyMalformedJsonIsUnavailable() {
        TavilySearchProvider tavily = new TavilySearchProvider(RestClient.builder(), jsonMapper, "k", 5);
        assertThatThrownBy(() -> tavily.parse("<html>oops")).isInstanceOf(SearchUnavailableException.class);
        assertThat(tavily.parse("{}")).isEmpty();
    }

    // ---------- Google (legacy) ----------

    @Test
    void googleSendsKeyAndParsesPublishedTime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleCustomSearchProvider google = new GoogleCustomSearchProvider(builder, jsonMapper, "gkey", "cx1", 20);

        server.expect(requestToUriTemplate(GoogleCustomSearchProvider.ENDPOINT + "?key={k}&cx={c}&num={n}&q={q}",
                        "gkey", "cx1", 10, "a b&c"))
                .andExpect(queryParam("num", "10"))
                .andRespond(withSuccess("""
                        {"items":[{"title":"T","link":"https://bbc.com/a","snippet":"S",
                          "pagemap":{"metatags":[{"article:published_time":"2026-01-02T10:00:00Z"}]}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(google.search("a b&c"))
                .containsExactly(new SearchResult("T", "https://bbc.com/a", "S", "2026-01-02T10:00:00Z"));
        server.verify();
    }

    @Test
    void googleErrorsNeverIncludeTheUrlWithTheKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleCustomSearchProvider google = new GoogleCustomSearchProvider(builder, jsonMapper, "gkey-secret", "cx", 5);
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> google.search("q"))
                .isInstanceOf(SearchUnavailableException.class)
                .hasMessageContaining("403")
                .hasMessageNotContaining("gkey-secret");
    }

    // ---------- Selection ----------

    private SearchProvider select(String provider, String tavilyKey, String googleKey, String cx) {
        return new SearchConfig().searchProvider(RestClient.builder(), jsonMapper, provider, 6, tavilyKey, googleKey, cx);
    }

    @Test
    void autoPrefersTavilyThenGoogleThenNone() {
        assertThat(select("auto", "tvly", "g", "cx").name()).isEqualTo("tavily");
        assertThat(select("auto", "", "g", "cx").name()).isEqualTo("google");
        assertThat(select("auto", "", "g", "").name()).isEqualTo("none");
        assertThat(select("auto", "", "", "").name()).isEqualTo("none");
    }

    @Test
    void explicitChoiceIsHonoured() {
        assertThat(select("google", "tvly", "g", "cx").name()).isEqualTo("google");
        assertThat(select(" TAVILY ", "tvly", "", "").name()).isEqualTo("tavily");
        assertThat(select("tavily", "", "g", "cx").name()).as("no silent fallback").isEqualTo("none");
    }

    @Test
    void unconfiguredSearchFailsAsUnavailable() {
        assertThatThrownBy(() -> select("auto", "", "", "").search("q")).isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void unknownProviderFailsFastAtStartup() {
        assertThatThrownBy(() -> select("bing", "", "", "")).isInstanceOf(IllegalStateException.class);
    }
}
