---
description: Assess a product idea or feature for customer, problem, value and willingness to pay, with evidence labels, and record the result
argument-hint: <feature, workflow, or customer segment>
---

Validate: **$ARGUMENTS**

You are the orchestrator (`.claude/orchestrator.md`). No code changes.

1. Read `.claude/memory/product-validation.md`, `customer-research.md`, `experiments.md`, `pricing.md`. Reuse what's recorded; don't re-research it.
2. If current market facts are needed, use `product-growth-agent` (one agent). Add `legal-product-agent` only for LegalFact safety/UPL questions.
3. Produce the feature scorecard (see `product-growth-agent.md`) answering: who, problem, how they solve it today, frequency, cost of the current workflow, alternatives, why switch, measurable value, would they pay, evidence, what's unvalidated. Label every claim FACT / MARKET CLAIM / CUSTOMER FEEDBACK / EXPERIMENT RESULT / HYPOTHESIS.
4. Status: VALIDATED / PARTIALLY_VALIDATED / HYPOTHESIS / UNKNOWN. Propose the cheapest experiment that could prove it wrong, with a pass/fail threshold.
5. Update `product-validation.md` (and `experiments.md` if an experiment is proposed). Report in a few lines.
