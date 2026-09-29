package com.ai.agent.verifact.feedback;

import com.ai.agent.verifact.verification.VerificationRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedbackController.class)
@Import(com.ai.agent.verifact.config.TimeConfig.class)
@TestPropertySource(properties = "app.rate-limit.feedback-per-ip-per-minute=1000")
class FeedbackControllerTest {

    private static final UUID ID = UUID.fromString("6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private VerificationFeedbackRepository feedbackRepository;
    @MockitoBean
    private VerificationRecordRepository verificationRepository;

    private ResultActions send(String id, String json) throws Exception {
        return mockMvc.perform(post("/api/v2/verifications/" + id + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void acceptsHelpfulFeedback() throws Exception {
        when(verificationRepository.existsById(ID)).thenReturn(true);
        send(ID.toString(), "{\"helpful\":true}").andExpect(status().isNoContent());
        verify(feedbackRepository).save(any());
    }

    @Test
    void rejectsInvalidFeedbackWithoutStoringIt() throws Exception {
        when(verificationRepository.existsById(ID)).thenReturn(true);
        send(ID.toString(), "{}").andExpect(status().isBadRequest());
        send(ID.toString(), "{\"helpful\":false,\"reason\":\"DROP TABLE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown feedback reason."));
        send(ID.toString(), "{\"helpful\":false,\"comment\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
        send("not-a-uuid", "{\"helpful\":true}").andExpect(status().isNotFound());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void unknownReportIs404() throws Exception {
        when(verificationRepository.existsById(ID)).thenReturn(false);
        send(ID.toString(), "{\"helpful\":true}").andExpect(status().isNotFound());
        verify(feedbackRepository, never()).save(any());
    }
}
