---
description: Find measured or clearly-reasoned performance and cost improvements in VeriFact
argument-hint: [optional: area, e.g. "AI cost", "history query", "bundle size"]
---

Optimize: **$ARGUMENTS**

Do not optimize blindly. Every recommendation needs a measurement or a concrete reason.

Look for:
- Excessive or duplicate LLM calls; unbounded tool loops; oversized prompts (fetched pages not truncated)
- Search calls that could be cached or reused
- Unnecessary network calls, missing timeouts
- Slow queries, N+1 queries, missing or unused indexes
- Large API payloads (e.g., returning full responses in list endpoints)
- Frontend bundle size, unnecessary re-renders, render-blocking fonts
- Expensive work on hot paths (e.g., health checks that call the LLM)

Delegate to relevant agents (`ai-engineer` for AI cost, `database-engineer` for queries, `frontend-engineer` for bundle, `architect` for structure). Report each finding as: *evidence → change → expected impact → risk*. Ask before implementing.
