package com.ai.agent.verifact.tool;

import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/** Exposes the configured {@link SearchProvider} to the model as a tool. */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** Returned to the model when search fails; AiService detects it and responds 503. */
    public static final String UNAVAILABLE = "Web Search is not available at the moment.";
    private static final int MAX_SNIPPET_CHARS = 500;

    private final SearchProvider searchProvider;

    public WebSearchTool(SearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    @Tool(description = "Search the web. Returns one result per line as "
            + "'title | url | published: date | snippet'. Only these URLs may be cited as sources.")
    public String searchWeb(String query) {
        if (query == null || query.isBlank()) {
            return "No results found.";
        }
        long startedAt = System.nanoTime();
        List<SearchResult> results;
        try {
            results = searchProvider.search(query.trim());
        } catch (SearchUnavailableException e) {
            log.warn("Web search failed via {}: {}", searchProvider.name(), e.getMessage());
            return UNAVAILABLE;
        }
        log.info("Web search via {} returned {} results in {} ms",
                searchProvider.name(), results.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return format(results);
    }

    static String format(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No results found.";
        }
        StringBuilder out = new StringBuilder();
        for (SearchResult r : results) {
            out.append("- ")
                    .append(clean(r.title()))
                    .append(" | ")
                    .append(r.url())
                    .append(" | published: ")
                    .append(r.publishedDate() == null ? "unknown" : clean(r.publishedDate()))
                    .append(" | ")
                    .append(truncate(clean(r.snippet())))
                    .append('\n');
        }
        return out.toString();
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private static String truncate(String text) {
        return text.length() > MAX_SNIPPET_CHARS ? text.substring(0, MAX_SNIPPET_CHARS) + "…" : text;
    }
}
