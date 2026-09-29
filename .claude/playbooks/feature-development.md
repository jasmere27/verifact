# Playbook: Feature Development

For meaningful features. Small changes can skip steps; say which ones you skipped.

```
1. Frame       product-engineer   Who needs it, smallest valuable version, success signal
2. Design      architect          Where it lives, API shape, compatibility
               database-engineer  Schema + migration (if data)
               product-designer   UX / wireframe (if UI)
               research-agent     Any external API/pricing facts
3. Risk        security-engineer  Threats introduced
4. Synthesize  orchestrator       One plan (/vf-plan format), decisions → decisions.md
5. Approve     USER               Required for major changes
6. Build       backend / frontend / ai / database engineers (parallel where independent)
7. Test        qa-engineer        Tests for "how can this break?"
8. Review      /vf-review         architect + security + relevant engineers
9. Fix + QA    /vf-qa             Until green
10. Record     orchestrator       api-contracts.md, architecture.md, roadmap status
11. Report     orchestrator       git diff --stat + summary
```

Example: "Add verification history"
architect (ownership & API) → database-engineer (tables, indexes, retention) →
backend-engineer (endpoints, pagination, authz) → frontend-engineer + product-designer
(history UI) → security-engineer (who can see whose history?) → qa-engineer.
