package com.ai.agent.verifact.verification;

/**
 * How well the verdict is backed, derived by rules the backend can explain (ADR-4), counting
 * distinct source domains cited in the verdict's direction.
 */
public enum EvidenceStrength {
    /** Three or more independent sources agree. */
    STRONG,
    /** Two independent sources, or stronger evidence with some disagreement. */
    MODERATE,
    /** One source, or none. */
    LIMITED
}
