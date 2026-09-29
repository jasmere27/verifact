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
**Accepted — implemented as API v2, 2026-09-30** (`verification/VerificationService`) · proposed 2026-09-29
- Context: Current design lets the LLM call search/fetch tools freely → unbounded cost, SSRF via injection, citations not tied to retrieved data.
- Decision: Backend runs claim extraction (LLM #1) → search → safe fetch → assessment (LLM #2, structured, cites evidence IDs) → validation. No tools exposed to the model.
- Consequences: Predictable cost (~2 LLM calls), testable steps, verifiable citations. Loses some model "agency" (e.g., follow-up searches); can add one bounded refinement round later if evals show a need.

## ADR-4 — Verdict taxonomy
**Accepted — implemented 2026-09-30.** Evidence strength rule as built: distinct cited domains in the verdict's direction, ≥3 STRONG, 2 MODERATE, else LIMITED; capped at MODERATE when cited sources disagree. Overall verdict = the common claim verdict, else MIXED. · proposed 2026-09-29
- Per-claim: `SUPPORTED`, `PARTLY_SUPPORTED`, `MISLEADING` (true facts, false framing/context), `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`.
- Overall result = summary of per-claim verdicts (not a single TRUE/FALSE).
- Replace the numeric "confidence %" with **evidence strength** (`STRONG` / `MODERATE` / `LIMITED`) derived from rules the backend can explain: number of independent sources, agreement, recency, source type. Model proposes; backend clamps (e.g., can't be STRONG with one source).
- Legacy mapping for v1 endpoints: SUPPORTED→real, CONTRADICTED→fake, PARTLY_SUPPORTED/MISLEADING→mixed, INSUFFICIENT_EVIDENCE→unverified.

## ADR-5 — Replace Google Custom Search with Tavily behind a SearchProvider interface
**Accepted — implemented 2026-09-30** · proposed 2026-09-29
- Context: Google Custom Search JSON API is discontinued 2027-01-01 and closed to new customers.
- Research (2026-09-30, official pricing/docs/ToS pages):
  - **Tavily** (chosen): 1,000 free credits/month; PAYG ~$0.008/search → ~$16–152/mo at 3k–20k searches. Returns `published_date`. No storage ban or attribution requirement found. ToS forbids relying on output "in isolation" for decisions with legal/significant effect on a person — relevant to claims about individuals.
  - **Exa** (fallback): own index, `publishedDate`, $10 free/month. ToS not verified (PDF unreadable).
  - **Brave** rejected despite the best index/price: ToS forbids storing results beyond transient use and requires "POWERED BY BRAVE" attribution — conflicts with persisting evidence URLs/snippets.
  - Excluded: Bing (retired 2025-08-11), Serper/SerpApi/SearXNG (Google scraping, legal risk), Mojeek (small index, no dates).
- Decision: `search/SearchProvider` with `TavilySearchProvider` and legacy `GoogleCustomSearchProvider`; `SEARCH_PROVIDER=auto` prefers Tavily. Adding Exa later is one class.
- To re-check: Tavily's `published_date` coverage for `topic=general`; written confirmation that storing URLs/snippets indefinitely is allowed.

## ADR-6 — Upgrade to Spring Boot 4.x + Spring AI 2.0 GA
**Accepted — done 2026-09-30** (Boot 4.1.1, Spring AI 2.0.1, Java 21) · proposed 2026-09-29
- Notes for future work: Boot 4 is modular (use `spring-boot-starter-webmvc`, `-restclient`, `-flyway`, `-webmvc-test`); Jackson 3 (`tools.jackson.*`, inject `JsonMapper`); test slices live in `org.springframework.boot.webmvc.test.autoconfigure`. Spring AI 2.0's OpenAI client is the official openai-java SDK, so AI timeouts/retries are `spring.ai.openai.chat.timeout` / `spring.ai.openai.max-retries`, not `spring.ai.retry.*` or `spring.http.clients.*`.
- Codebase is small (~15 classes), so migrating now is cheap; staying on an unsupported Boot 3.4 + a pre-GA Spring AI milestone blocks structured output/provider improvements. Do it as its own step before the pipeline rewrite. Consider Java 21.

## ADR-7 — v2 API alongside v1; reports stored as JSON with unguessable IDs
**Accepted** · 2026-09-30
- Context: the structured result can't fit v1's plain-text contract; v1 has external-looking callers (README curl examples).
- Decision: new `/api/v2/verifications` (JSON). v1 stays unchanged but deprecated (it still uses the old model-driven prompt); the bundled frontend uses v2 only. Reports persist to `verifications` (Flyway V2) as a JSON document plus indexed columns, keyed by random UUID, and are readable via `GET /api/v2/verifications/{id}` — this makes shareable report links possible without accounts.
- Consequences: anyone holding a report link can read that report (unlisted-link model); the frontend should say so near the share action. Normalised evidence/source tables deferred until a query needs them. Remove v1 once nothing uses it.

## ADR-8 — Deployment topology
**Accepted** · 2026-09-30
- Cloudflare Pages (frontend, `_redirects` SPA fallback) → Render Docker web service (`starter`, $7/mo; `free` sleeps) → Supabase Postgres via the **Session pooler** (IPv4; direct connections are IPv6-only, and Render has no IPv6 egress) + OpenAI + Tavily. Guide: `docs/DEPLOYMENT.md`.

## ADR-9 — Default model: Spring AI's OpenAI default (gpt-5-mini)
**Accepted** · 2026-09-30
- Live eval with the default `gpt-5-mini-2025-08-07`: 20/20, ~2 calls and ~3k prompt + ~1–2k completion tokens per check. Good enough to keep; no need for a larger model now. Override with `SPRING_AI_OPENAI_CHAT_MODEL` and re-run `VerificationEvalIT` before switching.
