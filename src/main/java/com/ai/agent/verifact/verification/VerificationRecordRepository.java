package com.ai.agent.verifact.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, UUID> {
}
