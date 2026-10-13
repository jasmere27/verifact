package com.ai.agent.verifact.search;

import java.util.List;

/** A web search backend. Implementations are selected by {@link SearchConfig}. */
public interface SearchProvider {

    /** Short identifier for logs, e.g. "tavily". */
    String name();

    /**
     * @return results in relevance order; empty if nothing matched
     * @throws SearchUnavailableException if the provider can't be reached or rejects the request
     */
    List<SearchResult> search(String query);

    /**
     * Search limited to the given domains (and their subdomains) where the provider supports it.
     * Providers that can't restrict fall back to a normal search; {@code EvidenceRetriever} filters
     * the results either way.
     *
     * @param includeDomains e.g. ["govinfo.gov", "dol.gov"]; empty = no restriction
     */
    default List<SearchResult> search(String query, List<String> includeDomains) {
        return search(query);
    }

    /**
     * Recent results only, for brand monitoring. Providers that can't filter by date report themselves
     * unavailable for it rather than returning undated results as if they were recent.
     *
     * @param news    true = news sources only
     * @param maxDays how far back to look
     */
    default List<SearchResult> searchRecent(String query, boolean news, int maxDays) {
        throw new SearchUnavailableException(name() + " can't limit results by date");
    }
}
