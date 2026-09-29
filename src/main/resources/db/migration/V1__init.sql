CREATE TABLE fact_check_results (
    id                  BIGSERIAL PRIMARY KEY,
    input_type          VARCHAR(16)  NOT NULL,
    original_input      TEXT         NOT NULL,
    classification      VARCHAR(32),
    confidence_score    INTEGER,
    sources             TEXT,
    cybersecurity_tips  TEXT,
    full_response       TEXT         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_fact_check_results_created_at ON fact_check_results (created_at DESC);
