package com.ai.agent.verifact.research;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Crossref + OpenAlex response handling against recorded-shape responses (no network). */
class CrossrefOpenAlexIndexTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final String CROSSREF_WORK = """
            {"message":{"DOI":"10.1016/S0140-6736(97)11096-0","title":["Ileal-lymphoid-nodular hyperplasia, non-specific colitis, and pervasive developmental disorder in children"],
             "author":[{"given":"AJ","family":"Wakefield"},{"given":"SH","family":"Murch"}],
             "issued":{"date-parts":[[1998,2]]},"container-title":["The Lancet"],"publisher":"Elsevier BV",
             "is-referenced-by-count":3000,"updated-by":[{"type":"retraction","source":"publisher"}]}}""";

    private static final String OPENALEX_WORK = """
            {"is_retracted":true,"cited_by_count":3100,"primary_location":{"source":{"display_name":"The Lancet"}},
             "abstract_inverted_index":{"We":[0],"investigated":[1],"a":[2],"consecutive":[3],"series":[4]}}""";

    @Test
    void doiLookupCombinesCrossrefMetadataWithOpenAlexRetractionAndAbstract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CrossrefOpenAlexIndex index = new CrossrefOpenAlexIndex(builder, jsonMapper, "ops@example.org", "");
        server.expect(requestTo("https://api.crossref.org/works/10.1016/S0140-6736%2897%2911096-0?mailto=ops%40example.org"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CROSSREF_WORK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openalex.org/works/doi:10.1016/s0140-6736")))
                .andRespond(withSuccess(OPENALEX_WORK, MediaType.APPLICATION_JSON));

        ScholarlyWork w = index.byDoi("10.1016/S0140-6736(97)11096-0").orElseThrow();

        server.verify();
        assertThat(w.doi()).isEqualTo("10.1016/s0140-6736(97)11096-0");
        assertThat(w.authors()).containsExactly("AJ Wakefield", "SH Murch");
        assertThat(w.year()).isEqualTo(1998);
        assertThat(w.venue()).isEqualTo("The Lancet");
        assertThat(w.retracted()).isTrue();
        assertThat(w.notices()).containsExactly("retraction");
        assertThat(w.abstractText()).isEqualTo("We investigated a consecutive series");
        assertThat(w.citedByCount()).isEqualTo(3100); // OpenAlex count preferred (broader coverage)
    }

    @Test
    void anUnregisteredDoiIsNotFoundButAnOutageIsAnError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CrossrefOpenAlexIndex index = new CrossrefOpenAlexIndex(builder, jsonMapper, "", "");
        server.expect(requestTo("https://api.crossref.org/works/10.9999/fake.123")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openalex.org/works/doi:10.9999/fake.123")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("https://api.crossref.org/works/10.1000/xyz")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(index.byDoi("10.9999/fake.123")).isEmpty();
        assertThatThrownBy(() -> index.byDoi("10.1000/xyz")).isInstanceOf(ScholarlyIndex.ScholarlyIndexUnavailableException.class);
    }

    @Test
    void referenceMatchingAndRelatedSearchUseTheDocumentedParameters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CrossrefOpenAlexIndex index = new CrossrefOpenAlexIndex(builder, jsonMapper, "", "oa-key");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.crossref.org/works?")))
                .andExpect(queryParam("query.bibliographic", "Smith%20J%202020%20Sleep%20and%20memory"))
                .andExpect(queryParam("rows", "3"))
                .andRespond(withSuccess("""
                        {"message":{"items":[{"DOI":"10.1/abc","title":["Sleep and memory"],"author":[{"family":"Smith","given":"J"}],
                         "issued":{"date-parts":[[2020]]}}]}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openalex.org/works?")))
                .andExpect(queryParam("search", "sleep%20memory"))
                .andExpect(queryParam("filter", "has_abstract:true"))
                .andExpect(queryParam("api_key", "oa-key"))
                .andRespond(withSuccess("""
                        {"results":[{"doi":"https://doi.org/10.2/def","display_name":"Sleep loss and recall","publication_year":2021,
                          "authorships":[{"author":{"display_name":"Ana Lee"}}],"is_retracted":false,
                          "abstract_inverted_index":{"Sleep":[0],"loss":[1],"impaired":[2],"recall":[3]}}]}""", MediaType.APPLICATION_JSON));

        List<ScholarlyWork> candidates = index.byReference("Smith J 2020 Sleep and memory", 3);
        List<ScholarlyWork> related = index.related("sleep memory", 3);

        server.verify();
        assertThat(candidates).singleElement().satisfies(w -> assertThat(w.doi()).isEqualTo("10.1/abc"));
        assertThat(related).singleElement().satisfies(w -> {
            assertThat(w.doi()).isEqualTo("10.2/def");
            assertThat(w.abstractText()).isEqualTo("Sleep loss impaired recall");
        });
    }

    @Test
    void doisKeepTheirSlashButEncodeUnsafeCharacters() {
        assertThat(CrossrefOpenAlexIndex.encodeDoi("10.1016/S0140-6736(97)11096-0")).isEqualTo("10.1016/S0140-6736%2897%2911096-0");
    }

    @Test
    void doisOutsideCrossrefAreFoundThroughOpenAlex() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CrossrefOpenAlexIndex index = new CrossrefOpenAlexIndex(builder, jsonMapper, "", "");
        server.expect(requestTo("https://api.crossref.org/works/10.48550/arXiv.1706.03762")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openalex.org/works/doi:10.48550/arxiv.1706.03762")))
                .andRespond(withSuccess("""
                        {"doi":"https://doi.org/10.48550/arxiv.1706.03762","display_name":"Attention Is All You Need","publication_year":2017,
                         "authorships":[{"author":{"display_name":"Ashish Vaswani"}}],"is_retracted":false,
                         "abstract_inverted_index":{"The":[0],"dominant":[1]}}""", MediaType.APPLICATION_JSON));

        ScholarlyWork w = index.byDoi("10.48550/arXiv.1706.03762").orElseThrow();

        assertThat(w.title()).isEqualTo("Attention Is All You Need");
        assertThat(w.abstractText()).isEqualTo("The dominant");
    }
}
