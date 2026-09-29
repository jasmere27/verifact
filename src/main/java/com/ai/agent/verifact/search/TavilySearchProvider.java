package com.ai.agent.verifact.search;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tavily Search API (https://docs.tavily.com/documentation/api-reference/endpoint/search).
 * Chosen to replace Google Custom Search (discontinued 2027-01-01); see ADR-5.
 * A "basic" search costs 1 credit.
 */
public class TavilySearchProvider implements SearchProvider {

    static final String ENDPOINT = "https://api.tavily.com/search";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final int maxResults;

    public TavilySearchProvider(RestClient.Builder restClientBuilder, JsonMapper jsonMapper, String apiKey, int maxResults) {
        this.restClient = restClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public List<SearchResult> search(String query) {
        Map<String, Object> body = Map.of(
                "query", query,
                "search_depth", "basic",
                "topic", "general",
                "max_results", maxResults,
                "include_published_date", true);

        String response;
        try {
            response = restClient.post()
                    .uri(ENDPOINT)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new SearchUnavailableException("Tavily returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new SearchUnavailableException("Tavily request failed: " + e.getClass().getSimpleName());
        }
        return parse(response);
    }

    List<SearchResult> parse(String response) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(response == null ? "{}" : response);
        } catch (RuntimeException e) {
            throw new SearchUnavailableException("Tavily returned malformed JSON");
        }
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String url = item.path("url").asString("");
            if (url.isBlank()) {
                continue;
            }
            String date = item.path("published_date").asString("");
            results.add(new SearchResult(
                    item.path("title").asString(""),
                    url,
                    item.path("content").asString(""),
                    date.isBlank() ? null : date));
        }
        return results;
    }
}
