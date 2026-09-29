-- Structured verification reports (API v2). The full report is stored as JSON text so it can be
-- re-displayed exactly; indexed columns support operations and future history queries.
-- Rollback: forward-fix only (e.g. V3 dropping the table); nothing else depends on it yet.
CREATE TABLE verifications (
    id               UUID         PRIMARY KEY,
    created_at       TIMESTAMPTZ  NOT NULL,
    input_type       VARCHAR(16)  NOT NULL,
    overall_verdict  VARCHAR(32)  NOT NULL,
    search_provider  VARCHAR(32),
    duration_ms      BIGINT       NOT NULL,
    result_json      TEXT         NOT NULL
);

CREATE INDEX idx_verifications_created_at ON verifications (created_at DESC);
