package com.ai.agent.verifact.news;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored NewsFact workspace (check + review as JSON). Only the edit token's hash is kept. */
@Entity
@Table(name = "news_reviews")
public class NewsReviewRecord {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    private String resultJson;

    @Column(name = "review_json", nullable = false, columnDefinition = "text")
    private String reviewJson;

    @Column(name = "edit_token_hash", nullable = false, length = 64)
    private String editTokenHash;

    protected NewsReviewRecord() {
    }

    public NewsReviewRecord(UUID id, OffsetDateTime createdAt, String sourceUrl, String resultJson, String reviewJson,
                            String editTokenHash) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.sourceUrl = sourceUrl;
        this.resultJson = resultJson;
        this.reviewJson = reviewJson;
        this.editTokenHash = editTokenHash;
    }

    public UUID getId() {
        return id;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getReviewJson() {
        return reviewJson;
    }

    public String getEditTokenHash() {
        return editTokenHash;
    }

    public void updateReview(String reviewJson, OffsetDateTime at) {
        this.reviewJson = reviewJson;
        this.updatedAt = at;
    }
}
