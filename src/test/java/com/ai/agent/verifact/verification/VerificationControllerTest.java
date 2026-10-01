package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.config.SecurityConfig;
import com.ai.agent.verifact.account.SupabaseAuthConfig;
import org.springframework.context.annotation.Import;
import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.SourceType;
import com.ai.agent.verifact.model.InputType;
import com.ai.agent.verifact.service.ImageOcrService;
import com.ai.agent.verifact.tool.VoiceToTextTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VerificationController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class})
@TestPropertySource(properties = {
        "app.input.max-chars=50",
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class VerificationControllerTest {

    private static final UUID ID = UUID.fromString("6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService service;
    @MockitoBean
    private VerificationStreamer streamer;
    @MockitoBean
    private VerificationStore store;
    @MockitoBean
    private ImageOcrService imageOcrService;
    @MockitoBean
    private VoiceToTextTool voiceToText;

    private static VerificationResult sample() {
        return new VerificationResult(ID, Instant.parse("2026-09-30T12:00:00Z"), InputType.TEXT, "claim", "claim",
                OverallVerdict.CONTRADICTED, "It's false.",
                List.of(new ClaimAssessment("C1", "claim", Verdict.CONTRADICTED, EvidenceStrength.MODERATE, "Why",
                        List.of(), List.of("E1"))),
                List.of(new Evidence("E1", "https://a.example/1", "a.example", "Title", "Snippet", null,
                        Instant.parse("2026-09-30T12:00:01Z"), SourceType.NEWS)),
                List.of("Only two sources."), "tavily", 1234, null);
    }

    @Test
    void returnsTheStructuredContract() throws Exception {
        when(service.verifyText("claim", VerificationProgress.NONE, false)).thenReturn(sample());

        mockMvc.perform(post("/api/v2/verifications").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"claim\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-09-30T12:00:00Z"))
                .andExpect(jsonPath("$.overallVerdict").value("CONTRADICTED"))
                .andExpect(jsonPath("$.claims[0].evidenceStrength").value("MODERATE"))
                .andExpect(jsonPath("$.claims[0].contradictingEvidenceIds[0]").value("E1"))
                .andExpect(jsonPath("$.evidence[0].publishedDate").isEmpty())
                .andExpect(jsonPath("$.evidence[0].retrievedAt").value("2026-09-30T12:00:01Z"))
                .andExpect(jsonPath("$.limitations[0]").value("Only two sources."))
                .andExpect(jsonPath("$.durationMs").value(1234));
    }

    @Test
    void blankAndOversizedInputAreRejectedBeforeAnyWork() throws Exception {
        mockMvc.perform(post("/api/v2/verifications").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/api/v2/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"" + "x".repeat(51) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mockMvc.perform(post("/api/v2/verifications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).verifyText(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void imageIsPreparedForTheVisionModelThenVerified() throws Exception {
        ImageInput prepared = new ImageInput(new byte[]{9}, "image/jpeg");
        when(imageOcrService.prepareForVision(any())).thenReturn(prepared);
        when(service.verifyImage(eq("post.png"), same(prepared), any(), eq(VerificationProgress.NONE))).thenReturn(sample());

        mockMvc.perform(multipart("/api/v2/verifications/image")
                        .file(new MockMultipartFile("file", "post.png", "image/png", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.imageContext").isEmpty());
        // OCR only runs if the service falls back to it.
        verify(imageOcrService, never()).extractText(any());
    }

    @Test
    void audioIsTranscribedThenVerified() throws Exception {
        when(voiceToText.transcribe(any())).thenReturn("spoken claim");
        when(service.verifyAudioTranscript("clip.wav", "spoken claim")).thenReturn(sample());

        mockMvc.perform(multipart("/api/v2/verifications/audio")
                        .file(new MockMultipartFile("file", "clip.wav", "audio/wav", new byte[]{1})))
                .andExpect(status().isOk());
    }

    @Test
    void reportsCanBeReopenedById() throws Exception {
        when(store.find(ID)).thenReturn(Optional.of(sample()));
        mockMvc.perform(get("/api/v2/verifications/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("It's false."));
    }

    @Test
    void unknownOrMalformedIdsAre404() throws Exception {
        when(store.find(any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v2/verifications/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v2/verifications/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v2/verifications/../../etc")).andExpect(status().is4xxClientError());
    }
}
