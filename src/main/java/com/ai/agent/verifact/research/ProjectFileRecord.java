package com.ai.agent.verifact.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored project file (ADR-23): a small summary and the full detail (text and analysis), both JSON. */
@Entity
@Table(name = "research_project_files")
public class ProjectFileRecord {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "label", length = 80)
    private String label;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "summary_json", nullable = false, columnDefinition = "text")
    private String summaryJson;

    @Column(name = "detail_json", nullable = false, columnDefinition = "text")
    private String detailJson;

    protected ProjectFileRecord() {
    }

    public ProjectFileRecord(UUID id, UUID projectId, String kind, String label, String fileName, OffsetDateTime createdAt,
                             String summaryJson, String detailJson) {
        this.id = id;
        this.projectId = projectId;
        this.kind = kind;
        this.label = label;
        this.fileName = fileName;
        this.createdAt = createdAt;
        this.summaryJson = summaryJson;
        this.detailJson = detailJson;
    }

    void relabel(String label, String summaryJson, String detailJson) {
        this.label = label;
        this.summaryJson = summaryJson;
        this.detailJson = detailJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public String getDetailJson() {
        return detailJson;
    }
}
