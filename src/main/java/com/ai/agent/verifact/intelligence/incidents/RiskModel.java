package com.ai.agent.verifact.intelligence.incidents;

import com.ai.agent.verifact.evidence.SourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A transparent, rule-based priority score (0-100) for an incident. It is a triage aid, not a probability and
 * not statistically calibrated: every point comes from a named factor with its reason, and authorised people
 * can override the resulting priority. Reach is never estimated: "spread" counts only the distinct sites our own
 * monitoring has actually seen the claim on.
 */
public final class RiskModel {

    public enum Priority { CRITICAL, HIGH, MEDIUM, LOW }

    /** Where the evidence stands for the incident's claim (from its latest investigation). */
    public enum EvidenceState { NOT_INVESTIGATED, UNRESOLVED, SUPPORTED, CONTRADICTED, MISSING_CONTEXT }

    /**
     * @param severity     HIGH/MEDIUM/LOW: the AI's reading of the allegation (labelled as such), or the analyst's; null = not assessed
     * @param sourceType   the discovery source's type, classified from its domain; null for manual incidents without a link
     * @param relevance    HIGH/MEDIUM/LOW from term matching; null for manual incidents
     */
    public record Inputs(String severity, SourceType sourceType, int distinctSites, Instant lastSeenAt, String relevance,
                         EvidenceState evidence) {}

    public record Factor(String name, int points, int max, String reason) {}

    public record Assessment(int score, Priority priority, List<Factor> factors, Inputs inputs, String method) {}

    static final String METHOD = "Rule-based triage score from the factors listed. It is not a probability and has not been "
            + "statistically calibrated; review it and override the priority when you know more.";

    private RiskModel() {
    }

    public static Assessment assess(Inputs in, Instant now) {
        List<Factor> f = new ArrayList<>();
        f.add(switch (in.severity() == null ? "" : in.severity()) {
            case "HIGH" -> new Factor("Allegation severity", 30, 30, "Serious allegation (safety, legal, fraud, data or misconduct) — AI assessment of the claim's wording.");
            case "MEDIUM" -> new Factor("Allegation severity", 18, 30, "Moderate allegation (quality, service, employment or pricing) — AI assessment of the claim's wording.");
            case "LOW" -> new Factor("Allegation severity", 8, 30, "Minor allegation — AI assessment of the claim's wording.");
            default -> new Factor("Allegation severity", 12, 30, "Severity not assessed yet.");
        });
        SourceType st = in.sourceType();
        f.add(st == null ? new Factor("Source type", 5, 15, "No discovery source recorded.")
                : switch (st) {
            case NEWS -> new Factor("Source type", 15, 15, "Published by a news outlet (classified from its domain).");
            case GOVERNMENT -> new Factor("Source type", 15, 15, "Published on a government site.");
            case FACT_CHECKER -> new Factor("Source type", 10, 15, "Discussed by a fact-checking organisation.");
            case SOCIAL -> new Factor("Source type", 8, 15, "Posted on a social or user-generated platform.");
            case ACADEMIC, REFERENCE -> new Factor("Source type", 8, 15, "Published on a reference or academic site.");
            default -> new Factor("Source type", 5, 15, "Published on another website or blog.");
        });
        int sites = Math.max(in.distinctSites(), 0);
        int spread = sites >= 5 ? 20 : sites >= 3 ? 12 : sites == 2 ? 8 : 0;
        f.add(new Factor("Observed spread", spread, 20, sites <= 1
                ? "Seen on one site by monitoring so far."
                : "Seen on " + sites + " different sites by monitoring (no audience or engagement estimate)."));
        int recency = 0;
        String recencyReason = "Not seen recently.";
        if (in.lastSeenAt() != null) {
            Duration age = Duration.between(in.lastSeenAt(), now);
            if (age.compareTo(Duration.ofHours(24)) <= 0) {
                recency = 10;
                recencyReason = "Seen in the last 24 hours.";
            } else if (age.compareTo(Duration.ofDays(7)) <= 0) {
                recency = 5;
                recencyReason = "Seen in the last 7 days.";
            }
        }
        f.add(new Factor("Recency", recency, 10, recencyReason));
        f.add(switch (in.relevance() == null ? "" : in.relevance()) {
            case "HIGH" -> new Factor("Business relevance", 15, 15, "Names the brand together with a monitored keyword or topic of concern.");
            case "MEDIUM" -> new Factor("Business relevance", 10, 15, "Names the brand in the headline.");
            case "LOW" -> new Factor("Business relevance", 4, 15, "Mentions the brand in passing.");
            default -> new Factor("Business relevance", 10, 15, "Added by a team member.");
        });
        EvidenceState ev = in.evidence() == null ? EvidenceState.NOT_INVESTIGATED : in.evidence();
        f.add(switch (ev) {
            case NOT_INVESTIGATED -> new Factor("Evidence status", 8, 10, "Not investigated yet.");
            case UNRESOLVED -> new Factor("Evidence status", 8, 10, "The investigation found insufficient or mixed evidence.");
            case SUPPORTED -> new Factor("Evidence status", 10, 10, "Available evidence supports the claim: a response needs care.");
            case MISSING_CONTEXT -> new Factor("Evidence status", 6, 10, "Evidence suggests the claim is outdated or missing context.");
            case CONTRADICTED -> new Factor("Evidence status", 2, 10, "Available evidence contradicts the claim: corrective information may suffice.");
        });
        int score = Math.min(100, f.stream().mapToInt(Factor::points).sum());
        Priority p = score >= 65 ? Priority.CRITICAL : score >= 45 ? Priority.HIGH : score >= 25 ? Priority.MEDIUM : Priority.LOW;
        return new Assessment(score, p, List.copyOf(f), in, METHOD);
    }
}
