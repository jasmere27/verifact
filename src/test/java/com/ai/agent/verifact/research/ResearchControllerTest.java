package com.ai.agent.verifact.research;

import com.ai.agent.verifact.verification.VerificationStreamer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
@TestPropertySource(properties = {
        "app.input.max-chars=200",
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class ResearchControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ResearchCheckService service;
    @MockitoBean
    private VerificationStreamer streamer;

    @Test
    void emptyTooShortAndTooLongTextIsRejectedBeforeAnyWork() throws Exception {
        mockMvc.perform(post("/api/v2/research/check").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/research/check").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Smith 2020.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/research/check/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        verify(service, never()).check(anyString(), any());
    }
}
