# Known Issues

_Last updated: 2026-09-30 (after Phase 0a). Ranked. Resolved items are listed at the bottom
for context; delete them once they stop being useful._

## Critical
1. **Google Custom Search JSON API is discontinued 2027-01-01** and closed to new customers. New developers cannot get keys; production breaks in ~3 months. → Phase 1 `SearchProvider` (ADR-5).

## High
2. **Model-driven, unstructured verification.** The LLM decides when to search (unbounded tool loop, capped only by Spring AI internals) and returns free-form markdown that is regex-parsed in `FactCheckResponseParser` and `frontend/src/parseResponse.ts`. → Phase 1 pipeline (ADR-3).
3. **Prompt biases toward confident, knowledge-based verdicts.** "Trusted source → real, 100%", "use internal knowledge if search unavailable", "don't classify unverified unless absolutely no evidence", fixed confidence rules. Left unchanged in Phase 0a deliberately: verdict logic changes need the eval set first (ai-verification playbook).
4. **Citations are constrained, not enforced.** Search now returns URLs and the prompt forbids citing others, but nothing validates the model's citations server-side. → Phase 1 citation validator.
5. **Outdated framework versions.** Spring Boot 3.4.5 (OSS support ended), Spring AI 1.0.0-M8 (pre-GA). Current: Boot 4.1.x, Spring AI 2.0.x. → ADR-6, next step.
6. **Audio path needs Google credentials** that no deploy config provides → `503` in practice. Also LINEAR16/en-US only.
7. **Stored submissions have no retention policy.** Every check (including user text) is persisted to `fact_check_results` indefinitely; no deletion path. Decide retention before public launch.

## Medium
8. **DNS rebinding** window between `UrlGuard` resolution and Jsoup's own connect-time resolution. Fix needs an HTTP client with a pluggable resolver or connecting by IP.
9. Rate limits are in-memory: reset on restart, per instance. Fine for one instance.
10. Images: OCR only; the model never sees the image (prompt now says so honestly).
11. `/history` JSON is Spring's `PageImpl` serialization (unstable per Spring Data); revisit when history returns with accounts.
12. Render `plan: starter` is paid; free tier (512 MB, sleeps after 15 min) is tight for JVM + Tesseract.
13. Local dev environment: JDK 21 installed at `~/.local/jdks/` (not on PATH by default); no Docker, so the Docker image build is untested locally.

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
- No working tests → 87 tests with fakes.
