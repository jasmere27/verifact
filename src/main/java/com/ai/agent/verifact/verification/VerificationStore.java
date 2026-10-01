package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.common.EditTokens;
import com.ai.agent.verifact.common.Retention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Saves and loads reports. Saving is best-effort: a database outage must not lose the user's result.
 * Reports are deleted {@link Retention#PERIOD} after creation, or earlier by the browser that ran the check.
 */
@Component
public class VerificationStore {

    private static final Logger log = LoggerFactory.getLogger(VerificationStore.class);

    private final VerificationRecordRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public VerificationStore(VerificationRecordRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public void save(VerificationResult result) {
        save(result, null);
    }

    /** @param inputHash lets a later identical input reuse this report; null to never reuse it */
    public void save(VerificationResult result, String inputHash) {
        try {
            repository.save(new VerificationRecord(
                    result.id(),
                    result.createdAt().atOffset(ZoneOffset.UTC),
                    result.inputType().name(),
                    result.overallVerdict().name(),
                    result.searchProvider(),
                    result.durationMs(),
                    jsonMapper.writeValueAsString(result),
                    inputHash));
        } catch (RuntimeException e) {
            log.error("Failed to store verification {}", result.id(), e);
        }
    }

    /** The newest report for the same input created after {@code since}; empty on any problem. */
    public Optional<VerificationResult> findRecent(String inputHash, Instant since) {
        try {
            return repository.findFirstByInputHashAndCreatedAtAfterOrderByCreatedAtDesc(
                            inputHash, since.atOffset(ZoneOffset.UTC))
                    .flatMap(this::read);
        } catch (RuntimeException e) {
            // Reuse is an optimisation: if the database is down, just run a fresh check.
            log.warn("Could not look up a recent report: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Empty if unknown, or if a stored report can no longer be read (e.g. after a format change). */
    public Optional<VerificationResult> find(UUID id) {
        return repository.findById(id).flatMap(this::read);
    }

    private Optional<VerificationResult> read(VerificationRecord record) {
        try {
            return Optional.of(jsonMapper.readValue(record.getResultJson(), VerificationResult.class));
        } catch (RuntimeException e) {
            log.error("Stored verification {} could not be read", record.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * Lets the browser that ran a check delete its report: stores the token's hash, but only on a report
     * created by this request. A reused report (created before {@code requestStartedAt}) belongs to
     * whoever ran it first and is left alone. Best-effort, like saving.
     */
    public void attachEditToken(UUID id, String token, Instant requestStartedAt) {
        if (token == null) {
            return;
        }
        try {
            repository.findById(id)
                    .filter(r -> r.getEditTokenHash() == null && !r.getCreatedAt().toInstant().isBefore(requestStartedAt))
                    .ifPresent(r -> {
                        r.setEditTokenHash(EditTokens.hash(token));
                        repository.save(r);
                    });
        } catch (RuntimeException e) {
            log.warn("Could not attach an edit token to report {}: {}", id, e.getClass().getSimpleName());
        }
    }

    /** @throws ApiException 404 unknown id, 403 wrong or missing token (or a report nobody can delete) */
    @Transactional
    public void delete(UUID id, String token) {
        VerificationRecord record = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That report doesn't exist or was already deleted."));
        if (!EditTokens.matches(token, record.getEditTokenHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the browser that ran this check can delete its report.");
        }
        repository.delete(record);
    }

    /** Daily: reports (and their feedback) older than the retention period are deleted. */
    @Scheduled(cron = "${app.retention.cleanup-cron:0 27 3 * * *}")
    public void deleteExpired() {
        int n = repository.deleteCreatedBefore(clock.instant().minus(Retention.PERIOD).atOffset(ZoneOffset.UTC));
        if (n > 0) {
            log.info("Deleted {} reports past retention", n);
        }
    }
}
