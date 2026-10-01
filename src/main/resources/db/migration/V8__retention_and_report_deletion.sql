-- Reports can be deleted by the browser that ran the check: SHA-256 of its edit token (null for older
-- reports and for clients that sent none). Retention jobs delete old rows by date (ADR-19).
ALTER TABLE verifications ADD COLUMN edit_token_hash VARCHAR(64);

CREATE INDEX idx_news_reviews_updated_at ON news_reviews (updated_at);
