-- Postgres only: no Data API access to the new table (see V7).
ALTER TABLE account_checks ENABLE ROW LEVEL SECURITY;
