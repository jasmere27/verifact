# Project Context

_Last updated: 2026-09-30_

## What VeriFact is
AI-assisted claim verification: a user submits text, a URL, an image, or audio; VeriFact
searches the web, analyzes the evidence, and returns an assessment with sources.
Slogan: **"Verify Truth, Fight the False."** Started as an IT capstone; the goal is now a
real, maintainable, eventually monetizable product run by one developer.

## Repository
- GitHub: `jasmere27/verifact`. Local: `verifactRelive/verifact/` (the parent folder is not a repo).
- **Branches:** work on feature branches and open PRs with `gh`; the user merges (merging without review is blocked for Claude). PR #1 (foundation) and PR #2 (LegalFact MVP, merge `1914930`) are merged into `main`.
- **Toolchain:** JDK 21 (Temurin) at `~/.local/jdks/jdk-21*` — not on PATH; use `export JAVA_HOME=$(ls -d ~/.local/jdks/jdk-21*) PATH=$JAVA_HOME/bin:$PATH` before `./mvnw`. Node 24 available. No Docker. GitHub CLI at `~/.local/bin/gh`, logged in as `jasmere27` and set up as the git credential helper.
- **Claude Code:** start sessions from the `verifact/` directory so `.claude/agents/` register as agent types. From a parent folder they don't load; fall back to a general-purpose agent told to follow the agent file.

## Stack
- Backend: Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (OpenAI starter, backed by the official openai-java SDK), Jackson 3, Maven wrapper, Jsoup 1.23, Tess4j 5.20, Google Cloud Speech 4.93, Spring Data JPA (Hibernate 7), Flyway 12, PostgreSQL driver.
- Frontend: React 19, Vite 8, TypeScript 6, oxlint. No router, no test runner.
- DB: Supabase Postgres, project ref `yappnvazdkscgqzipdxd`, region ap-southeast-2 (Sydney). Session pooler host `aws-0-ap-southeast-2.pooler.supabase.com:5432`, user `postgres.yappnvazdkscgqzipdxd`. Tables via Flyway (`fact_check_results`, `verifications`). Render region set to singapore to match.
- Search: Tavily (ADR-5); Google Custom Search remains as a legacy fallback (**shuts down 2027-01-01**).
- AI: OpenAI gpt-5-mini via Spring AI; images read by the same model (ADR-11). No auth/accounts yet.
- Hosting config: `render.yaml` (Render, Docker, `plan: starter`), `frontend/wrangler.toml` + `public/_redirects` (Cloudflare Pages). **Live since 2026-09-30:** frontend https://verifact-blf.pages.dev (Pages project `verifact`, direct upload via wrangler), backend https://verifact-backend-5mux.onrender.com (Render `srv-dad6lhrncjis7387if1g`, free, Singapore, auto-deploys `main`). Redeploy steps: top of `docs/DEPLOYMENT.md`. Render CLI at `~/.local/bin/render` and wrangler (via npx) are logged in as the owner. The old `verifact-bw9.pages.dev` site lives in a different Cloudflare account and is stale.

## Users (hypothesis, not validated)
Students, general public checking viral claims/screenshots, educators; later journalists/moderators.

## Working agreements
- Orchestrator protocol: `.claude/orchestrator.md`. Commands: `/vf-investigate`, `/vf-plan`, `/vf-implement`, `/vf-review`, `/vf-qa`, `/vf-optimize`, `/vf-ship`, `/vf-legal-audit`, `/vf-validate-product`, `/vf-research-market`, `/vf-revenue-review`, `/vf-experiment`.
- Business goal (owner, 2026-09-30): $1M/year revenue as a target, not a promise; build customer problem → value → willingness to pay. Business memory: `revenue-strategy.md`, `product-validation.md`, `experiments.md`, `customer-research.md`, `pricing.md`. (Prefixed `vf-` because `/review`, `/plan` etc. clash with Claude Code built-ins.)
- Direction (2026-09-30): VeriFact becomes an evidence and information verification platform; **LegalFact** is its first vertical, built as a module in this codebase (`.claude/memory/legalfact.md`).
- Major changes need user approval. No pushes/deploys without explicit approval.
