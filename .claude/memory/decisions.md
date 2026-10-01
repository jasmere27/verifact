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

## ADR-12 — LegalFact as a module in the VeriFact monolith
**Accepted** · 2026-09-30 (MVP approved; professional-facing first)
- Context: LegalFact (legal information and evidence intelligence) should reuse VeriFact's evidence engine and may later become its own product. One developer, low budget.
- Decision: a `legal` package in the same Spring Boot app and a `/legal` area in the same frontend; no separate repo, service or database. Shared core through narrow seams (`LlmClient`, evidence retrieval extracted from `VerificationService`, `SearchProvider` with a domain allow-list option). MVP uses Tavily restricted to official domains; case law (CourtListener) excluded until a commercial agreement; Cornell LII is link-only (non-commercial licence). MVP stores no case text.
- Consequences: extraction later means moving a package and a frontend folder. Legal safety rules live in `legalfact.md` and `/vf-legal-audit`. Storing cases waits for auth + retention.

## ADR-13 — ResearchFact as a third product on the shared evidence core
**Accepted** · 2026-09-30 (owner request)
- Decision: `research` package + `/research` frontend (own folder, copy and positioning), sharing `LlmClient`, `evidence.Grounding` (moved from `legal`), the SSE streamer, rate limiting and key trimming. New `ScholarlyIndex` seam (Crossref + DataCite + OpenAlex + PubMed) instead of web search. Two LLM calls. Nothing stored.
- Focus: whether the cited paper supports the claim (verbatim abstract quote) plus retraction/existence checks, since existence/retraction alone is already free elsewhere.
- Rules in code: existence/match/retraction from index data only; a model DOI is used only if it appears in the text; LOOKUP_FAILED ≠ NOT_FOUND; verdicts/conflicts need verbatim abstract quotes; quotes ≤300 chars.
- Semantic Scholar excluded (licence). OpenAlex key and Crossref contact email optional env vars.
- Consequences: judgments are abstract-only; validate with editors before any pricing (E5).

## ADR-14 — NewsFact: newsroom workspace, the first product with saved, editable state
**Accepted** · 2026-09-30 (owner request)
- Decision: `news` package + `/news` frontend, reusing `SafeUrlFetcher`, `EvidenceRetriever`, `LlmClient`, `Grounding` (new `findSpan`), the VeriFact `Verdict` taxonomy, the SSE streamer and rate limiting. Quotes are verified in code, not by the model. Verdicts and context flags need sources' verbatim excerpts.
- Human review needs saved state without accounts: `news_reviews` table (Flyway V4) with an unguessable UUID for reading and a random edit token (only its SHA-256 stored) for changes. CORS now allows PUT and `X-Edit-Token`; review saves share the feedback rate limiter.
- Consequences: link = read access; token loss = read-only; retention policy still missing (known issues). Accounts/teams when newsroom demand is shown.


## ADR-15 — ResearchFact Student Research Mode: discovery from a scholarly index, saved workspaces
**Accepted** · 2026-09-30 (owner request; plan approved)
- Decision: students discover sources (RRL, RRS, local/foreign, theories, concepts, methods, recent, supporting/contradicting a claim) through `ResearchDiscoveryService`. The model only **plans searches and suggests names**; every listed source is a real OpenAlex record fetched by the backend. Relevance notes are shown only with a verbatim abstract quote (`ResearchCheckService.verbatim`); suggested theories/concepts/methods are VERIFIED only when a found source's title/abstract names them, else shown as "Unverified suggestion". Local vs foreign is decided in code from author-affiliation countries.
- Workspaces (`research_workspaces`, Flyway V5) reuse the NewsFact pattern via the new shared `common/EditTokens`: UUID link = read, edit token (SHA-256 stored) = write. Saving a source **re-fetches it server-side** by key, so clients can't inject fabricated metadata; a nightly job deletes workspaces 90 days after the last change; explicit delete endpoint.
- APA references are formatted client-side from index metadata, with a "check against the paper" note.
- Phases 2 (file upload and analysis: PDF/DOCX/TXT/PPTX) and 3 (gaps, conceptual frameworks) are not built yet.
- **Addendum 2026-10-01 (phases 2-3):**
  - Drafts: `DocumentExtractor` (PDFBox 3 / POI 5; type from magic bytes, in memory, 10 MB, 60 pages / 80 slides / 60k chars, 25 s timeout, 2 concurrent, POI zip-bomb limits). Only the extracted text and analysis are stored, in the workspace JSON.
  - `DraftAnalysisService`: one quick model pass (summary, concepts, uncited claims), all checked against the draft's words; the reference check reuses the existing citation check on a generated excerpt instead of a new pipeline.
  - `ResearchInsightsService`: coverage counted in code; gaps must cite saved sources; relations need verbatim abstract quotes; framework variables are VERIFIED only if a saved source names them.
  - Discovery, draft reading and insights use `LlmClient.generateQuick` (reasoning effort low) for latency.
  - A live run found the citation check VERIFYING a fabricated reference on title overlap alone: title matching now requires the shorter title's words to be present (one miss allowed at 6+ words).

