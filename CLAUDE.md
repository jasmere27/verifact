# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Orchestration (read first)

The main session acts as orchestrator for a team of specialist subagents. Protocol:

@.claude/orchestrator.md

- Specialist agents: `.claude/agents/`
- Commands: `/vf-investigate`, `/vf-plan`, `/vf-implement`, `/vf-review`, `/vf-qa`, `/vf-optimize`, `/vf-ship` (`.claude/commands/`)
- Playbooks: `.claude/playbooks/` (feature development, bug fix, AI verification, release)
- Project memory (keep current; don't fill with temporary details): `.claude/memory/` — start with `project-context.md` and `known-issues.md`

**Current state:** `.claude/memory/architecture.md` is the detailed, maintained description; update it when architecture changes.

## Project overview

VeriFact verifies claims submitted as text, a link, an image (read by a vision model, OCR fallback), or audio (speech-to-text). The v2 pipeline extracts claims with an LLM, searches the web itself, has the LLM judge each claim only against the retrieved evidence, validates the citations server-side, and returns a structured report. A React/Vite frontend lives in `frontend/`. Results are persisted to Supabase Postgres.

## Stack

Java 21 · Spring Boot 4.1 (modular starters, Jackson 3) · Spring AI 2.0 (OpenAI via openai-java SDK) · Spring Data JPA + Flyway · PostgreSQL (Supabase; H2 in tests) · Jsoup · Tess4j · Google Cloud Speech · Tavily Search (Google Custom Search as legacy fallback) · React 19 + Vite + TypeScript.

## Commands

The JDK is not on PATH in this environment:

```bash
export JAVA_HOME=$(ls -d ~/.local/jdks/jdk-21*) PATH=$JAVA_HOME/bin:$PATH
./mvnw test                         # all backend tests (no keys, DB, or network needed)
./mvnw test -Dtest=AiServiceTest    # one class
./mvnw clean package                # build jar (run `clean` after dependency changes; incremental builds hide errors)
./mvnw spring-boot:run              # needs .env values exported (see .env.example)
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=VerificationEvalIT   # live eval (costs money)
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=ImageVisionEvalIT    # live image check (costs money)
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=CaseIntelligenceEvalIT  # live LegalFact check; outputs in target/legal-eval/
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=ContentAuditEvalIT      # experiment E2: audits real law-firm pages; reports in target/content-audit/
RUN_EVALS=true OPEN_AI_API_KEY=... ./mvnw test -Dtest=ResearchCheckEvalIT                        # live ResearchFact known-answer check (Crossref/OpenAlex/PubMed)
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=NewsCheckEvalIT         # live NewsFact known-answer check

cd frontend && npm ci && npm run build && npm run lint
```

## Code map (`src/main/java/com/ai/agent/verifact/`)

- `verification/` — **the product**: API v2 (`VerificationController`), the pipeline (`VerificationService`: extract claims → search → cited assessment → server-side validation), prompts, DTOs, JSON report persistence (`VerificationStore`).
- `ai/` — `LlmClient` seam and `SpringAiLlmClient` (plain messages, JSON parsing, usage logging, `LlmException` failure kinds).
- `evidence/` — shared by all verticals: `EvidenceRetriever` (queries → de-duplicated, capped evidence; optional official-domain allow-list; refuses when search is down), `Evidence`, `SourceType`, `Urls`.
- `news/` — **NewsFact** (ADR-14, `.claude/memory/newsfact.md`): `NewsCheckService` (article → typed claims → web evidence → quote check in code → verdicts/context from sources' verbatim excerpts), `NewsStore` (`news_reviews`, edit tokens), `NewsController` (`/api/v2/news/checks`). Frontend: `frontend/src/news/`.
- `research/` — **ResearchFact** (ADR-13, `.claude/memory/researchfact.md`): `ResearchCheckService` (references + cited claims → `ScholarlyIndex` = `CrossrefOpenAlexIndex`: Crossref, DataCite, OpenAlex, PubMed → claim support from abstracts with verbatim quotes), `ResearchController` (`/api/v2/research/check`). Frontend: `frontend/src/research/`.
- `legal/` — **LegalFact** (ADR-12, `.claude/memory/legalfact.md`): `CaseIntelligenceService` (intake → allow-listed official sources → source matching, with quote/date/jurisdiction/citation/number checks and `AdviceLanguage` filter), `LegalController` (`/api/v2/legal/case-intelligence`). Legal information, never advice; nothing stored. Frontend: `frontend/src/legal/`.
- `controller/` — deprecated v1: `AiController` (`/api/v1/isFakeNews`, `/analyzeImage`, `/analyzeAudio`), `HistoryController` (disabled unless `HISTORY_API_ENABLED`).
- `service/AiService` — (v1) builds the prompt, calls the model with tools `dateTimeTool` + `webSearchTool`, maps failures to `ApiException`, persists results. `ImageOcrService` decodes/validates images, prepares them for the vision model (scaled, re-encoded JPEG), and runs Tesseract for the fallback. `FactCheckResponseParser` regex-extracts fields from the model's markdown.
- `search/` — `SearchProvider` interface, `TavilySearchProvider`, `GoogleCustomSearchProvider` (legacy), `SearchConfig` (selection via `SEARCH_PROVIDER`).
- `tool/` — Spring AI `@Tool`s given to the model: `WebSearchTool`, `DateTimeTool`. `VoiceToTextTool` is a plain service (not a model tool).
- `fetch/` — `UrlGuard` + `SafeUrlFetcher`: the only way the server fetches user-supplied URLs.
- `common/` — `ApiException`, `GlobalExceptionHandler` (problem+json), `RequestIdFilter`, `RateLimitFilter`, `FixedWindowRateLimiter`.
- `config/CorsConfig` — CORS as a servlet filter (so 429/413 responses get CORS headers).
- `model/`, `repository/` — `FactCheckResult` entity; schema in `src/main/resources/db/migration/` (Flyway, never edit applied migrations).

## Rules specific to this codebase

- Never give the model a tool that fetches URLs or has side effects; the backend fetches via `SafeUrlFetcher`.
- Verdict changes follow `.claude/playbooks/ai-verification.md`: run the eval before and after. Evidence strength and the overall verdict are computed in code, never taken from the model.
- Deployment: `docs/DEPLOYMENT.md`.
- User input and fetched/search content are untrusted: keep them inside the per-request nonce-delimited block in the prompt.
- Errors to clients go through `ApiException` / `GlobalExceptionHandler` with user-safe messages; never return exception messages.
- Don't log API keys, full user submissions at INFO, or URLs that carry keys.
- New config → `application.properties` with an env var, plus `.env.example` (and `render.yaml` if needed for deploy).
- Tests must not call real AI/search providers; use mocks, `MockRestServiceServer`, or local HTTP servers.
- Boot 4 notes: inject `tools.jackson.databind.json.JsonMapper` (not Jackson 2 `ObjectMapper`); test slices are in `org.springframework.boot.webmvc.test.autoconfigure`; AI timeouts/retries are `spring.ai.openai.*` properties.
