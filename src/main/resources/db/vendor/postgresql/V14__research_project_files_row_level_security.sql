-- Postgres only: no Data API access to the new table (see V7).
ALTER TABLE research_project_files ENABLE ROW LEVEL SECURITY;
