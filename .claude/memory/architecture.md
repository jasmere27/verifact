# Architecture

_Last updated: 2026-09-30 (Phase 1). Describes the code as it exists._

Modular monolith: one Spring Boot 4 app (Java 21) + a static React SPA. Deploy topology: ADR-8.

## Request path

```
React SPA (frontend/, Cloudflare Pages) ── JSON ──►
RequestIdFilter → CorsFilter → RateLimitFilter (normalized path; verification POSTs only) → Spring MVC
  ├─ VerificationController  /api/v2/verifications[/image|/audio|/{id}]      ← current
  ├─ AiController            /api/v1/isFakeNews|analyzeImage|analyzeAudio    ← deprecated (v1)
  ├─ HistoryController       /api/v1/history*  (404 unless HISTORY_API_ENABLED)
  └─ /actuator/health        (no DB/AI/search)
GlobalExceptionHandler → RFC 9457 problem+json with requestId for every error
```

## v2 verification pipeline (`verification/VerificationService`, ADR-3/4/7)

```
input ─► text | link → SafeUrlFetcher (UrlGuard every hop) | image → ImageOcrService | audio → VoiceToTextTool
      ─► truncate (MAX_CONTENT_CHARS)
      ─► LlmClient #1  ClaimExtraction   (≤3 claims, ≤2 queries each; no tools; nonce-delimited content)
             no claims → 422
      ─► SearchProvider (Tavily | Google legacy)  ≤4 queries, ≤4 results each, dedupe by URL, ≤10 evidence E1..En
             all searches failed → 503 (never answer from memory)
             no evidence → every claim INSUFFICIENT_EVIDENCE, skip LLM #2
      ─► LlmClient #2  Assessment        (per-claim verdict + cited evidence IDs + explanation + limitations)
      ─► validate: drop unknown IDs, downgrade unbacked verdicts, strength from distinct cited domains,
                   overall = common verdict else MIXED (all computed server-side)
      ─► VerificationStore.save (JSON in `verifications`, best-effort) ─► VerificationResult JSON
```

- `ai/LlmClient` is the provider seam; `SpringAiLlmClient` sends plain `SystemMessage`/`UserMessage` (no templating) and parses JSON with `BeanOutputConverter`; logs model/tokens/latency per step.
- Provider/model: Spring AI OpenAI starter (`SPRING_AI_OPENAI_CHAT_MODEL`), `max-retries=1`, `timeout=60s`.
- Prompts: `verification/VerificationPrompts`.

## Legacy v1 (deprecated)
`AiService` → one PromptTemplate, the model calls `WebSearchTool`/`DateTimeTool` itself, returns markdown; `FactCheckResponseParser` regex-extracts fields into `fact_check_results`.

## Data model (Flyway)
- `V1__init.sql` `fact_check_results` — v1 reports (text columns).
- `V2__verifications.sql` `verifications(id uuid pk, created_at, input_type, overall_verdict, search_provider, duration_ms, result_json text)` + index on `created_at desc`.

## Packages (`com.ai.agent.verifact`)
`ai/` LLM seam · `verification/` v2 pipeline, DTOs, persistence, controller · `search/` providers + selection · `fetch/` SSRF-safe fetching · `common/` errors, request ID, rate limit · `config/` CORS, clock · `controller/`, `service/`, `tool/`, `model/`, `repository/` v1 + shared OCR/speech.

## Frontend (`frontend/`)
React 19 + Vite + TS, no router library. Home (check form, recent checks in localStorage) and report view `/r/{id}` (loads via GET). `public/_redirects` for SPA deep links. Talks only to v2.
