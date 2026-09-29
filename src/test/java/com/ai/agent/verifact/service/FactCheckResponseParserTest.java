package com.ai.agent.verifact.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FactCheckResponseParserTest {

    private final FactCheckResponseParser parser = new FactCheckResponseParser();

    private static final String REPORT = """
            ### News Analysis Result
            The claim was verified through multiple credible sources.

            **Classification:** Mixed
            **Confidence Score:** 50%

            ### Sources
            - https://reuters.com/a
            - https://apnews.com/b

            ### Cybersecurity Tips
            Cybersecurity Tip: Check the URL.

            ### Original Input
            "claim"
            """;

    @Test
    void extractsStructuredFields() {
        assertThat(parser.extractClassification(REPORT)).isEqualTo("Mixed");
        assertThat(parser.extractConfidenceScore(REPORT)).isEqualTo(50);
        assertThat(parser.extractSources(REPORT)).contains("reuters.com").doesNotContain("Cybersecurity");
        assertThat(parser.extractCybersecurityTips(REPORT)).contains("Check the URL").doesNotContain("Original");
    }

    @Test
    void missingFieldsAreNullNotErrors() {
        assertThat(parser.extractClassification("free text")).isNull();
        assertThat(parser.extractConfidenceScore("free text")).isNull();
        assertThat(parser.extractSources(null)).isNull();
    }
}
