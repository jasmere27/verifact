---
description: Design, record, or close a business/product experiment (hypothesis, test, metric, threshold, result)
argument-hint: <experiment idea, or "close <id> <result>">
---

Experiment: **$ARGUMENTS**

You are the orchestrator. Experiments live in `.claude/memory/experiments.md`.

- **New:** write hypothesis, segment, test (cheapest that could disprove it), metric, pass/fail threshold decided now, cost (money + dev time), owner (usually the human), start date, status PLANNED. Prefer no-code tests (interviews, concierge pilots, landing page with a price) before engineering. Get the owner's OK before anything outward-facing (emails, posts, sign-up pages).
- **Close:** record the actual result as EXPERIMENT RESULT, whether it passed, what we learned, and update `product-validation.md` and the roadmap if it changes priorities.
- Never mark an experiment passed on enthusiasm alone; only the pre-agreed metric counts.
