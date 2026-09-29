# Playbook: Bug Fix

1. **Reproduce.** Get exact input, endpoint, branch, and observed vs. expected output. If it can't be reproduced, say so before guessing.
2. **Locate.** Read the code path end to end. Use one engineer agent only if the path is large.
3. **Write a failing test** that captures the bug (`qa-engineer` or the owning engineer).
4. **Fix** with the smallest change that makes the test pass. No drive-by refactors.
5. **Check blast radius.** Grep for other callers of changed methods; check the frontend if an API response changed.
6. **Security glance** if the bug involved input handling, fetching, auth, or AI output. Ask `security-engineer` if unsure.
7. **Run** `/vf-qa`.
8. **Record** in `.claude/memory/known-issues.md` (remove the entry if it was listed; add a note if a root cause affects other areas).
