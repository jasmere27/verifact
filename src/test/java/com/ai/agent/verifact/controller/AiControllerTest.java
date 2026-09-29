package com.ai.agent.verifact.controller;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.config.CorsConfig;
import com.ai.agent.verifact.model.InputType;
import com.ai.agent.verifact.service.AiService;
import com.ai.agent.verifact.service.ImageOcrService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@Import(CorsConfig.class)
@TestPropertySource(properties = {
        "app.allowed-origin=http://localhost:5173",
        "app.input.max-chars=50",
        "app.rate-limit.per-ip-per-minute=1000",
        "app.rate-limit.per-ip-per-day=1000",
        "app.rate-limit.global-per-day=1000",
})
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private ImageOcrService imageOcrService;

    @Test
    void postJsonReturnsPlainTextReport() throws Exception {
        when(aiService.isFakeNews("The sky is green")).thenReturn("**Classification:** fake");

        mockMvc.perform(post("/api/v1/isFakeNews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"news\":\"The sky is green\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().string("**Classification:** fake"));
    }

    @Test
    void getWithQueryParamStillWorks() throws Exception {
        when(aiService.isFakeNews("claim")).thenReturn("report");
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "claim"))
                .andExpect(status().isOk())
                .andExpect(content().string("report"));
    }

    @Test
    void blankInputIs400Problem() throws Exception {
        mockMvc.perform(post("/api/v1/isFakeNews").contentType(MediaType.APPLICATION_JSON).content("{\"news\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.requestId").exists());
        verify(aiService, never()).isFakeNews(anyString());
    }

    @Test
    void oversizedInputIs413() throws Exception {
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "x".repeat(51)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value(containsString("at most 50 characters")));
    }

    @Test
    void malformedJsonIs400Problem() throws Exception {
        mockMvc.perform(post("/api/v1/isFakeNews").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void serviceApiExceptionKeepsStatusAndSafeMessage() throws Exception {
        when(aiService.isFakeNews(anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "The analysis service is unavailable right now."));
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "claim"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The analysis service is unavailable right now."));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() throws Exception {
        when(aiService.isFakeNews(anyString()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host password=hunter2"));
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "claim"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("secret-host"))));
    }

    @Test
    void imageIsOcrdThenChecked() throws Exception {
        when(imageOcrService.extractText(any())).thenReturn("Extracted claim");
        when(aiService.isFakeNews("Extracted claim", InputType.IMAGE)).thenReturn("report");

        mockMvc.perform(multipart("/api/v1/analyzeImage")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(content().string("report"));
    }

    @Test
    void emptyImageUploadIs400() throws Exception {
        mockMvc.perform(multipart("/api/v1/analyzeImage")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .andExpect(status().isBadRequest());
        verify(imageOcrService, never()).extractText(any());
    }

    @Test
    void imageWithNoTextIsNotFactChecked() throws Exception {
        when(imageOcrService.extractText(any()))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "No readable text was found in that image."));
        mockMvc.perform(multipart("/api/v1/analyzeImage")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1})))
                .andExpect(status().isUnprocessableEntity());
        verify(aiService, never()).isFakeNews(anyString(), any());
    }

    @Test
    void nonMultipartUploadIs400NotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/analyzeImage").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void standardSpringErrorsCarryRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/isFakeNews").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void missingFilePartIs400() throws Exception {
        mockMvc.perform(multipart("/api/v1/analyzeAudio")).andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsConfiguredOriginOnly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/v1/isFakeNews")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/v1/isFakeNews")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
