---
description: Use the VeriFact specialist agents to produce an implementation plan for approval
argument-hint: <feature or change to plan>
---

Plan: **$ARGUMENTS**

You are the orchestrator (see `.claude/orchestrator.md`). Do not modify application code.

1. Check `git status`, current branch, and `.claude/memory/*`.
2. Delegate in parallel to the relevant agents (always `architect`; add `product-engineer` for new user-facing features, `ai-engineer` for anything touching verification, `security-engineer` for input/fetch/auth/data, `database-engineer` for schema, `research-agent` for anything depending on current external facts).
3. Resolve disagreements using the synthesis rules; record non-trivial decisions in `.claude/memory/decisions.md` as **Proposed**.
4. Present the plan with these sections:

```
Problem
Current behavior
Desired behavior
Architecture
Files affected
Database changes
API changes (and compatibility impact)
Frontend changes
Testing
Security concerns
Deployment concerns
Risks
Rollback strategy
Steps (small, ordered, each independently verifiable)
```

5. Stop and ask the user to approve before implementing.
