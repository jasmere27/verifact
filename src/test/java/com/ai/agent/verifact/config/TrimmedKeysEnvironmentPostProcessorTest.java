package com.ai.agent.verifact.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrimmedKeysEnvironmentPostProcessorTest {

    @Test
    void keysPastedWithATrailingNewlineStillWork() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("env", Map.of(
                "OPEN_AI_API_KEY", "sk-abc\n", "TAVILY_API_KEY", " tvly-1 ", "OTHER", "keep\n")));
        environment.getPropertySources().addLast(new MapPropertySource("app",
                Map.of("spring.ai.openai.api-key", "${OPEN_AI_API_KEY}")));

        new TrimmedKeysEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-abc");
        assertThat(environment.getProperty("TAVILY_API_KEY")).isEqualTo("tvly-1");
        assertThat(environment.getProperty("OTHER")).isEqualTo("keep\n");
    }

    @Test
    void cleanKeysAreLeftAlone() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("env", Map.of("OPEN_AI_API_KEY", "sk-abc")));
        int before = environment.getPropertySources().size();

        new TrimmedKeysEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().size()).isEqualTo(before);
    }
}
