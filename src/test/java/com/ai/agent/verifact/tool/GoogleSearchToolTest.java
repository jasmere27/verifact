package com.ai.agent.verifact.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleSearchToolTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final GoogleSearchTool tool = new GoogleSearchTool(restTemplate, new ObjectMapper(), "key", "cx");

    @Test
    void returnsTitleUrlAndSnippetSoCitationsCanBeReal() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("""
                {"items":[{"title":"Moon | Facts","link":"https://nasa.gov/moon","snippet":"The Moon is\\nrocky."}]}
                """);

        assertThat(tool.searchWeb("moon")).isEqualTo("- Moon / Facts | https://nasa.gov/moon | The Moon is rocky.\n");
    }

    @Test
    void reportsNoResults() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("{}");
        assertThat(tool.searchWeb("zzz")).isEqualTo("No results found.");
    }

    @Test
    void outageReturnsMarkerTheServiceDetects() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new ResourceAccessException("I/O error on GET request for https://...key=secret"));
        assertThat(tool.searchWeb("moon")).startsWith("Web Search is not available").doesNotContain("secret");
    }
}
