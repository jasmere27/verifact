package com.ai.agent.verifact.intelligence.incidents;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Incident read models. */
public final class Incident {

    private Incident() {
    }

    public enum Status { NEW, NEEDS_REVIEW, INVESTIGATING, AWAITING_EVIDENCE, RESPONSE_DRAFTED, RESOLVED, MONITORING }

    public record Summary(UUID id, String title, String claim, Status status, RiskModel.Priority priority, int riskScore,
                          UUID projectId, String projectName, UUID ownerId, String ownerEmail, Instant firstSeenAt,
                          Instant lastSeenAt, Instant updatedAt, int mentionCount, UUID investigationId) {}

    public record Event(UUID id, String kind, String detail, UUID actorId, String actorEmail, Instant createdAt) {}

    public record LinkedMention(UUID id, String url, String domain, String title, String snippet, String publishedDate,
                                String connector, String relevance, String claimQuote, Instant firstSeenAt) {}

    public record LinkedInvestigation(UUID id, String title, String status, int version, Instant updatedAt) {}

    /**
     * @param risk             the rule-based assessment with its factors
     * @param suggestedSteps   rule-based suggestions from the incident's state
     */
    public record Detail(Summary summary, String discoveryUrl, RiskModel.Assessment risk, boolean priorityOverride,
                         String nextSteps, List<String> suggestedSteps, List<Event> events, List<LinkedMention> mentions,
                         List<Summary> related, List<LinkedInvestigation> investigations) {}

    public record Page(List<Summary> items, int page, int size, long total) {}
}
