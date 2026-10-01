package com.ai.agent.verifact.news;

import com.ai.agent.verifact.config.SecurityConfig;
import com.ai.agent.verifact.account.SupabaseAuthConfig;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NewsController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class})
@TestPropertySource(properties = {
        "app.input.max-chars=300",
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class NewsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private NewsCheckService service;
    @MockitoBean
    private NewsStore store;
    @MockitoBean
    private VerificationStreamer streamer;

    @Test
    void inputIsValidatedBeforeAnyWork() throws Exception {
        mockMvc.perform(post("/api/v2/news/checks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/news/checks").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"Council approves budget.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/news/checks/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        verify(service, never()).check(anyString(), any());
    }

    @Test
    void reviewsAreValidatedAndMalformedIdsAreNotFound() throws Exception {
        mockMvc.perform(put("/api/v2/news/checks/6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f/review").header("X-Edit-Token", "t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":{\"<script>\":{\"status\":\"CONFIRMED\"}}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v2/news/checks/6f1c1a8e-2b3c-4d5e-8f90-1a2b3c4d5e6f/review").header("X-Edit-Token", "t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":{\"C1\":{\"status\":\"MAYBE\"}}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v2/news/checks/not-a-uuid")).andExpect(status().isNotFound());
        verify(store, never()).updateReview(any(), any(), any());
    }

    @Test
    void notesAreCappedBeforeSaving() {
        String longNote = "x".repeat(5000);
        NewsReview r = NewsController.validReview(new NewsReview(
                java.util.Map.of("C1", new NewsReview.Decision(NewsReview.Status.DISPUTED, longNote)), longNote, null));
        org.assertj.core.api.Assertions.assertThat(r.decisions().get("C1").note()).hasSize(NewsController.MAX_NOTE_CHARS);
        org.assertj.core.api.Assertions.assertThat(r.editorNote()).hasSize(NewsController.MAX_NOTE_CHARS);
    }
}
