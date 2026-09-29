# Architecture

_Last updated: 2026-09-30. Describes code as it exists; the target is at the bottom._

## Current (after Phase 0a)

```
React SPA (frontend/, Cloudflare Pages)
   │  fetch; 200 text/plain reports, problem+json errors
   ▼
RequestIdFilter → RateLimitFilter (verification paths) → CORS
   ▼
AiController  /api/v1/*                      (controller/AiController.java)
   ├─ isFakeNews (GET ?news= | POST {news})
   ├─ analyzeImage (multipart) → ImageOcrService (in-memory decode + size checks, Tesseract) → AiService
   ├─ analyzeAudio (multipart) → VoiceToTextTool (Google Speech, LINEAR16/en-US) → AiService
HistoryController /api/v1/history* → 404 unless HISTORY_API_ENABLED
GlobalExceptionHandler → problem+json for ApiException, MVC errors, and a generic 500
   ▼
AiService.isFakeNews(input)                  (service/AiService.java)
   ├─ if input is a single http(s) token: SafeUrlFetcher (UrlGuard on every hop, size/time caps)
   ├─ content sanitized (delimiters stripped, truncated) and placed in an untrusted-content block
   ├─ ChatClient.prompt().tools(dateTimeTool, googleSearchTool).call()
   │     the MODEL still decides when to search (no fetch tool any more)
   ├─ returns free-form markdown string; 502 on provider failure, 503 if search was down
   └─ FactCheckResponseParser regex-extracts classification/confidence/sources → DB (failure is logged, not fatal)
```

- **Model-driven search.** The LLM calls `searchWeb` and `getCurrentDateTime` as Spring AI `@Tool`s. URL fetching is backend-only.
- **Search results** are `title | url | snippet` lines; the prompt forbids citing other URLs (not yet validated server-side).
- **Output is unstructured text**; both the backend parser and the frontend (`parseResponse.ts`) regex-scrape `**Classification:**` and `**Confidence Score:**`.
- Verdicts: `real | fake | mixed | unverified` + a prompt-dictated confidence number.
- No auth or users. In-memory rate limits; SLF4J logging with request IDs; `/actuator/health`.

### Data model (feature branch, `V1__init.sql`)
`fact_check_results(id bigserial, input_type, original_input text, classification, confidence_score, sources text, cybersecurity_tips text, full_response text, created_at timestamptz)` + index on `created_at desc`.

### Packages
`common/` (errors, request ID, rate limit) · `config/` (RestTemplate timeouts, CORS) · `controller/` · `fetch/` (UrlGuard, SafeUrlFetcher) · `model/` · `repository/` · `service/` · `tool/`.

## Target (proposed, pending approval; see decisions.md)

Modular monolith, backend-controlled pipeline:

```
verification/   VerificationController (v2 JSON API), VerificationService (pipeline), DTO records
claims/         ClaimExtractor (LLM #1, structured)
search/         SearchProvider interface → one impl (chosen via research), result cache
fetch/          SafeContentFetcher (SSRF guard, size/time caps, redirect re-validation)
evidence/       EvidenceItem, Source (publisher, domain, published_at, retrieved_at)
assessment/     Assessor (LLM #2, structured, cites evidence IDs) + CitationValidator
ai/             provider config (AI_PROVIDER / AI_MODEL), usage/cost logging
media/          image → text (OCR or vision model), audio → transcript
history/        persistence + read APIs (scoped per user once auth exists)
common/         ProblemDetail error handling, request ID filter, rate limiting
```

Legacy `/api/v1/isFakeNews`, `/analyzeImage`, `/analyzeAudio` stay working (adapter over the new pipeline) until the frontend migrates.
