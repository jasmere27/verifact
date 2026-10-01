-- Checks run while signed in (ADR-20). owner_id: the account whose request created the report; it may delete
-- it, and deleting the account deletes it. account_checks: each account's history, including reused reports;
-- rows go when the report goes (retention or deletion) or the account is deleted.
ALTER TABLE verifications ADD COLUMN owner_id UUID REFERENCES app_users (id) ON DELETE CASCADE;
CREATE INDEX idx_verifications_owner ON verifications (owner_id);

CREATE TABLE account_checks (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    verification_id  UUID          NOT NULL REFERENCES verifications (id) ON DELETE CASCADE,
    checked_at       TIMESTAMPTZ   NOT NULL,
    overall_verdict  VARCHAR(32)   NOT NULL,
    label            VARCHAR(200)  NOT NULL,
    CONSTRAINT uq_account_checks_user_verification UNIQUE (user_id, verification_id)
);

CREATE INDEX idx_account_checks_user ON account_checks (user_id, checked_at DESC);
CREATE INDEX idx_account_checks_verification ON account_checks (verification_id);
