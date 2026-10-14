# Architecture

_Last updated: 2026-10-14. The detailed, maintained description of the consumer products lives in
`.claude/memory/architecture.md`; this file covers the system as a whole and VeriFact Intelligence (ADR-26)._

## Current system (audit, 2026-10-14)

| Area | What exists |
|---|---|
| Frontend | React 19 + Vite + TypeScript SPA (`frontend/`), own minimal router (`router.ts`), Cloudflare Pages. Consumer products: VeriFact `/check`, NewsFact `/news`, LegalFact `/legal`, ResearchFact `/research`; homepage `/`. **New:** VeriFact Intelligence `/app/**` (lazy-loaded chunk). |
| Backend | Java 21, Spring Boot 4.1 modular monolith, Render (Docker, Singapore). Problem+json errors, request ids, per-IP rate limits. |
| AI | Spring AI 2.0 / OpenAI (gpt-5-mini default) behind `LlmClient`; prompts keep untrusted content in nonce-delimited blocks; no tools exposed to the model. |
| Search | `SearchProvider` (Tavily; Google CSE legacy). The backend searches, never the model. |
| Evidence core | `core/` (ADR-17): claim grounding, verbatim citation validation, verdict gates. NewsFact runs on it. |
| Media | Images (vision model, Tesseract fallback), audio (Google Speech; needs credentials), documents (PDFBox/POI). |
| Auth | Supabase Auth (JWT verified via JWKS); organisations + memberships (OWNER/ADMIN/MEMBER) in our DB; `OrganizationAccess.require` is the single membership check. |
| Data | Supabase Postgres via Flyway (V1–V16); H2 in tests; every table has RLS enabled with no policies (Data API locked out; backend is the table owner). |
| Billing | None before this work. |
| Tests | ~440 backend tests (JUnit, MockMvc, H2, embedded Postgres for migrations); frontend has typecheck + oxlint, no test runner. |

## VeriFact Intelligence (ADR-26)

```
React /app/{orgId}/{section}  ──Bearer JWT──►  /api/v2/me/orgs/{orgId}/**
                                                 │
                         IntelligenceAccess.require(jwt, orgId, role)  ← membership from our DB (404 / 403)
                                                 │  OrgContext (only way to reach a service)
   ┌────────────────────┬─────────────────────┬──┴─────────────────┬─────────────────────┬──────────────┐
 Monitoring          Incidents            Investigations        Response Studio        Reports      Billing
 ProjectService      IncidentService      InvestigationService  ResponseStudioService  ReportService SubscriptionService
 MonitoringRunner    RiskModel (rules)    InvestigationMapper   (LLM + grounding)      ReportPdf     BillingService
 SourceConnectors    Similarity           EvidenceLibrary                                            PaddleBillingProvider
 MentionStore        ──────────────── all SQL: WHERE organization_id = ? ─────────────────────────────────────────
```

### Tenancy and authorisation
- Every table has `organization_id`; every statement filters by it. Services accept `OrgContext`, which only
  `IntelligenceAccess.require` (after `OrganizationAccess.require`) can create, so an unchecked id can't reach SQL.
- Non-members get 404 for everything (ids can't be probed); weaker roles get 403. Ids from another workspace
  used through your own workspace are also 404 (tested in `IntelligenceApiTest`).
- Roles: MEMBER (work incidents, investigations, drafts, run checks), ADMIN (+ manage projects, people,
  approvals, priority overrides, deletions), OWNER (+ billing, roles). Seats are limited by plan.
- Supabase RLS is enabled on all new tables (V16) with no policies, as for existing ones.

### Monitoring pipeline
```
scheduler tick (1 min) or external cron or "Run now"
  → claim project lease → SubscriptionService.reserve(MONITORING_RUN)  (plan + monthly quota, row-locked)
  → QueryPlanner (bounded queries) → connectors: WEB_SEARCH, NEWS_SEARCH (Tavily, time-limited), YOUTUBE (Data API, recent uploads, daily budget), RSS, PAGE_WATCH (SafeUrlFetcher)
  → Relevance (code: entity terms required, exclusions, concern terms → HIGH/MEDIUM/LOW)
  → MentionStore.upsert (dedupe by normalised URL minus tracking params; copies flagged by content hash)
  → MentionClaimExtractor (≤ 8 mentions, one quick LLM call; quote must be verbatim in the retrieved text)
  → IncidentService: link to a similar open incident (word overlap) or open a new one; RiskModel score
  → persist run: per-source outcome (OK / PARTIAL / FAILED / UNAVAILABLE), counts, lateness; retries 15/30/60 min
```

### Investigation pipeline
Reuses `NewsCheckService` (claims grounded in the input → Tavily evidence → verdicts that stand only on
verbatim source excerpts, checked in code), without the video search. Results map to nuanced assessments
(supported / contradicted / mixed / insufficient / outdated or missing context), are stored as versions
(`investigation_versions`) with a computed diff, and each source becomes an `evidence_records` row
(Evidence Library). Runs are async with polling; interrupted runs are swept to FAILED.

### Response Studio
LLM drafts from the latest investigation version only. Server-side: unknown citations removed; sentences or key
facts with figures/case names not present in the evidence removed (`Grounding.supported`); evidence warning
computed from assessments, not by the model. Revisions are stored; approval needs ADMIN; editing an approved
draft returns it to DRAFT. Nothing is sent or published.

### Reports
Deterministic snapshot (no model-written text) of coverage, mentions, incidents, findings with citations,
themes (matched concern terms), evidence changes, rule-based actions, unresolved issues and limitations.
Stored as JSON; PDF rendered server-side with PDFBox.

### Billing and usage
Plans and quotas in `application.properties` (`app.billing.plans.*`), not code. `usage_events` records every
metered action with LLM calls/tokens and search calls (captured by `ai/UsageScope`), so pricing can follow
real costs. Paddle integration: see `docs/INTEGRATIONS.md`.

### Data model (V15)
`org_subscriptions`, `billing_events`, `usage_events`, `audit_events`, `monitoring_projects`, `monitoring_runs`,
`mentions`, `incidents`, `incident_events`, `investigations`, `investigation_versions`, `evidence_records`,
`response_drafts`, `response_draft_revisions`, `intelligence_reports`; `organizations.created_by`.
Additive only; no existing table changed except one nullable column. Indexes on every org-scoped list query.

### Why JdbcClient instead of JPA here
Fifteen tables of mostly read-model queries with filters and joins. Explicit SQL keeps the
`organization_id = ?` filter visible in every statement (easy to review for tenant isolation) and avoids
~15 entity classes. Existing products keep JPA.
