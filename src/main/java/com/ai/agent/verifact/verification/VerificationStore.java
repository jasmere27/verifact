package com.ai.agent.verifact.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Saves and loads reports. Saving is best-effort: a database outage must not lose the user's result. */
@Component
public class VerificationStore {

    private static final Logger log = LoggerFactory.getLogger(VerificationStore.class);

    private final VerificationRecordRepository repository;
    private final JsonMapper jsonMapper;

    public VerificationStore(VerificationRecordRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
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
}
