---
description: Execute an approved VeriFact plan with the smallest maintainable change
argument-hint: <plan reference or description of the approved change>
---

Implement: **$ARGUMENTS**

You are the orchestrator (see `.claude/orchestrator.md`). Only implement work the user approved.

Before modifying files:
1. `git status`, `git branch --show-current`, `git diff`. If there are uncommitted user changes, do not overwrite them; ask if unsure. If on `main`, propose a feature branch.
2. Understand the existing implementation and related code.
3. Check tests that cover it.
4. Check dependencies (`pom.xml`, `frontend/package.json`).
5. Check API contracts (`.claude/memory/api-contracts.md`, `frontend/src/api.ts`).

Then:
- Implement in small steps. Delegate self-contained slices to the owning agent (backend/frontend/ai/database) when that saves context; do cross-cutting glue yourself.
- Write or update tests with each step (`qa-engineer` can author them).
- Add any new env vars to `.env.example` with safe placeholders.
- Schema changes only as new Flyway migrations.
- Run the build and tests. Report failures honestly.
- Finish by running `/vf-review` on the result, or ask the user whether to.
