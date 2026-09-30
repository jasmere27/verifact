package com.ai.agent.verifact.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ResearchWorkspaceRepository extends JpaRepository<ResearchWorkspaceRecord, UUID> {

    @Modifying
    @Transactional
    @Query("delete from ResearchWorkspaceRecord r where r.expiresAt < :now")
    int deleteExpired(OffsetDateTime now);
}
