package com.ai.agent.verifact.intelligence.billing;

import com.ai.agent.verifact.ai.UsageScope;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriptions, entitlements and usage metering, all decided on the server. A paid feature runs only after
 * {@link #reserve} has checked the organisation's plan and this month's usage under a row lock (so concurrent
 * requests can't overspend the quota) and written the usage event. Usage months are calendar months in UTC.
 */
@Service
public class SubscriptionService {

    /** Read model of an organisation's subscription; {@code status} is effective now (an ended trial is EXPIRED). */
    public record Subscription(UUID organizationId, SubscriptionStatus status, Plan plan, String provider, Instant trialEndsAt,
                               Instant currentPeriodEnd, boolean cancelAtPeriodEnd, boolean trialAvailable) {

        @JsonProperty("entitled")
        public boolean entitled() {
            return plan != null && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING
                    || status == SubscriptionStatus.PAST_DUE);
        }
    }

    public record FeatureUsage(Feature feature, int used, int limit) {}

    public record Costs(int llmCalls, long promptTokens, long completionTokens, int searchCalls) {}

    public record Usage(Instant periodStart, Instant periodEnd, List<FeatureUsage> features, Costs costs,
                        Map<Feature, Costs> costsByFeature) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PlanCatalog catalog;
    private final Clock clock;

    public SubscriptionService(JdbcClient jdbc, TransactionTemplate tx, PlanCatalog catalog, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Subscription current(UUID orgId) {
        Instant now = clock.instant();
        return jdbc.sql("SELECT plan_id, status, provider, trial_started_at, trial_ends_at, current_period_end, cancel_at_period_end "
                        + "FROM org_subscriptions WHERE organization_id = ?")
                .param(orgId)
                .query((rs, i) -> {
                    SubscriptionStatus status = SubscriptionStatus.valueOf(rs.getString("status"));
                    Instant trialEnds = Db.instant(rs, "trial_ends_at");
                    if (status == SubscriptionStatus.TRIALING && trialEnds != null && !trialEnds.isAfter(now)) {
                        status = SubscriptionStatus.EXPIRED;
                    }
                    Plan plan = catalog.find(rs.getString("plan_id")).orElse(null);
                    boolean trialAvailable = rs.getObject("trial_started_at") == null && catalog.getTrialDays() > 0
                            && (status == SubscriptionStatus.NONE);
                    return new Subscription(orgId, status, plan, rs.getString("provider"), trialEnds,
                            Db.instant(rs, "current_period_end"), rs.getBoolean("cancel_at_period_end"), trialAvailable);
                })
                .optional()
                .orElseGet(() -> new Subscription(orgId, SubscriptionStatus.NONE, null, null, null, null, false, catalog.getTrialDays() > 0));
    }

    /** The plan whose entitlements apply now. 402 when the organisation has no active subscription or trial. */
    public Plan requirePlan(UUID orgId) {
        Subscription s = current(orgId);
        if (!s.entitled()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, switch (s.status()) {
                case EXPIRED -> "Your trial has ended. Choose a plan under Subscription to keep using VeriFact Intelligence.";
                case CANCELED -> "This workspace's subscription has ended. Choose a plan under Subscription to continue.";
                default -> "This workspace has no plan yet. Start a trial or choose a plan under Subscription.";
            });
        }
        return s.plan();
    }

    /** Starts the one free trial an organisation gets. */
    public Subscription startTrial(OrgContext ctx) {
        if (catalog.getTrialDays() <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Trials aren't available right now.");
        }
        Plan plan = catalog.find(catalog.getTrialPlan())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Trials aren't available right now."));
        Instant now = clock.instant();
        // One trial per person, not per workspace they create.
        int trialsByUser = ctx.userId() == null ? 0 : jdbc.sql("SELECT COUNT(*) FROM org_subscriptions s JOIN organizations o ON o.id = s.organization_id "
                        + "WHERE s.trial_started_at IS NOT NULL AND (o.created_by = ? OR o.personal_owner_id = ?)")
                .params(ctx.userId(), ctx.userId()).query(Integer.class).single();
        if (trialsByUser > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "You've already used a free trial in another workspace. Choose a plan to continue.");
        }
        ensureRow(ctx.orgId());
        Boolean started = tx.execute(s -> {
            int updated = jdbc.sql("UPDATE org_subscriptions SET plan_id = ?, status = 'TRIALING', trial_started_at = ?, "
                            + "trial_ends_at = ?, updated_at = ? WHERE organization_id = ? AND trial_started_at IS NULL AND status = 'NONE'")
                    .params(plan.id(), Db.ts(now), Db.ts(now.plus(Duration.ofDays(catalog.getTrialDays()))), Db.ts(now), ctx.orgId())
                    .update();
            return updated == 1;
        });
        if (!Boolean.TRUE.equals(started)) {
            throw new ApiException(HttpStatus.CONFLICT, "This workspace has already used its trial or has a subscription.");
        }
        return current(ctx.orgId());
    }

    /** Applies a verified billing-provider event to the organisation's subscription. */
    public void applyProviderState(UUID orgId, String provider, String customerId, String subscriptionId, Plan plan,
                                   SubscriptionStatus status, Instant periodEnd, boolean cancelAtPeriodEnd, Instant occurredAt) {
        ensureRow(orgId);
        tx.executeWithoutResult(s -> {
            // Out-of-order deliveries: an older event never overwrites a newer one.
            jdbc.sql("UPDATE org_subscriptions SET plan_id = COALESCE(?, plan_id), status = ?, provider = ?, "
                            + "provider_customer_id = COALESCE(?, provider_customer_id), provider_subscription_id = COALESCE(?, provider_subscription_id), "
                            + "current_period_end = ?, cancel_at_period_end = ?, last_event_at = ?, updated_at = ? "
                            + "WHERE organization_id = ? AND (last_event_at IS NULL OR last_event_at <= ?)")
                    .params(plan == null ? null : plan.id(), status.name(), provider, customerId, subscriptionId, Db.ts(periodEnd),
                            cancelAtPeriodEnd, Db.ts(occurredAt), Db.ts(clock.instant()), orgId, Db.ts(occurredAt))
                    .update();
        });
    }

    /** The organisation a provider subscription belongs to, for events that don't carry our id. */
    public Optional<UUID> orgForProviderSubscription(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT organization_id FROM org_subscriptions WHERE provider_subscription_id = ?")
                .param(subscriptionId).query(UUID.class).optional();
    }

    public Optional<String> providerCustomerId(UUID orgId) {
        return jdbc.sql("SELECT provider_customer_id FROM org_subscriptions WHERE organization_id = ?")
                .param(orgId).query(String.class).optional().filter(c -> c != null && !c.isBlank());
    }

    // ---------------------------------------------------------------- metering

    /**
     * Checks the plan and this month's quota for {@code feature} and records one use, atomically. Call before the
     * work starts (a failed paid call still cost money); attach what it cost with {@link #recordCost}.
     *
     * @return the usage event id
     * @throws ApiException 402 without a plan, 403 when the plan doesn't include the feature, 429 when this month's quota is used up
     */
    public UUID reserve(OrgContext ctx, Feature feature) {
        UUID id = UUID.randomUUID();
        ensureRow(ctx.orgId());
        tx.executeWithoutResult(s -> {
            // Serialises quota checks per organisation.
            jdbc.sql("SELECT organization_id FROM org_subscriptions WHERE organization_id = ? FOR UPDATE").param(ctx.orgId())
                    .query(UUID.class).single();
            Plan plan = requirePlan(ctx.orgId());
            int limit = plan.monthlyLimit(feature);
            if (limit <= 0) {
                throw new ApiException(HttpStatus.FORBIDDEN, "The " + plan.name() + " plan doesn't include " + label(feature)
                        + ". Upgrade under Subscription to use it.");
            }
            Instant start = monthStart();
            int used = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM usage_events WHERE organization_id = ? AND feature = ? AND created_at >= ?")
                    .params(ctx.orgId(), feature.name(), Db.ts(start)).query(Integer.class).single();
            if (used >= limit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "This workspace has used all " + limit + " " + label(feature)
                        + " included this month. The allowance resets on the 1st (UTC), or upgrade under Subscription.");
            }
            jdbc.sql("INSERT INTO usage_events (id, organization_id, feature, quantity, actor_id, created_at) VALUES (?, ?, ?, 1, ?, ?)")
                    .params(id, ctx.orgId(), feature.name(), ctx.userId(), Db.ts(clock.instant())).update();
        });
        return id;
    }

    public void recordCost(UUID usageId, UsageScope.Totals totals) {
        jdbc.sql("UPDATE usage_events SET llm_calls = llm_calls + ?, prompt_tokens = prompt_tokens + ?, "
                        + "completion_tokens = completion_tokens + ?, search_calls = search_calls + ? WHERE id = ?")
                .params(totals.llmCalls(), totals.promptTokens(), totals.completionTokens(), totals.searchCalls(), usageId)
                .update();
    }

    public Usage usage(UUID orgId) {
        Instant start = monthStart();
        Instant end = LocalDate.ofInstant(start, ZoneOffset.UTC).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Subscription s = current(orgId);
        Map<Feature, Integer> used = new EnumMap<>(Feature.class);
        Map<Feature, Costs> costs = new EnumMap<>(Feature.class);
        jdbc.sql("SELECT feature, SUM(quantity), SUM(llm_calls), SUM(prompt_tokens), SUM(completion_tokens), SUM(search_calls) "
                        + "FROM usage_events WHERE organization_id = ? AND created_at >= ? GROUP BY feature")
                .params(orgId, Db.ts(start))
                .query(rs -> {
                    Feature f;
                    try {
                        f = Feature.valueOf(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    used.put(f, rs.getInt(2));
                    costs.put(f, new Costs(rs.getInt(3), rs.getLong(4), rs.getLong(5), rs.getInt(6)));
                });
        List<FeatureUsage> features = java.util.Arrays.stream(Feature.values())
                .map(f -> new FeatureUsage(f, used.getOrDefault(f, 0), s.entitled() ? s.plan().monthlyLimit(f) : 0)).toList();
        Costs total = costs.values().stream().reduce(new Costs(0, 0, 0, 0), (a, b) -> new Costs(a.llmCalls() + b.llmCalls(),
                a.promptTokens() + b.promptTokens(), a.completionTokens() + b.completionTokens(), a.searchCalls() + b.searchCalls()));
        return new Usage(start, end, features, total, costs);
    }

    private Instant monthStart() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Outside any transaction: a duplicate-key failure would abort a surrounding Postgres transaction. */
    private void ensureRow(UUID orgId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM org_subscriptions WHERE organization_id = ?").param(orgId)
                .query(Integer.class).single() > 0;
        if (exists) {
            return;
        }
        try {
            jdbc.sql("INSERT INTO org_subscriptions (organization_id, status, updated_at) VALUES (?, 'NONE', ?)")
                    .params(orgId, Db.ts(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            // Created concurrently; fine.
        }
    }

    static String label(Feature f) {
        return switch (f) {
            case MONITORING_RUN -> "monitoring runs";
            case INVESTIGATION -> "investigations";
            case RESPONSE_DRAFT -> "Response Studio drafts";
            case REPORT -> "reports";
        };
    }
}
