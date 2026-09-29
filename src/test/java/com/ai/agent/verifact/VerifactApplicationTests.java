package com.ai.agent.verifact;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

    @Test
    void contextLoads() {
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
}
