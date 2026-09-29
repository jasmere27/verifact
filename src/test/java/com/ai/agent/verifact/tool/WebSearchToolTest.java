package com.ai.agent.verifact.tool;

import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSearchToolTest {

    private final SearchProvider provider = mock(SearchProvider.class);
    private final WebSearchTool tool = new WebSearchTool(provider);

    @Test
    void formatsTitleUrlDateAndSnippetForTheModel() {
        when(provider.search("moon")).thenReturn(List.of(
                new SearchResult("Moon | Facts", "https://nasa.gov/moon", "The Moon is\nrocky.", "2024-05-01"),
                new SearchResult("Undated", "https://example.org", "Text", null)));

        assertThat(tool.searchWeb(" moon ")).isEqualTo("""
                - Moon / Facts | https://nasa.gov/moon | published: 2024-05-01 | The Moon is rocky.
                - Undated | https://example.org | published: unknown | Text
                """);
    }

    @Test
    void longSnippetsAreTruncated() {
        when(provider.search(anyString())).thenReturn(List.of(
                new SearchResult("T", "https://a.example", "x".repeat(2000), null)));
        assertThat(tool.searchWeb("q").length()).isLessThan(600);
    }

    @Test
    void noResults() {
        when(provider.search(anyString())).thenReturn(List.of());
        assertThat(tool.searchWeb("zzz")).isEqualTo("No results found.");
    }

    @Test
    void blankQueryDoesNotSpendACredit() {
        assertThat(tool.searchWeb("  ")).isEqualTo("No results found.");
        verifyNoInteractions(provider);
    }

    @Test
    void outageReturnsTheMarkerAiServiceDetects() {
        when(provider.name()).thenReturn("tavily");
        when(provider.search(anyString())).thenThrow(new SearchUnavailableException("Tavily returned HTTP 500"));
        assertThat(tool.searchWeb("q")).isEqualTo(WebSearchTool.UNAVAILABLE);
    }
}
