# API Contracts

_Last updated: 2026-09-30 (Phase 0a). Base path `/api/v1`. No authentication yet._

## Conventions (all endpoints)
- **Errors:** non-2xx status + `application/problem+json` (RFC 9457): `{type, title, status, detail, instance, requestId}`. `detail` is safe to show users. Never contains stack traces or internal exception text.
- **Request ID:** every response has `X-Request-Id` (a well-formed incoming one is reused). Logged via MDC.
- **Rate limits** (verification endpoints only, matched on the normalized path): per IP (IPv6 per /64) 5/min and 50/day, global 1000/day (env-configurable). Exceeded → `429` + `Retry-After` seconds.
- **CORS:** origins from `ALLOWED_ORIGIN`; methods GET/POST/OPTIONS; headers Content-Type, X-Request-Id; exposes X-Request-Id, Retry-After. Implemented as a servlet filter so 429/413 responses also carry CORS headers.

## Verification endpoints (success contract unchanged since v1)

### `GET|POST /api/v1/isFakeNews`
- In: `?news=<text or URL>` or JSON `{"news": "..."}` (JSON body wins if both). Max 10,000 chars.
- A single `http(s)://` token is treated as a link and fetched server-side (SSRF-guarded).
- Out: `200 text/plain` markdown report containing `**Classification:** real|fake|mixed|unverified` and `**Confidence Score:** N%`.
- Errors: `400` blank/malformed input or unsafe link · `413` too long · `422` link unreachable/unreadable · `429` · `502` AI provider failure/empty reply · `503` web search unavailable.
- Callers: `frontend/src/api.ts#checkText` (POST JSON).

### `POST /api/v1/analyzeImage`
- In: multipart `file`, ≤ 10 MB, JPEG/PNG/GIF/BMP/TIFF, ≤ 16 MP; at most 2 OCR jobs at once (else 503).
- Out: `200 text/plain` report (OCR text is what gets checked).
- Errors: `400` missing/empty file or non-multipart request · `413` file or dimensions too large · `415` not a supported image · `422` no readable text · `503` OCR unavailable · plus the text-check errors.
- Caller: `frontend/src/api.ts#checkImage`.

### `POST /api/v1/analyzeAudio`
- In: multipart `file`, ≤ 10 MB, LINEAR16 WAV, en-US, ≲ 1 min.
- Out: `200 text/plain` report.
- Errors: `400` missing/empty · `413` · `422` no speech detected · `503` transcription unavailable (e.g. no Google credentials) · plus the text-check errors.
- Caller: `frontend/src/api.ts#checkAudio`.

## History (disabled by default)
`GET /api/v1/history?page=&size=` and `GET /api/v1/history/{id}` → `404 problem+json` ("History is not available yet.") unless `HISTORY_API_ENABLED=true`. When enabled: Spring Data `Page<FactCheckResult>` JSON / single `FactCheckResult`. Frontend `HistoryPanel` shows an "available once accounts exist" message on 404.

## Operational
`GET /actuator/health` → `{"status":"UP"}`. Only `health` is exposed; details hidden; DB is excluded (persistence is best-effort). Used by `render.yaml`.

## Breaking changes in Phase 0a (2026-09-30)
- Errors that used to be `200 text/plain` messages are now non-2xx `problem+json`. The bundled frontend was updated. Any external script that treated every 200 as a report must now check the status.
- History endpoints return 404 by default.
- Uploads capped at 10 MB (was 50 MB); images must be decodable formats.

## v2 — structured verification (current; used by the frontend)
- `POST /api/v2/verifications` JSON `{"input": "..."}` (≤10,000 chars; a single http(s) token is fetched as a link)
- `POST /api/v2/verifications/image` multipart `file` · `POST /api/v2/verifications/audio` multipart `file`
- Images (2026-09-30): read by a vision model when `VISION_ENABLED` (default true); OCR is the fallback. Same upload limits and errors as before (`413`/`415` on bad uploads; `503` only when the OCR fallback is needed and unavailable).
- `GET /api/v2/verifications/{id}` → stored report (404 problem+json if unknown/malformed). Not rate limited.
- All three POSTs are rate limited like v1. Typical latency 10–40 s.
- Errors: `400` blank/unsafe link/bad upload · `413` · `415` · `422` link unreadable **or no checkable claim** · `429` · `502` AI failure/unparseable output · `503` search unavailable (never answers from model memory).

```ts
type Verdict = "SUPPORTED" | "PARTLY_SUPPORTED" | "MISLEADING" | "CONTRADICTED" | "INSUFFICIENT_EVIDENCE";
type OverallVerdict = Verdict | "MIXED";
type EvidenceStrength = "STRONG" | "MODERATE" | "LIMITED";
interface Evidence { id: string; url: string; domain: string; title: string; snippet: string; publishedDate: string | null; retrievedAt: string; }
interface ClaimAssessment { id: string; text: string; verdict: Verdict; evidenceStrength: EvidenceStrength; explanation: string; supportingEvidenceIds: string[]; contradictingEvidenceIds: string[]; }
interface VerificationResult { id: string; createdAt: string; inputType: "TEXT"|"URL"|"IMAGE"|"AUDIO"; input: string; checkedText: string;
  overallVerdict: OverallVerdict; summary: string; claims: ClaimAssessment[]; evidence: Evidence[]; limitations: string[]; searchProvider: string; durationMs: number;
  imageContext: ImageContext | null; }
// Added 2026-09-30 (additive, non-breaking). Only on images read by the vision model; null otherwise and absent on older reports.
// Model-read context, never evidence: values are as shown in the image, length-capped (source 120, date 60, description 400).
interface ImageContext { kind: "SOCIAL_MEDIA_POST"|"NEWS_HEADLINE"|"ARTICLE"|"CHART"|"MEME"|"PHOTO"|"DOCUMENT"|"OTHER";
  shownSource: string | null; shownDate: string | null; description: string | null; }
```
For images, `checkedText` is the text the vision model read (or its description if the image has no text); with the OCR fallback it's the OCR text. Image reports always include the limitation that VeriFact can't tell whether the image was edited.
Guarantees: every evidence ID referenced by a claim exists in `evidence`; SUPPORTED/PARTLY cite ≥1 supporting, CONTRADICTED cites ≥1 contradicting; evidence URLs are http(s); at most 3 claims and 10 evidence items. `publishedDate` format varies by provider.

