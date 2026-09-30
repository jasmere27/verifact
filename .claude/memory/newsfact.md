# NewsFact

_Last updated: 2026-09-30. Status: **MVP built** (branch `feature/newsfact`). Decisions: ADR-14._

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
- Next if validated: accounts/teams, assignments, story monitoring for updates, CMS integration.
- Validation: E6 (experiments.md).
