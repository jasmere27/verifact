package com.ai.agent.verifact.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Supabase Auth admin calls, made only by the backend with the project's secret key ({@code SUPABASE_SECRET_KEY},
 * sent on the {@code apikey} header as Supabase requires). The key never appears in logs or error messages.
 */
@Component
public class SupabaseAdmin {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAdmin.class);

    private final RestClient client;
    private final boolean enabled;

    public SupabaseAdmin(RestClient.Builder builder, @Value("${app.auth.supabase-url:}") String supabaseUrl,
                         @Value("${app.auth.supabase-secret-key:}") String secretKey) {
        String base = supabaseUrl == null ? "" : supabaseUrl.trim().replaceAll("/+$", "");
        String key = secretKey == null ? "" : secretKey.trim();
        this.enabled = !base.isEmpty() && !key.isEmpty();
        this.client = builder.baseUrl(base.isEmpty() ? "https://invalid.example" : base)
                .defaultHeader("apikey", key)
                .build();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Deletes the user from Supabase Auth (sign-in identities, sessions). An already-deleted user counts as done. */
    public void deleteUser(UUID id) {
        if (!enabled) {
            throw new AdminException("Supabase admin access isn't configured");
        }
        try {
            client.delete().uri("/auth/v1/admin/users/{id}", id)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().value() != 404) {
                            throw new AdminException("Supabase refused the deletion (HTTP " + response.getStatusCode().value() + ")");
                        }
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Supabase user deletion failed: {}", e.getClass().getSimpleName());
            throw new AdminException("Supabase couldn't be reached");
        }
    }

    public static class AdminException extends RuntimeException {
        AdminException(String message) {
            super(message);
        }
    }
}
