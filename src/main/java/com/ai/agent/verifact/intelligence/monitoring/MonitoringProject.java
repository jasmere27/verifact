package com.ai.agent.verifact.intelligence.monitoring;

import java.time.Instant;
import java.util.UUID;

/**
 * A monitoring project as the API shows it.
 *
 * @param health derived from recent runs: NEVER_RUN, HEALTHY, DEGRADED (last run partial or one failure), FAILING (repeated failures), PAUSED
 */
public record MonitoringProject(UUID id, String name, String brand, MonitoringConfig config, Status status, int checkIntervalHours,
                                Instant nextRunAt, Instant lastRunAt, Instant lastSuccessAt, int consecutiveFailures,
                                Instant createdAt, Instant updatedAt, String health) {

    public enum Status { ACTIVE, PAUSED }
}
