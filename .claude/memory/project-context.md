# Project Context

_Last updated: 2026-09-29_

## What VeriFact is
AI-assisted claim verification: a user submits text, a URL, an image, or audio; VeriFact
searches the web, analyzes the evidence, and returns an assessment with sources.
Slogan: **"Verify Truth, Fight the False."** Started as an IT capstone; the goal is now a
real, maintainable, eventually monetizable product run by one developer.

## Repository
- GitHub: `jasmere27/verifact`. Local: `verifactRelive/verifact/` (the parent folder is not a repo).
- **Branches:**
  - `main` — backend only (Spring Boot). Last commit `7560c14`.
  - `origin/feature/supabase-db-and-deploy` — 3 commits ahead of `main`, fast-forwardable. Adds Supabase persistence (JPA + Flyway), `/history` endpoints, configurable CORS/Tesseract, Tesseract in Docker, `render.yaml`, and the React/Vite frontend in `frontend/`. **This is the most current code.** Not yet merged or checked out locally.
- No local JDK in the WSL environment as of 2026-09-29 (`java` not found). Node 24 is available.

## Stack (on feature branch)
- Backend: Java 17, Spring Boot 3.4.5, Spring AI 1.0.0-M8 (OpenAI starter), Maven wrapper, Jsoup, Tess4j, Google Cloud Speech, Spring Data JPA, Flyway, PostgreSQL driver.
- Frontend: React 19, Vite 8, TypeScript 6, oxlint. No router, no test runner.
- DB: Supabase Postgres (single table `fact_check_results`).
- Search: Google Custom Search JSON API (**shuts down 2027-01-01; closed to new customers**).
- Hosting config: `render.yaml` (Render, Docker, `plan: starter`), `frontend/wrangler.toml` (Cloudflare Pages).

## Users (hypothesis, not validated)
Students, general public checking viral claims/screenshots, educators; later journalists/moderators.

## Working agreements
- Orchestrator protocol: `.claude/orchestrator.md`. Commands: `/vf-investigate`, `/vf-plan`, `/vf-implement`, `/vf-review`, `/vf-qa`, `/vf-optimize`, `/vf-ship`.
- Major changes need user approval. No pushes/deploys without explicit approval.
