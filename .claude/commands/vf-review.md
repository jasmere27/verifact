---
description: Have VeriFact specialist agents review the current changes and report concrete issues
argument-hint: [optional: branch, commit range, or area to focus on]
---

Review: **$ARGUMENTS** (default: uncommitted changes plus commits on this branch not on `main`)

You are the orchestrator (see `.claude/orchestrator.md`). Do not modify files during review.

1. Collect the diff: `git diff`, and `git diff main...HEAD` if on a feature branch. Save large diffs to the scratchpad and give agents the path.
2. Launch reviewers **in parallel**, at minimum:
   - `architect` — structure, boundaries, overengineering
   - `backend-engineer` — Spring correctness, errors, validation
   - `ai-engineer` — if prompts/AI/verification touched
   - `security-engineer` — always
   - `qa-engineer` — test coverage and missing cases
   - `frontend-engineer` / `product-designer` — if `frontend/` touched
3. Each reviewer must report only concrete, reachable issues with `file:line`. No invented problems.
4. De-duplicate, verify the important findings yourself by reading the code, and rank: **Blocker / Should fix / Nice to have**.
5. Ask the user whether to apply fixes.
