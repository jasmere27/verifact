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

### Deletion and retention (2026-10-01, ADR-19)
- Checks (`POST /api/v2/verifications`, `/stream`, `/image/stream`, `/audio/stream`) accept an optional header `X-Edit-Token` (browser-generated, base64url, 32–128 chars; anything else is ignored). Its SHA-256 is stored only on a report the request created, never on a reused one. Response unchanged.
- `DELETE /api/v2/verifications/{id}` header `X-Edit-Token` → 204; 403 wrong/missing token or a report without one; 404 unknown/malformed. Feedback is deleted with the report.
- `DELETE /api/v2/news/checks/{id}` header `X-Edit-Token` (the edit token from creation) → 204; 403; 404.
- Retention: daily jobs (`app.retention.cleanup-cron`, default 03:27 UTC) delete reports + feedback 90 days after creation, NewsFact reviews 90 days after the last change, v1 `fact_check_results` 90 days after creation (research workspaces already: 90 days after last change).

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

## ResearchFact — citation check (added 2026-09-30, ADR-13)
- `POST /api/v2/research/check` JSON `{"text": "..."}` (30–10,000 chars) → `ResearchCheck`; `/check/stream` → SSE (`stage`, `claims`, `sources`, `result`/`error`). Nothing stored. Rate limited with the others. Typical 30–90 s.
- Errors: `400` too short · `413` too long · `422` no citations found · `429` · `502` AI failure.
```ts
type ReferenceStatus = "VERIFIED"|"FOUND_WITH_DIFFERENCES"|"RETRACTED"|"NOT_FOUND"|"LOOKUP_FAILED";
type Support = "SUPPORTED"|"PARTIALLY_SUPPORTED"|"OVERSTATED"|"CONTRADICTED"|"NOT_ADDRESSED_IN_ABSTRACT"|"NO_ABSTRACT"|"CITATION_PROBLEM"|"NEEDS_REVIEW";
interface WorkSummary { doi: string|null; url: string|null; title: string|null; authors: string[]; year: number|null; venue: string|null; publisher: string|null; citedByCount: number|null; notices: string[]; hasAbstract: boolean; }
interface ResearchCheck { createdAt: string;
  references: { id: string; textAsWritten: string; status: ReferenceStatus; differences: string[]; work: WorkSummary|null }[];
  claims: { id: string; quote: string; claim: string; referenceIds: string[]; support: Support; evidenceFrom: string|null; evidenceQuote: string|null; note: string|null;
            conflicting: { work: WorkSummary; quote: string; note: string|null }[] }[];
  referenceCounts: Record<ReferenceStatus, number>; supportCounts: Record<Support, number>; limitations: string[]; notice: string; durationMs: number; }
```
Guarantees: `evidenceQuote` and conflict quotes are verbatim from the named work's abstract (≤300 chars); statuses other than the five model verdicts are set by code only.

