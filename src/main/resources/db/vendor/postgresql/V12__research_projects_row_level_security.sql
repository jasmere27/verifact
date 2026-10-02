-- Postgres only: no Data API access to the new table (see V7).
ALTER TABLE research_projects ENABLE ROW LEVEL SECURITY;
