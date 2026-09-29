# API Contracts

_Last updated: 2026-09-30 (Phase 0a). Base path `/api/v1`. No authentication yet._

## Conventions (all endpoints)
- **Errors:** non-2xx status + `application/problem+json` (RFC 9457): `{type, title, status, detail, instance, requestId}`. `detail` is safe to show users. Never contains stack traces or internal exception text.
- **Request ID:** every response has `X-Request-Id` (a well-formed incoming one is reused). Logged via MDC.
- **Rate limits** (verification endpoints only): per IP 5/min and 50/day, global 1000/day (env-configurable). Exceeded → `429` + `Retry-After` seconds.
- **CORS:** origins from `ALLOWED_ORIGIN`; methods GET/POST/OPTIONS; headers Content-Type, X-Request-Id; exposes X-Request-Id, Retry-After.

## Verification endpoints (success contract unchanged since v1)

### `GET|POST /api/v1/isFakeNews`
- In: `?news=<text or URL>` or JSON `{"news": "..."}` (JSON body wins if both). Max 10,000 chars.
- A single `http(s)://` token is treated as a link and fetched server-side (SSRF-guarded).
- Out: `200 text/plain` markdown report containing `**Classification:** real|fake|mixed|unverified` and `**Confidence Score:** N%`.
- Errors: `400` blank/malformed input or unsafe link · `413` too long · `422` link unreachable/unreadable · `429` · `502` AI provider failure/empty reply · `503` web search unavailable.
- Callers: `frontend/src/api.ts#checkText` (POST JSON).

### `POST /api/v1/analyzeImage`
- In: multipart `file`, ≤ 10 MB, JPEG/PNG/GIF/BMP/TIFF, ≤ 40 MP.
- Out: `200 text/plain` report (OCR text is what gets checked).
- Errors: `400` missing/empty file · `413` file or dimensions too large · `415` not a supported image · `422` no readable text · `503` OCR unavailable · plus the text-check errors.
- Caller: `frontend/src/api.ts#checkImage`.

### `POST /api/v1/analyzeAudio`
- In: multipart `file`, ≤ 10 MB, LINEAR16 WAV, en-US, ≲ 1 min.
- Out: `200 text/plain` report.
- Errors: `400` missing/empty · `413` · `422` no speech detected · `503` transcription unavailable (e.g. no Google credentials) · plus the text-check errors.
- Caller: `frontend/src/api.ts#checkAudio`.

## History (disabled by default)
`GET /api/v1/history?page=&size=` and `GET /api/v1/history/{id}` → `404 problem+json` ("History is not available yet.") unless `HISTORY_API_ENABLED=true`. When enabled: Spring Data `Page<FactCheckResult>` JSON / single `FactCheckResult`. Frontend `HistoryPanel` shows an "available once accounts exist" message on 404.

## Operational
`GET /actuator/health` → `{"status":"UP"}`. Only `health` is exposed; details hidden. Used by `render.yaml`.

## Breaking changes in Phase 0a (2026-09-30)
- Errors that used to be `200 text/plain` messages are now non-2xx `problem+json`. The bundled frontend was updated. Any external script that treated every 200 as a report must now check the status.
- History endpoints return 404 by default.
- Uploads capped at 10 MB (was 50 MB); images must be decodable formats.

## Proposed (not implemented)
`POST /api/v2/verifications` → structured JSON (claims, per-claim verdicts, evidence items, sources with dates, limitations). Defined during the Phase 1 plan.
