package com.ai.agent.verifact.account;

/** A member's role in an organisation, strongest first. */
public enum Role {
    OWNER, ADMIN, MEMBER;

    /** True when this role has at least the rights of {@code required}. */
    public boolean atLeast(Role required) {
        return ordinal() <= required.ordinal();
    }
}
