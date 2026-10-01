package com.ai.agent.verifact.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountCheckRepository extends JpaRepository<AccountCheck, UUID> {

    List<AccountCheck> findTop100ByUserIdOrderByCheckedAtDesc(UUID userId);

    Optional<AccountCheck> findByUserIdAndVerificationId(UUID userId, UUID verificationId);

    @Transactional
    long deleteByUserIdAndVerificationId(UUID userId, UUID verificationId);
}
