package com.ai.agent.verifact.intelligence.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts due monitoring runs every minute while the service is up. A host that sleeps when idle (Render's free
 * plan) runs nothing while asleep; an external cron calling {@code POST /api/v2/internal/monitoring/tick} (see
 * MonitoringTickController) wakes it and dispatches the same way. Missed checks are caught up on the next tick,
 * and the run records how late it started.
 */
@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final MonitoringRunner runner;
    private final boolean enabled;
    private final int batch;

    public MonitoringScheduler(MonitoringRunner runner, @Value("${app.monitoring.scheduler-enabled:true}") boolean enabled,
                               @Value("${app.monitoring.batch:5}") int batch) {
        this.runner = runner;
        this.enabled = enabled;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${app.monitoring.tick-millis:60000}", initialDelayString = "${app.monitoring.initial-delay-millis:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        tickNow();
    }

    /** @return how many runs were started */
    public int tickNow() {
        try {
            int interrupted = runner.failInterruptedRuns();
            if (interrupted > 0) {
                log.warn("Marked {} interrupted monitoring runs as failed", interrupted);
            }
            return runner.dispatchDue(batch);
        } catch (RuntimeException e) {
            // A database blip must not stop future ticks.
            log.warn("Monitoring tick failed: {}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
