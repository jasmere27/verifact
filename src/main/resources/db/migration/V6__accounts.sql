-- Accounts (ADR-18): one account across VeriFact, NewsFact, LegalFact and ResearchFact. Identity (passwords,
-- Google sign-in, email verification, resets) lives in Supabase Auth; these tables hold our side: the profile,
-- and organisations with memberships so ownership, teams, roles and plans attach to an organisation.
-- Every user gets a personal organisation on first sign-in.
-- Rollback: forward-fix only (a later migration dropping the tables); nothing else references them yet.
CREATE TABLE app_users (
    id            UUID          PRIMARY KEY,   -- Supabase Auth user id (the access token's "sub")
    email         VARCHAR(320),
    display_name  VARCHAR(80),
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE organizations (
    id                 UUID          PRIMARY KEY,
    name               VARCHAR(120)  NOT NULL,
    personal_owner_id  UUID          UNIQUE REFERENCES app_users (id) ON DELETE CASCADE,
    created_at         TIMESTAMPTZ   NOT NULL
);

CREATE TABLE memberships (
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id          UUID          NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    role             VARCHAR(16)   NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    created_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (organization_id, user_id)
);

CREATE INDEX idx_memberships_user ON memberships (user_id);
