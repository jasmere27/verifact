package com.ai.agent.verifact.core.provenance;

import com.ai.agent.verifact.evidence.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    static Evidence source(String id, String title, String snippet) {
        return new Evidence(id, "https://" + id.toLowerCase() + ".example/a", id.toLowerCase() + ".example", title, snippet,
                null, null, null);
    }

    static final List<Evidence> SOURCES = List.of(
            source("E1", "Council passes budget", "The council voted 7-2 to approve the $4.2 billion transit budget on Tuesday."),
            source("E2", "Ridership report", "Ridership fell 8 percent last year according to the annual report."));
    static final Map<String, Evidence> BY_ID = CitationValidator.byId(SOURCES);

    @Test
    void acceptsOnlyASourcesOwnWords() {
        SourceExcerpt ok = CitationValidator.verbatim("e1", "the council voted 7-2 to approve the $4.2 billion transit budget", BY_ID, 5, 300);

        assertThat(ok).isNotNull();
        assertThat(ok.sourceId()).isEqualTo("E1");
        assertThat(ok.excerpt()).isEqualTo("The council voted 7-2 to approve the $4.2 billion transit budget");
        // Paraphrase, an unknown source, the wrong source, and missing values are all rejected.
        assertThat(CitationValidator.verbatim("E1", "the council backed a big budget for transit", BY_ID, 5, 300)).isNull();
        assertThat(CitationValidator.verbatim("E9", "Ridership fell 8 percent last year", BY_ID, 5, 300)).isNull();
        assertThat(CitationValidator.verbatim("E1", "Ridership fell 8 percent last year", BY_ID, 5, 300)).isNull();
        assertThat(CitationValidator.verbatim(null, "Ridership fell 8 percent last year", BY_ID, 5, 300)).isNull();
        assertThat(CitationValidator.verbatim("E2", null, BY_ID, 5, 300)).isNull();
    }

    @Test
    void longExcerptsAreCapped() {
        SourceExcerpt e = CitationValidator.verbatim("E2", "Ridership fell 8 percent last year according to the annual report", BY_ID, 5, 20);

        assertThat(e.excerpt()).isEqualTo("Ridership fell 8 per…");
    }

    @Test
    void quotesAreFoundWordForWordInTheFirstSourceThatHasThem() {
        assertThat(QuoteVerifier.locate("voted 7-2 to approve", SOURCES, 3, 300))
                .isEqualTo(new SourceExcerpt("E1", "voted 7-2 to approve"));
        assertThat(QuoteVerifier.locate("voted 9-0 to reject", SOURCES, 3, 300)).isNull();
    }
}
