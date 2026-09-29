package com.ai.agent.verifact.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, UUID> {

    Optional<VerificationRecord> findFirstByInputHashAndCreatedAtAfterOrderByCreatedAtDesc(
            String inputHash, OffsetDateTime createdAfter);
}
