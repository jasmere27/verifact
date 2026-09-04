package com.ai.agent.verifact.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "fact_check_results")
public class FactCheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 16)
    private InputType inputType;

    @Column(name = "original_input", nullable = false, columnDefinition = "text")
    private String originalInput;

    @Column(name = "classification", length = 32)
    private String classification;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "sources", columnDefinition = "text")
    private String sources;

    @Column(name = "cybersecurity_tips", columnDefinition = "text")
    private String cybersecurityTips;

    @Column(name = "full_response", nullable = false, columnDefinition = "text")
    private String fullResponse;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public FactCheckResult() {
    }

    public Long getId() {
        return id;
    }

    public InputType getInputType() {
        return inputType;
    }

    public void setInputType(InputType inputType) {
        this.inputType = inputType;
    }

    public String getOriginalInput() {
        return originalInput;
    }

    public void setOriginalInput(String originalInput) {
        this.originalInput = originalInput;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public Integer getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Integer confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public String getCybersecurityTips() {
        return cybersecurityTips;
    }

    public void setCybersecurityTips(String cybersecurityTips) {
        this.cybersecurityTips = cybersecurityTips;
    }

    public String getFullResponse() {
        return fullResponse;
    }

    public void setFullResponse(String fullResponse) {
        this.fullResponse = fullResponse;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