### Streaming (used by the frontend)
`POST /api/v2/verifications/stream` (JSON `{"input"}`), `POST /api/v2/verifications/image/stream`, `POST /api/v2/verifications/audio/stream` (multipart `file`) → `text/event-stream`. Validation errors (400/413/415/429) are ordinary problem+json before the stream starts. Then events, each `data:` one JSON line:
- `stage` `{"stage":"READING_INPUT"|"EXTRACTING_CLAIMS"|"SEARCHING"|"ASSESSING"}`
- `claims` `{"claims":[...]}` · `sources` `{"count":n,"domains":[...≤8]}`
- exactly one of `result` (VerificationResult) or `error` `{"status","detail","requestId"}`
Runs on a virtual thread; 180 s emitter timeout; `X-Accel-Buffering: no`. Rate limited like the other POSTs.

`Evidence.sourceType`: `FACT_CHECKER|GOVERNMENT|ACADEMIC|REFERENCE|NEWS|OTHER|SOCIAL` (declaration order = display priority; null on reports stored before 2026-09-30 → treat as OTHER). Evidence is sorted by it before numbering. SOCIAL is capped at 2 per check, never counts toward strength, and can't alone back a verdict.

### Reuse, feedback, link previews (2026-09-30)
- `POST /api/v2/verifications[/stream]` body accepts `"refresh": true`. Without it, the same text/link (normalised: case, whitespace, surrounding quotes/end punctuation; links via normalised URL) checked within `REUSE_TTL_HOURS` (24) returns the stored report immediately — same id, older `createdAt`. Uploads are never reused. The frontend treats `createdAt` > 2 min before submit as reused and offers "Check again now".
- `POST /api/v2/verifications/{id}/feedback` `{"helpful": bool, "reason": "WRONG_VERDICT"|"BAD_SOURCES"|"MISSED_CLAIM"|"OTHER"|null, "comment": string≤500|null}` → 204; 400 invalid; 404 unknown report; 429 (own limit: 10/min/IP, separate from check quota). Stored in `verification_feedback`; never shown publicly (read it in Supabase).
- Link previews: Cloudflare Pages Function `frontend/functions/r/[id].ts` injects OG/Twitter tags for `/r/{id}` (4 s API timeout, falls back to the SPA). Images `frontend/public/og/{verdict}.png`, generated by `scripts/OgImages.java`.

**v1 is deprecated** (still served, unchanged) — it uses the older model-driven prompt. Remove once no callers remain.

## LegalFact — case intelligence (added 2026-09-30, ADR-12; branch `legalfact-mvp`)
- `POST /api/v2/legal/case-intelligence` JSON `{"description": "..."}` (40–10,000 chars) → `CaseIntelligence`.
- `POST /api/v2/legal/case-intelligence/stream` → `text/event-stream`, same events as verifications: `stage` (EXTRACTING_CLAIMS = organising, SEARCHING, ASSESSING), `claims` (issue topics), `sources`, then `result` or `error`.
- Nothing is stored; there is no GET. Rate limited with the verification endpoints (same per-IP/global buckets). Typical latency 40–60 s.
- Errors: `400` blank/too short · `413` too long · `422` nothing to organise · `429` · `502` AI failure · `503` search unavailable.

```ts
type Basis = "USER_STATED" | "SOURCE_BACKED" | "AI_INTERPRETATION";
interface CaseIntelligence { createdAt: string; practiceAreas: PracticeArea[];
  jurisdiction: { status: "IDENTIFIED"|"UNCERTAIN"|"OUTSIDE_US"; country: string|null; state: string|null; stateName: string|null; basisQuote: string|null };
  summary: string|null; keyFacts: { statement: string; userQuote: string; date: string|null; basis: Basis }[];
  timeline: { date: string|null; approximate: boolean; event: string; userQuote: string; basis: Basis }[];
  issues: { id: string; topic: string; note: string|null; basis: Basis; sources: { sourceId: string; whatItSays: string|null; relevance: string|null; basis: Basis }[] }[];
  missingInformation: { item: string; whyItMatters: string|null }[]; uncertainties: string[];
  sources: { id: string; url: string; domain: string; title: string; excerpt: string; publishedDate: string|null; retrievedAt: string; type: "STATUTE"|"REGULATION"|"OFFICIAL_GUIDANCE"|"GOVERNMENT" }[];
  notice: string; searchProvider: string; durationMs: number; }
```
Guarantees: every `userQuote` occurs in the description; dates are as written in it (else null); `IDENTIFIED` jurisdiction has a `basisQuote` from it; every `sourceId` is in `sources`; sources are on the official allow-list (`legal/LegalSources`); `whatItSays`/`relevance` contain no number absent from that source and no advice wording (else null); `notice` always present.

