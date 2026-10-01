package com.ai.agent.verifact.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

/**
 * Verifies Supabase Auth access tokens with the project's public signing keys (JWKS, asymmetric ES256/RS256).
 * The backend holds no signing secret, and the legacy shared-secret HS256 tokens are refused. Without
 * {@code SUPABASE_URL} accounts are off: every token is rejected and signed-out use is unaffected.
 */
@Configuration
public class SupabaseAuthConfig {

    static final String AUDIENCE = "authenticated";

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.auth.supabase-url:}") String supabaseUrl) {
        String base = supabaseUrl == null ? "" : supabaseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            return token -> {
                throw new BadJwtException("Accounts aren't enabled on this server");
            };
        }
        if (!base.startsWith("https://")) {
            throw new IllegalStateException("SUPABASE_URL must be an https:// URL");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return decoder(base, new RestTemplate(factory));
    }

    /** The project's keys are fetched from its JWKS endpoint, cached, and refetched for an unknown key id (rotation). */
    static NimbusJwtDecoder decoder(String base, RestOperations rest) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(base + "/auth/v1/.well-known/jwks.json")
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .restOperations(rest)
                .build();
        decoder.setJwtValidator(validator(base + "/auth/v1"));
        return decoder;
    }

    /**
     * Signature and expiry (60 s clock skew) are checked by the decoder's defaults; on top: our project as the
     * issuer, a signed-in (not anonymous) user, and a user id we can use as a key.
     */
    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Object>("aud", aud -> aud instanceof Collection<?> c ? c.contains(AUDIENCE) : AUDIENCE.equals(aud)),
                new JwtClaimValidator<Object>("role", AUDIENCE::equals),
                new JwtClaimValidator<Object>("is_anonymous", anonymous -> anonymous == null || Boolean.FALSE.equals(anonymous)),
                new JwtClaimValidator<Object>("sub", sub -> sub instanceof String s && isUuid(s)));
    }

    private static boolean isUuid(String s) {
        try {
            return UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
