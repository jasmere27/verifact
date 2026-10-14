package com.ai.agent.verifact.intelligence.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Plans from {@code app.plans.*} (defaults in application.properties). The trial grants {@code trialPlan}'s
 * entitlements for {@code trialDays} once per organisation; 0 days disables trials.
 */
@ConfigurationProperties("app.billing")
public class PlanCatalog {

    public static class PlanProperties {
        private String name;
        private BigDecimal priceUsdMonthly = BigDecimal.ZERO;
        private String summary = "";
        private int maxProjects;
        private int maxTermsPerProject;
        private int minCheckIntervalHours = 24;
        private int monitoringRunsPerMonth;
        private int investigationsPerMonth;
        private int responseDraftsPerMonth;
        private int reportsPerMonth;
        private int seats = 1;
        private int maxRssFeedsPerProject;
        private String providerPriceId = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPriceUsdMonthly() { return priceUsdMonthly; }
        public void setPriceUsdMonthly(BigDecimal priceUsdMonthly) { this.priceUsdMonthly = priceUsdMonthly; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public int getMaxProjects() { return maxProjects; }
        public void setMaxProjects(int maxProjects) { this.maxProjects = maxProjects; }
        public int getMaxTermsPerProject() { return maxTermsPerProject; }
        public void setMaxTermsPerProject(int maxTermsPerProject) { this.maxTermsPerProject = maxTermsPerProject; }
        public int getMinCheckIntervalHours() { return minCheckIntervalHours; }
        public void setMinCheckIntervalHours(int minCheckIntervalHours) { this.minCheckIntervalHours = minCheckIntervalHours; }
        public int getMonitoringRunsPerMonth() { return monitoringRunsPerMonth; }
        public void setMonitoringRunsPerMonth(int monitoringRunsPerMonth) { this.monitoringRunsPerMonth = monitoringRunsPerMonth; }
        public int getInvestigationsPerMonth() { return investigationsPerMonth; }
        public void setInvestigationsPerMonth(int investigationsPerMonth) { this.investigationsPerMonth = investigationsPerMonth; }
        public int getResponseDraftsPerMonth() { return responseDraftsPerMonth; }
        public void setResponseDraftsPerMonth(int responseDraftsPerMonth) { this.responseDraftsPerMonth = responseDraftsPerMonth; }
        public int getReportsPerMonth() { return reportsPerMonth; }
        public void setReportsPerMonth(int reportsPerMonth) { this.reportsPerMonth = reportsPerMonth; }
        public int getSeats() { return seats; }
        public void setSeats(int seats) { this.seats = seats; }
        public int getMaxRssFeedsPerProject() { return maxRssFeedsPerProject; }
        public void setMaxRssFeedsPerProject(int maxRssFeedsPerProject) { this.maxRssFeedsPerProject = maxRssFeedsPerProject; }
        public String getProviderPriceId() { return providerPriceId; }
        public void setProviderPriceId(String providerPriceId) { this.providerPriceId = providerPriceId; }
    }

    private Map<String, PlanProperties> plans = new LinkedHashMap<>();
    private String trialPlan = "intelligence";
    private int trialDays = 14;

    public Map<String, PlanProperties> getPlans() { return plans; }
    public void setPlans(Map<String, PlanProperties> plans) { this.plans = plans; }
    public String getTrialPlan() { return trialPlan; }
    public void setTrialPlan(String trialPlan) { this.trialPlan = trialPlan; }
    public int getTrialDays() { return trialDays; }
    public void setTrialDays(int trialDays) { this.trialDays = trialDays; }

    public List<Plan> all() {
        return plans.keySet().stream().map(this::find).flatMap(Optional::stream)
                .sorted(Comparator.comparing(Plan::priceUsdMonthly)).toList();
    }

    public Optional<Plan> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.toLowerCase(Locale.ROOT);
        PlanProperties p = plans.get(key);
        if (p == null) {
            return Optional.empty();
        }
        return Optional.of(new Plan(key, p.getName() == null ? key : p.getName(), p.getPriceUsdMonthly(), p.getSummary(),
                p.getMaxProjects(), p.getMaxTermsPerProject(), Math.max(1, p.getMinCheckIntervalHours()), p.getMonitoringRunsPerMonth(),
                p.getInvestigationsPerMonth(), p.getResponseDraftsPerMonth(), p.getReportsPerMonth(), Math.max(1, p.getSeats()),
                p.getMaxRssFeedsPerProject(), p.getProviderPriceId()));
    }

    /** The plan whose provider price id this is (for webhooks), if any. */
    public Optional<Plan> byProviderPriceId(String priceId) {
        if (priceId == null || priceId.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(p -> priceId.equals(p.providerPriceId())).findFirst();
    }
}
