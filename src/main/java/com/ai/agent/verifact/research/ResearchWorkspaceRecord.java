package com.ai.agent.verifact.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored workspace (JSON). Only the edit token's hash is kept. */
@Entity
@Table(name = "research_workspaces")
public class ResearchWorkspaceRecord {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "data_json", nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(name = "edit_token_hash", nullable = false, length = 64)
    private String editTokenHash;

    protected ResearchWorkspaceRecord() {
    }

    public ResearchWorkspaceRecord(UUID id, OffsetDateTime createdAt, OffsetDateTime expiresAt, String dataJson,
                                   String editTokenHash) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.expiresAt = expiresAt;
        this.dataJson = dataJson;
        this.editTokenHash = editTokenHash;
    }

    public UUID getId() {
        return id;
    }

    public String getDataJson() {
        return dataJson;
    }

    public String getEditTokenHash() {
        return editTokenHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void update(String dataJson, OffsetDateTime at, OffsetDateTime expiresAt) {
        this.dataJson = dataJson;
        this.updatedAt = at;
        this.expiresAt = expiresAt;
    }
}
