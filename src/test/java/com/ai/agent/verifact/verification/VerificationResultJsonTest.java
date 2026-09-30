package com.ai.agent.verifact.verification;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Reports are stored as JSON (ADR-7): reports saved before a field existed must still load. */
class VerificationResultJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void reportsStoredBeforeImageContextExistedStillLoad() {
        String stored = """
                {"id":"6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f","createdAt":"2026-09-30T12:00:00Z","inputType":"IMAGE",
                 "input":"post.png","checkedText":"OCR text","overallVerdict":"SUPPORTED","summary":"ok",
                 "claims":[],"evidence":[],"limitations":[],"searchProvider":"tavily","durationMs":5}""";

        VerificationResult result = jsonMapper.readValue(stored, VerificationResult.class);

        assertThat(result.imageContext()).isNull();
        assertThat(result.input()).isEqualTo("post.png");
    }

    @Test
    void imageContextRoundTrips() {
        String stored = """
                {"id":"6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f","createdAt":"2026-09-30T12:00:00Z","inputType":"IMAGE",
                 "input":"post.png","checkedText":"t","overallVerdict":"SUPPORTED","summary":"ok","claims":[],
                 "evidence":[],"limitations":[],"searchProvider":"tavily","durationMs":5,
                 "imageContext":{"kind":"CHART","shownSource":"@stats","shownDate":null,"description":"A bar chart."}}""";

        VerificationResult result = jsonMapper.readValue(stored, VerificationResult.class);

        assertThat(result.imageContext()).isEqualTo(
                new ImageContext(ImageContext.Kind.CHART, "@stats", null, "A bar chart."));
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(result), VerificationResult.class)).isEqualTo(result);
    }
}
