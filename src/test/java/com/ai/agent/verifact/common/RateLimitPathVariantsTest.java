package com.ai.agent.verifact.common;

import com.ai.agent.verifact.config.CorsConfig;
import com.ai.agent.verifact.controller.AiController;
import com.ai.agent.verifact.service.AiService;
import com.ai.agent.verifact.service.ImageOcrService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for a rate-limit bypass: path variants that Spring MVC still routes to the
 * controller must be counted too. Each variant is first shown to reach the controller (200),
 * then shown to be limited (429).
 */
@WebMvcTest(AiController.class)
@Import(CorsConfig.class)
@TestPropertySource(properties = {
        "app.allowed-origin=http://localhost:5173",
        "app.rate-limit.per-ip-per-minute=1",
        "app.rate-limit.per-ip-per-day=100",
        "app.rate-limit.global-per-day=100",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitPathVariantsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private ImageOcrService imageOcrService;

    @Test
    void rateLimitResponsesCarryCorsHeadersSoBrowsersCanReadThem() throws Exception {
        when(aiService.isFakeNews(anyString())).thenReturn("report");
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "a").header("Origin", "http://localhost:5173"));
        mockMvc.perform(get("/api/v1/isFakeNews").param("news", "a").header("Origin", "http://localhost:5173"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().exists("Retry-After"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/isFakeNews?news=a",
            "/api/v1/isFakeNews;x=1?news=a",
            "/api/v1/isFakeNews;jsessionid=abc?news=a",
            "/api/v1/isFake%4Eews?news=a",
            "/api/v1/%69sFakeNews?news=a",
    })
    void variantsThatReachTheControllerAreCounted(String path) throws Exception {
        when(aiService.isFakeNews(anyString())).thenReturn("report");

        mockMvc.perform(get(URI.create(path))).andExpect(status().isOk());
        mockMvc.perform(get(URI.create(path))).andExpect(status().isTooManyRequests());
        mockMvc.perform(get(URI.create("/api/v1/isFakeNews?news=a"))).andExpect(status().isTooManyRequests());
    }
}
