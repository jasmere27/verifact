package com.ai.agent.verifact.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** Deletes a user's personal organisation (memberships cascade in the database). */
    @Modifying
    @Query("DELETE FROM Organization o WHERE o.personalOwnerId = :userId")
    int deleteByPersonalOwnerId(@Param("userId") UUID userId);
}
