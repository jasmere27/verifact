package com.ai.agent.verifact.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

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
        try {
            repository.save(new VerificationRecord(
                    result.id(),
                    result.createdAt().atOffset(ZoneOffset.UTC),
                    result.inputType().name(),
                    result.overallVerdict().name(),
                    result.searchProvider(),
                    result.durationMs(),
                    jsonMapper.writeValueAsString(result)));
        } catch (RuntimeException e) {
            log.error("Failed to store verification {}", result.id(), e);
        }
    }

    public Optional<VerificationResult> find(UUID id) {
        return repository.findById(id)
                .map(record -> jsonMapper.readValue(record.getResultJson(), VerificationResult.class));
    }
}
