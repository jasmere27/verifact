package com.ai.agent.verifact.repository;

import com.ai.agent.verifact.model.FactCheckResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactCheckResultRepository extends JpaRepository<FactCheckResult, Long> {
    Page<FactCheckResult> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
