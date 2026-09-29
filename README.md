# VeriFact

**Verify Truth, Fight the False.**

VeriFact checks claims against the web and shows its work. Paste a claim, an article, or a link (or upload a screenshot or a short voice clip), and VeriFact:

1. finds the specific factual claims in it,
2. searches the web for evidence,
3. judges each claim **only against the sources it retrieved**, and
4. returns a report: what was checked, a verdict per claim, the supporting and contradicting sources with dates, and what remains uncertain.

Verdicts: **Supported · Partly supported · Misleading · Contradicted · Not enough evidence** (plus **Mixed results** across several claims). Evidence strength (Strong / Moderate / Limited) is computed from how many independent sites were cited. Neither the verdict nor its strength is taken on the model's word: citations are checked against what was actually retrieved.

VeriFact is an aid for checking information, not a final authority.

## Architecture

```
frontend/ (React + Vite, Cloudflare Pages) ──► Spring Boot API (Render) ──► Supabase Postgres
                                                     ├─► OpenAI (claim extraction + assessment)
                                                     └─► Tavily (web search)
```

- **Backend:** Java 21, Spring Boot 4.1, Spring AI 2.0, Flyway, Jsoup, Tesseract OCR, Google Cloud Speech (optional).
- **Pipeline:** at most 2 AI calls and 4 searches per check. The model has no tools. User content and web results are treated as untrusted data.
- **Safety:** SSRF-guarded link fetching, per-IP and global rate limits, RFC 9457 errors with request IDs, no secrets in logs.

Details: `.claude/memory/architecture.md`. API contract: `.claude/memory/api-contracts.md`.

## Quick start (local)

Prerequisites: Java 21+, Node 22+, a Postgres database (a free Supabase project works), an OpenAI API key and a Tavily API key. Tesseract is only needed for image checks.

```bash
cp .env.example .env            # fill in the values
set -a; source .env; set +a     # export them into your shell
./mvnw spring-boot:run          # API on http://localhost:8080 (Flyway creates the tables)

cd frontend
cp .env.example .env            # VITE_API_BASE_URL=http://localhost:8080
npm ci
npm run dev                     # http://localhost:5173
```

Or run the backend in Docker: `docker compose up --build` (reads `.env`).

## API (v2)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/v2/verifications` | `{"input": "claim, article text, or one http(s) link"}` | report |
| POST | `/api/v2/verifications/image` | multipart `file` (JPEG/PNG/GIF/BMP/TIFF, ≤10 MB) | report |
| POST | `/api/v2/verifications/audio` | multipart `file` (WAV, English, ≲1 min) | report |
| GET | `/api/v2/verifications/{id}` | | stored report (shareable) |
| GET | `/actuator/health` | | `{"status":"UP"}` |

```bash
curl -s -X POST http://localhost:8080/api/v2/verifications \
  -H 'Content-Type: application/json' -d '{"input":"The Eiffel Tower is in Rome."}'
```

Errors are `application/problem+json` with a user-safe `detail` and a `requestId`. `422` means no checkable claim was found or a link couldn't be read, `429` means you're rate limited (see `Retry-After`), and `503` means web search is down (VeriFact won't guess without evidence).

The v1 endpoints (`/api/v1/isFakeNews`, `/analyzeImage`, `/analyzeAudio`) still work but are **deprecated**.

## Configuration

Everything is configured through environment variables; `.env.example` lists them all with safe placeholders. The main ones:

| Variable | Purpose |
|---|---|
| `OPEN_AI_API_KEY` | AI provider key (optionally `SPRING_AI_OPENAI_CHAT_MODEL` to choose the model) |
| `TAVILY_API_KEY` | Web search (`SEARCH_PROVIDER=auto` uses Tavily when set; legacy Google keys are a fallback until 2027-01-01) |
| `SUPABASE_DB_URL`, `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD` | Postgres via the Supabase **Session pooler** |
| `ALLOWED_ORIGIN` | Comma-separated frontend origins for CORS |
| `RATE_LIMIT_*`, `MAX_INPUT_CHARS` | Abuse and cost limits |
| `TRUST_FORWARDED_FOR` | `true` behind a proxy that appends the client IP (Render) |

## Tests

```bash
./mvnw test                     # 147 tests; no keys, database, or network needed
cd frontend && npm run build && npm run lint
```

AI and search are faked in tests, and H2 stands in for Postgres. A **live evaluation** over 20 labeled claims (true, false, unverifiable, opinion, prompt injection) runs against the real services and costs a few cents:

```bash
RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=VerificationEvalIT
```

## Deployment

Cloudflare Pages (frontend) + Render (backend Docker image) + Supabase (Postgres). Step-by-step guide: **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**.

## Project structure

```
src/main/java/com/ai/agent/verifact/
  verification/   v2 pipeline, prompts, report model, persistence, API
  ai/             LLM client seam (Spring AI)
  search/         SearchProvider: Tavily, Google (legacy)
  fetch/          SSRF-safe URL fetching
  common/         errors, request IDs, rate limiting
  config/         CORS, clock
  controller/ service/ tool/ model/ repository/   v1 (deprecated) + OCR / speech
src/main/resources/db/migration/   Flyway migrations
frontend/         React + Vite SPA
docs/             deployment guide
.claude/          AI-assisted development setup (agents, commands, project memory)
```

## Known limitations

- Evidence comes from search-result snippets, not full articles, so nuanced claims can end up as "Not enough evidence".
- Images are read with OCR; the image itself isn't analysed.
- Audio requires Google Cloud credentials (`GOOGLE_APPLICATION_CREDENTIALS`).
- Anyone with a report's link can view it; there are no accounts yet.

Full, ranked list: `.claude/memory/known-issues.md`.
