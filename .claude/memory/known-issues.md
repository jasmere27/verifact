# Known Issues

_Last updated: 2026-09-30 (Phase 1). Ranked. Resolved items are listed at the bottom
for context; delete them once they stop being useful._

## Critical
0. **Rotate API keys.** The OpenAI, Tavily and Google keys were pasted into a chat on 2026-09-30. Replace them in the Render dashboard (and local `.env`), redeploy, then delete the old keys at each provider.
1. **Production search still needs a Tavily key.** Code supports Tavily (ADR-5), but until `TAVILY_API_KEY` is set, `auto` falls back to Google Custom Search, which stops working on 2027-01-01. Tavily integration has only been tested against its documented contract (mocked), not a live call.

## High
2. **Eval set is small.** Live run 2026-09-30 (gpt-5-mini + Tavily): **20/20**, including the prompt-injection case; ~15–30 s per check, ~600 + ~2,200 prompt tokens per check. 20 mostly well-known claims can't catch subtle failures: grow it with real user claims (recent news, local/Filipino topics, partly-true and misleading cases).
3. **v1 endpoints (deprecated) keep the old behaviour:** model-driven search, free-form markdown regex-parsed, and a prompt biased toward confident verdicts ("trusted source → real 100%", "use internal knowledge"). v2 fixes all of this; remove v1 once unused.
4. **Evidence is search snippets only.** v2 judges from titles/snippets, not full articles, so nuanced claims may land on INSUFFICIENT_EVIDENCE or be judged on thin excerpts. Possible next step: safe-fetch the top 2–3 cited pages for longer excerpts (cost/latency tradeoff).
6. **Audio path needs Google credentials** that no deploy config provides → `503` in practice. Also LINEAR16/en-US only.
7. **Stored submissions have no retention policy.** Every check (including user text) is persisted to `fact_check_results` indefinitely; no deletion path. Decide retention before public launch.

## Medium
8. **DNS rebinding** window between `UrlGuard` resolution and Jsoup's own connect-time resolution. Fix needs an HTTP client with a pluggable resolver or connecting by IP.
9. Rate limits are in-memory: reset on restart, per instance. Fine for one instance.
10. Images (vision, ADR-11): the image eval is 6 synthetic screenshots (`ImageVisionEvalIT`); grow it with real ones (👎 feedback on image reports). Defences against text in the image steering the model are prompt-level plus a judgement-word filter on the displayed context. A quick vision failure falls back to OCR (up to 3 LLM calls). Spring AI exposes no image `detail` setting. Image checks can't detect edited or out-of-context images (every image report says so).
11. `/history` JSON is Spring's `PageImpl` serialization (unstable per Spring Data); revisit when history returns with accounts.
12. Render `plan: starter` is paid; free tier (512 MB, sleeps after 15 min) is tight for JVM + Tesseract.
13. Local dev environment: JDK 21 installed at `~/.local/jdks/` (not on PATH by default); no Docker, so the Docker image build is untested locally.

14a. **X-Forwarded-For on Render:** verified 2026-09-30 that spoofed XFF headers don't bypass per-IP limits (right-most entry is Render's). Not yet verified that two real clients get separate buckets. With `TRUST_FORWARDED_FOR=true` the right-most entry is used; if Render/Cloudflare puts its own hop there, all users share one bucket. Check real headers on first deploy (alternative: `server.forward-headers-strategy=native`).
14b. (v1 only) Search-outage detection relies on the model echoing a marker. v2 detects outages directly.
14c. **No overall per-request deadline.** v2 worst case ≈ 2 model calls × 60 s × 2 attempts + 4 searches × 15 s. Acceptable for now; consider a global deadline if users hit it.
14d. Legacy Google provider sends its API key as a query parameter (would appear with HTTP DEBUG logging). Moot once Google is unused.
14e. Global daily cap (1000) can be exhausted by ~20 IPs → deliberate cost-over-availability tradeoff until accounts exist.

