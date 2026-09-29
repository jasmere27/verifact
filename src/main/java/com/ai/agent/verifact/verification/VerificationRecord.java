package com.ai.agent.verifact.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A stored verification report. The full {@link VerificationResult} is kept as JSON so reports
 * render exactly as first shown; the indexed columns exist for querying and operations.
 */
@Entity
@Table(name = "verifications")
public class VerificationRecord {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "input_type", nullable = false, length = 16)
    private String inputType;

    @Column(name = "overall_verdict", nullable = false, length = 32)
    private String overallVerdict;

    @Column(name = "search_provider", length = 32)
    private String searchProvider;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    private String resultJson;

    /** SHA-256 of the normalised input; null when the report must never be reused. */
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    protected VerificationRecord() {
    }

    public VerificationRecord(UUID id, OffsetDateTime createdAt, String inputType, String overallVerdict,
                              String searchProvider, long durationMs, String resultJson, String inputHash) {
        this.id = id;
        this.createdAt = createdAt;
        this.inputType = inputType;
        this.overallVerdict = overallVerdict;
        this.searchProvider = searchProvider;
        this.durationMs = durationMs;
        this.resultJson = resultJson;
        this.inputHash = inputHash;
    }

    public UUID getId() {
        return id;
    }

    public String getResultJson() {
        return resultJson;
    }
}
