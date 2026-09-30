# Product Validation

_Last updated: 2026-09-30. Status per idea: VALIDATED / PARTIALLY_VALIDATED / HYPOTHESIS / UNKNOWN. Labels: FACT, MARKET CLAIM, CUSTOMER FEEDBACK, EXPERIMENT RESULT, HYPOTHESIS._
_No customer interviews or payments have happened yet, so nothing is validated._

## 1. Legal content audit (law-firm content/SEO agencies, firms, legal publishers). **HYPOTHESIS** (most promising)
- **Customer:** agencies writing/maintaining law-firm practice-area pages; firms' marketing leads; legal publishers.
- **Problem (HYPOTHESIS):** legal pages go stale when statutes/regulations change; inaccurate lawyer advertising carries Rule 7.1 risk.
- **Current workflow:** manual attorney/editor review. Agencies charge $175/new page, $75/page editing (FACT, PaperStreet pricing); attorney-edited retainers $2–4k/mo (MARKET CLAIM, Juris Digital). Agencies list "outdated content (old laws)" in audit checklists (MARKET CLAIM).
- **Alternatives:** generic AI fact checkers (Originality.ai, $14.95/mo, FACT); no dedicated legal-content audit product found (unverified absence).
- **Our fit:** existing pipeline (claim extraction → official-source search → cited assessment) maps directly; needs page crawl, statuses (POTENTIALLY_OUTDATED / POTENTIALLY_UNSUPPORTED / REQUIRES_REVIEW / UNABLE_TO_VERIFY), per-page report.
- **Unknowns:** will agencies pay; audit precision on real pages; which states/practice areas; liability comfort.
- **Next:** E1 + E2 in `experiments.md`.

## 2. LegalFact case intelligence (small firms' intake). **HYPOTHESIS** (built, unvalidated)
- **Customer:** intake staff/attorneys at small firms (PI, employment).
- **Problem:** slow/incomplete intake: 40% of firms answered the phone, 33% replied to email in a 2024 secret-shopper study (FACT, vendor research, Clio). HYPOTHESIS that structuring free-text intake saves reviewer time.
- **Alternatives:** Clio Grow $59–69/user/mo (MARKET CLAIM), Lawmatics $99–199/mo + QualifyAI credits (FACT/MARKET CLAIM), LawDroid $25–99/mo (FACT), Smith.ai (FACT).
- **Our differentiation (HYPOTHESIS):** every statement traced to the client's words or an official source; missing-info questions; inconsistency flags.
- **Unknowns:** minutes saved per matter; whether firms paste intake into a separate tool; integration needs.
- **Next:** E3.

## 3. VeriFact consumer/journalist fact checking. **HYPOTHESIS** (weak revenue case)
- Buyers are budget-constrained: 45% of fact-checkers reported funding declines in 2025; 38% cut staff (FACT, Poynter/IFCN). Cheap substitutes: NewsGuard $4.95/mo, Perplexity $20/mo, Originality.ai $14.95/mo (FACT).
- **Role:** free showcase, credibility and SEO funnel for the evidence engine.
- **Next:** E4 (fake-door) only once there's real traffic (≥1,000 checks).

## Rejected / deferred
- Consumer-facing "legal help" routing people to attorneys: deferred (lawyer-referral/lead-gen rules, UPL risk; see `customer-research.md`).
