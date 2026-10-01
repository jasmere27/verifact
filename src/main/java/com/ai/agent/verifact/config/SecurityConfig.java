package com.ai.agent.verifact.config;

import com.ai.agent.verifact.common.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Accounts are optional: every existing endpoint stays open to signed-out use, and only account endpoints
 * require a signed-in user. Requests authenticate with a Supabase access token in the Authorization header
 * ("Bearer ..."): stateless, no cookies or server sessions, so there is no CSRF exposure to protect against.
 * A request that sends an invalid or expired token gets a 401, even on an open endpoint, so the client can
 * refresh the session instead of silently acting signed out. CORS and rate limiting run earlier, as servlet filters.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v2/me", "/api/v2/me/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, e) -> problem(response, jsonMapper, HttpStatus.UNAUTHORIZED,
                                "Please sign in again: your session is missing, expired or invalid."))
                        .accessDeniedHandler((request, response, e) -> problem(response, jsonMapper, HttpStatus.FORBIDDEN,
                                "You don't have permission to do that.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> problem(response, jsonMapper, HttpStatus.UNAUTHORIZED,
                                "Please sign in to continue.")));
        return http.build();
    }

    private static void problem(HttpServletResponse response, JsonMapper jsonMapper, HttpStatus status, String detail) throws IOException {
        ProblemDetail body = GlobalExceptionHandler.problem(status, detail);
        response.setStatus(status.value());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
