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
}
