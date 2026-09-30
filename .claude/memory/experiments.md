# Experiments

_Hypothesis → cheapest test → metric → pass/fail threshold (set before starting) → result. Anything outward-facing needs the owner's OK and must be legitimate, personalised outreach (no spam)._

| ID | Hypothesis | Test | Metric / threshold | Cost | Status |
|---|---|---|---|---|---|
| E1 | Law-firm content/SEO agencies will pay for legal-content audits | Owner sends ~40 personalised emails to legal SEO/content agencies, each with a free 5-page sample audit (produced with the tool + manual check) | Pass: ≥4 replies **and** ≥1 paid audit (≥$300) within 30 days. Fail: 0 paid | Owner time; ~$0 AI | PLANNED (needs owner OK + E2 first) |
| E2 | Automated audit flags are accurate enough to sell | Run real California employment-law pages from law-firm sites (`ContentAuditEvalIT`, prototype `legal/ContentAuditService`); verify each flag against the cited source | Pass: ≥70% of flags real **and** 0 fabricated citations. Fail otherwise | Prototype built 2026-09-30; ~$1 AI + ~170 search credits per 20-page run | RUNNING (run 1 failed; run 2 after fixes) |
| E3 | Small firms find case intelligence worth paying for | 5 small firms paste 10 anonymised intake notes each into /legal | Pass: ≥3 firms report ≥10 min saved per matter **and** ≥1 pre-pays $50/mo | Owner time | PLANNED |
| E4 | Some VeriFact users would pay for history/bulk checks | "$5/mo for history + bulk checks" fake-door button, shown after a check | Pass: ≥2% click-through over ≥1,000 completed checks | ~0.5 day dev | BLOCKED (needs traffic + analytics) |

## Results
- **E2 run 1 (2026-09-30), EXPERIMENT RESULT: FAIL.** 18 CA employment pages from 18 law-firm sites (7 more blocked fetching); 124 statements: 69 consistent, 26 requires review, 19 unable to verify, 10 potentially unsupported, 0 potentially outdated. 0 fabricated citations (enforced in code). Self-review of the 10 "unsupported" flags against cited excerpts (not attorney-verified): 3 clearly real (final pay "within 72 hours of notice"; sick leave paid at termination ×2), 1 imprecise wording worth review (liquidated damages "twice"), 6 false (source silent ≠ conflict ×5; an old bill version treated as law ×1) → ~35–40% precision vs 70% bar. Fixes: flags now require the source's verbatim conflicting words (checked in code), silence → UNABLE_TO_VERIFY, bill texts excluded as sources.
