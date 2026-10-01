package com.ai.agent.verifact.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchProjectRepository extends JpaRepository<ResearchProjectRecord, UUID> {

    List<ResearchProjectRecord> findTop50ByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    Optional<ResearchProjectRecord> findByIdAndOwnerId(UUID id, UUID ownerId);

    long countByOwnerId(UUID ownerId);

    @Modifying
    @Transactional
    @Query("delete from ResearchProjectRecord r where r.updatedAt < :cutoff")
    int deleteUpdatedBefore(OffsetDateTime cutoff);
}
