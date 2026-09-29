# Known Issues

_Last updated: 2026-09-29 (initial investigation). Paths are relative to repo root.
"(F)" = only on `feature/supabase-db-and-deploy`._

## Critical
1. **SSRF.** `tool/UriContentTool.java` fetches any URL (user-supplied, and also callable by the LLM as a tool) with Jsoup, following redirects, with no scheme/host/IP checks. Can reach localhost, private networks, cloud metadata (169.254.169.254).
2. **Prompt injection → tool use.** Fetched page text and user text are interpolated directly into the prompt (`service/AiService.java` `{input}`) while the model holds fetch/search tools. A hostile page can steer tool calls (incl. the SSRF above) and the verdict.
3. **Fabricated citations.** `tool/GoogleSearchTool.java` returns only snippets (no URL/title/date), yet the prompt demands "at least two credible sources (HTML links)". Cited links are model-generated, not retrieved.
4. **Unbounded cost / abuse.** No auth, no rate limiting, no cap on tool-call loops, 50 MB uploads. Anyone with the URL can spend the OpenAI and Google quota.
5. **(F) Health check triggers a full verification.** `render.yaml` `healthCheckPath: /api/v1/isFakeNews?news=ping` → LLM call + search + DB row on every probe.
6. **Google Custom Search JSON API is discontinued on 2027-01-01** and closed to new customers — new developers cannot get keys; production breaks in ~3 months.

## High
7. **Prompt biases toward confident, knowledge-based verdicts.** "Trusted source → real, 100%", "use internal knowledge if search unavailable", "do not classify as unverified unless absolutely no evidence", fixed confidence rules (mixed = 50%). Confidence numbers are not grounded in anything measurable.
8. **(F) History is public.** `/history` exposes every submission from every user; IDs are sequential.
9. **Error text is fact-checked and persisted.** OCR/speech failures return strings like "Speech recognition failed: …" which flow into `isFakeNews` as if they were claims.
10. **Errors returned as HTTP 200** with raw `e.getMessage()` (leaks internals, breaks clients).
11. **Outdated, unsupported framework versions.** Spring Boot 3.4.5 (OSS support ended) and Spring AI 1.0.0-M8 (pre-GA milestone). Current: Spring Boot 4.1.x, Spring AI 2.0.x GA (June 2026).
12. **Unstructured LLM output**, regex-parsed in both backend (`FactCheckResponseParser`) and frontend (`parseResponse.ts`).
13. **Audio path likely non-functional in deployment.** Google Speech needs GCP service-account credentials that aren't configured anywhere; only LINEAR16 WAV, en-US; sync API limit ~1 min.

## Medium
14. No timeouts on `RestTemplate` or the LLM call.
15. Temp files from `analyzeImage` are never deleted; no content-type/magic-byte checks.
16. `System.out.println` logging of every query and page fetch; `printStackTrace`.
17. Tests: only `contextLoads`, which needs real env vars (and a DB on (F)) → effectively no test suite. No JDK in the dev environment.
18. `isUrl` and `getCurrentDateTime` exposed as LLM tools (wasted tokens; date should be injected).
19. Prompt promises "evaluate visual context and manipulation" for images, but only OCR text is sent — the model never sees the image.
20. "Consistency within same session" instruction — there is no session.
21. (F) Render `plan: starter` is paid; JVM + Tesseract on a 512 MB free instance is likely to be tight.

## Low / hygiene
22. Committed build artifacts and IDE files: `bin/` (compiled classes, incl. stale `MixedNewsAnalyzerTool`), `.classpath`, `.project`, `.settings/`; empty `New Text Document.txt`; template `HELP.md`.
23. README/CLAUDE.md partly stale (e.g., says VoiceToTextTool is a placeholder; README claims MIT license but no LICENSE file).
24. Every response includes "Cybersecurity Tips" — capstone artifact; product value unclear.
25. CORS `allowedMethods("*")`, `allowedHeaders("*")`.
26. (F) Frontend: result shown as raw markdown in `<pre>`; TRUE/FALSE needle gauge reinforces binary framing and false precision; dark mode only.
