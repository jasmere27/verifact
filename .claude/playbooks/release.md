# Playbook: Release

Target topology: Cloudflare Pages (frontend) → one Spring Boot container (backend host) →
Supabase Postgres + AI provider + search provider.

1. **Freeze scope.** Only the approved change set.
2. **Run `/vf-qa`.** All green, or warnings explicitly accepted by the user.
3. **Security pass** on the diff (`security-engineer`).
4. **Config audit.**
   - Backend env vars set in the host dashboard (never in the repo): see `.env.example`.
   - Frontend `VITE_API_BASE_URL` points to the backend for that environment.
   - Backend `ALLOWED_ORIGIN` includes the Pages domain (and custom domain).
   - Health check path is cheap (actuator/health), not a verification endpoint.
5. **Database.** New Flyway migrations reviewed by `database-engineer`; take a Supabase backup/snapshot before applying destructive-looking migrations.
6. **Order.** Deploy backend first (migrations run on startup), verify health, then frontend.
7. **Smoke test** production: one text check, one URL check, error path (empty input).
8. **Rollback plan.** Redeploy previous backend image/commit; Pages rollback to previous deployment; forward-fix migration if schema changed.
9. **Record** the release in `.claude/memory/product-roadmap.md` (status) and any new known issues.

Pushing, merging, tagging, and deploying require explicit user approval every time.
