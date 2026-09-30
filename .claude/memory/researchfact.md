# ResearchFact

_Last updated: 2026-09-30. Status: **MVP built** (branch `feature/researchfact`). Decisions: ADR-13._

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
