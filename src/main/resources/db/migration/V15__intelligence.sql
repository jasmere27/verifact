-- VeriFact Intelligence (ADR-26): brand monitoring, incidents, investigations, response drafts, reports,
-- subscriptions, usage metering and audit events. Everything here is owned by an organisation (V6) and every
-- query filters by organization_id after OrganizationAccess.require has checked the caller's membership.
-- Deleting an organisation deletes its data (ON DELETE CASCADE). Users who are referenced as authors or owners
-- are kept as plain ids with ON DELETE SET NULL so deleting an account never deletes shared team work.
-- Rollback: forward-fix only (a later migration dropping these tables); nothing outside this feature references them.

-- Team workspaces: personal organisations already exist; teams are the same table.
ALTER TABLE organizations ADD COLUMN created_by UUID REFERENCES app_users (id) ON DELETE SET NULL;

-- One subscription row per organisation (created lazily). plan_id refers to app.plans.* in configuration, so
-- prices and quotas can change without a migration. Status follows the billing provider's lifecycle.
CREATE TABLE org_subscriptions (
    organization_id           UUID          PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    plan_id                   VARCHAR(32),
    status                    VARCHAR(24)   NOT NULL CHECK (status IN ('NONE', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED')),
    provider                  VARCHAR(24),
    provider_customer_id      VARCHAR(120),
    provider_subscription_id  VARCHAR(120)  UNIQUE,
    trial_started_at          TIMESTAMPTZ,
    trial_ends_at             TIMESTAMPTZ,
    current_period_end        TIMESTAMPTZ,
    cancel_at_period_end      BOOLEAN       NOT NULL DEFAULT FALSE,
    last_event_at             TIMESTAMPTZ,
    updated_at                TIMESTAMPTZ   NOT NULL
);

-- Webhook idempotency: a provider event is processed once.
CREATE TABLE billing_events (
    provider         VARCHAR(24)   NOT NULL,
    event_id         VARCHAR(160)  NOT NULL,
    event_type       VARCHAR(80)   NOT NULL,
    organization_id  UUID          REFERENCES organizations (id) ON DELETE SET NULL,
    occurred_at      TIMESTAMPTZ,
    received_at      TIMESTAMPTZ   NOT NULL,
    outcome          VARCHAR(40)   NOT NULL,
    PRIMARY KEY (provider, event_id)
);

-- Metered usage, one row per billable action, with what it cost us where known (for pricing decisions).
CREATE TABLE usage_events (
    id                 UUID          PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    feature            VARCHAR(32)   NOT NULL,
    quantity           INTEGER       NOT NULL,
    llm_calls          INTEGER       NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT        NOT NULL DEFAULT 0,
    completion_tokens  BIGINT        NOT NULL DEFAULT 0,
    search_calls       INTEGER       NOT NULL DEFAULT 0,
    actor_id           UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_usage_org_feature_time ON usage_events (organization_id, feature, created_at);

-- Sensitive actions (membership, billing, approvals, deletions).
CREATE TABLE audit_events (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    actor_id         UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    action           VARCHAR(60)   NOT NULL,
    target_type      VARCHAR(40),
    target_id        VARCHAR(80),
    detail           VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_audit_org_time ON audit_events (organization_id, created_at DESC);

-- A brand (or client) being monitored. Term lists and source settings are JSON documents validated in code.
CREATE TABLE monitoring_projects (
    id                    UUID          PRIMARY KEY,
    organization_id       UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                  VARCHAR(120)  NOT NULL,
    brand                 VARCHAR(120)  NOT NULL,
    config_json           TEXT          NOT NULL,
    status                VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED')),
    check_interval_hours  INTEGER       NOT NULL,
    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    last_success_at       TIMESTAMPTZ,
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    lease_until           TIMESTAMPTZ,
    created_by            UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_monitoring_projects_org ON monitoring_projects (organization_id, created_at);
CREATE INDEX idx_monitoring_projects_due ON monitoring_projects (status, next_run_at);

-- One execution of a project's connectors.
CREATE TABLE monitoring_runs (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id       UUID          NOT NULL REFERENCES monitoring_projects (id) ON DELETE CASCADE,
    trigger_kind     VARCHAR(16)   NOT NULL CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'RETRY')),
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    attempt          INTEGER       NOT NULL,
    scheduled_for    TIMESTAMPTZ,
    started_at       TIMESTAMPTZ   NOT NULL,
    finished_at      TIMESTAMPTZ,
    results_found    INTEGER       NOT NULL DEFAULT 0,
    new_mentions     INTEGER       NOT NULL DEFAULT 0,
    new_incidents    INTEGER       NOT NULL DEFAULT 0,
    sources_json     TEXT,
    error_summary    VARCHAR(500)
);
CREATE INDEX idx_monitoring_runs_project ON monitoring_runs (project_id, started_at DESC);
CREATE INDEX idx_monitoring_runs_org ON monitoring_runs (organization_id, started_at DESC);

-- A retrieved item that matched a project's terms. url_key is the normalised URL: one row per page per project.
CREATE TABLE mentions (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id       UUID          NOT NULL REFERENCES monitoring_projects (id) ON DELETE CASCADE,
    first_run_id     UUID          REFERENCES monitoring_runs (id) ON DELETE SET NULL,
    connector        VARCHAR(24)   NOT NULL,
    url              VARCHAR(2048) NOT NULL,
    url_key          VARCHAR(512)  NOT NULL,
    content_hash     VARCHAR(64)   NOT NULL,
    domain           VARCHAR(255),
    title            VARCHAR(400),
    snippet          VARCHAR(1200),
    published_date   VARCHAR(40),
    relevance        VARCHAR(8)    NOT NULL CHECK (relevance IN ('HIGH', 'MEDIUM', 'LOW')),
    matched_json     TEXT          NOT NULL,
    claim            VARCHAR(600),
    claim_quote      VARCHAR(600),
    claim_severity   VARCHAR(8),
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('NEW', 'REVIEWED', 'DISMISSED', 'ESCALATED')),
    incident_id      UUID,
    first_seen_at    TIMESTAMPTZ   NOT NULL,
    last_seen_at     TIMESTAMPTZ   NOT NULL,
    seen_count       INTEGER       NOT NULL DEFAULT 1,
    CONSTRAINT uq_mentions_project_url UNIQUE (project_id, url_key)
);
CREATE INDEX idx_mentions_org_seen ON mentions (organization_id, first_seen_at DESC);
CREATE INDEX idx_mentions_project_hash ON mentions (project_id, content_hash);

-- A case: a potentially damaging claim being handled.
CREATE TABLE incidents (
    id                 UUID          PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id         UUID          REFERENCES monitoring_projects (id) ON DELETE SET NULL,
    title              VARCHAR(200)  NOT NULL,
    claim              VARCHAR(1000) NOT NULL,
    discovery_url      VARCHAR(2048),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('NEW', 'NEEDS_REVIEW', 'INVESTIGATING', 'AWAITING_EVIDENCE',
                                                                'RESPONSE_DRAFTED', 'RESOLVED', 'MONITORING')),
    priority           VARCHAR(8)    NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    risk_score         INTEGER       NOT NULL,
    risk_json          TEXT          NOT NULL,
    priority_override  BOOLEAN       NOT NULL DEFAULT FALSE,
    owner_id           UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    investigation_id   UUID,
    next_steps         VARCHAR(2000),
    first_seen_at      TIMESTAMPTZ   NOT NULL,
    last_seen_at       TIMESTAMPTZ   NOT NULL,
    created_by         UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    resolved_at        TIMESTAMPTZ
);
CREATE INDEX idx_incidents_org_status ON incidents (organization_id, status, updated_at DESC);
CREATE INDEX idx_incidents_project ON incidents (project_id);

CREATE TABLE incident_events (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    incident_id      UUID          NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    actor_id         UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    kind             VARCHAR(32)   NOT NULL,
    detail           VARCHAR(1000),
    created_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_incident_events_incident ON incident_events (incident_id, created_at);

-- An evidence investigation of a statement; each run is a version so earlier assessments are kept.
CREATE TABLE investigations (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    incident_id      UUID          REFERENCES incidents (id) ON DELETE SET NULL,
    title            VARCHAR(200)  NOT NULL,
    input_text       TEXT          NOT NULL,
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    current_version  INTEGER       NOT NULL DEFAULT 0,
    error_message    VARCHAR(500),
    analyst_notes    TEXT,
    created_by       UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    last_reviewed_at TIMESTAMPTZ
);
CREATE INDEX idx_investigations_org ON investigations (organization_id, updated_at DESC);
CREATE INDEX idx_investigations_incident ON investigations (incident_id);

CREATE TABLE investigation_versions (
    investigation_id  UUID          NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    version           INTEGER       NOT NULL,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ   NOT NULL,
    result_json       TEXT          NOT NULL,
    changes_json      TEXT,
    PRIMARY KEY (investigation_id, version)
);

-- Evidence library: one row per retrieved source per investigation version, with the verbatim excerpts used.
CREATE TABLE evidence_records (
    id                UUID          PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    investigation_id  UUID          NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    version           INTEGER       NOT NULL,
    source_ref        VARCHAR(8)    NOT NULL,
    url               VARCHAR(2048) NOT NULL,
    domain            VARCHAR(255),
    title             VARCHAR(400),
    snippet           VARCHAR(1200),
    published_date    VARCHAR(40),
    retrieved_at      TIMESTAMPTZ   NOT NULL,
    source_type       VARCHAR(24),
    direction         VARCHAR(16)   NOT NULL CHECK (direction IN ('SUPPORTS', 'CONTRADICTS', 'MIXED', 'CONTEXT')),
    claim_refs        VARCHAR(200),
    excerpt           VARCHAR(1200)
);
CREATE INDEX idx_evidence_org_time ON evidence_records (organization_id, retrieved_at DESC);
CREATE INDEX idx_evidence_investigation ON evidence_records (investigation_id, version);

-- Response Studio drafts with revision history and human approval.
CREATE TABLE response_drafts (
    id                UUID          PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    investigation_id  UUID          REFERENCES investigations (id) ON DELETE SET NULL,
    investigation_version INTEGER,
    incident_id       UUID          REFERENCES incidents (id) ON DELETE SET NULL,
    kind              VARCHAR(32)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    body              TEXT          NOT NULL,
    meta_json         TEXT          NOT NULL,
    status            VARCHAR(16)   NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED')),
    revision          INTEGER       NOT NULL,
    approved_by       UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    approved_at       TIMESTAMPTZ,
    created_by        UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_response_drafts_org ON response_drafts (organization_id, updated_at DESC);

CREATE TABLE response_draft_revisions (
    draft_id    UUID          NOT NULL REFERENCES response_drafts (id) ON DELETE CASCADE,
    revision    INTEGER       NOT NULL,
    body        TEXT          NOT NULL,
    edited_by   UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    note        VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (draft_id, revision)
);

-- Generated reports: a snapshot of the account's data for a period (so a report doesn't change after the fact).
CREATE TABLE intelligence_reports (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    title            VARCHAR(200)  NOT NULL,
    period_start     TIMESTAMPTZ   NOT NULL,
    period_end       TIMESTAMPTZ   NOT NULL,
    report_json      TEXT          NOT NULL,
    created_by       UUID          REFERENCES app_users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_reports_org ON intelligence_reports (organization_id, created_at DESC);
