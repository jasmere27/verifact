# Decisions (ADR log)

Format: `ADR-N — Title` · Status (Proposed / Accepted / Superseded) · Date · Context · Decision · Consequences.
Only record decisions with real tradeoffs.

---

## ADR-1 — Orchestrator is the main session; specialists are Claude Code subagents
**Accepted** · 2026-09-29
- Context: Claude Code subagents cannot spawn subagents.
- Decision: Orchestrator protocol lives in `.claude/orchestrator.md` (imported by `CLAUDE.md`). Specialists in `.claude/agents/`. Commands are prefixed `vf-` to avoid colliding with built-in `/review` and `/plan`. Playbooks live in `.claude/playbooks/` because `.claude/workflows/` is reserved for executable workflow scripts.
- Consequences: Parallel delegation works from the main session only.

## ADR-2 — Modular monolith
**Accepted** · 2026-09-29
- One Spring Boot app, feature packages, interfaces only at provider seams (AI, search, fetch). No microservices/queues/caches-as-services until a measured need.

## ADR-3 — Backend-controlled verification pipeline instead of model-driven tool calls
**Proposed** · 2026-09-29
- Context: Current design lets the LLM call search/fetch tools freely → unbounded cost, SSRF via injection, citations not tied to retrieved data.
- Decision: Backend runs claim extraction (LLM #1) → search → safe fetch → assessment (LLM #2, structured, cites evidence IDs) → validation. No tools exposed to the model.
- Consequences: Predictable cost (~2 LLM calls), testable steps, verifiable citations. Loses some model "agency" (e.g., follow-up searches); can add one bounded refinement round later if evals show a need.

## ADR-4 — Verdict taxonomy
**Proposed** · 2026-09-29
- Per-claim: `SUPPORTED`, `PARTLY_SUPPORTED`, `MISLEADING` (true facts, false framing/context), `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`.
- Overall result = summary of per-claim verdicts (not a single TRUE/FALSE).
- Replace the numeric "confidence %" with **evidence strength** (`STRONG` / `MODERATE` / `LIMITED`) derived from rules the backend can explain: number of independent sources, agreement, recency, source type. Model proposes; backend clamps (e.g., can't be STRONG with one source).
- Legacy mapping for v1 endpoints: SUPPORTED→real, CONTRADICTED→fake, PARTLY_SUPPORTED/MISLEADING→mixed, INSUFFICIENT_EVIDENCE→unverified.

## ADR-5 — Search provider must be replaced before 2027-01-01
**Proposed** · 2026-09-29
- Google Custom Search JSON API is discontinued 2027-01-01 and closed to new customers.
- Decision: introduce `SearchProvider` interface; `research-agent` selects the replacement (candidates: Brave Search API, Tavily, Exa, Serper, self-hosted SearXNG) on cost, result quality, returned metadata (URL, title, date), and ToS for this use.

## ADR-6 — Upgrade to Spring Boot 4.x + Spring AI 2.0 GA
**Proposed** · 2026-09-29
- Codebase is small (~15 classes), so migrating now is cheap; staying on an unsupported Boot 3.4 + a pre-GA Spring AI milestone blocks structured output/provider improvements. Do it as its own step before the pipeline rewrite. Consider Java 21.
