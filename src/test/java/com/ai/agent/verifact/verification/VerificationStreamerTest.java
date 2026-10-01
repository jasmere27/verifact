package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.account.SupabaseAuthConfig;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.config.SecurityConfig;
import com.ai.agent.verifact.config.TimeConfig;
import com.ai.agent.verifact.model.InputType;
import com.ai.agent.verifact.service.ImageOcrService;
import com.ai.agent.verifact.tool.VoiceToTextTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Streams through the real VerificationStreamer with a scripted service. */
@WebMvcTest(VerificationController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class, VerificationStreamer.class, TimeConfig.class})
@TestPropertySource(properties = {
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class VerificationStreamerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService service;
    @MockitoBean
    private VerificationStore store;
    @MockitoBean
    private ImageOcrService imageOcrService;
    @MockitoBean
    private VoiceToTextTool voiceToText;

    private static VerificationResult result() {
        return new VerificationResult(UUID.fromString("6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f"),
                Instant.parse("2026-09-30T12:00:00Z"), InputType.TEXT, "claim", "claim", OverallVerdict.SUPPORTED,
                "ok", List.of(), List.of(), List.of(), "fake", 5, null);
    }

    private String stream(String json) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v2/verifications/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        started.getAsyncResult(10_000);
        return started.getResponse().getContentAsString();
    }

    @Test
    void streamsProgressThenTheResult() throws Exception {
        when(service.verifyText(eq("claim"), any(), eq(false))).thenAnswer(inv -> {
            VerificationProgress p = inv.getArgument(1);
            p.stage(VerificationProgress.Stage.EXTRACTING_CLAIMS);
            p.claims(List.of("claim"));
            p.stage(VerificationProgress.Stage.SEARCHING);
            p.sources(2, List.of("a.example", "b.example"));
            p.stage(VerificationProgress.Stage.ASSESSING);
            return result();
        });

        String body = stream("{\"input\":\"claim\"}");

        assertThat(body)
                .containsSubsequence("event:stage", "EXTRACTING_CLAIMS", "event:claims", "event:stage", "SEARCHING",
                        "event:sources", "\"count\":2", "event:stage", "ASSESSING", "event:result",
                        "6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f")
                .doesNotContain("event:error");
    }

    @Test
    void pipelineErrorsBecomeASafeErrorEvent() throws Exception {
        when(service.verifyText(eq("claim"), any(), eq(false)))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "No checkable claim."));

        assertThat(stream("{\"input\":\"claim\"}"))
                .contains("event:error").contains("\"status\":422").contains("No checkable claim.")
                .doesNotContain("event:result");
    }

    @Test
    void unexpectedFailuresDoNotLeakDetails() throws Exception {
        when(service.verifyText(eq("claim"), any(), eq(false))).thenThrow(new IllegalStateException("db password=hunter2"));

        assertThat(stream("{\"input\":\"claim\"}"))
                .contains("event:error").contains("\"status\":500").doesNotContain("hunter2");
    }

    @Test
    void invalidInputIsAPlainProblemResponseBeforeAnyStream() throws Exception {
        mockMvc.perform(post("/api/v2/verifications/stream").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted());
        verify(service, never()).verifyText(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
