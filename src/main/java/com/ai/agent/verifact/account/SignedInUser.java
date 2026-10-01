package com.ai.agent.verifact.account;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Who made the request, from a verified Supabase access token. Only the user id and email are read from the
 * token: roles and permissions come from our database, never from token metadata (users can edit theirs).
 */
public record SignedInUser(UUID id, String email) {

    public static SignedInUser from(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return new SignedInUser(UUID.fromString(jwt.getSubject()), email == null || email.isBlank() ? null : email.trim());
    }
}
