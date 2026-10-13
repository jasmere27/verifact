package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;

import java.util.UUID;

/**
 * A verified caller inside one organisation: produced only by {@link IntelligenceAccess#require}, after the
 * membership check. Services take this instead of raw ids so an unchecked organisation id can't reach a query.
 */
public final class OrgContext {

    private final UUID orgId;
    private final UUID userId;
    private final Role role;

    OrgContext(UUID orgId, UUID userId, Role role) {
        this.orgId = orgId;
        this.userId = userId;
        this.role = role;
    }

    /** For background work started by the system (scheduled monitoring) rather than a person. */
    public static OrgContext system(UUID orgId) {
        return new OrgContext(orgId, null, Role.OWNER);
    }

    public UUID orgId() {
        return orgId;
    }

    /** Null for system work. */
    public UUID userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    public boolean atLeast(Role required) {
        return role.atLeast(required);
    }
}
