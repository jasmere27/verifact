# API Contracts

_Last updated: 2026-09-29. Base path `/api/v1`. All responses today are unauthenticated._

## Existing (keep backward compatible until frontend migrates)

### `GET|POST /api/v1/isFakeNews`
- In: `?news=<text or URL>` or JSON body `{"news": "..."}`.
- Out: `200 text/plain` — free-form markdown containing `**Classification:** real|fake|mixed|unverified` and `**Confidence Score:** N%`.
- Errors: also returned as **200** plain text (e.g., "Please provide news text…", "Failed to analyze the news: <exception message>").
- Callers: `frontend/src/api.ts#checkText` (POST JSON); `render.yaml` health check (GET `?news=ping`) — **must change, see known-issues**.

### `POST /api/v1/analyzeImage`
- In: multipart `file` (≤ 50 MB, any type). Out: `200 text/plain` markdown as above. Errors as 200 plain text.
- Caller: `frontend/src/api.ts#checkImage`.

### `POST /api/v1/analyzeAudio`
- In: multipart `file` (expects LINEAR16 WAV, en-US). Out: `200 text/plain`; `400` if empty; `500` with exception message on IOException.
- Caller: `frontend/src/api.ts#checkAudio`.

### `GET /api/v1/history?page=&size=` (feature branch)
- Out: Spring Data `Page<FactCheckResult>` JSON (`content[]`, `number`, `totalPages`, `totalElements`, …). `size` clamped 1..100.
- **Public: returns every user's submissions.**
- Caller: `frontend/src/api.ts#fetchHistory`.

### `GET /api/v1/history/{id}` (feature branch)
- Out: `FactCheckResult` JSON or 404. Sequential numeric IDs.

## Proposed (not implemented)
`POST /api/v2/verifications` → structured JSON result (claims, per-claim assessment, evidence, sources, limitations). Errors as RFC 9457 `application/problem+json`. Details to be defined in `/vf-plan`.
