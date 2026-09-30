---
description: Have VeriFact specialist agents review the current changes and report concrete issues
argument-hint: [optional: branch, commit range, or area to focus on]
---

Review: **$ARGUMENTS** (default: uncommitted changes plus commits on this branch not on `main`)

You are the orchestrator (see `.claude/orchestrator.md`). Do not modify files during review.

1. Collect the diff: `git diff`, and `git diff main...HEAD` if on a feature branch. Save large diffs to the scratchpad and give agents the path.
2. Launch **only the reviewers the diff needs**, in parallel (model usage is limited; one good reviewer beats five overlapping ones):
   - `security-engineer` — input handling, prompts, uploads, persistence, config, auth
   - `ai-engineer` — prompts, AI calls, retrieval, verdicts
   - `legal-product-agent` — anything in LegalFact (or use `/vf-legal-audit`)
   - `architect` — new modules, cross-cutting refactors, API shape changes
   - `backend-engineer` / `frontend-engineer` / `product-designer` — larger changes in their layer
   - `qa-engineer` — when test coverage is the open question
   Small, single-layer diffs: review them yourself.
3. Each reviewer must report only concrete, reachable issues with `file:line`. No invented problems.
4. De-duplicate, verify the important findings yourself by reading the code, and rank: **Blocker / Should fix / Nice to have**.
5. Ask the user whether to apply fixes.
