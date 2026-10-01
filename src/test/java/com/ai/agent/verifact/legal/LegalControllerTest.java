package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.config.SecurityConfig;
import com.ai.agent.verifact.account.SupabaseAuthConfig;
import org.springframework.context.annotation.Import;
import com.ai.agent.verifact.verification.VerificationProgress;
import com.ai.agent.verifact.verification.VerificationStreamer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LegalController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class})
@TestPropertySource(properties = {
        "app.input.max-chars=200",
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class LegalControllerTest {

    private static final String DESCRIPTION = "I was fired in Fresno, California two weeks after complaining about overtime.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CaseIntelligenceService service;
    @MockitoBean
    private VerificationStreamer streamer;

    @Test
    void returnsTheCaseIntelligenceContract() throws Exception {
        when(service.analyze(DESCRIPTION, VerificationProgress.NONE)).thenReturn(new CaseIntelligence(
                Instant.parse("2026-09-30T12:00:00Z"), List.of(PracticeArea.EMPLOYMENT),
                new CaseIntelligence.Jurisdiction(CaseIntelligence.Jurisdiction.Status.IDENTIFIED, "US", "CA",
                        "California", "Fresno, California"),
                "The person says they were fired.",
                List.of(new CaseIntelligence.Fact("Was fired", "I was fired", null, CaseIntelligence.Basis.USER_STATED)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), CaseIntelligenceService.NOTICE, "tavily", 10));

        mockMvc.perform(post("/api/v2/legal/case-intelligence").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + DESCRIPTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practiceAreas[0]").value("EMPLOYMENT"))
                .andExpect(jsonPath("$.jurisdiction.status").value("IDENTIFIED"))
                .andExpect(jsonPath("$.keyFacts[0].basis").value("USER_STATED"))
                .andExpect(jsonPath("$.notice").value(CaseIntelligenceService.NOTICE));
    }

    @Test
    void emptyTooShortAndTooLongDescriptionsAreRejectedBeforeAnyWork() throws Exception {
        mockMvc.perform(post("/api/v2/legal/case-intelligence").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/legal/case-intelligence").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"I got fired.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/legal/case-intelligence").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mockMvc.perform(post("/api/v2/legal/case-intelligence/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).analyze(anyString(), any());
    }
}
