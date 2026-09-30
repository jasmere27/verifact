-- ResearchFact Student Research Mode workspaces (ADR-15): topic, saved sources and notes as JSON.
-- Deleted automatically 90 days after the last change (expires_at, cleaned daily); owners can delete
-- earlier with the edit token, of which only the SHA-256 is stored.
-- Rollback: forward-fix only (a later migration dropping the table); nothing else depends on it.
CREATE TABLE research_workspaces (
    id               UUID          PRIMARY KEY,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    data_json        TEXT          NOT NULL,
    edit_token_hash  VARCHAR(64)   NOT NULL
);

CREATE INDEX idx_research_workspaces_expires_at ON research_workspaces (expires_at);
