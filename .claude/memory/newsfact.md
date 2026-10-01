# NewsFact

_Last updated: 2026-09-30. Status: **MVP deployed** 2026-09-30 (PR #7; V4 applied on Supabase): https://verifact-blf.pages.dev/news. Production smoke test: create 200 (~99 s), load, review save with token 200, wrong token 403, CORS PUT preflight OK. Decisions: ADR-14._

## Positioning
**NewsFact by VeriFact: a fact-checking workspace for journalists and editors.** Article (link or text) →
typed claims → sources in their own words → quotes checked word for word → date/context issues → conflicting
sources → editor decisions (confirmed / needs work / disputed + notes) → report (Markdown, print). Own page
`/news`, workspace `/news/{id}`, own copy; shares the evidence core.

## How it works (`news/`)
- `NewsCheckService`: URL via `SafeUrlFetcher` (article's own site excluded from sources) or text → [LLM 1]
  ≤10 claims typed FACT/STATISTIC/QUOTE/DATE_TIME/ATTRIBUTION with the article's exact words (checked; quoted
  words and speaker must be in the article, else downgraded/dropped) → `EvidenceRetriever` (≤10 searches, ≤16
  sources) → quote check in code (`Grounding.findSpan`: FOUND_VERBATIM / NOT_LOCATED) → [LLM 2] verdict (VeriFact
  `Verdict`) + context issue (OUTDATED / OLD_EVENT_AS_NEW / MISSING_CONTEXT / MISATTRIBUTED) with sources' verbatim
  excerpts → unbacked verdicts → INSUFFICIENT_EVIDENCE, unbacked context flags dropped; conflict = supporting and
  contradicting excerpts from different sources.
- Supporting videos (`NewsVideoService`, `video/YouTubeVideoSearch`; ADR-16): starts right after claim
  extraction, in parallel with sources/verdicts (90 s cap; failure never fails the check). First
  `app.news.video-claims` (default 4) claims → one YouTube Data API search each (≤6 videos; `search.list` +
  `videos.list`, ~101 quota units, ~100 searches/day free) → [quick LLM] SUPPORTS / CONTRADICTS / CONTEXT per
  video from title, description and chapter list only. Code keeps a label only with a verbatim quote from the
  title/description; "claims made" must be verbatim spans; relevant timestamp must be one of the video's own
  chapters; earliest upload among relevant results is marked (not proof of original). No downloads, no
  transcripts (YouTube only lets owners download captions); key frames = YouTube's automatic thumbnails.
  Stored in `NewsCheck.videos` (null for older checks). No key (`YOUTUBE_API_KEY` blank) = section says it's off.
- Workspaces: `news_reviews` (V4): check + review JSON, SHA-256 of a random 256-bit edit token (shown once, kept in
  the creator's browser). Read by id (unguessable UUID); review changes need `X-Edit-Token`. Saving failures are
  errors (unlike VeriFact's best-effort report saving).

## Live check (NewsCheckEvalIT, 2026-09-30)
Kennedy "Ask not…" → FOUND_VERBATIM, SUPPORTED; Gandhi "Be the change…" → MISATTRIBUTED + sources conflict;
"world population is 7 billion" → not supported; ~67 s.

## Limits / next
- No accounts: anyone with the link can read a review; editing is tied to one browser's token (lost token = read-only).
- Retention: reviews kept indefinitely (same gap as VeriFact reports); add retention/delete before promoting.
- Snippet-level evidence (not full articles); paywalled sources invisible.
- Videos: V2 (upload / direct video-file link → ffmpeg frames + transcript; needs ffmpeg in the image and
  probably Render Standard) and V3 (Cloud Vision web detection for reused footage) planned, not built.
- Next if validated: accounts/teams, assignments, story monitoring for updates, CMS integration.
- Validation: E6 (experiments.md).
