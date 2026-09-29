package com.ai.agent.verifact.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GoogleSearchTool {

    private static final Logger log = LoggerFactory.getLogger(GoogleSearchTool.class);
    private static final String UNAVAILABLE = "Web Search is not available at the moment.";

    private final String apiKey;
    private final String searchEngineId;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GoogleSearchTool(RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            @Value("${google.api.key}") String apiKey,
                            @Value("${google.cse.id}") String searchEngineId) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.searchEngineId = searchEngineId;
    }

    @Tool(description = "Search the web. Returns results as 'title | url | snippet' lines. "
            + "Only these URLs may be cited as sources.")
    public String searchWeb(String query) {
        log.debug("Web search requested ({} chars)", query == null ? 0 : query.length());

        final String url = UriComponentsBuilder.fromUriString("https://www.googleapis.com/customsearch/v1")
                .queryParam("key", apiKey)
                .queryParam("cx", searchEngineId)
                .queryParam("q", query)
                .build()
                .toUriString();

        try {
            final String response = restTemplate.getForObject(url, String.class);
            return formatResults(objectMapper.readTree(response));
        } catch (RestClientResponseException e) {
            // Never log the exception message or URL: the request URL carries the API key.
            log.warn("Web search failed with HTTP {}", e.getStatusCode().value());
            return UNAVAILABLE;
        } catch (Exception e) {
            log.warn("Web search failed: {}", e.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    String formatResults(JsonNode root) {
        final StringBuilder results = new StringBuilder();
        for (final JsonNode item : root.path("items")) {
            results.append("- ")
                    .append(clean(item.path("title").asText()))
                    .append(" | ")
                    .append(item.path("link").asText())
                    .append(" | ")
                    .append(clean(item.path("snippet").asText()))
                    .append('\n');
        }
        return results.isEmpty() ? "No results found." : results.toString();
    }

    private static String clean(String text) {
        return text.replace('\n', ' ').replace('|', '/').trim();
    }
}
