package com.ai.agent.verifact;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the full application against in-memory H2 (Flyway migrations included). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerifactApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.ai.agent.verifact.verification.VerificationStore verificationStore;

    @Test
    void contextLoads() {
    }

    @Test
    void reportsRoundTripThroughTheDatabase() {
        var id = java.util.UUID.randomUUID();
        var result = new com.ai.agent.verifact.verification.VerificationResult(
                id, java.time.Instant.parse("2026-09-30T12:00:00Z"), com.ai.agent.verifact.model.InputType.URL,
                "https://news.example/a", "Headline text {with braces}",
                com.ai.agent.verifact.verification.OverallVerdict.MIXED, "Summary",
                java.util.List.of(new com.ai.agent.verifact.verification.ClaimAssessment("C1", "Claim",
                        com.ai.agent.verifact.core.assess.Verdict.SUPPORTED,
                        com.ai.agent.verifact.verification.EvidenceStrength.MODERATE, "Why",
                        java.util.List.of("E1"), java.util.List.of())),
                java.util.List.of(new com.ai.agent.verifact.evidence.Evidence("E1", "https://a.example/1",
                        "a.example", "Title", "Snippet", null, java.time.Instant.parse("2026-09-30T12:00:01Z"),
                        com.ai.agent.verifact.evidence.SourceType.NEWS)),
                java.util.List.of("Limitation"), "tavily", 42, null);

        verificationStore.save(result);

        org.assertj.core.api.Assertions.assertThat(verificationStore.find(id)).contains(result);
        org.assertj.core.api.Assertions.assertThat(verificationStore.find(java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void healthCheckIsCheapAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void historyIsDisabledByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/history"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.detail").value("History is not available yet."));
    }

    @Test
    void onlyHealthIsExposedFromActuator() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Autowired
    private com.ai.agent.verifact.feedback.VerificationFeedbackRepository feedbackRepository;

    @Test
    void recentReportsAreFoundByInputHashWithinTheWindow() {
        var result = new com.ai.agent.verifact.verification.VerificationResult(
                java.util.UUID.randomUUID(), java.time.Instant.parse("2026-09-30T12:00:00Z"),
                com.ai.agent.verifact.model.InputType.TEXT, "claim", "claim",
                com.ai.agent.verifact.verification.OverallVerdict.SUPPORTED, "s", java.util.List.of(),
                java.util.List.of(), java.util.List.of(), "fake", 1, null);
        String hash = "a".repeat(64);
        verificationStore.save(result, hash);

        org.assertj.core.api.Assertions.assertThat(
                verificationStore.findRecent(hash, java.time.Instant.parse("2026-09-30T00:00:00Z"))).contains(result);
        org.assertj.core.api.Assertions.assertThat(
                verificationStore.findRecent(hash, java.time.Instant.parse("2026-09-30T13:00:00Z"))).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                verificationStore.findRecent("b".repeat(64), java.time.Instant.EPOCH)).isEmpty();
    }

    @Test
    void feedbackIsStoredForExistingReportsOnly() throws Exception {
        var id = java.util.UUID.randomUUID();
        verificationStore.save(new com.ai.agent.verifact.verification.VerificationResult(
                id, java.time.Instant.now(), com.ai.agent.verifact.model.InputType.TEXT, "c", "c",
                com.ai.agent.verifact.verification.OverallVerdict.CONTRADICTED, "s", java.util.List.of(),
                java.util.List.of(), java.util.List.of(), "fake", 1, null));
        long before = feedbackRepository.count();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v2/verifications/" + id + "/feedback")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"helpful\":false,\"reason\":\"wrong_verdict\",\"comment\":\"  It is true  \"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v2/verifications/" + java.util.UUID.randomUUID() + "/feedback")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(feedbackRepository.count()).isEqualTo(before + 1);
        var saved = feedbackRepository.findAll().stream().filter(f -> f.getVerificationId().equals(id)).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.isHelpful()).isFalse();
        org.assertj.core.api.Assertions.assertThat(saved.getReason())
                .isEqualTo(com.ai.agent.verifact.feedback.FeedbackReason.WRONG_VERDICT);
        org.assertj.core.api.Assertions.assertThat(saved.getComment()).isEqualTo("It is true");
    }
}
