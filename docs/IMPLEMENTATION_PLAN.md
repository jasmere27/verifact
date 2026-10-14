# Implementation plan and status: VeriFact Intelligence

_Last updated: 2026-10-14. Branch `feature/intelligence-b2b` (not pushed, not deployed)._

## Status by phase

| Phase | Status |
|---|---|
| A. Audit and architecture | Done: `ARCHITECTURE.md`, `INTEGRATIONS.md`, `PRODUCT_VISION.md` |
| B. Product experience | Done: `/app` workspace with its own design system (dark navy, light variant), all nine sections, onboarding, loading/empty/error states, responsive (phone drawer). Visually checked with fixture data (desktop dark/light, 390 px). |
| C. Core data and monitoring | Done: V15/V16 migrations, projects, 4 connectors, scheduler + lease + retries + cron endpoint, dedupe, run history, source health |
| D. Investigation engine | Done on the NewsFact evidence pipeline: versions, diffs, evidence records, chronology, unresolved questions, analyst notes |
| E. Business workflows | Done: incidents (filters, search, sort, paging, assignment, statuses, notes, priority override), Response Studio (generation, grounding, revisions, approval, copy/export), reports (on screen + PDF) |
| F. Commercialisation | Done in code: plan catalog, trial, server-side entitlements and quotas, usage metering with cost data, Paddle checkout/plan change/cancel/portal, verified idempotent webhooks. **Not connected**: needs Paddle account + credentials |
| G. Production readiness | Tests pass (see below); security review items listed; **not deployed** |

## Tests
- Backend: full suite run on 2026-10-14 (see the final report for the exact count). New: `IntelligenceApiTest`
  (end to end over HTTP incl. cross-tenant attempts, roles, quotas, monitoring dedupe, failing sources, incident
  workflow, investigations/versions/evidence, Response Studio grounding, reports + PDF), `PaddleWebhookTest`
  (signatures, replay window, tampering, rotation, idempotency, out-of-order), `MonitoringRulesTest`,
  `RiskModelTest`, `InvestigationMapperTest`, `RateLimitTest` (workspace limiter), `PostgresMigrationsTest`
  (V15/V16 on real Postgres, RLS on every table).
- Frontend: `tsc -b`, `oxlint` (0 warnings), `vite build`. No frontend test runner exists.
- Not run: live evals against OpenAI/Tavily (cost money; not needed for this change since the evidence
  pipeline is reused unchanged except for an opt-out of video search).

## Cost estimates (ESTIMATE, from `revenue-strategy.md` unit costs; confirm with real usage data)
- Monitoring run: ≤ 8 Tavily credits ($0.064) + one quick LLM call (~$0.002).
- Investigation: ≤ 10 searches ($0.08) + 2 LLM calls (~$0.01).
- Response draft: 1 LLM call (~$0.005–0.01). Report: no AI cost.
- Worst case if a workspace uses its whole quota: Monitor ≈ $6 of $49; Intelligence ≈ $63 of $149;
  Business ≈ $240 of $399, before Paddle's 5% + $0.50. **Business margin is thin at full use**: lower its run
  quota, cut queries per run, or raise the price before launch. `usage_events` records real tokens and searches
  per workspace and feature to decide this.

## Remaining work, by business value
1. **Validate demand** (orchestrator rule): show the workspace to 5–10 agencies/brand teams before more build.
2. Connect Paddle (sandbox → live) per `INTEGRATIONS.md`; verify payout to the Philippines first.
3. Make monitoring run reliably on the host: always-on plan or external cron hitting the tick endpoint.
4. Email: alerts for new HIGH/CRITICAL incidents, member invitations, scheduled report delivery.
5. Public pricing/landing section for teams (outward-facing: needs the owner's OK; Paddle approval needs it).
6. Fetch full articles for the top cited sources (evidence is search snippets today).
7. Additional sources: GDELT (check terms), X pay-per-use API if customers ask.
8. Data export and per-workspace deletion; retention settings.
9. Frontend tests (Vitest + Testing Library) for the workspace's critical flows.

## Affected files
- Backend new: `src/main/java/com/ai/agent/verifact/intelligence/**`, `ai/UsageScope.java`,
  `db/migration/V15__intelligence.sql`, `db/vendor/postgresql/V16__intelligence_row_level_security.sql`.
- Backend changed: `SpringAiLlmClient` (usage counting), `EvidenceRetriever` (search counting),
  `SearchProvider`/`TavilySearchProvider` (`searchRecent`), `SafeUrlFetcher` (`fetchFeed`, shared hop logic),
  `NewsCheckService` (`withVideos` overload; default unchanged), `RateLimitFilter` (workspace limiter),
  `application.properties`, `.env.example`, `render.yaml`.
- Frontend new: `frontend/src/intel/**`. Changed: `router.ts`, `App.tsx` (lazy `/app`), `AccountMenu.tsx`, `SiteNav.tsx`.
- Tests new: `src/test/java/com/ai/agent/verifact/intelligence/**`; changed: `RateLimitTest`, test properties.
