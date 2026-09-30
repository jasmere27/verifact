---
description: Review LegalFact functionality for hallucinated or unsupported legal content, citation and source problems, jurisdiction errors, privacy, prompt injection and unsafe wording
argument-hint: [optional: area, e.g. "case intelligence prompt", "report UI", or a branch]
---

Legal audit: **$ARGUMENTS** (default: LegalFact code and its uncommitted changes)

You are the orchestrator (see `.claude/orchestrator.md`). Do not modify files during the audit.

1. Scope: find LegalFact code (`legal/` backend package, LegalFact frontend components, legal prompts) and the diff if any. Read `.claude/memory/legalfact.md` for the safety boundaries.
2. Launch **in parallel**, only these unless the scope clearly needs more:
   - `legal-product-agent` — safety boundaries, fact vs allegation labelling, jurisdiction handling, missing information, disclaimers, wording a non-lawyer could misread
   - `ai-engineer` — prompts and structured output: can the model invent authorities, dates or facts; are citations validated against retrieved sources in code
   - `security-engineer` — prompt injection via case text and retrieved pages, sensitive data in logs/storage, who can read a case
3. Check yourself, with evidence (`file:line`, test names, or a sample output):
   - Every authority in the output is traceable to a retrieved source ID; none from model memory.
   - User-stated facts are never labelled verified.
   - "Not provided" / "Uncertain" appear instead of guesses.
   - The "AI assistance — professional review required" notice appears on every result and export.
   - Tests exist for: vague input, contradictory input, wrong/unknown jurisdiction, injection in case text, injection in a retrieved page, model failure, no sources found.
4. Report ranked **Blocker / Should fix / Nice to have**, each with `file:line` and a concrete fix. No invented issues.
5. Ask the user whether to apply fixes.
