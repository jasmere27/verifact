package com.ai.agent.verifact.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored capstone project (ADR-21): the owner and the project as JSON ({@link ProjectData}). */
@Entity
@Table(name = "research_projects")
public class ResearchProjectRecord {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "data_json", nullable = false, columnDefinition = "text")
    private String dataJson;

    protected ResearchProjectRecord() {
    }

    public ResearchProjectRecord(UUID id, UUID ownerId, OffsetDateTime createdAt, String dataJson) {
        this.id = id;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.dataJson = dataJson;
    }

    void update(String dataJson, OffsetDateTime at) {
        this.dataJson = dataJson;
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getDataJson() {
        return dataJson;
    }
}
