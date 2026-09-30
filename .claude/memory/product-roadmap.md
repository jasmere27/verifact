# Product Roadmap

_Last updated: 2026-09-30. Status: ☐ not started · ◐ in progress · ☑ done._

## Phase 0 — Foundation (make it safe to put online)
- ☑ Merge `feature/supabase-db-and-deploy` into `main` (local fast-forward 2026-09-29; not pushed)
- ☑ Remove committed build/IDE artifacts; fix `.gitignore`
- ☑ Upgrade to Spring Boot 4.1.1 + Spring AI 2.0.1 + Java 21 (ADR-6)
- ☑ Real test suite with deterministic AI/search fakes (98 tests)
- ☑ SSRF-safe fetcher; rate limiting; input/upload limits; ProblemDetail errors; timeouts; request IDs; cheap health check
- ☑ Make `/history` non-public (disabled by default) until accounts exist
- ☐ Decide data retention for stored submissions

## Phase 1 — Core verification (the product)
- ☑ Backend-controlled pipeline (ADR-3) with structured output — API v2
- ◐ Replace Google CSE with a new `SearchProvider` (ADR-5) returning URL/title/date — Tavily implemented; needs a production key + live smoke test
- ☑ New taxonomy + evidence strength (ADR-4)
- ◐ Evidence model persisted — full report as JSON (ADR-7); normalised tables deferred
- ☐ Result page redesign: checked → assessment → why → supporting/contradicting evidence → sources/dates → limitations
- ☑ Eval set (20 labeled claims, opt-in live runner) + token/latency logging — live run 20/20 on 2026-09-30

## Phase 2 — Better verification
- ☐ Source credibility signals (source type, known publisher, date recency) — explainable, not a secret score
- ☐ Contradiction detection across sources; better uncertainty messaging
- ☐ Search/result caching for repeated claims

## Phase 3 — Multimodal
- ◐ Image: vision model reads screenshot directly (claims + visible context), OCR fallback — deployed 2026-09-30 (ADR-11)
- ☐ Audio: transcription via the configured AI provider; language support
- ☐ (Later) reverse image search / metadata signals; video only if clear demand

## Phase 4 — Accounts
- ☐ Auth (evaluate Supabase Auth JWT verified by Spring Security vs. alternatives)
- ☐ Private history, saved reports, shareable public report pages (opt-in)

## Commercial priorities (2026-09-30; see revenue-strategy.md, product-validation.md, experiments.md)
Revenue $0, no validated demand yet. Order of work (P = priority):
- ☑ P1 Measure (2026-09-30): Cloudflare Web Analytics live; cost per use estimated (~$0.04 check, ~$0.06 case; revenue-strategy.md).
- ☑ P1 E2 (prototype built, run 2 passed preliminarily 2026-09-30): legal-content audit prototype (page URL → legal claims → official-source check → POTENTIALLY_OUTDATED / POTENTIALLY_UNSUPPORTED / REQUIRES_REVIEW / UNABLE_TO_VERIFY), first used internally to produce sample audits; measure precision on ~100 real pages.
- P1 E1 (owner): personalised outreach to legal content/SEO agencies with sample audits; sell audits as a service before productising.
- P2 E3 (owner): 5-firm case-intelligence pilot on anonymised intake notes.
- ☑ P2 LegalFact nav: "Back to Fact check" link (deployed 2026-09-30, PR #3).
- Deferred until validated demand: accounts/teams, billing, public API, SSO, dashboards, consumer legal routing.

## ResearchFact (ADR-13, researchfact.md)
- ☑ R1: citation-check MVP (existence/match, retraction, claim support from abstracts with verbatim quotes, conflicting research), `/research` (2026-09-30)
- ☐ E5: validate with journal editors (see experiments.md) before any pricing
- ☐ Reference-parsing accuracy on a labelled set (200 real + 50 fabricated refs)

## LegalFact vertical (planned 2026-09-30 — see legalfact.md, ADR-12)
- ☐ L0: design sign-off, 10–15 hypothetical case eval set (vague, contradictory, multi-state, non-US, injection)
- ◐ L1: Case Intelligence MVP (US, employment tuned; allow-listed official sources; not stored) — deployed 2026-09-30 **unlisted** at https://verifact-blf.pages.dev/legal (PR #2); needs attorney wording review before linking it publicly
- ☐ L2: Evidence pack (source metadata, export/print with disclaimer)
- ☐ L3: Accounts + saved cases (needs Phase 4 auth, retention, delete)
- ☐ L4: Legal content audit (articles → potentially outdated/unsupported claims)
- ☐ L5: LegalMatch-style demo on a hypothetical case
- ☐ Validate with legal professionals before any billing

## Phase 5 — Monetization (only after usage data)
Candidates: higher limits, batch checks, API access, team workspaces, exportable reports.

## Phase 6 — Platform (possibilities)
Browser extension (verify selected text), public API, newsroom/education/moderation integrations.
