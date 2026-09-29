package com.ai.agent.verifact.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Anonymous user feedback on a report. Stored only; never shown publicly. */
@Entity
@Table(name = "verification_feedback")
public class VerificationFeedback {

    @Id
    private UUID id;

    @Column(name = "verification_id", nullable = false)
    private UUID verificationId;

    @Column(name = "helpful", nullable = false)
    private boolean helpful;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private FeedbackReason reason;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected VerificationFeedback() {
    }

    public VerificationFeedback(UUID id, UUID verificationId, boolean helpful, FeedbackReason reason, String comment,
                                OffsetDateTime createdAt) {
        this.id = id;
        this.verificationId = verificationId;
        this.helpful = helpful;
        this.reason = reason;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public UUID getVerificationId() {
        return verificationId;
    }

    public boolean isHelpful() {
        return helpful;
    }

    public FeedbackReason getReason() {
        return reason;
    }

    public String getComment() {
        return comment;
    }
}
