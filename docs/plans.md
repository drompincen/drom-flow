---
title: Plans and closed loops
nav_order: 8
---

# Plans and closed loops

## Chapter-based plans

Plans live in `drom-plans/` as markdown with YAML frontmatter, broken into **chapters** — each a
logical phase with its own steps. Chapter status runs `pending` → `in-progress` → `completed`, and the
session-start hook surfaces any plan still in progress.

```markdown
---
title: Add Auth Middleware
status: in-progress
current_chapter: 2
---

## Chapter 2: Implementation
**Status:** in-progress
- [x] Create auth middleware module
- [ ] Add token validation
```

Create them with `/planner`. On resume, read the plan's **current chapter**, not the whole file — a
long plan is expensive, and every turn re-reads resident context.

## Closed loops

A closed loop is: **check → analyse → fix in parallel → re-check**, repeating until a machine-checked
pass condition holds or a max-iteration cap is reached.

The discipline that makes it work:

- The check emits **machine-readable** output (JSON), so "done" is not a judgment call
- Fixes for independent categories run **in parallel**, one agent each
- A **regression** (more failures than the previous iteration) triggers an immediate revert, and the
  same fix is never retried
- Every iteration is logged to `context/MEMORY.md`

`/orchestrator` runs these; `workflows/closed-loop.md` is the protocol. Real example: a QA pipeline
took **134 visual issues to 0 in 15 iterations**.

### Gates, honestly

A loop is only as good as its pass condition. Write gates that can fail for the right reason — and
when a gate fails because your *checker* is wrong rather than the work, fix the checker rather than
loosening the gate.
