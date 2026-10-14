package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.billing.BillingProvider;
import com.ai.agent.verifact.intelligence.billing.BillingService;
import com.ai.agent.verifact.intelligence.billing.Plan;
import com.ai.agent.verifact.intelligence.billing.PlanCatalog;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Plans, subscription state, usage, trial, checkout and the provider webhook. Prices and limits come from the
 * server's plan catalog; the client never sends a price, and paid features unlock only through verified webhooks.
 */
@RestController
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    public record PlanChoice(String planId) {}

    /** @param checkoutUrl where to pay for a new subscription; null when an existing subscription was switched */
    public record ChoiceResult(String checkoutUrl, String message) {}

    public record BillingState(SubscriptionService.Subscription subscription, SubscriptionService.Usage usage, List<Plan> plans,
                               boolean onlinePayment) {}

    private final IntelligenceAccess access;
    private final SubscriptionService subscriptions;
    private final BillingService billing;
    private final PlanCatalog catalog;

    public BillingController(IntelligenceAccess access, SubscriptionService subscriptions, BillingService billing, PlanCatalog catalog) {
        this.access = access;
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.catalog = catalog;
    }

    /** Public: the price list. */
    @GetMapping("/api/v2/plans")
    public List<Plan> plans() {
        return catalog.all();
    }

    @GetMapping("/api/v2/me/orgs/{orgId}/billing")
    public BillingState state(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        OrgContext ctx = access.require(jwt, orgId, Role.MEMBER);
        return new BillingState(subscriptions.current(ctx.orgId()), subscriptions.usage(ctx.orgId()), catalog.all(), billing.onlinePaymentAvailable());
    }

    @PostMapping("/api/v2/me/orgs/{orgId}/billing/trial")
    public SubscriptionService.Subscription startTrial(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return subscriptions.startTrial(access.require(jwt, orgId, Role.ADMIN));
    }

    @PostMapping("/api/v2/me/orgs/{orgId}/billing/checkout")
    public ChoiceResult choose(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @RequestBody(required = false) PlanChoice r) {
        OrgContext ctx = access.require(jwt, orgId, Role.OWNER);
        String url = billing.choosePlan(ctx, r == null ? null : r.planId(), jwt.getClaimAsString("email"));
        return new ChoiceResult(url, url == null ? "Plan change requested. It takes effect when the payment provider confirms it." : null);
    }

    @PostMapping("/api/v2/me/orgs/{orgId}/billing/cancel")
    public Map<String, String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        billing.cancel(access.require(jwt, orgId, Role.OWNER));
        return Map.of("message", "Cancellation requested. Your plan stays active until the end of the billing period.");
    }

    @PostMapping("/api/v2/me/orgs/{orgId}/billing/portal")
    public Map<String, String> portal(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        String url = billing.portal(access.require(jwt, orgId, Role.OWNER)).orElseThrow(() ->
                new com.ai.agent.verifact.common.ApiException(HttpStatus.CONFLICT, "Billing history and payment details are available once this workspace has a paid subscription."));
        return Map.of("url", url);
    }

    /** Paddle notification destination. Unverified requests are refused and change nothing. */
    @PostMapping("/api/v2/billing/webhooks/paddle")
    public ResponseEntity<Map<String, String>> webhook(@RequestHeader Map<String, String> headers, @RequestBody(required = false) byte[] body) {
        try {
            BillingService.WebhookOutcome outcome = billing.handleWebhook(headers, body == null ? new byte[0] : body);
            return ResponseEntity.ok(Map.of("outcome", outcome.name()));
        } catch (BillingProvider.InvalidSignatureException e) {
            log.warn("Rejected billing webhook: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature"));
        }
    }
}