14f. Feedback has no admin view yet — query `verification_feedback` in Supabase. Worth a tiny internal page once volume grows; 👎 reports with comments are the best source of new eval cases.
14g. Link previews depend on the backend answering within 4 s; on the free plan a sleeping backend means the first share of a link may show the generic preview (crawlers cache it).

14h. **Production OpenAI config (2026-09-30):** the account ran out of credits, then `OPEN_AI_API_KEY` on Render was saved with a trailing newline. Resolved by deploy `1914930`: keys are trimmed at startup and the image OCR fallback is skipped on provider/quota/auth errors. The Render value itself still has the newline (harmless now); clean it next time the key is rotated. Keep auto-recharge or a budget alert on OpenAI so credits don't run out silently.

14i. **LegalFact (MVP, deployed unlisted at /legal on 2026-09-30):**
   - Latency 55–76 s per case (two gpt-5-mini calls, ~3–4k completion tokens each, mostly reasoning). Frontend waits 170 s; call 2 skipped after 75 s. Options: lower reasoning effort for these calls; cancel work when the client disconnects (not done: the streamer keeps running).
   - Coverage: only California has curated state sources; for CA cases the 10 source slots can fill with state pages and crowd out federal guidance (FLSA etc.). Consider reserving slots per level. No case law (CourtListener needs a commercial agreement).
   - Guards are deterministic but regex-based: paraphrased advice ("appears to have grounds") can still slip through; keep growing `AdviceLanguageTest` from eval output.
   - Search queries derived from the case (possibly with names) go to Tavily.
   - Shares the per-IP rate limit with fact checks although a case costs ~2×; add a legal-specific quota before public launch.
   - Wording not yet reviewed by a licensed attorney. LegalFact is linked publicly since 2026-09-30 (owner's decision); get the review done soon.

14k. **NewsFact (ADR-14):** saved reviews (`news_reviews`) kept indefinitely with no delete path; readable by anyone with the link; edit tokens live in one browser (lost token = read-only). Evidence is search snippets only. Add retention + delete before promoting.

14j. **ResearchFact (MVP, ADR-13):**
   - Judgments are abstract-only; ~40% of works lack open abstracts (NO_ABSTRACT). PubMed lookup failures also surface as NO_ABSTRACT (not distinguished yet).
   - Latency: production checks took 64–134 s (Render in Singapore → US-hosted indexes; 134 s while another check ran concurrently), close to the 170 s client limit. Options: parallelise non-Crossref lookups, cache DOI lookups, set SCHOLARLY_CONTACT_EMAIL (Crossref polite pool allows concurrency).
   - Outbound calls: up to ~50 sequential lookups per check (12 refs × Crossref/DataCite/OpenAlex/PubMed + searches); 50 s lookup budget; PubMed and Crossref list calls throttled per instance. No cache yet.
   - DataCite/Zenodo DOIs can be self-deposited: their abstracts are user-controlled (treated as untrusted; verdicts need a verbatim, claim-relevant quote). Consider showing lower trust for DataCite-only works.
   - Reference-parsing accuracy not yet measured on a labelled set (planned).

## Low / hygiene
14. Every report includes "Cybersecurity Tips" — capstone artifact; product value unclear.
15. README claims MIT license but there is no LICENSE file.
16. Reports are readable by anyone with the link (UUID) — the report page says so. No retention policy or deletion path: reports keep up to 1,500 chars of submitted/OCR/transcript text indefinitely. Add a retention job (e.g. 90 days) and a delete route before promoting sharing widely.

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
- (Phase 1 review) Self-corroborating links, subdomain/`site:` strength inflation, stance inversion on debunking articles, MISLEADING hedging, unbacked model summaries, prose-wrapped JSON → all fixed with tests.
- (review) Malformed redirect → 500; non-multipart upload → 500; requestId missing on Spring's own errors; health check tied to DB → all fixed.
