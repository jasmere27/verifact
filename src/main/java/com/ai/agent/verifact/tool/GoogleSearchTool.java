package com.ai.agent.verifact.tool;

import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GoogleSearchTool {

    @Value("${GOOGLE_API_KEY}")
    private String API_KEY;

    @Value("${GOOGLE_SEARCH_ENGINE}")
    private String SEARCH_ENGINE_ID;

    private final RestTemplate restTemplate;

    public GoogleSearchTool(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Tool(description = "Search the web using Google Custom Search API")
    public String searchWeb(String query) {
        System.out.println("Google Search query: " + query);

        final String url = UriComponentsBuilder.fromUriString("https://www.googleapis.com/customsearch/v1")
                .queryParam("key", API_KEY)
                .queryParam("cx", SEARCH_ENGINE_ID)
                .queryParam("q", query)
                .build()
                .toUriString();

        final StringBuilder snippets = new StringBuilder();

        try {
            final String response = restTemplate.getForObject(url, String.class);

            final JsonNode root = new ObjectMapper().readTree(response);
            for (final JsonNode item : root.path("items")) {
                snippets.append("- ").append(item.path("snippet").asText()).append("\n");
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                System.out.println("Web Search is not available: 403 Forbidden. " +
                        "Check if Custom Search API is enabled and API key is valid.");
                return "Web Search is not available at the moment: 403 Forbidden. " +
                        "Please check your API key and enable the Custom Search API.";
            }
            System.out.println("HttpClientErrorException: " + e.getMessage());
            return "Web Search is not available at the moment: " + e.getMessage();
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            return "Web Search is not available at the moment.";
        }

        System.out.println("Google Search Response: " + snippets.toString());

        return snippets.toString();
    }
}
