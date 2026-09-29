package com.ai.agent.verifact.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationFeedbackRepository extends JpaRepository<VerificationFeedback, UUID> {
}
