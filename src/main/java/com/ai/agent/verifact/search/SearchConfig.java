package com.ai.agent.verifact.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;

/**
 * Picks the search backend from SEARCH_PROVIDER: {@code tavily}, {@code google}, or {@code auto}
 * (default: Tavily if TAVILY_API_KEY is set, otherwise Google if its keys are set). With no
 * usable configuration the app still starts, and every search reports itself unavailable.
 */
@Configuration
public class SearchConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchConfig.class);

    @Bean
    public SearchProvider searchProvider(RestClient.Builder restClientBuilder,
                                         JsonMapper jsonMapper,
                                         @Value("${app.search.provider:auto}") String provider,
                                         @Value("${app.search.max-results:6}") int maxResults,
                                         @Value("${tavily.api-key:}") String tavilyKey,
                                         @Value("${google.api.key:}") String googleKey,
                                         @Value("${google.cse.id:}") String googleEngineId) {
        String choice = provider.trim().toLowerCase(Locale.ROOT);
        boolean hasTavily = !tavilyKey.isBlank();
        boolean hasGoogle = !googleKey.isBlank() && !googleEngineId.isBlank();

        SearchProvider selected = switch (choice) {
            case "tavily" -> hasTavily ? tavily(restClientBuilder, jsonMapper, tavilyKey, maxResults) : null;
            case "google" -> hasGoogle ? google(restClientBuilder, jsonMapper, googleKey, googleEngineId, maxResults) : null;
            case "auto" -> hasTavily ? tavily(restClientBuilder, jsonMapper, tavilyKey, maxResults)
                    : hasGoogle ? google(restClientBuilder, jsonMapper, googleKey, googleEngineId, maxResults)
                    : null;
            default -> throw new IllegalStateException(
                    "Unknown SEARCH_PROVIDER '" + provider + "'. Use tavily, google, or auto.");
        };

        if (selected == null) {
            log.warn("No web search configured (SEARCH_PROVIDER={}). Set TAVILY_API_KEY. "
                    + "Verifications will fail with 503 until search is available.", choice);
            return new UnconfiguredSearchProvider();
        }
        if (selected instanceof GoogleCustomSearchProvider) {
            log.warn("Using Google Custom Search, which is discontinued on 2027-01-01. Set TAVILY_API_KEY to migrate.");
        }
        log.info("Web search provider: {}", selected.name());
        return selected;
    }

    private static SearchProvider tavily(RestClient.Builder builder, JsonMapper jsonMapper, String key, int max) {
        return new TavilySearchProvider(builder.clone(), jsonMapper, key, max);
    }

    private static SearchProvider google(RestClient.Builder builder, JsonMapper jsonMapper, String key,
                                         String engineId, int max) {
        return new GoogleCustomSearchProvider(builder.clone(), jsonMapper, key, engineId, max);
    }

    static final class UnconfiguredSearchProvider implements SearchProvider {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public List<SearchResult> search(String query) {
            throw new SearchUnavailableException("No search provider configured");
        }
    }
}
