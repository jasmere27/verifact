package com.ai.agent.verifact.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileRepository extends JpaRepository<ProjectFileRecord, UUID> {

    List<ProjectFileRecord> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<ProjectFileRecord> findByIdAndProjectId(UUID id, UUID projectId);

    long countByProjectId(UUID projectId);

    /** Only the small summaries (the detail holds whole papers), newest first. */
    @Query("select r.summaryJson from ProjectFileRecord r where r.projectId = :projectId order by r.createdAt desc")
    List<String> summaries(UUID projectId);
}
