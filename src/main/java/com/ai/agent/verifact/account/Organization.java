package com.ai.agent.verifact.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What owns work (and later plans, usage and billing). Every user has one personal organisation; team
 * organisations come later.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Set only for a user's personal organisation. */
    @Column(name = "personal_owner_id", unique = true)
    private UUID personalOwnerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Organization() {
    }

    public Organization(UUID id, String name, UUID personalOwnerId, OffsetDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.personalOwnerId = personalOwnerId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isPersonal() {
        return personalOwnerId != null;
    }
}
