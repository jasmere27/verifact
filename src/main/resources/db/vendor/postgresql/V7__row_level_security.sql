-- Postgres only (Flyway location db/vendor/{vendor}). Supabase exposes tables in the public schema through its
-- Data API to anyone holding the project's public (anon/publishable) key, which the frontend ships once sign-in
-- exists. Enabling row-level security with no policies gives those API roles no access at all. The backend is
-- unaffected: it connects as the tables' owner, and owners bypass RLS (it is not FORCEd).
-- Every new table must be added here or in a later migration (see known-issues).
-- Rollback: ALTER TABLE ... DISABLE ROW LEVEL SECURITY (only if the Data API is disabled).
ALTER TABLE fact_check_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE news_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE research_workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;
