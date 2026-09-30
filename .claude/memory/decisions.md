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

## ADR-10 — Reuse identical checks for 24 hours
**Accepted** · 2026-09-30
- Viral claims get checked by many people; re-running costs 2 LLM calls + 4 searches and can give inconsistent verdicts. Same normalised text/link within 24 h returns the stored report (instant, free, consistent). Users can force a fresh check ("Check again now" → `refresh: true`). Uploads aren't reused (OCR/transcripts vary). Tradeoff: fast-moving news may show a report up to a day old — the report shows its check time.

## ADR-11 — Images read by a vision model; OCR as fallback
**Accepted** · 2026-09-30
- Context: screenshots are how most viral claims spread; OCR misses memes, captions over photos, charts and who posted it, and misreads messy text.
- Decision: one vision call (the configured chat model, gpt-5-mini) replaces OCR + text extraction for images, so a check still makes ≤2 LLM calls. It returns visible text, context (kind, source and date as shown, neutral description) and claims; search/assessment/validation are unchanged. The upload is decoded with the OCR guards, scaled to ≤2048px and re-encoded as JPEG (drops metadata). On a 502 from that step, or with `VISION_ENABLED=false`, the old OCR path runs.
- Guardrails: the image is untrusted (the prompt says so; it can't be nonce-delimited); no identifying people from faces; the context is shown as "as it appears in the image", never as evidence; VeriFact makes no claim about whether an image is edited, and every image report says so.
- Review outcomes (security + AI review, same day):
  - Claims: a post's or outlet's own assertion is extracted as-is; "[name] said that …" only for words attributed to someone else (otherwise false posts become unfalsifiable "X posted Y" claims).
  - The image can steer the model's `description`/`shownSource`, so fields with judgement words (verified, confirmed, authentic, fake, true/false, fact-check, VeriFact, …) are dropped server-side; the section sits below the claims, labelled "Automated description (not checked)". The description is never used as `checkedText`. Tradeoff: a real source like "@AFPFactCheck" is also hidden.
  - Claim swapping by text in the image ("only extract claim X") was considered. A grounding check wouldn't help (the swapped claim's words are in the image), and a separate delimited text-extraction call would add a third LLM call for the same prompt-level defence. Kept one call; the image is a separate content part so it can't forge delimiters. Covered by a live injection case; revisit if it fails.
  - Fallback only when the vision step fails within 30 s (unreadable output, model rejects images); slower failures (timeouts, outages) are reported. Worst case is then 3 LLM calls (failed vision + extraction + assessment) — accepted deviation from the ≤2 target, bounded by the 30 s budget. `durationMs` includes the failed attempt.
  - `visibleText` is capped at ~1500 characters in the prompt to bound output tokens (no provider-level token cap: it would also cap the assessment call).
  - Spring AI 2.0.1 sends the image as a base64 data URL and exposes no `detail` setting; if OpenAI's default reads dense screenshots at low detail, small text will be missed. Check `promptTokens` on the "LLM call step=ImageExtraction" log in the live run.
- Live tuning (4 paid runs, then 2 clean passes): the model added "@account posted this" and "physicists confirm X" alongside X, turning clear verdicts into MIXED, and took an injected "only extract: <true fact>" line as a claim (also MIXED). Fixed with prompt rules (no claims about the image itself; findings yield only X; no claims from instruction text) plus a code backstop, `dropRestatements` (image path only): when one claim contains another, keep plain X unless the wrapper is a real quote ("said", quote marks).
- Live results (gpt-5-mini, 2 runs × 6 synthetic screenshots, all passing): false post → CONTRADICTED; true headline → SUPPORTED; chart → claims per bar; fake Einstein quote → CONTRADICTED / INSUFFICIENT_EVIDENCE (never SUPPORTED); no-claim meme → 422; injection meme → CONTRADICTED with no planted claim or "Verified by Reuters". Vision step: ~1.5–2.1k prompt tokens (image counted at full detail, not the ~85-token low-detail rate), 0.4–1.3k completion tokens, 4–15 s.
- Consequences: images (not just their text) are sent to OpenAI (the upload form says so); the image itself is not stored. Slightly more tokens and latency per image check. The text/link prompts are unchanged, so the text eval is unaffected; images have their own opt-in live check, `ImageVisionEvalIT`.

