# Architecture

_Last updated: 2026-09-29. Describes code as it exists; the target is at the bottom._

## Current (feature/supabase-db-and-deploy ⊇ main)

```
React SPA (frontend/, Cloudflare Pages)
   │  fetch, plain-text responses
   ▼
AiController  /api/v1/*                      (controller/AiController.java)
   ├─ isFakeNews (GET ?news= | POST {news})
   ├─ analyzeImage (multipart) → ImageOcrService (Tesseract) → AiService
   ├─ analyzeAudio (multipart) → VoiceToTextTool (Google Speech, LINEAR16/en-US) → AiService
   └─ history, history/{id} (feature branch) → FactCheckResultRepository
   ▼
AiService.isFakeNews(input)                  (service/AiService.java)
   ├─ if input is URL: UriContentTool.fetchContentFromUrl (Jsoup, no SSRF guard)
   ├─ one big PromptTemplate, user content interpolated inline
   ├─ ChatClient.prompt().tools(dateTimeTool, uriContentTool, googleSearchTool).call()
   │     the MODEL decides when to search / fetch URLs (unbounded tool loop)
   ├─ returns free-form markdown string
   └─ (feature) FactCheckResponseParser regex-extracts classification/confidence/sources → DB
```

- **Agentic, model-driven retrieval.** The LLM calls `searchWeb`, `fetchContentFromUrl`, `isUrl`, `getCurrentDateTime` as Spring AI `@Tool`s.
- **Search results carry only snippets** (no URL/title/date), so the model cannot cite real sources.
- **Output is unstructured text**; both the backend parser and the frontend (`parseResponse.ts`) regex-scrape `**Classification:**` and `**Confidence Score:**`.
- Verdicts: `real | fake | mixed | unverified` + a prompt-dictated confidence number.
- No auth, no users, no rate limiting, no request IDs, `System.out` logging.

### Data model (feature branch, `V1__init.sql`)
`fact_check_results(id bigserial, input_type, original_input text, classification, confidence_score, sources text, cybersecurity_tips text, full_response text, created_at timestamptz)` + index on `created_at desc`.

### Packages
`config/` (RestTemplate, CORS) · `controller/` · `service/` · `tool/` · (feature) `model/`, `repository/`.

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
