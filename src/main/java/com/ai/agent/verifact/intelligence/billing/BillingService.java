package com.ai.agent.verifact.intelligence.billing;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Checkout, plan changes, cancellation and webhook processing (idempotent per provider event id). */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public enum WebhookOutcome { APPLIED, DUPLICATE, IGNORED }

    private final BillingProvider provider;
    private final SubscriptionService subscriptions;
    private final PlanCatalog catalog;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public BillingService(BillingProvider provider, SubscriptionService subscriptions, PlanCatalog catalog, AuditLog audit,
                          JdbcClient jdbc, Clock clock) {
        this.provider = provider;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean onlinePaymentAvailable() {
        return provider.configured();
    }

    /**
     * New subscription: a hosted checkout URL. Existing paid subscription: the provider switches plans (the
     * webhook that follows updates our record), and null is returned.
     */
    public String choosePlan(OrgContext ctx, String planId, String email) {
        Plan plan = catalog.find(planId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan."));
        if (!provider.configured() || !plan.purchasable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Online payment isn't set up yet. Please contact us to subscribe.");
        }
        SubscriptionService.Subscription current = subscriptions.current(ctx.orgId());
        Optional<String> subscriptionId = providerSubscriptionId(ctx.orgId());
        boolean paid = current.status() == SubscriptionStatus.ACTIVE || current.status() == SubscriptionStatus.PAST_DUE;
        if (paid && subscriptionId.isPresent()) {
            if (current.plan() != null && current.plan().id().equals(plan.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "This workspace is already on the " + plan.name() + " plan.");
            }
            provider.changePlan(subscriptionId.get(), plan);
            audit.record(ctx, "billing.plan_change_requested", "plan", plan.id(), "Requested change to " + plan.name());
            return null;
        }
        String url = provider.createCheckout(ctx.orgId(), plan, email, subscriptions.providerCustomerId(ctx.orgId()).orElse(null));
        audit.record(ctx, "billing.checkout_started", "plan", plan.id(), null);
        return url;
    }

    public void cancel(OrgContext ctx) {
        String id = providerSubscriptionId(ctx.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "This workspace has no paid subscription to cancel."));
        provider.cancelAtPeriodEnd(id);
        audit.record(ctx, "billing.cancel_requested", "subscription", null, "Cancels at the end of the billing period");
    }

    public Optional<String> portal(OrgContext ctx) {
        if (!provider.configured()) {
            return Optional.empty();
        }
        return subscriptions.providerCustomerId(ctx.orgId())
                .flatMap(c -> provider.customerPortalUrl(c, providerSubscriptionId(ctx.orgId()).orElse(null)));
    }

    /** @throws BillingProvider.InvalidSignatureException for unverified requests (nothing is recorded) */
    public WebhookOutcome handleWebhook(Map<String, String> headers, byte[] rawBody) {
        if (!provider.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing isn't configured.");
        }
        BillingProvider.WebhookEvent event = provider.parseVerifiedWebhook(headers, rawBody);
        boolean seen = jdbc.sql("SELECT COUNT(*) FROM billing_events WHERE provider = ? AND event_id = ?")
                .params(provider.name(), event.eventId()).query(Integer.class).single() > 0;
        if (seen) {
            return WebhookOutcome.DUPLICATE;
        }
        UUID org = null;
        WebhookOutcome outcome = WebhookOutcome.IGNORED;
        BillingProvider.SubscriptionUpdate u = event.subscription();
        if (u != null) {
            org = u.organizationId() != null ? u.organizationId() : subscriptions.orgForProviderSubscription(u.subscriptionId()).orElse(null);
            if (org != null) {
                Plan plan = catalog.byProviderPriceId(u.priceId()).orElse(null);
                try {
                    // Applying the same state twice is harmless, so a concurrent duplicate delivery is safe too.
                    subscriptions.applyProviderState(org, provider.name(), u.customerId(), u.subscriptionId(), plan, u.status(),
                            u.currentPeriodEnd(), u.cancelAtPeriodEnd(), event.occurredAt());
                    outcome = WebhookOutcome.APPLIED;
                    log.info("Billing event {} applied: status={} plan={}", event.type(), u.status(), plan == null ? null : plan.id());
                } catch (DataIntegrityViolationException e) {
                    log.warn("Billing event {} names an unknown organisation; ignored", event.type());
                    org = null;
                }
            } else {
                log.warn("Billing event {} without a known organisation; ignored", event.type());
            }
        }
        try {
            jdbc.sql("INSERT INTO billing_events (provider, event_id, event_type, organization_id, occurred_at, received_at, outcome) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(provider.name(), event.eventId(), event.type(), org, Db.ts(event.occurredAt()), Db.ts(clock.instant()), outcome.name())
                    .update();
        } catch (DuplicateKeyException e) {
            return WebhookOutcome.DUPLICATE;
        }
        return outcome;
    }

    private Optional<String> providerSubscriptionId(UUID orgId) {
        return jdbc.sql("SELECT provider_subscription_id FROM org_subscriptions WHERE organization_id = ?").param(orgId)
                .query(String.class).optional().filter(s -> s != null && !s.isBlank());
    }
}
