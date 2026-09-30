# Revenue Strategy

_Last updated: 2026-09-30. Target: **$1,000,000/year** (≈ $83,333/month). This is the owner's goal, not a forecast._
_Rule: every number has a date and a source; missing data is `UNKNOWN` with how to measure it._

## Metrics (as of 2026-09-30)
| Metric | Value | Source / how to measure |
|---|---|---|
| Revenue (MRR / ARR) | **$0** | FACT: no pricing or billing exists |
| Paying customers | **0** | FACT |
| Sales pipeline | **none** | FACT: no outreach yet |
| Completed fact checks since launch (2026-09-29) | **25** (18 text, 7 image) + 3 reused | FACT: Render logs ("Verification done"). Mostly owner/developer testing |
| LegalFact analyses | **2** (both developer smoke tests) | FACT: Render logs |
| Feedback submissions | 2 log entries | FACT: Render logs; content in `verification_feedback` |
| Active / unique users | **UNKNOWN until data accrues**: Cloudflare Web Analytics enabled 2026-09-30 (beacon on verifact-blf.pages.dev) | Cloudflare dashboard → Analytics & Logs → Web Analytics |
| Conversion, retention, churn, CAC, LTV | **UNKNOWN** | Need accounts or at least analytics + a priced offer |
| AI cost per fact check | **≈ $0.005** (ESTIMATE: 25 production checks, avg 622+527 tokens extraction, 2,243+1,542 assessment, at gpt-5-mini $0.25/$2.00 per 1M in/out, developers.openai.com/api/docs/pricing, 2026-09-30) | Confirm on OpenAI usage page |
| AI cost per image check | ≈ $0.006 (ESTIMATE, n=2) | Same |
| AI cost per LegalFact case | **≈ $0.016** (ESTIMATE: intake 2,179+4,320, sources 2,413+3,305 tokens avg, n=3–4) | Same |
| All-in cost per use after Tavily free tier | fact check ≈ $0.04 (≤4 searches × $0.008); LegalFact case ≈ $0.06 (≤6 searches) | ESTIMATE; search dominates cost |
| Search cost | Tavily: 1,000 free credits/mo, then $0.008/credit (FACT, researched 2026-09-30); ≤4 searches/check, ≤6/case | Tavily dashboard |
| Infrastructure | Render web service (plan per `render.yaml`; free vs starter to confirm), Cloudflare Pages free, Supabase free tier | Owner: billing pages |

## Revenue math (illustrative only)
$1M/year ≈ 100 × $10k/yr · 250 × $4k/yr · 500 × $2k/yr · 1,000 × $1k/yr. At ~$150/mo per account (typical legal-software price, see `pricing.md`), ≈ 555 accounts. HYPOTHESIS: for a solo founder, fewer, higher-value B2B customers (agencies, publishers, firms) are more reachable than ~1,000s of consumers.

## Current revenue path (HYPOTHESIS, ranked; see `product-validation.md`)
1. **Legal content audit for law-firm content/SEO agencies and firms**: sold first as a done-for-you service (per-site audit), productised only if it sells. Nearest to existing tech; no dedicated competitor found; buyers already pay per page for content work.
2. **LegalFact case intelligence as an add-on for small-firm intake**: structured summaries of intake notes; competes with bundled intake tools ($59–150/mo), so needs a sharp wedge.
3. **VeriFact**: keep free as showcase + acquisition funnel; revenue only if a fake-door test shows demand.
Illustrative only: 1 → e.g. 60 agencies/firms × ~$1k/mo recurring audits ≈ $720k/yr; 2 → e.g. 150 firms × $100/mo ≈ $180k/yr. Unvalidated.

## Experiments
See `experiments.md`. None run yet.

## Risks
- No distribution yet; the product is unknown (FACT).
- Legal: FTC v. DoNotPay (no "AI lawyer" claims), UPL for consumer-facing use, lawyer-referral rules if routing consumers (see `legalfact.md`, `customer-research.md`).
- AI cost vs price unknown until measured.
- Single developer: sales time competes with engineering time.
