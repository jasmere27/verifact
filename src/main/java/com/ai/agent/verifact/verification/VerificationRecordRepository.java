package com.ai.agent.verifact.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, UUID> {

    Optional<VerificationRecord> findFirstByInputHashAndCreatedAtAfterOrderByCreatedAtDesc(
            String inputHash, OffsetDateTime createdAfter);

    /** Feedback on these reports goes with them (ON DELETE CASCADE). */
    @Modifying
    @Transactional
    @Query("delete from VerificationRecord r where r.createdAt < :cutoff")
    int deleteCreatedBefore(OffsetDateTime cutoff);
}
