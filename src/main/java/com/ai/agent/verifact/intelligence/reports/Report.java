package com.ai.agent.verifact.intelligence.reports;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A snapshot of a workspace's monitoring, incidents and investigations for a period. Built from stored data with
 * fixed templates: no model writes report text. AI readings included from earlier steps (mention claims) are
 * labelled as such.
 */
public record Report(UUID id, String title, String workspaceName, Instant periodStart, Instant periodEnd, Instant generatedAt,
                     String generatedBy, List<String> executiveSummary, Coverage coverage, MentionStats mentions,
                     List<MentionLine> importantMentions, IncidentStats incidents, List<IncidentLine> openIncidents,
                     List<Finding> findings, List<Theme> themes, List<EvidenceChange> evidenceChanges, List<String> recommendedActions,
                     List<String> unresolved, List<String> limitations, String disclaimer) {

    public record ProjectCoverage(String name, String brand, String status, List<String> sources, int runs, int failedRuns,
                                  Instant lastSuccessAt) {}

    public record Coverage(List<ProjectCoverage> projects, int runs, int failedRuns, int partialRuns) {}

    public record MentionStats(int total, Map<String, Integer> byRelevance, Map<String, Integer> bySource, int withClaims) {}

    /** @param aiClaim the AI's reading of the mention's claim (its quote was verified in the mention text), or null */
    public record MentionLine(String title, String url, String domain, String publishedDate, Instant firstSeenAt, String relevance,
                              String aiClaim) {}

    public record IncidentStats(int opened, int resolved, int openNow, Map<String, Integer> openByPriority, Map<String, Integer> openByStatus) {}

    public record IncidentLine(String title, String priority, int riskScore, String status, String owner, Instant firstSeenAt, int mentions) {}

    /** @param citationUrl the source of the verified excerpt, or null when no source settled the claim */
    public record Finding(String investigation, int version, String claim, String assessment, String excerpt, String citationUrl,
                          String citationDomain, Instant assessedAt) {}

    public record Theme(String term, int mentions) {}

    public record EvidenceChange(String investigation, int version, Instant at, String summary) {}

    public record Summary(UUID id, String title, Instant periodStart, Instant periodEnd, Instant generatedAt, String generatedBy) {}
}
