# Playbook: AI Verification Changes

Any change to prompts, models, retrieval, evidence handling, verdicts, or AI providers.
Always involve `ai-engineer` and `security-engineer`; `qa-engineer` for evals.

## Invariants (must hold after every change)

1. **Evidence decides.** A verdict other than insufficient-evidence requires at least one retrieved evidence item that the assessment cites.
2. **Citations are real.** Every cited source ID maps to evidence the backend retrieved in this run. The model cannot introduce URLs.
3. **Untrusted content is data.** User input and fetched pages are wrapped in delimited blocks; the system prompt says they may contain instructions that must be ignored.
4. **No tools while reading untrusted content.** Retrieval is done by the backend, not by the model calling fetch tools.
5. **Structured output.** The model returns JSON matching a schema, parsed into Java records and validated. Invalid output → one bounded retry → handled error.
6. **Bounded cost.** Fixed maximum LLM calls per verification (target ≤ 2), capped input tokens (truncate fetched content), timeouts, and logged token usage.
7. **No chain-of-thought exposure.** Explanations are short and cite evidence.

## Pipeline (target)

```
input ─► normalize (text / URL→safe fetch / image→OCR or vision / audio→transcript)
      ─► [LLM #1, cheap] extract checkable claims + search queries  (JSON)
      ─► search provider (N queries, cached)  ─► safe fetch top results (SSRF-guarded, size-capped)
      ─► evidence items {id, url, domain, publisher, published_at, retrieved_at, snippet}
      ─► [LLM #2] per-claim assessment citing evidence IDs          (JSON)
      ─► validate (enums, citations resolve, required fields) ─► persist ─► respond
```

## Change process

1. State the hypothesis ("this prompt change reduces unsupported confident verdicts").
2. Run the eval set before and after (deterministic fakes for CI; a small real-provider run manually, cost-capped).
3. Compare: verdict accuracy on labeled set, citation validity rate, insufficient-evidence rate, tokens per run, latency.
4. Record the result and decision in `.claude/memory/decisions.md`.
