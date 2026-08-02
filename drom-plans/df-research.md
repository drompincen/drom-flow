---
title: df-research — deep research on the grok fleet (trimmed scope)
status: pending
created: 2026-08-02
updated: 2026-08-02
current_chapter: 1
loop: true
check_command: bash scripts/df-research.sh verify --json
pass_condition: all nine gates PASS in reports/df-research.json
max_iterations: 10
---

# Plan: df-research

Give drom-flow a deep-research capability that runs on **grok CLI sub-agents**: one question in, a
sourced, adversarially-critiqued, citation-verified report out — for almost no Claude tokens.

**Scope decision (2026-08-02): borrow the method, not the harness.** We evaluated porting
[hyperresearch](https://github.com/jordan-gibbs/hyperresearch) (MIT, cloned to `../hyperresearch`)
and deliberately did not. Rationale below. This plan is **one chapter of build work** plus the
standard ship gates, not a seven-chapter port.

---

## Why we are not porting hyperresearch

Probed first, then decided.

**Its engineering value sits exactly where it cannot travel.** Hyperresearch v0.10.0 installs 16 step
skills, 16 `.claude/agents/` definitions, a PreToolUse vault-first hook, and Jinja-rendered per-gear
skill bodies. Grok exposes **no `Skill` tool** — and that router is the mechanism its architecture is
built around. Porting means rebuilding its orchestration on our fleet, which already does the job
better: a separate process per step (hard context isolation), state on disk, resume, stall detection,
8-way concurrency verified.

**And it would rot.** Upstream ships fast (71 KB changelog). A script translating 32 generated files
into our format is a maintenance treadmill where each release risks silently unmapping a construct.

**What is actually worth having is markdown.** Four ideas carry most of the value, and none require
their Python package, vault, hook, or router:

| Borrowed idea | Why it matters |
|---|---|
| **Cite-check** | Audit whether each cited source actually supports its sentence; hallucinated quotes are a hard block |
| **Independence audit** | Cluster syndicated copies so five reprints of one press release argue with the weight of one |
| **Adversarial critics** | Several critics attack the draft in parallel; the patcher may only make surgical edits, never rewrite |
| **Contradiction graph** | Pair conflicts across the corpus into ranked clusters instead of averaging them away |

Credit to hyperresearch (MIT) for the methodology. We reimplement the ideas as fleet task templates;
we do not vendor or depend on the package.

**Honest caveats.** Their prompts are presumably tuned — we lose that tuning and will need our own
iteration to get good. And hyperresearch works well *as-is in Claude Code today*; the only reason to
move research onto grok is Claude token cost, which is a real motive here but worth naming.

### What grok brings (probed directly)

`web_search`, `web_fetch`, `open_page_with_find`, `spawn_subagent`, plus — uniquely —
`x_semantic_search`, `x_user_search`, `x_thread_fetch`. **X/Twitter search is a source class Claude
Code does not have**, valuable for current events and practitioner signal, and dangerous if it is
allowed to masquerade as peer-reviewed evidence. Gate 3 requires it to be labelled distinctly.

---

## EXIT CRITERIA

`scripts/df-research.sh verify` writes `reports/df-research.json`.

| # | Gate | Criterion |
|---|---|---|
| 1 | `templates` | 5 research templates exist and lint clean: **no** external package, vault, or `Skill`-tool dependency |
| 2 | `pipeline` | One question runs end to end on grok and emits a report with a sources section and inline citations |
| 3 | `quality` | **≥20 distinct sources**; every citation resolves to a fetched source; ≥1 contradiction cluster; **zero uncited claims** in findings; X/social sources labelled as a distinct class |
| 4 | `adversarial` | Critics run **concurrently** (`status` shows >1 RUNNING) and their objections demonstrably change the final report |
| 5 | `cheap` | A full run costs **≤6 Claude turns** and ≤8 KB Claude context (measured by `token-audit.sh`) |
| 6 | `host` | Docs written; skill + scripts mirrored into `template/`; `## Deep Research` added to `init.sh`'s CLAUDE.md merge list so **existing** installs receive the guidance |
| 7 | `dotfiles` | In host projects, drom-flow docs install to **`.claude/docs/`** (a dotfile path), are **gitignored**, and never touch the project's own `docs/`. Includes migrating the already-shipped `grok-fleet.md` / `token-economy.md` out of host `docs/` |
| 8 | `catsandbears` | A real research question runs there after `init.sh --update` and passes gate 3; its own `docs/` contains no drom-flow files |
| 9 | `ship` | `VERSION` upticked; committed and pushed in both repos |

Gate 3 is the anti-theatre gate: a report that runs but is thin or uncited fails.

**Max iterations: 10.**

---

## Chapter 1: Build it
**Status:** pending
**Depends on:** none

Five templates under `scripts/task-templates/research/`, driven by the existing fleet.

- [ ] `decompose.md` — question → atomic sub-questions + a coverage matrix stating what evidence would answer each, and what would falsify it
- [ ] `sweep.md` — multi-perspective search plan; one fleet unit per perspective; each unit fetches, extracts claims with quotes, and writes `sources.md` with URL + retrieval date + a verbatim supporting quote per claim. **One unit uses X search** and must tag every X source `[social]`
- [ ] `audit.md` — contradiction + independence pass: cluster derivative/syndicated copies to one effective source, and pair genuine conflicts into ranked contradiction clusters
- [ ] `critique.md` — adversarial critic, run as **N concurrent units** with different mandates (coverage gaps, weak sourcing, overclaiming, alternative explanations). Output is objections only — critics never rewrite
- [ ] `citecheck.md` — for each citation, decide whether the cited source *actually supports* the sentence; emit a schema verdict (`supported | partial | unsupported`) per claim
- [ ] `scripts/df-research.sh` — `run "<question>" [--depth quick|deep]`, `verify`, `doctor`; sequences the phases, fans out sweep and critics as fleet manifests, and returns **brief output only** (per-phase verdict lines, never bodies) to satisfy gate 5
- [ ] Patch step: apply critic objections as **surgical edits only**; the patcher receives the objections and the draft, and may not restructure the report
- [ ] `scripts/df-research-audit.sh <report>` — machine-checks gate 3 and writes `reports/df-research-audit.json`: distinct source count, unresolved citations, contradiction clusters, uncited claims in findings, unlabelled social sources. **Unsupported citations are a hard block**
- [ ] Reports land in `research/<slug>/` — draft, `sources.md`, `objections.md`, `citecheck.json`, final report — so every claim is traceable to a fetched source
- [ ] Inherit fleet controls: `stop --all`, stall detection, `resume`, `GROK_BUDGET_USD=0`

## Chapter 2: Ship
**Status:** pending
**Depends on:** Chapter 1

- [ ] `template/.claude/skills/df-research/df-research.md` — `/df-research <question>`, documenting depths, the quality gate, and the social-source caveat
- [ ] `docs/df-research.md` in the drom-flow repo — how it works, what was borrowed from hyperresearch and what was deliberately not, the X-source caveat, and honest limits
- [ ] Mirror scripts + templates into `template/`, embed in `SCRIPTS.md`, round-trip verify byte-for-byte
- [ ] Add `## Deep Research` to `init.sh`'s CLAUDE.md merge list; register in `CLAUDE.md`, `template/CLAUDE.md`, `README.md`

### Docs must land in dotfiles, gitignored (gate 7)

Host projects own their `docs/` directory. drom-flow must not write into it — and currently does:
`grok-fleet.md` and `token-economy.md` were installed into catsandbears' own `docs/` **and committed
there**. That must be undone as part of this work.

- [ ] Move the shipped location from `template/docs/` to **`template/.claude/docs/`**, so host installs
      receive them at `.claude/docs/` alongside `.claude/skills/` and `.claude/hooks/`
- [ ] Add `.claude/docs/` to `init.sh`'s gitignore pattern list and to `MANAGED_DIRS` (uninstall cleanup);
      re-embed `init.sh` in `SCRIPTS.md` and round-trip verify
- [ ] Update every reference so it points at `.claude/docs/…`: `template/CLAUDE.md`'s Token Economy
      section, the `grok-fleet` and `df-research` skills, and the CLAUDE.md merge text
- [ ] **Migration for existing installs**: on `--update`, if `docs/grok-fleet.md` or
      `docs/token-economy.md` exist and match the shipped versions, move them to `.claude/docs/` and
      remove the originals — never delete a file the user has edited (compare before removing)
- [ ] In catsandbears specifically: `git rm` the two docs from `docs/`, confirm they now live at
      `.claude/docs/` and are gitignored, and verify its `docs/` holds only its own files
- [ ] Keep the drom-flow repo's own `docs/` as-is — it is the source of truth and *should* be tracked there
- [ ] Bump `VERSION` (this ships a relocation, so host projects need the new version to migrate)
- [ ] `init.sh --update` catsandbears; confirm skill, scripts, and docs arrive and every reference resolves
- [ ] Run a real research question in catsandbears; it must pass the gate-3 audit
- [ ] Log the run's wall clock, grok spend, and Claude turns to `context/MEMORY.md`
- [ ] Commit and push both repos, staging only drom-flow-owned paths in catsandbears

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **Our prompts are untuned vs hyperresearch's** | Gate 3 sets a measurable floor; iterate the templates until it passes, and say plainly if quality lags |
| Report looks impressive but is thin | Audit counts distinct sources and resolves every citation; unsupported citations hard-block |
| X/social sources inflate apparent consensus | Tagged `[social]`, counted separately, and the independence pass clusters derivatives |
| grok's web fetch weaker than Claude's | Gate 3 is the test. Honest fallback: grok for breadth, Claude for the cite-check gate only |
| Critics rubber-stamp the draft | Gate 4 requires objections to demonstrably change the report, not merely exist |
| Long runs exhaust Claude mid-pipeline | Already solved: detached `drain`, checkpoint, hourly ping, `resume` |
| Guidance never reaches existing host installs | `## Deep Research` added to the merge list — the gap that previously left catsandbears with dangling doc references |
| drom-flow docs pollute a host project's own `docs/` | Ship to `.claude/docs/` and gitignore it; migrate the two already-shipped files out on `--update` (gate 7) |
| Migration deletes a doc the user edited | Compare against the shipped version before removing; if it differs, leave it and warn |

## Assumptions

- grok's `web_search` / `web_fetch` are good enough for the sweep — gate 3 is what proves it
- The project lives under `/mnt/<drive>` (grok is a Windows process)
- `../hyperresearch` stays cloned for reference only; **nothing in drom-flow depends on it**
