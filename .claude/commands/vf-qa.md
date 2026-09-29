---
description: Run VeriFact's builds, tests, and checks and report PASS / FAIL / WARNING with evidence
argument-hint: [optional: area to focus on]
---

QA: **$ARGUMENTS**

Delegate to `qa-engineer` (or run directly if quick). Run what applies and report each line as PASS / FAIL / WARNING with an output excerpt:

| Check | Command |
|---|---|
| Backend compile + unit/integration tests | `./mvnw -q test` |
| Backend package | `./mvnw -q -DskipTests package` |
| Frontend type check + build | `cd frontend && npm ci && npm run build` |
| Frontend lint | `cd frontend && npm run lint` |
| Docker image builds | `docker build -t verifact:qa .` (if Docker available) |
| Secrets scan of diff | `git diff main...HEAD \| grep -nE "(sk-|AIza|password=\S+|SUPABASE_DB_PASSWORD=\S+)"` |
| Manual API smoke (if app is running) | `curl` the changed endpoints with valid, empty, oversized, and malicious inputs |

If a tool is missing (e.g., no JDK or Docker in this environment), report it as WARNING with the reason. Do not mark something PASS that wasn't run.

End with: overall status, blocking failures, and suggested fixes.
