package com.ai.agent.verifact.intelligence.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A retrieved item that matched a project. {@code claim} is an AI reading of the item (verified only in that its
 * quote is the item's own words); {@code claimSeverity} is an AI assessment.
 */
public record Mention(UUID id, UUID projectId, String projectName, String connector, String url, String domain, String title,
                      String snippet, String publishedDate, String relevance, List<String> matchedTerms, String claim,
                      String claimQuote, String claimSeverity, String status, UUID incidentId, Instant firstSeenAt,
                      Instant lastSeenAt, int seenCount) {

    public record Page(List<Mention> items, int page, int size, long total) {}
}
