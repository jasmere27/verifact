-- Capstone / research projects owned by an account (ADR-21). The project (topic, questions, evidence library,
-- gaps, notes, draft text and analysis, insights) is one JSON document; deleting the account deletes its
-- projects. A daily job deletes projects 12 months after their last change.
CREATE TABLE research_projects (
    id          UUID          PRIMARY KEY,
    owner_id    UUID          NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    data_json   TEXT          NOT NULL
);

CREATE INDEX idx_research_projects_owner ON research_projects (owner_id, updated_at DESC);
CREATE INDEX idx_research_projects_updated ON research_projects (updated_at);