## NewsFact — story checks and reviews (added 2026-09-30, ADR-14)
- `POST /api/v2/news/checks` JSON `{"input": "<article URL or text>"}` (text ≥60 chars, ≤10,000) → `NewsWorkspace` (includes `editToken` once); `/stream` → SSE (`stage` incl. READING_INPUT for links, `claims`, `sources`, `result`/`error`).
- `GET /api/v2/news/checks/{id}` → `NewsWorkspace` (`editToken` null). Not rate limited.
- `NewsCheck.videos` (nullable; older checks): `{claims: [{claimId, query, videos: [{platform, videoId, url, title, channel, publishedAt, durationSeconds, thumbnailUrl, keyFrames, chapters, relevantAt, stance SUPPORTS|CONTRADICTS|CONTEXT, kind NEWS_REPORT|OFFICIAL|EYEWITNESS|OTHER, why, quote, claimsMade, earliestFound, embeddable}]}], searched, limitations, notice}`.
- `DELETE /api/v2/news/checks/{id}`: see Deletion and retention above.
- `PUT /api/v2/news/checks/{id}/review` header `X-Edit-Token`, body `{decisions: {"C1": {status, note}}, editorNote}` → `NewsReview`. 403 without the right token; ids `C1`–`C99`, ≤20 decisions, notes ≤1,000 chars. Shares the feedback limiter (10/min/IP).
- Errors: `400` · `413` · `422` link unreadable or no claims · `429` · `502` AI · `503` search or save failure.
```ts
type ClaimType = "FACT"|"STATISTIC"|"QUOTE"|"DATE_TIME"|"ATTRIBUTION";
type ContextIssue = "NONE"|"OUTDATED"|"OLD_EVENT_AS_NEW"|"MISSING_CONTEXT"|"MISATTRIBUTED";
interface NewsClaim { id: string; type: ClaimType; articleQuote: string; claim: string; speaker: string|null; quotedWords: string|null;
  verdict: Verdict; supporting: {sourceId: string; excerpt: string}|null; contradicting: {sourceId: string; excerpt: string}|null;
  contextIssue: ContextIssue; quoteStatus: "NOT_A_QUOTE"|"FOUND_VERBATIM"|"NOT_LOCATED"; quoteSource: {sourceId: string; excerpt: string}|null;
  sourcesConflict: boolean; explanation: string|null; }
interface NewsCheck { id: string; createdAt: string; articleUrl: string|null; articleTitle: string|null; articleDate: string|null;
  claims: NewsClaim[]; sources: Evidence[]; verdictCounts: Record<Verdict, number>; limitations: string[]; notice: string; searchProvider: string; durationMs: number; }
interface NewsReview { decisions: Record<string, {status: "UNREVIEWED"|"CONFIRMED"|"DISPUTED"|"NEEDS_WORK"; note: string|null}>; editorNote: string|null; updatedAt: string|null; }
interface NewsWorkspace { check: NewsCheck; review: NewsReview; editToken: string|null; }
```
Guarantees: every excerpt is the source's own words (≤300 chars); a verdict other than INSUFFICIENT_EVIDENCE and any context flag have an excerpt; quote status is computed in code.


## ResearchFact Student Research Mode (added 2026-09-30, ADR-15)
- `POST /api/v2/research/discover` `{topic (5–300), category, text? (≤3000; required for FOR_TEXT/SUPPORTING/CONTRADICTING), country? (ISO-2; required for LOCAL/FOREIGN)}` → `Discovery {category, topic, searches[], sources[FoundSource], leads[Lead], limitations[], notice, durationMs}`. Rate-limited like checks. 503 when the index is down (never an empty result).
- `POST /api/v2/research/workspaces` `{topic, field?, country?}` → `{workspace, editToken}` (token shown once; stored in the browser as `researchfact.token.{id}`).
- `GET /api/v2/research/workspaces/{id}` → `{workspace, editToken: null}`; 404 for unknown or expired.
- `PUT /api/v2/research/workspaces/{id}` (X-Edit-Token) `{topic?, field?, country?, notes?, sources?[{key, folder, studentNote}]}` → `Workspace`. `sources` can only keep/refile/annotate already-saved keys.
- `POST /api/v2/research/workspaces/{id}/sources` (X-Edit-Token) `{key, folder, relevance?, relevanceQuote?, stance?}` → `Workspace`; the work is re-fetched by key (404 if not in the index); relevance kept only if the quote is verbatim.
- `POST /api/v2/research/workspaces/{id}/draft` (X-Edit-Token, multipart `file`: PDF/DOCX/PPTX/TXT ≤10 MB) → `Workspace` with `draft {fileName, kind, pages, chars, truncated, uploadedAt, text, summary, concepts[], needsCitation[{quote, why}], referenceEntries, inTextCitations, citationCheckText, limitations[]}`. 415 unsupported type, 422 unreadable/scanned/password-protected. Counted like checks. `DELETE .../draft` removes it (light limiter).
- `POST /api/v2/research/workspaces/{id}/insights` (X-Edit-Token) → `Workspace` with `insights {generatedAt, basedOnSources, coverage, gaps[{statement, kind, basis[keys]}], relations[{key, title, kind, how, quote}], framework[{name, role, sourceKeys, verification}], limitations, notice}`; 400 with fewer than 3 saved sources with abstracts. Counted like checks.
- `Workspace` now also has `draft` and `insights` (null when absent; older workspaces load unchanged).
- `DELETE /api/v2/research/workspaces/{id}` (X-Edit-Token) → 204. Workspace writes share the light (feedback) limiter, now 20/min. CORS allows DELETE.

