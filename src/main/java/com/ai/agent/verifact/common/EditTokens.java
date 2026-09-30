package com.ai.agent.verifact.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Edit tokens for saved workspaces without accounts (NewsFact reviews, ResearchFact workspaces): a
 * random 256-bit token is shown once; only its SHA-256 is stored and compared in constant time.
 */
public final class EditTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private EditTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of a presented token with a stored hash. */
    public static boolean matches(String token, String storedHash) {
        return token != null && storedHash != null
                && MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
