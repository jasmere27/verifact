package com.ai.agent.verifact.intelligence.billing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * A subscription plan's price and entitlements, from configuration ({@code app.plans.<id>.*}), so prices and
 * quotas change without code. Limits of 0 switch a feature off for the plan.
 *
 * @param providerPriceId the billing provider's price id used at checkout (e.g. Paddle {@code pri_...}); blank = not purchasable yet
 */
public record Plan(String id, String name, BigDecimal priceUsdMonthly, String summary, int maxProjects, int maxTermsPerProject,
                   int minCheckIntervalHours, int monitoringRunsPerMonth, int investigationsPerMonth, int responseDraftsPerMonth,
                   int reportsPerMonth, int seats, int maxRssFeedsPerProject, String providerPriceId) {

    @JsonIgnore
    public int monthlyLimit(Feature feature) {
        return switch (feature) {
            case MONITORING_RUN -> monitoringRunsPerMonth;
            case INVESTIGATION -> investigationsPerMonth;
            case RESPONSE_DRAFT -> responseDraftsPerMonth;
            case REPORT -> reportsPerMonth;
        };
    }

    @JsonProperty("purchasable")
    public boolean purchasable() {
        return providerPriceId != null && !providerPriceId.isBlank();
    }
}
