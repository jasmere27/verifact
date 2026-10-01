package com.ai.agent.verifact.repository;

import com.ai.agent.verifact.common.Retention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;

/** Daily: deprecated v1 results (which include the submitted text) older than the retention period are deleted. */
@Component
public class FactCheckRetention {

    private static final Logger log = LoggerFactory.getLogger(FactCheckRetention.class);

    private final FactCheckResultRepository repository;
    private final Clock clock;

    public FactCheckRetention(FactCheckResultRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 27 3 * * *}")
    public void deleteExpired() {
        int n = repository.deleteCreatedBefore(clock.instant().minus(Retention.PERIOD).atOffset(ZoneOffset.UTC));
        if (n > 0) {
            log.info("Deleted {} v1 results past retention", n);
        }
    }
}
