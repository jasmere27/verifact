package com.ai.agent.verifact.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfig {

    // Comma-separated list, e.g. "http://localhost:3000,https://verifact.pages.dev"
    @Value("${app.allowed-origin}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins.split("\\s*,\\s*"))
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("Content-Type", "X-Request-Id")
                        .exposedHeaders("X-Request-Id", "Retry-After");
            }
        };
    }
}