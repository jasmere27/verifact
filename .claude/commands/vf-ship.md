---
description: Prepare a VeriFact change for release. Checks everything, never deploys or pushes without approval
argument-hint: [optional: branch or release name]
---

Prepare release: **$ARGUMENTS**

Follow `.claude/playbooks/release.md`. Check and report each item as PASS / FAIL / WARNING:

- [ ] `git status` clean or intentionally staged; `git diff main...HEAD --stat` understandable
- [ ] Tests and builds pass (`/vf-qa`)
- [ ] Security review done for this change (`security-engineer`)
- [ ] No secrets in diff or history of this branch
- [ ] New/changed env vars in `.env.example`, `frontend/.env.example`, `render.yaml` (or hosting config), and README
- [ ] Flyway migrations present, ordered, non-destructive, and not edited after being applied
- [ ] API changes recorded in `.claude/memory/api-contracts.md`; breaking changes called out
- [ ] Frontend `VITE_API_BASE_URL` and backend `ALLOWED_ORIGIN` consistent for the target environment
- [ ] Health check endpoint is cheap (no LLM/search/DB writes)
- [ ] Docs and `.claude/memory/*` updated

Then show the diff summary and a proposed commit message / PR description.
**Do not push, merge, tag, or deploy until the user explicitly approves.**
