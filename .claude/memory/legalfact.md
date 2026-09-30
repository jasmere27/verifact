# LegalFact

_Last updated: 2026-09-30. Status: **L1 Case Intelligence MVP built** on branch `legalfact-mvp` (not deployed). Design decisions: ADR-12._

## Vision
**LegalFact by VeriFact — AI-powered legal information and evidence intelligence.** A vertical on the
VeriFact evidence engine that turns a messy, natural-language description of a legal problem into
structured, sourced case information a professional can review quickly. It assists legal
professionals; it never replaces them and never gives legal advice.

Differentiation (not "ChatGPT for lawyers"): every statement is traceable either to the user's own
words or to a retrieved official source; citations are validated in code; jurisdiction is detected,
not assumed; missing information is surfaced instead of guessed.

## Users (hypotheses — not validated)
- Legal intake staff / attorneys reviewing incoming consumer case descriptions (primary).
- Consumers describing a problem before talking to a lawyer (secondary; highest UPL/wording risk).
- Legal publishers auditing articles for outdated claims (later: Content Audit).

## LegalMatch (possible customer only)
Public workflow (2026-09-30, legalmatch.com/how-it-works): consumers answer a guided questionnaire
(problem type, location, details in a consultation-style Q&A) and stay anonymous until they choose;
attorneys get alerts for cases in their practice area and location and review within ~24 h. No
public API or partner program found. Plausible fit: structuring free-text case details, flagging
missing information before an attorney sees it. Do **not** claim LegalMatch needs or would buy this;
any integration is a business conversation. The demo uses hypothetical cases only.

## Safety boundaries (non-negotiable)
1. Legal **information**, not advice. Never: "you have a case", "you will win", "you should sue",
   "this is illegal", or claims of lawyer-level quality (FTC v. DoNotPay, final order 2025-01-16).
