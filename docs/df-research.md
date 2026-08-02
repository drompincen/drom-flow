# df-research — deep research on the grok fleet

One question in; a sourced, adversarially-critiqued, citation-verified report out — running on
**grok CLI sub-agents**, so Claude pays only for dispatch and verdicts.

```bash
bash scripts/df-research.sh doctor
bash scripts/df-research.sh run "<question>" [--depth quick|deep]
```

Output lands in `research/<slug>/`: `plan.md`, `corpus/`, `audit.md`, `report.md`,
`objections-*.md`, `patch-log.md`, `citecheck.json`.

## The pipeline

| Phase | Units | Purpose |
|---|---|---|
| decompose | 1 | canonical question, sub-questions with **falsifiers**, search perspectives |
| sweep | 4–6 **parallel** | one perspective each; every claim carries a verbatim quote; one unit runs **X/Twitter search** |
| audit | 1 | independence clustering + ranked contradiction graph |
| draft | 1 | cited report; disagreements presented, not averaged |
| critics | 3–4 **parallel** | adversarial, **objections only** — critics never rewrite |
| patch | 1 | surgical edits only; may not restructure |
| cite-check | 1 | does each source *actually support* its sentence — hard gate |

Each phase is a fleet unit: its own grok process, its own context, state on disk. A dropped step is a
missing artifact, not a silent omission.

## What was borrowed — and what deliberately was not

The method is adapted from [hyperresearch](https://github.com/jordan-gibbs/hyperresearch) (MIT).

**Borrowed (as ideas, reimplemented as our own templates):**

| Idea | Why it matters |
|---|---|
| Cite-check | Audits whether each cited source actually supports its sentence; hallucinated quotes hard-block |
| Independence audit | Five reprints of one press release argue with the weight of one |
| Adversarial critics | Several critics attack in parallel; the patcher may only make surgical edits |
| Contradiction graph | Conflicts are ranked and presented, not averaged away |

**Deliberately not ported:** their 16 step skills, 16 `.claude/agents/` definitions, PreToolUse
vault-first hook, Jinja-rendered gear profiles, and the Python package. Grok exposes **no `Skill`
tool**, and that router is what their architecture is built around — porting would mean rebuilding
their orchestration on our fleet, which already does that job better. Their changelog also moves
fast, so a translation layer would rot. **Nothing here depends on that package**; it is cloned at
`../hyperresearch` for reference only.

The honest cost of that decision: their prompts are presumably tuned, ours are not. The quality gate
below is how we keep ourselves honest about the difference.

## The quality gate

`scripts/df-research-audit.sh <workdir>` machine-checks the report and writes
`reports/df-research-audit.json`. Exit non-zero = not shippable.

| Check | Bar |
|---|---|
| `distinct_sources` | ≥20 distinct source URLs actually fetched (`DF_MIN_SOURCES`) |
| `citations_resolve` | every `[S<n>]` resolves to a real corpus source — no phantom citations |
| `contradictions` | ≥1 contradiction cluster identified |
| `no_uncited_claims` | zero uncited claims in the Findings section |
| `social_labelled` | X/social sources labelled distinctly in the report |
| `citecheck` | **any `unsupported`, `missing`, or fabricated quote is a hard block** |

This is the anti-theatre gate. A pipeline that runs and emits a confident, thinly-sourced report
fails it — by design.

## Sources: X/Twitter is signal, not evidence

Grok has `x_semantic_search`, `x_user_search`, and `x_thread_fetch` — a source class Claude Code does
not have. It is genuinely useful for current events and practitioner signal, and genuinely dangerous
if it is allowed to look like peer-reviewed evidence.

So: one sweep unit is dedicated to X, every source it returns is tagged `type: social`, the audit
counts social sources separately, and the report must label them. Never treat a viral thread as
corroboration of a study.

## Reading a run without burning tokens

Claude should read **`reports/df-research-audit.json`** and the report's `## Answer` section. The
phase bodies, corpus, and objections stay on disk for drill-down when something fails.

The orchestrator prints one line per phase (`sweep  4 ok / 0 failed`) — never phase output.

## Surviving interruption

Phases are fleet units, so everything from the fleet applies: `grok-fleet.sh status` for progress,
`stop --all` to halt, and `resume --run-id <phase-run>` to continue. If Claude hits its usage limit
mid-run, `limit-watch.sh` checkpoints and the detached grok work keeps going. See
`.claude/docs/token-economy.md`.

## Limits worth stating

- Quality depends on grok's web reach. If the audit shows thin sourcing, the honest fallback is grok
  for breadth and Claude for the cite-check gate only.
- The templates are new and untuned; expect to iterate them against the audit before trusting a
  report on an unfamiliar topic.
- `quick` depth targets a working answer, not exhaustiveness. Use `deep` when the answer matters.
