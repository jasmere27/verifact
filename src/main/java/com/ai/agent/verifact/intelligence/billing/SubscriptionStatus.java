package com.ai.agent.verifact.intelligence.billing;

/** An organisation's subscription state; only TRIALING (before its end), ACTIVE and PAST_DUE grant entitlements. */
public enum SubscriptionStatus {
    /** Never subscribed or trialled. */
    NONE,
    TRIALING,
    ACTIVE,
    /** A renewal payment failed; the provider is retrying. Access continues during the provider's retry period. */
    PAST_DUE,
    CANCELED,
    /** The trial ended without a subscription. */
    EXPIRED
}
