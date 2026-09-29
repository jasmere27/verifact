---
description: Have the VeriFact agent team inspect code related to a topic and report, without modifying anything
argument-hint: <topic, feature, bug, or area to investigate>
---

Investigate: **$ARGUMENTS**

You are the orchestrator (see `.claude/orchestrator.md`). **Do not modify any files.**

1. Run `git status` and `git branch --show-current`. Note which branch the relevant code lives on.
2. Read `.claude/memory/project-context.md`, `architecture.md`, and `known-issues.md`.
3. Pick the relevant agents (usually 2–4, e.g. `architect` + the owning engineer + `security-engineer`). Launch them **in parallel**, each with a self-contained brief, read-only.
4. Synthesize into:
   - Current implementation
   - Dependencies
   - Architecture
   - Problems (ranked)
   - Risks
   - Relevant files (`path:line`)
   - Existing tests
   - Recommended next steps
5. If you discovered durable facts (not temporary details), propose updates to `.claude/memory/known-issues.md` or `architecture.md` and apply them.
