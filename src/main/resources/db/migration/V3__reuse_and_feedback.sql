-- Reuse recent reports for repeated inputs, and collect user feedback on reports.
-- Rollback: forward-fix only (a later migration dropping the column/table).

-- SHA-256 of the normalised text or link; NULL for image/audio checks (never reused).
ALTER TABLE verifications ADD COLUMN input_hash VARCHAR(64);
CREATE INDEX idx_verifications_input_hash ON verifications (input_hash, created_at DESC);

CREATE TABLE verification_feedback (
    id               UUID         PRIMARY KEY,
    verification_id  UUID         NOT NULL REFERENCES verifications (id) ON DELETE CASCADE,
    helpful          BOOLEAN      NOT NULL,
    reason           VARCHAR(32),
    comment          TEXT,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_verification_feedback_verification ON verification_feedback (verification_id);
