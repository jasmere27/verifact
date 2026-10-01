package com.ai.agent.verifact.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One entry in an account's check history (ADR-20): a report the user checked, created by them or reused. */
@Entity
@Table(name = "account_checks")
public class AccountCheck {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "verification_id", nullable = false)
    private UUID verificationId;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(name = "overall_verdict", nullable = false, length = 32)
    private String overallVerdict;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    protected AccountCheck() {
    }

    public AccountCheck(UUID id, UUID userId, UUID verificationId, OffsetDateTime checkedAt, String overallVerdict, String label) {
        this.id = id;
        this.userId = userId;
        this.verificationId = verificationId;
        this.checkedAt = checkedAt;
        this.overallVerdict = overallVerdict;
        this.label = label;
    }

    /** Checking the same report again moves it to the top. */
    void checkedAgain(OffsetDateTime at) {
        this.checkedAt = at;
    }

    public UUID getVerificationId() {
        return verificationId;
    }

    public OffsetDateTime getCheckedAt() {
        return checkedAt;
    }

    public String getOverallVerdict() {
        return overallVerdict;
    }

    public String getLabel() {
        return label;
    }
}
