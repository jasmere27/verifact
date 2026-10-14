package com.ai.agent.verifact.intelligence.billing;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment provider. Payment state only ever changes through {@link #parseVerifiedWebhook verified webhooks}:
 * starting a checkout, changing plans or cancelling asks the provider, and the provider's event then updates the
 * subscription. Nothing is unlocked because the browser says a payment happened.
 */
public interface BillingProvider {

    String name();

    /** False when credentials are missing: billing endpoints answer 503 and nothing is faked. */
    boolean configured();

    /** A hosted checkout URL for a new subscription to {@code plan}, tagged with our organisation id. */
    String createCheckout(UUID orgId, Plan plan, String customerEmail, String customerId);

    /** Switches an existing subscription to another plan (prorated by the provider). */
    void changePlan(String subscriptionId, Plan plan);

    /** Cancels at the end of the current billing period. */
    void cancelAtPeriodEnd(String subscriptionId);

    /** A provider-hosted page where the customer updates payment details and sees invoices, if the provider has one. */
    Optional<String> customerPortalUrl(String customerId, String subscriptionId);

    /**
     * Verifies the signature over the exact raw body and parses the event.
     *
     * @throws InvalidSignatureException when the signature is missing, wrong or outside the replay window
     */
    WebhookEvent parseVerifiedWebhook(Map<String, String> headers, byte[] rawBody);

    /** A subscription-relevant event; {@code subscription} is null for events we don't act on. */
    record WebhookEvent(String eventId, String type, Instant occurredAt, SubscriptionUpdate subscription) {}

    /** The provider's view of one subscription after the event. */
    record SubscriptionUpdate(String subscriptionId, String customerId, UUID organizationId, String priceId,
                              SubscriptionStatus status, Instant currentPeriodEnd, boolean cancelAtPeriodEnd) {}

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