## ADR-16 — NewsFact supporting videos from platform metadata only (V1)
**Accepted** · 2026-10-01 (owner request; plan approved)
- Decision: per claim, search YouTube Data API v3 and classify videos as supporting / contradicting / context from their published title, description and chapters. Labels, "claims made" and timestamps must be verbatim from that metadata, checked in code. Videos are never downloaded and third-party transcripts aren't fetched (YouTube terms; captions.download is owner-only), and the UI says so.
- Quota: ~100 searches/day free, so only the first 4 claims (`NEWS_VIDEO_CLAIMS`) get a search; quota exhaustion is reported as a limitation, not an error.
- Runs in parallel with the evidence step on virtual threads; never fails the check.
- Deferred: V2 uploaded-video analysis (ffmpeg, speech-to-text, frame analysis; keeps "what the video shows / what the uploader claims / what sources confirm / unverified" separate; no AI real/fake verdict) and V3 reused-footage detection (Cloud Vision web detection).

## ADR-17 — Evidence Intelligence Core extracted from NewsFact
**Accepted** · 2026-10-01 (owner request; plan approved)
- Decision: the product-neutral rules NewsFact proved (claim grounding, verbatim citations, quote verification, verdict/flag gates, conflict detection, grounded explanations) move to `core/` as small stateless helpers over plain records. `Verdict` moves from `verification/` to `core/assess/` (JSON unchanged).
- Products keep their prompts **and model output schemas** (the schema the model sees is generated from the product's class), storage, endpoints and wording; they map their model output to core candidates (`ClaimCandidate`, `ReviewCandidate`) and core results back to their own records. No pipeline framework.
- Safety: `NewsCheckGoldenTest` pins NewsFact's full result and every prompt byte for byte, plus round-tripping of real stored `news_reviews` JSON (nested new fields must load as null). `CoreBoundaryTest` forbids product imports in `core/`.
- Known existing behaviour kept as-is: explanation grounding only checks numbers/names, so a wordy unrelated explanation can pass; a context flag can stand with INSUFFICIENT_EVIDENCE when an excerpt exists. Candidates for a deliberate change later (eval before/after).
- Next: source passages and page dates, timeline, comparison, report model; then the VeriFact Investigation workspace on the core; LegalFact/ResearchFact adopt parts where they fit.

## ADR-18 — Accounts: Supabase Auth identity, Spring Security token checks, organisation-owned data
**Accepted** · 2026-10-01 (owner request; plan approved, auth before core step 5)
- Decision: Supabase Auth handles sign-up/in/out, Google OAuth, email verification and resets (hashing, refresh-token rotation, its own rate limits; free to 50k MAU). The backend is an OAuth2 resource server verifying access tokens against the project's JWKS (asymmetric ES256/RS256 only, no shared secret; HS256 refused), plus issuer, audience `authenticated`, role `authenticated`, not anonymous, UUID `sub`.
- Sessions: bearer token in the Authorization header (short-lived, refreshed by supabase-js); no cookies → no CSRF. Frontend and API are different sites, so cookies would need a custom domain/BFF; revisit then. XSS is the token's risk → add a strict CSP with the frontend work (phase B).
- Authorisation: only from our tables (`app_users`, `organizations`, `memberships`, V6), never from token metadata (users can edit `user_metadata`). Every user gets a personal organisation (OWNER) on first `/me`; teams, roles, plans and usage attach to organisations. `OrganizationAccess.require` is the single check; isolation is enforced in code because the backend's DB role owns the tables and bypasses RLS.
- Supabase Data API: enabling auth publishes the project's public key, which can query `public` tables through PostgREST unless RLS is on. V7 (Postgres-only, `db/vendor/postgresql`) enables RLS with no policies on every table; owners (the backend) are unaffected. Every new table must get RLS too; also disable the Data API in the dashboard.
- Signed-out use and edit-token links keep working; all existing endpoints stay open. A request with an invalid token gets 401 (the client refreshes) rather than silently running signed out.
- Phase B (2026-10-01): frontend uses `@supabase/auth-js` only (not supabase-js: no PostgREST/realtime in the browser), PKCE flow, session in localStorage key `verifact.auth`; pages `/signin` `/signup` `/forgot-password` `/reset-password` `/auth/callback` `/account`; sign-up and reset show the same message whether or not the email exists; post-sign-in redirect only to same-site paths; Google button appears only when the provider is enabled (`/auth/v1/settings`). CSP (no inline scripts; connect-src = API + Supabase) written at build; verified with a report-uri collector across 13 pages (0 violations). Product API calls don't send the token yet (phase C).
- Phases: A backend foundation · B frontend auth + CSP + account deletion · C ownership of workspaces/reports, "my work", claiming edit-token workspaces, isolation tests · D per-user limits/usage counters · later invites/roles/plans/billing.

## ADR-19 — 90-day retention and owner deletion without accounts
**Accepted** · 2026-10-01 (owner request: "automatic deletion" + delete button; promised in the Privacy Policy)
- Decision: one retention period, `common/Retention.PERIOD` = 90 days, for everything users submit: reports (+ feedback, by FK cascade) and v1 results by creation date; NewsFact reviews and research workspaces by last change. Daily `@Scheduled` bulk deletes in each store; a report can be served for up to a day past 90 days until the job runs.
- Owner deletion uses edit tokens until accounts own data (ADR-18 phase C). Reports had none, and the check response is the stored `VerificationResult` (changing it would put the token in stored JSON), so the browser generates the token and sends it as `X-Edit-Token`; the server stores its hash only if the report was created during that request (`createdAt >= request start`), so a reused report can't be claimed by a later submitter. The frontend keeps the token only when it didn't detect reuse; if it guesses wrong, delete answers 403 with a clear message.
- Alternatives rejected: anyone-with-the-link delete (shared links are public); a token in the response body (contract + stored JSON change); per-product retention periods (harder to explain in the policy).
- Changing the period means changing `Retention.PERIOD`, the frontend's `RETENTION_DAYS`/NewsFact text, and the Privacy Policy together.

## ADR-20 — Account check history and report ownership (ADR-18 phase C, VeriFact reports only)
**Accepted** · 2026-10-01 (owner request: "save checks to your account"; also clear the browser list on sign-out)
- Signed-in checks send the bearer token. `account_checks` (V9; RLS in V10) holds each account's history: every report the user checked, including reused ones, with label and verdict copied at check time. `verifications.owner_id` marks the account whose request created the report (same "created during this request" rule as edit tokens, ADR-19), so it can delete it from any device.
- Cascades do the bookkeeping: report deleted (retention or owner) → its history rows go; account deleted → its history and the reports it owns go (`owner_id ON DELETE CASCADE`). Reused reports owned by someone else stay.
- Ownership is per user, not per organisation: the history is personal; team sharing waits for teams (ADR-18 phase "later").
- Signing out clears this browser's recent-checks list and report edit tokens (shared school computers). Signed in, the page shows the account history instead of the browser list and stops adding to the browser list.
- Not yet: NewsFact reviews and research workspaces stay edit-token only (not linked to accounts); claiming older browser-made reports into an account.

