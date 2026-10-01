package com.ai.agent.verifact.repository;

import com.ai.agent.verifact.model.FactCheckResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface FactCheckResultRepository extends JpaRepository<FactCheckResult, Long> {
    Page<FactCheckResult> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    @Transactional
    @Query("delete from FactCheckResult r where r.createdAt < :cutoff")
    int deleteCreatedBefore(OffsetDateTime cutoff);
}
