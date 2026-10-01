package com.ai.agent.verifact.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Our side of an account. The id is the Supabase Auth user id; credentials never reach this table. */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AppUser() {
    }

    public AppUser(UUID id, String email, OffsetDateTime at) {
        this.id = id;
        this.email = email;
        this.createdAt = at;
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Supabase confirms email changes; the token then carries the new address. */
    public void syncEmail(String email, OffsetDateTime at) {
        this.email = email;
        this.updatedAt = at;
    }

    public void rename(String displayName, OffsetDateTime at) {
        this.displayName = displayName;
        this.updatedAt = at;
    }
}
