package com.ai.agent.verifact.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strips whitespace from API keys before anything reads them. Keys pasted into a hosting dashboard
 * often pick up a trailing newline, which then breaks every provider call ("Unexpected char 0x0a
 * in Authorization value"). The trimmed values take precedence over the originals.
 */
public class TrimmedKeysEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final List<String> KEYS = List.of("OPEN_AI_API_KEY", "TAVILY_API_KEY", "GOOGLE_API_KEY", "GOOGLE_SEARCH_ENGINE", "OPENALEX_API_KEY", "SCHOLARLY_CONTACT_EMAIL");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = environment.getProperty(key);
            if (value != null && !value.equals(value.strip())) {
                trimmed.put(key, value.strip());
            }
        }
        if (!trimmed.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("trimmedApiKeys", trimmed));
        }
    }
}
