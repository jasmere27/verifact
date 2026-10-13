package com.ai.agent.verifact.intelligence.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One execution of a project's connectors, with per-source outcomes (source health). */
public record MonitoringRun(UUID id, UUID projectId, String projectName, String trigger, String status, int attempt,
                            Instant scheduledFor, Instant startedAt, Instant finishedAt, int resultsFound, int newMentions,
                            int newIncidents, List<SourceOutcome> sources, String errorSummary) {

    /** @param status OK, PARTIAL, FAILED, UNAVAILABLE (not configured in this deployment) */
    public record SourceOutcome(String connector, String status, int items, int relevant, String detail) {}
}
