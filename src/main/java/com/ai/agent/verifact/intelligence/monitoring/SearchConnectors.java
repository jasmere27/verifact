package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.ai.UsageScope;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Web and news search through the configured {@link SearchProvider} (Tavily), limited to the project's
 * look-back window. A provider that can't filter by date makes the connector fail visibly instead of returning
 * old results as new.
 */
@Configuration
public class SearchConnectors {

    @Bean
    public SourceConnector webSearchConnector(SearchProvider provider) {
        return new SearchConnector("WEB_SEARCH", provider, false);
    }

    @Bean
    public SourceConnector newsSearchConnector(SearchProvider provider) {
        return new SearchConnector("NEWS_SEARCH", provider, true);
    }

    static final class SearchConnector implements SourceConnector {

        private final String id;
        private final SearchProvider provider;
        private final boolean news;

        SearchConnector(String id, SearchProvider provider, boolean news) {
            this.id = id;
            this.provider = provider;
            this.news = news;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean enabledFor(MonitoringConfig config) {
            return news ? config.sources().newsSearch() : config.sources().webSearch();
        }

        @Override
        public boolean available() {
            return !"none".equals(provider.name());
        }

        @Override
        public List<Item> fetch(Request request) {
            List<Item> items = new ArrayList<>();
            int failures = 0;
            String lastError = null;
            for (String q : request.queries()) {
                try {
                    UsageScope.searchCall();
                    for (SearchResult r : provider.searchRecent(q, news, request.config().lookbackDays())) {
                        items.add(new Item(r.url(), r.title(), r.snippet(), r.publishedDate()));
                    }
                } catch (SearchUnavailableException e) {
                    failures++;
                    lastError = e.getMessage();
                }
            }
            if (failures > 0 && failures == request.queries().size()) {
                throw new ConnectorException("Search unavailable (" + lastError + ")");
            }
            if (failures > 0) {
                throw new PartialResult(items, failures + " of " + request.queries().size() + " searches failed");
            }
            return items;
        }
    }
}
