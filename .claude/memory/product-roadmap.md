# Product Roadmap

_Last updated: 2026-09-30. Status: ☐ not started · ◐ in progress · ☑ done._

## Phase 0 — Foundation (make it safe to put online)
- ☑ Merge `feature/supabase-db-and-deploy` into `main` (local fast-forward 2026-09-29; not pushed)
- ☑ Remove committed build/IDE artifacts; fix `.gitignore`
- ☐ Upgrade Spring Boot 4.x + Spring AI 2.0 GA (ADR-6) — next
- ☑ Real test suite with deterministic AI/search fakes (87 tests)
- ☑ SSRF-safe fetcher; rate limiting; input/upload limits; ProblemDetail errors; timeouts; request IDs; cheap health check
- ☑ Make `/history` non-public (disabled by default) until accounts exist
- ☐ Decide data retention for stored submissions

## Phase 1 — Core verification (the product)
- ☐ Backend-controlled pipeline (ADR-3) with structured output
- ☐ Replace Google CSE with a new `SearchProvider` (ADR-5) returning URL/title/date
- ☐ New taxonomy + evidence strength (ADR-4)
- ☐ Evidence model persisted: claims, evidence items, sources, AI run metadata
- ☐ Result page redesign: checked → assessment → why → supporting/contradicting evidence → sources/dates → limitations
- ☐ Eval set (30–50 labeled claims) + cost/latency logging

## Phase 2 — Better verification
- ☐ Source credibility signals (source type, known publisher, date recency) — explainable, not a secret score
- ☐ Contradiction detection across sources; better uncertainty messaging
- ☐ Search/result caching for repeated claims

## Phase 3 — Multimodal
- ☐ Image: vision model reads screenshot directly (claims + visible context), OCR fallback
- ☐ Audio: transcription via the configured AI provider; language support
- ☐ (Later) reverse image search / metadata signals; video only if clear demand

## Phase 4 — Accounts
- ☐ Auth (evaluate Supabase Auth JWT verified by Spring Security vs. alternatives)
- ☐ Private history, saved reports, shareable public report pages (opt-in)

## Phase 5 — Monetization (only after usage data)
Candidates: higher limits, batch checks, API access, team workspaces, exportable reports.

## Phase 6 — Platform (possibilities)
Browser extension (verify selected text), public API, newsroom/education/moderation integrations.