## Accounts (ADR-18)
- Auth: `Authorization: Bearer <Supabase access token>` (optional on every existing endpoint; an invalid/expired token → 401 problem+json with `WWW-Authenticate: Bearer`, so the client refreshes).
- `GET /api/v2/me` → `{id, email, displayName, organizations: [{id, name, personal, role OWNER|ADMIN|MEMBER}]}`; first call provisions the account and its personal organisation. 401 without a valid token.
- `DELETE /api/v2/me` → 204: deletes the Supabase Auth user (secret key, `apikey` header) then our rows (personal organisation and memberships cascade). 503 without `SUPABASE_SECRET_KEY`; 502 if Supabase fails (nothing deleted).
- `PATCH /api/v2/me` `{displayName}` (≤80 chars, control characters removed, blank clears) → same shape. Light rate limiter.
- CORS now allows `PATCH` and the `Authorization` header.
- **Check history (ADR-20, 2026-10-01):** checks (`POST /api/v2/verifications`, `/stream`, `/image/stream`, `/audio/stream`) sent with a valid bearer token add the report to the account's history and, if the request created the report, set `verifications.owner_id`. `DELETE /api/v2/verifications/{id}` also succeeds for that owner (with or without `X-Edit-Token`). Deleting the account deletes the reports it owns (FK cascade).
- `GET /api/v2/me/checks` → `[{id, checkedAt, overallVerdict, label, yours}]`, newest first, ≤100; `yours` = this account created the report. 401 signed out.
- `DELETE /api/v2/me/checks/{id}` → 204: removes from the history only (the report stays); unknown ids are ignored; malformed → 404.

## ResearchFact capstone projects (ADR-21, 2026-10-02; signed in, owner only)
- `GET /api/v2/me/projects` → `[{id, title, updatedAt, deletesAt, sources, questions, percent}]` (≤50, newest first). `POST` `{title, field, country}` → `ResearchProject` (≤20 per account). `POST /import` `{workspaceId, editToken}` copies a quick workspace.
- `GET|PUT|DELETE /api/v2/me/projects/{id}`; PUT `{title?, field?, country?, notes?, questions?: [{id?, text}] (≤10, ≤400 chars), gaps?: [{id?, statement, sourceKeys}] (≤20, keys must be in the library)}`; lists replace; removing a question unlinks it from the library.
- Library: `POST /{id}/library` `{key, folder, relevance, relevanceQuote, stance, questionId}` (re-fetched from the index; 404 if not found; ≤300 items) · `PUT /{id}/library` `{key, folder?, status? TO_READ|READ|CITED, note?, keyFindings?, method? (≤3,000 chars), questionIds?}` · `DELETE /{id}/library?key=…` (keys are DOIs with "/", so never in the path).
- `POST|DELETE /{id}/draft` (multipart `file`, same limits as workspaces), `POST /{id}/insights` (≥3 sources with abstracts). Rate limits: draft/insights count like checks; other writes use the light limiter.
- `ResearchProject` includes `progress {percent, milestones[{id,label,done,detail}]}` and `nextSteps [{id, priority HIGH|MEDIUM|LOW, title, detail, action, category, questionId, basis}]`, computed by `ProjectAdvisor`.
- **Files (ADR-23):** `POST /api/v2/me/projects/{id}/files` multipart `file` + `kind` DRAFT|PAPER + optional `label` (≤80) → `ResearchProject` (newest file first in `files`); 400 at 20 files. `GET /{id}/files/{fileId}` → `ProjectFile {id, kind, label, fileName, docKind, pages, chars, truncated, uploadedAt, text (papers), draft (Draft), paper (PaperAnalysis)}`. `PUT /{id}/files/{fileId}` `{label}` · `DELETE /{id}/files/{fileId}` → `ResearchProject`. The old `/{id}/draft` endpoints are gone (phase-1 drafts migrate to a file). `ResearchProject.files` = summaries `{id, kind, label, fileName, uploadedAt, pages, needsCitation, referenceEntries, match, matchedKey, title, findings}`; `NextStep.fileId` added; action `OPEN_FILES` added. Upload counts like a check (rate limit).

