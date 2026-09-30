---
description: Review current revenue, usage, costs and the path toward the $1M/year target using only real data
argument-hint: [optional: period or focus]
---

Revenue review: **$ARGUMENTS**

You are the orchestrator. No code changes. Never invent a metric: unknown = `UNKNOWN` plus how to measure it.

1. Gather real data only:
   - Usage: Render logs (`render logs --resources srv-dad6lhrncjis7387if1g --text "Verification done" --start <date> --limit 1000 --output text`, likewise "Case intelligence done", "Reusing report"), feedback table if DB access is available.
   - Cost: OpenAI and Tavily dashboards (ask the owner), Render plan, token counts from "LLM call" log lines.
   - Revenue, customers, pipeline: from the owner; do not assume.
2. Update `.claude/memory/revenue-strategy.md` metrics table (with date and source for each number).
3. Compare against the current revenue path and experiments; say plainly what moved, what didn't, and the single most important next step.
