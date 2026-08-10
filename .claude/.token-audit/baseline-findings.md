# Audit: skill frontmatter + structure drift (Claude-only baseline)

## template/.claude/skills/architect/architect.md
- Frontmatter complete and valid (name, description, user-invocable) — no drift
- Ordered-list numbering broken in `## Responsibilities`: sequence is 1, 2, 3, 3, 4, 5 — "Evaluate trade-offs" and "Design interfaces" are both numbered 3 (lines 15–16)

## template/.claude/skills/debugger/debugger.md
- Frontmatter complete and valid — no drift
- Ordered-list numbering broken in `## Process`: sequence is 1, 2, 3, 4, 4, 5, 6, 7 — "Form 2-3 hypotheses" and "Test each hypothesis" are both numbered 4 (lines 25–26)

## template/.claude/skills/implementer/implementer.md
- Frontmatter complete and valid — no drift
- Ordered-list numbering broken in `## Process`: sequence is 1, 2, 3, 4, 4, 5 — "Implement the change" and "Run tests" are both numbered 4 (lines 24–25)

RESULT: 3 files audited, 3 findings (all duplicate ordered-list numbers); frontmatter clean in all 3.
