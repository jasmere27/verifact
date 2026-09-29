package com.ai.agent.verifact.search;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Custom Search JSON API. Legacy: closed to new customers and discontinued on
 * 2027-01-01. Kept only so existing keys keep working while migrating to Tavily.
 */
public class GoogleCustomSearchProvider implements SearchProvider {

    static final String ENDPOINT = "https://www.googleapis.com/customsearch/v1";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String searchEngineId;
    private final int maxResults;

    public GoogleCustomSearchProvider(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
                                      String apiKey, String searchEngineId, int maxResults) {
        this.restClient = restClientBuilder.build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
        this.searchEngineId = searchEngineId;
        this.maxResults = Math.min(maxResults, 10); // API maximum
    }

    @Override
    public String name() {
        return "google";
    }

    @Override
    public List<SearchResult> search(String query) {
        String url = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("key", apiKey)
                .queryParam("cx", searchEngineId)
                .queryParam("num", maxResults)
                .queryParam("q", query)
                .encode()
                .toUriString();
        String response;
        try {
            response = restClient.get().uri(java.net.URI.create(url)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new SearchUnavailableException("Google search returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            // Never include the exception message: it can contain the request URL, which carries the key.
            throw new SearchUnavailableException("Google search request failed: " + e.getClass().getSimpleName());
        }
        return parse(response);
    }

    List<SearchResult> parse(String response) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(response == null ? "{}" : response);
        } catch (RuntimeException e) {
            throw new SearchUnavailableException("Google search returned malformed JSON");
        }
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String url = item.path("link").asString("");
            if (url.isBlank()) {
                continue;
            }
            JsonNode meta = item.path("pagemap").path("metatags").path(0);
            String date = meta.path("article:published_time").asString("");
            results.add(new SearchResult(
                    item.path("title").asString(""),
                    url,
                    item.path("snippet").asString(""),
                    date.isBlank() ? null : date));
        }
        return results;
    }
}
