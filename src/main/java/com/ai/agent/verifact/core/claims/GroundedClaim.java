package com.ai.agent.verifact.core.claims;

import java.util.List;

/**
 * A claim that passed {@link ClaimGrounder}: its quote is in the input, and so are its quoted words and speaker
 * when present. Empty strings mean "none".
 *
 * @param type          one of the profile's types
 * @param searchQueries 1-2 cleaned queries
 */
public record GroundedClaim(String type, String quote, String claim, String speaker, String quotedWords,
                            List<String> searchQueries) {}
