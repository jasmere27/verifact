# ResearchFact

_Last updated: 2026-09-30. Status: **MVP deployed** 2026-09-30 (PR #5, time-budget fix PR #6): https://verifact-blf.pages.dev/research. Production sample: all four references correct (verified ×2, retracted, not found), 64–134 s. Decisions: ADR-13._

## Positioning
**ResearchFact by VeriFact: citation integrity for research writing.** Checks that every citation
exists and matches, flags retractions/corrections, and compares each cited claim with what the cited
paper's abstract actually says (verbatim quote), plus other research reporting a different finding.
Separate product (own page `/research`, workflow, copy, future pricing) on the shared evidence core.

## Why this angle (research 2026-09-30; labels per product-growth-agent)
- Existence + retraction checks are commoditised: Zotero retraction alerts (free, FACT), scite Reference
  Check (free tier; Basic ~$20/mo, Pro ~$50/mo, FACT via scite.ai/pricing), open-source hallucinated-
  reference checkers that only test existence (FACT, arXiv 2607.22693).
- Gap: whether the cited paper *supports* the claim, with a verbatim quote: HYPOTHESIS differentiator.
- Paying segments (HYPOTHESIS): small/society journals and editorial offices screening submissions
  (publishers already pay for integrity screening: Clear Skies, Signals; ERS screens all references,
  MARKET CLAIM); university writing centres/integrity offices. Individuals have cheap/free options.

## Data sources (researched 2026-09-30; re-check before relying on limits)
| Source | Use | Terms |
|---|---|---|
| Crossref REST | DOI lookup, `query.bibliographic` matching, `updated-by` notices | free; polite pool with `mailto` (`SCHOLARLY_CONTACT_EMAIL`); limits changed 2025-12 and 2026-07 |
| DataCite REST | DOIs outside Crossref (arXiv, Zenodo, datasets) incl. abstracts | free |
| OpenAlex | DOI lookup (free), `is_retracted` (Retraction Watch), abstracts (inverted index), citation counts, related-work `search=` ($0.001 each) | CC0 metadata; free $1/day budget per key, smaller without (`OPENALEX_API_KEY` optional); withholds many publishers' abstracts |
| PubMed E-utilities | abstract fallback by PMID (from OpenAlex ids) | free; 3 req/s keyless |
| Semantic Scholar | **not used** | licence restricts commercial redistribution |

## How it works (`research/`)
text → [LLM 1] references as written + cited claims → resolve each reference (DOI only if it's in the text;
else bibliographic match with title similarity ≥0.75) → retraction/notices → abstracts (Crossref → OpenAlex →
PubMed / DataCite) → related works with abstracts (≤4 OpenAlex searches) → [LLM 2] support per claim from
abstracts only → code checks.
- Reference status (code): VERIFIED / FOUND_WITH_DIFFERENCES (year ±1, first author, title) / RETRACTED /
  NOT_FOUND / LOOKUP_FAILED (outage ≠ not found).
- Claim support: SUPPORTED / PARTIALLY_SUPPORTED / OVERSTATED / CONTRADICTED / NOT_ADDRESSED_IN_ABSTRACT (model,
  only with a verbatim abstract quote from a cited work) · NO_ABSTRACT / CITATION_PROBLEM (code) · NEEDS_REVIEW
  (verdict without a verbatim quote).
- Conflicts: related works only with a verbatim conflicting quote from their abstract.
- Quotes ≤300 chars, links to DOIs; nothing stored (abstracts can be copyrighted).

## Live check (ResearchCheckEvalIT, 2026-09-30)
Known-answer text: arXiv DOI (found via DataCite, SUPPORTED with quote), LeCun 2015 Nature without DOI (VERIFIED;
abstract via PubMed; SUPPORTED), overstated "solved general AI" claim (NOT_ADDRESSED, never supported), Wakefield
1998 (RETRACTED), fabricated reference (NOT_FOUND). ~58 s. First run found and fixed: DataCite DOIs, missing
abstracts (PubMed fallback), no OVERSTATED status.

## Limits / next
- Abstract-only: full text isn't read; ~40% of works lack abstracts in open indexes (research estimate).
- Reference parsing is the main failure point for such tools; measure with a labelled set (experiment E5).
- Validation (not done): E5 in experiments.md.

## Student Research Mode (ADR-15, Phase 1, 2026-09-30)
- `/research` has a "Start my research workspace" card (topic, field, country, PH default) → `/research/w/{id}`.
- Tabs: Find sources (8 category buttons + paragraph/claim: find sources, supporting, contradicting) · My sources (folders RRL/RRS/Theory/Concept/Method/Evidence/Other, notes, remove) · Citations (APA 7, copy) · Notes (autosave 2.5 s).
- Backend: `ResearchDiscoveryService`, `DiscoveryPrompts`, `ResearchWorkspaceStore`, `StudentResearchController`; OpenAlex search needs `OPENALEX_API_KEY` (anonymous search is paused; $0.001/search, single-work lookups free).
- Integrity rules: no source from the model; relevance only with a verbatim abstract quote; claim-mode lists a source only when its stance is backed by a quote; unverified leads are labelled "Unverified suggestion"; retracted works flagged "don't cite".
- Next: Phase 2 upload/analysis, Phase 3 gaps/frameworks; validation experiment E7.

## Student Mode phases 2-3 (2026-10-01)
- **My draft tab:** upload PDF/DOCX/PPTX/TXT, then:
  - AI summary (numbers checked against the draft) and key concepts (click to search)
  - "statements that may need a citation" (verbatim, uncited), each with Find sources / Supporting / Contradicting
  - "Check my citations" (existing check on the reference list + cited sentences)
  - highlight-to-search on the draft text
- **Gaps & framework tab:** coverage counts, possible gaps (AI interpretation + basis sources), framework variables (named in sources / unverified), how each saved study relates (with abstract quote). Needs ≥3 saved sources with abstracts.
- **Live runs (local, gpt-5-mini, reasoning effort low):**
  - Draft analysis: 15.7 s; found all 4 planted uncited claims, with no false positives on cited sentences.
  - Insights: 31 s.
  - Citation check on the draft excerpt: ~100 s; the fabricated reference is NOT_FOUND after the title-matching fix.
- Production smoke test 2026-10-01: discovery 142 s → 44 s after PR #10; draft upload 22 s; insights 12 s; auth/delete OK.
  - The low-effort planner once returned "please resubmit the topic outside the protected markers" as a query, and three off-topic papers were shown.
  - Fixed: queries must share a distinctive word with the topic; results in non-name modes must too (whatever the model said); the plan prompt now says the delimited text is the topic.

## Capstone projects (ADR-21, Phase 1, 2026-10-02)
- Signed-in students: "Start my capstone project" on `/research` → `/research/p/{id}`; "My capstone projects" list; quick workspaces get "Make it a capstone project on my account".
- Tabs: Next steps (progress checklist + "What should I do next?") · Questions (per-RQ coverage, "Find studies for RQn") · Library (filters by RQ/section/status/local/foreign/retracted; key findings, method, how you'll use it, linked RQs) · Find sources (shared `FindSources`, save with an RQ link) · Gaps & framework (own gap statements + existing insights) · My draft (shared `DraftTab`) · Citations · Notes.
- Shared frontend pieces: `research/student/FindSources.tsx`, `categories.ts`, prop-driven `DraftTab`/`InsightsTab`.
- Next phases: see ADR-21.

## Capstone files (ADR-23, phase 2, 2026-10-02)
- "Files" tab replaces "My draft": upload "My draft" (chapter label) or "A research paper". Drafts open in the existing draft view; papers open in `PaperView` (record match + Add to library, In plain words, Key findings with the paper's words and supporting/contradicting search, How the study was done, Limitations the authors state).
- Live run: see ADR-23. Next (phase 3): AI-suggested source↔RQ links with quotes for the student to confirm; per-RQ supporting/conflicting search.

