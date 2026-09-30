-- NewsFact reviews (ADR-14): a check of one article plus the editor's decisions. The check and the
-- review are stored as JSON; only the hash of the edit token is kept (the token itself is shown once).
-- Rollback: forward-fix only (a later migration dropping the table); nothing else depends on it.
CREATE TABLE news_reviews (
    id               UUID          PRIMARY KEY,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    source_url       VARCHAR(2000),
    result_json      TEXT          NOT NULL,
    review_json      TEXT          NOT NULL,
    edit_token_hash  VARCHAR(64)   NOT NULL
);

CREATE INDEX idx_news_reviews_created_at ON news_reviews (created_at DESC);
