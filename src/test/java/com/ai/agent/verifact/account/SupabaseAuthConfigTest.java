package com.ai.agent.verifact.account;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Token checks against a JWKS served locally: only our project's signed-in users get through. */
class SupabaseAuthConfigTest {

    static final String BASE = "https://project.supabase.co";
    static final String ISSUER = BASE + "/auth/v1";
    static final String USER = "6f1c2a7e-5b9d-4c3e-8a1f-2d3b4c5e6f70";

    private RSAKey key;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(ISSUER + "/.well-known/jwks.json"))
                .andRespond(withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        decoder = SupabaseAuthConfig.decoder(BASE, rest);
    }

    private String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        return sign(key, change);
    }

    private static String sign(RSAKey signingKey, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(USER).audience("authenticated").claim("role", "authenticated")
                .claim("email", "reader@example.com").claim("is_anonymous", false)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void aSignedInUsersTokenFromOurProjectIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(token(c -> { }));

        SignedInUser user = SignedInUser.from(jwt);
        assertThat(user.id()).isEqualTo(UUID.fromString(USER));
        assertThat(user.email()).isEqualTo("reader@example.com");
    }

    @Test
    void expiredForeignAndNonUserTokensAreRejected() throws Exception {
        rejected(token(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
        rejected(token(c -> c.issuer("https://other.supabase.co/auth/v1")));
        rejected(token(c -> c.audience("some-other-app")));
        rejected(token(c -> c.claim("role", "anon")));
        rejected(token(c -> c.claim("role", "service_role")));
        rejected(token(c -> c.claim("is_anonymous", true)));
        rejected(token(c -> c.subject("not-a-uuid")));
        rejected(token(c -> c.subject(null)));
    }

    @Test
    void forgedTokensAreRejected() throws Exception {
        // Signed by a key that isn't the project's (unknown key id, then a known id with the wrong key).
        rejected(sign(new RSAKeyGenerator(2048).keyID("key-2").generate(), c -> { }));
        rejected(sign(new RSAKeyGenerator(2048).keyID("key-1").generate(), c -> { }));
        // Algorithm confusion: HS256 "signed" with the public key's bytes must not verify.
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).subject(USER).audience("authenticated").claim("role", "authenticated")
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600))).build());
        hs.sign(new MACSigner(java.util.Arrays.copyOf(key.toPublicJWK().toRSAPublicKey().getEncoded(), 64)));
        rejected(hs.serialize());
        // Unsigned.
        rejected(token(c -> { }).replaceAll("\\.[^.]+$", "."));
    }

    @Test
    void withoutAProjectUrlAccountsAreOffAndEveryTokenIsRejected() throws Exception {
        JwtDecoder off = new SupabaseAuthConfig().jwtDecoder(" ");

        assertThatThrownBy(() -> off.decode(token(c -> { }))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new SupabaseAuthConfig().jwtDecoder("http://project.supabase.co"))
                .isInstanceOf(IllegalStateException.class);
    }

    private void rejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
