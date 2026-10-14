package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * For an external scheduler (e.g. a cron service) on hosts that sleep when idle: dispatches due monitoring runs.
 * Requires {@code X-Cron-Secret} to equal MONITORING_CRON_SECRET; without a configured secret the endpoint
 * doesn't exist (404). It never runs a project that isn't due.
 */
@RestController
public class MonitoringTickController {

    private final MonitoringScheduler scheduler;
    private final byte[] secret;

    public MonitoringTickController(MonitoringScheduler scheduler, @Value("${app.monitoring.cron-secret:}") String secret) {
        this.scheduler = scheduler;
        this.secret = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/v2/internal/monitoring/tick")
    public Map<String, Integer> tick(@RequestHeader(value = "X-Cron-Secret", required = false) String provided) {
        if (secret.length == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found.");
        }
        if (provided == null || !MessageDigest.isEqual(secret, provided.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authorised.");
        }
        return Map.of("started", scheduler.tickNow());
    }
}
