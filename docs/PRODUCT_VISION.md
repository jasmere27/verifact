# Product vision: VeriFact Intelligence

_Last updated: 2026-10-14._

**Know the claim. Verify the evidence. Protect your reputation.**

## Who it's for (HYPOTHESIS: no customer interviews yet)
PR and digital agencies, corporate communications teams, e-commerce and consumer brands, and agencies
managing several clients' reputations.

## The problem (HYPOTHESIS)
A damaging claim about a brand spreads faster than a team can establish what's true. Social-listening tools
count mentions; they don't tell you whether the claim holds up, what the primary sources say, or what you can
safely say in response.

## What it does
1. A workspace per company or client, with members and roles.
2. Monitoring projects: brand, aliases, products, executives, keywords, topics, exclusions, sources, schedule.
3. Scheduled collection from web search, news search, public RSS feeds and public pages the customer names.
4. Relevance rules in code, de-duplication, and AI flagging of potentially damaging claims, kept only when the
   quoted words are in the retrieved text.
5. Incidents with a transparent rule-based priority score, owners, statuses, activity history.
6. Investigations: claims → web evidence → assessments that rest only on verbatim source excerpts; versions
   record what changed.
7. Response Studio: drafts built from the evidence, with citations, warnings and human approval.
8. Reports (on screen and PDF) from the workspace's own data.
9. Plans, quotas and usage metering enforced on the server.

## Principles
- Evidence over assertion: the model's own knowledge never decides an assessment; every quotation is checked
  against the retrieved source.
- Separate allegation, verified source words, AI interpretation, and open questions, everywhere.
- No fabricated metrics, reach estimates or results. Failed integrations show as failures.
- Nothing is published or sent automatically.
- Honest coverage: social platforms are not monitored, and the product says so.

## Plans (pricing hypotheses, configurable)
| Plan | Price | Shape |
|---|---|---|
| Monitor | $49/mo | 1 project, daily checks, 20 investigations, 2 reports, 1 seat, no Response Studio |
| Intelligence | $149/mo | 5 projects, checks every 6 h, 100 investigations, Response Studio, 10 reports, 5 seats |
| Business | $399/mo | 20 projects, checks every 2 h, 400 investigations, 40 reports, 15 seats |

A 14-day trial of Intelligence, once per person. These numbers are starting points to validate with
customers; see the cost estimates in `IMPLEMENTATION_PLAN.md`.

## Relationship to the existing products
The consumer tools (VeriFact, NewsFact, LegalFact, ResearchFact) stay as they are. Intelligence reuses their
evidence pipeline and accounts, and lives at `/app`.
