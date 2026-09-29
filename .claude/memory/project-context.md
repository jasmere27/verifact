# Project Context

_Last updated: 2026-09-30_

## What VeriFact is
AI-assisted claim verification: a user submits text, a URL, an image, or audio; VeriFact
searches the web, analyzes the evidence, and returns an assessment with sources.
Slogan: **"Verify Truth, Fight the False."** Started as an IT capstone; the goal is now a
real, maintainable, eventually monetizable product run by one developer.

## Repository
- GitHub: `jasmere27/verifact`. Local: `verifactRelive/verifact/` (the parent folder is not a repo).
- **Branches:** everything is merged into `main` via PR #1 (2026-09-30, merge `cf1a30e`). Work on feature branches and open PRs with `gh`.
- **Toolchain:** JDK 21 (Temurin) at `~/.local/jdks/jdk-21*` — not on PATH; use `export JAVA_HOME=$(ls -d ~/.local/jdks/jdk-21*) PATH=$JAVA_HOME/bin:$PATH` before `./mvnw`. Node 24 available. No Docker. GitHub CLI at `~/.local/bin/gh`, logged in as `jasmere27` and set up as the git credential helper.
- **Claude Code:** start sessions from the `verifact/` directory so `.claude/agents/` register as agent types. From a parent folder they don't load; fall back to a general-purpose agent told to follow the agent file.

## Stack
- Backend: Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (OpenAI starter, backed by the official openai-java SDK), Jackson 3, Maven wrapper, Jsoup 1.23, Tess4j 5.20, Google Cloud Speech 4.93, Spring Data JPA (Hibernate 7), Flyway 12, PostgreSQL driver.
- Frontend: React 19, Vite 8, TypeScript 6, oxlint. No router, no test runner.
- DB: Supabase Postgres, project ref `yappnvazdkscgqzipdxd`, region ap-southeast-2 (Sydney). Session pooler host `aws-0-ap-southeast-2.pooler.supabase.com:5432`, user `postgres.yappnvazdkscgqzipdxd`. Tables via Flyway (`fact_check_results`, `verifications`). Render region set to singapore to match.
- Search: Google Custom Search JSON API (**shuts down 2027-01-01; closed to new customers**).
- Hosting config: `render.yaml` (Render, Docker, `plan: starter`), `frontend/wrangler.toml` + `public/_redirects` (Cloudflare Pages). Guide: `docs/DEPLOYMENT.md`. **Not deployed yet** as of 2026-09-30 — waiting on the owner's Supabase/Render/Cloudflare/OpenAI/Tavily accounts.

## Users (hypothesis, not validated)
Students, general public checking viral claims/screenshots, educators; later journalists/moderators.

## Working agreements
- Orchestrator protocol: `.claude/orchestrator.md`. Commands: `/vf-investigate`, `/vf-plan`, `/vf-implement`, `/vf-review`, `/vf-qa`, `/vf-optimize`, `/vf-ship`.
- Major changes need user approval. No pushes/deploys without explicit approval.
