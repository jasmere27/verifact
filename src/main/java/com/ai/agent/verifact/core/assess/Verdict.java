package com.ai.agent.verifact.core.assess;

import java.util.Locale;

/** Assessment of a single claim, decided from retrieved evidence (see ADR-4). */
public enum Verdict {
    /** Credible evidence confirms the claim. */
    SUPPORTED,
    /** Parts are confirmed, but some details are wrong or unconfirmed. */
    PARTLY_SUPPORTED,
    /** The facts may be accurate but the framing or context gives a false impression. */
    MISLEADING,
    /** Credible evidence contradicts the claim. */
    CONTRADICTED,
    /** The evidence found doesn't settle it either way. */
    INSUFFICIENT_EVIDENCE;

    /** Lenient parse of model output; anything unrecognised is treated as not established. */
    public static Verdict parse(String value) {
        if (value == null) {
            return INSUFFICIENT_EVIDENCE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Verdict v : values()) {
            if (v.name().equals(normalized)) {
                return v;
            }
        }
        return INSUFFICIENT_EVIDENCE;
    }
}
