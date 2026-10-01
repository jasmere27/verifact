package com.ai.agent.verifact.core.claims;

import java.util.Set;

/**
 * A product's claim vocabulary and limits.
 *
 * @param types              the claim types the product uses
 * @param defaultType        used when the model's type isn't one of them
 * @param quoteType          the type whose quoted words must be in the input, or null if the product has none
 * @param unverifiedQuoteType what a quote becomes when its quoted words aren't in the input (e.g. an attribution)
 * @param maxClaims          claims kept, in the model's order
 */
public record ClaimProfile(Set<String> types, String defaultType, String quoteType, String unverifiedQuoteType,
                           int maxClaims) {

    public ClaimProfile {
        types = Set.copyOf(types);
        if (!types.contains(defaultType) || (quoteType != null && !types.contains(unverifiedQuoteType))) {
            throw new IllegalArgumentException("default and fallback types must be among the profile's types");
        }
    }
}
