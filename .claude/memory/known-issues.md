# Known Issues

_Last updated: 2026-09-30 (after Phase 0a). Ranked. Resolved items are listed at the bottom
for context; delete them once they stop being useful._

## Critical
1. **Google Custom Search JSON API is discontinued 2027-01-01** and closed to new customers. New developers cannot get keys; production breaks in ~3 months. → Phase 1 `SearchProvider` (ADR-5).

## High
2. **Model-driven, unstructured verification.** The LLM decides when to search (unbounded tool loop, capped only by Spring AI internals) and returns free-form markdown that is regex-parsed in `FactCheckResponseParser` and `frontend/src/parseResponse.ts`. → Phase 1 pipeline (ADR-3).
3. **Prompt biases toward confident, knowledge-based verdicts.** "Trusted source → real, 100%", "use internal knowledge if search unavailable", "don't classify unverified unless absolutely no evidence", fixed confidence rules. Left unchanged in Phase 0a deliberately: verdict logic changes need the eval set first (ai-verification playbook).
4. **Citations are constrained, not enforced.** Search now returns URLs and the prompt forbids citing others, but nothing validates the model's citations server-side. → Phase 1 citation validator.
6. **Audio path needs Google credentials** that no deploy config provides → `503` in practice. Also LINEAR16/en-US only.
7. **Stored submissions have no retention policy.** Every check (including user text) is persisted to `fact_check_results` indefinitely; no deletion path. Decide retention before public launch.

## Medium
8. **DNS rebinding** window between `UrlGuard` resolution and Jsoup's own connect-time resolution. Fix needs an HTTP client with a pluggable resolver or connecting by IP.
9. Rate limits are in-memory: reset on restart, per instance. Fine for one instance.
10. Images: OCR only; the model never sees the image (prompt now says so honestly).
11. `/history` JSON is Spring's `PageImpl` serialization (unstable per Spring Data); revisit when history returns with accounts.
12. Render `plan: starter` is paid; free tier (512 MB, sleeps after 15 min) is tight for JVM + Tesseract.
13. Local dev environment: JDK 21 installed at `~/.local/jdks/` (not on PATH by default); no Docker, so the Docker image build is untested locally.

14a. **Verify X-Forwarded-For on Render before relying on per-IP limits.** With `TRUST_FORWARDED_FOR=true` the right-most entry is used; if Render/Cloudflare puts its own hop there, all users share one bucket. Check real headers on first deploy (alternative: `server.forward-headers-strategy=native`).
14b. **Search-outage detection is weak.** `AiService` relies on the model echoing `Web Search is not available`, while the prompt still says to "continue using internal knowledge" if search is down. Replace with a tool-set flag / backend-run search in Phase 1.
14c. **No overall per-request deadline.** Model call ≤60 s × 2 attempts (max-retries=1) × several tool rounds. Bounded by Phase 1's fixed pipeline.
14d. Google API key is sent as a query parameter (would appear if RestTemplate DEBUG logging were enabled). Consider the `X-goog-api-key` header once verified against the Custom Search API — or moot after the search provider is replaced.
14e. Global daily cap (1000) can be exhausted by ~20 IPs → deliberate cost-over-availability tradeoff until accounts exist.

## Low / hygiene
14. Every report includes "Cybersecurity Tips" — capstone artifact; product value unclear.
15. README claims MIT license but there is no LICENSE file.
16. Frontend: result shown as raw markdown in `<pre>`; TRUE/FALSE needle gauge implies binary truth and false precision; dark mode only. → Phase 1 result page redesign.

## Resolved in Phase 0a (2026-09-30)
- SSRF via user URLs and LLM-callable fetch tool → `SafeUrlFetcher`/`UrlGuard`; fetch tool removed from the model.
- Prompt injection via undelimited content → delimited untrusted-content block, delimiter stripping, truncation.
- No rate limiting / unbounded cost → per-IP and global limits; Spring AI retries 2; timeouts.
- Health check triggered a paid verification → `/actuator/health`.
- Public history exposing all submissions → disabled by default.
- OCR/speech error strings fact-checked and persisted → typed errors (415/422/503).
- Errors as HTTP 200 with internal messages → problem+json, safe messages.
- Temp files never deleted → in-memory decoding.
- `System.out`/`printStackTrace` logging, API key in logged URLs → SLF4J, request IDs, no key in logs.
- Committed `bin/` and IDE files → removed and ignored.
- No working tests → 98 tests with fakes.
- Outdated/unsupported Spring Boot 3.4.5 + Spring AI 1.0.0-M8 → Boot 4.1.1, Spring AI 2.0.1, Java 21 (ADR-6).
- (review) Rate-limit bypass via `;params`/percent-encoded paths → filter matches the normalized path; regression tests prove variants route AND are counted.
- (review) 429/413 responses lacked CORS headers → CORS moved to a servlet filter ahead of the rate limiter.
- (review) Nested delimiter reconstruction → per-request random delimiter nonce; attacker URL removed from trusted prompt text (host only).
- (review) Unbounded rate-limiter map / per-request scans → capped at 100k keys, cleanup at most once a minute, IPv6 grouped by /64.
- (review) OCR memory exhaustion → 16 MP cap, at most 2 concurrent OCR jobs.
- (review) Malformed redirect → 500; non-multipart upload → 500; requestId missing on Spring's own errors; health check tied to DB → all fixed.