2. Three labels on every statement: **User-stated** (the default for case facts — web search can't
   verify private events), **Source-backed** (a retrieved official source says it; for legal rules,
   not for the user's story), **AI interpretation** (clearly marked, cautious wording).
3. Authorities only from sources retrieved in this run, cited by evidence ID and validated in code.
   No statute or case from model memory. Unverifiable → dropped or shown as "Unable to verify".
4. Jurisdiction detected with the quote that supports it; otherwise "Uncertain" plus what's needed.
   Never apply one jurisdiction's law to another.
5. "Not provided" instead of guesses; approximate dates stay approximate.
6. Every result and export: "AI assistance — not legal advice. Professional review required."
   plus "consult a licensed attorney in [jurisdiction]".
7. Case text is sensitive: not logged; not stored in the MVP (see Privacy); injection-delimited.
8. Issue-spotting framing only ("potential issues to discuss with a lawyer"), never "these facts
   meet element X" — tailoring the law to the user's facts moves toward UPL (state-specific).

## Legal sources (researched 2026-09-30; re-check before relying on limits/terms)
| Source | Use | Notes |
|---|---|---|
| Tavily search, `include_domains` + `include_domains_mode=restrict` | MVP retrieval over an allow-list | ≤300 domains; 1,000 free credits/mo, then $0.008/credit |
| govinfo.gov (GovInfo API) | US Code, CFR, Federal Register | free, api.data.gov key, public domain |
| ecfr.gov (eCFR API) | current CFR text + search | free, no key; not the official legal edition |
| uscode.house.gov | US Code | official site |
| leginfo.legislature.ca.gov | California codes | no API; bulk "pubinfo" files; search via allow-list |
| eeoc.gov, dol.gov, calcivilrights.ca.gov, dir.ca.gov | agency guidance (employment) | web pages only |
| CourtListener (Free Law Project) | case law + citation lookup API | free tier 125 req/day (since May 2026); **commercial use needs an agreement**; may not imply FLP endorsed our analysis. Case law is out of the MVP |
| Cornell LII | link only | CC BY-NC-SA — no commercial reuse/scraping |
| Caselaw Access Project | — | API shut down Sept 2024; data is in CourtListener |

Source priority (depends on the question): statutes/regulations and official agency guidance →
court opinions → official secondary sources → reputable secondary legal sources → news/other.
SourceType for legal evidence: STATUTE, REGULATION, COURT, GOVERNMENT, OFFICIAL_GUIDANCE,
SECONDARY_SOURCE, NEWS, OTHER.

## Architecture (planned — ADR-12)
Module inside the monolith: `com.ai.agent.verifact.legal` (controller, service, prompts, outputs,
legal source types), reusing the core through narrow seams:
- `LlmClient` (unchanged), nonce-delimited prompts, server-side citation validation.
- Evidence retrieval extracted from `VerificationService` into a shared component that accepts
  search options (domain allow-list). `SearchProvider` gains an options parameter; Tavily maps it
  to `include_domains`.
- API: `POST /api/v2/legal/case-intelligence` (+ `/stream`), separate rate-limit bucket.
- Frontend: `/legal` route, own components (`frontend/src/legal/`), shared design tokens.
Extraction into a standalone product later = move the package + frontend folder, not a rewrite.

## MVP: Case Intelligence (first slice)
Input: one hypothetical case description (≤10k chars), US only in v1, employment as the tuned
practice area (others allowed, flagged as less tested).
Pipeline (2 LLM calls, like VeriFact):
1. **Intake** (LLM #1, delimited): practice area(s), jurisdiction {country, state, basis quote or
   UNCERTAIN}, neutral summary, key facts (each with the user's supporting quote and date as
   stated/approximate), timeline, potential issues (as questions/topics), missing information,
   2–4 search queries per issue.
2. **Code checks**: every fact's quote must occur in the input (else dropped); dates not in the
   input → "approximate/unclear"; nothing from intake is labelled source-backed.
3. **Retrieval**: allow-listed search per issue (federal + detected state domains only).
4. **Authorities** (LLM #2, delimited): per issue, "potentially relevant" sources by evidence ID,
   what the source says (quote/snippet), relationship, uncertainty. Code drops unknown IDs.
5. **Output**: the Case Intelligence report + disclaimer. Not stored.
Success criteria: zero fabricated citations and zero advice phrasing across the eval set;
facts traceable to input; an attorney-style reviewer finds it faster to read than the raw text.

## Current implementation (2026-09-30)
- Backend `legal/` + `/api/v2/legal/case-intelligence(/stream)`; frontend `/legal` (`frontend/src/legal/`). Professional-facing copy.
- Curated state sources: California only; other states get federal sources and an uncertainty note.
- Live eval `CaseIntelligenceEvalIT` (7 hypothetical cases: CA employment, TX deposit, UK, injection with a fake citation and planted advice, multi-state, contradictory, vague) passes all invariants; outputs saved to `target/legal-eval/`. 55–76 s per case, ~3–4k completion tokens per call; frontend waits up to 170 s; the source-matching call is skipped after 75 s.
- Deterministic guards (after legal + security/AI review):
  - quotes ≥3 words/15 chars, verbatim in the input; statements must share most content words with the input, else shown as the quote; advice-like quotes dropped
  - dates must appear as written (contiguous phrase, ordinals normalised)
  - jurisdiction IDENTIFIED only if the quote is in the input and names the state (name or capitalised code); a city alone → Uncertain + a note
  - OUTSIDE_US: facts/timeline only; no legal topics or missing-information items
  - `Grounding`: no digits, number words or case names ("X v. Y") in model text unless in the input (intake fields) or the cited source's title/excerpt (source notes)
  - `AdviceLanguage`: have a case / will win / should sue / entitled to / illegal / violated / rules were met or violated / time-barred / damages / strength of claims / meets legal definitions / grounds for a claim
  - model uncertainties claiming federal law doesn't apply are dropped; the sources prompt says federal applies in every state
  - allow-listed domains only; press releases/reports typed NEWS_OR_REPORT; citations validated; duplicate URLs (case) merged
  - parse failures never carry model output into logs
- Labels: facts/timeline USER_STATED; source summaries SOURCE_BACKED; relevance, topics, summary, conflicts AI_INTERPRETATION.
- Report adds "Inconsistencies to clarify" (quotes of each version) and "Missing information: questions to ask"; unmatched sources are collapsed; notice top and bottom with "consult a licensed attorney in [state]".

## Privacy
MVP stores nothing (result returned only; no share link). Storing cases requires auth (Supabase
Auth JWT verified by Spring Security), per-user access, a retention job and a delete path first.

## Roadmap (LegalFact phases; see product-roadmap.md)
L0 design + eval set → L1 Case Intelligence MVP → L2 evidence pack (source metadata, export) →
L3 accounts + saved cases → L4 content audit → L5 LegalMatch-style demo → validation with real
legal professionals before any billing.

## Open questions
- Who reviews the wording (a licensed attorney) before any public launch?
- Consumer-facing or professional-only first? (Professional-only lowers UPL risk.)
- Case law: CourtListener commercial agreement, or stay statutes/regulations/guidance only?
- Non-US input: reject, or "Uncertain jurisdiction" only?
- Is the California AI rules amendment (COPRAC, March 2026) adopted? Affects attorney users.
