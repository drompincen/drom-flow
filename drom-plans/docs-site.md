---
title: drom-flow Guide — GitHub Pages site with a Claude-orchestrator / grok-workers section
status: pending
created: 2026-08-03
updated: 2026-08-03
current_chapter: 1
loop: true
check_command: bash scripts/docs-verify.sh --json
pass_condition: all ten gates PASS in reports/docs-site.json
max_iterations: 10
---

# Plan: drom-flow Guide (GitHub Pages)

Publish a complete guide for drom-flow at `drompincen.github.io/drom-flow`, with a first-class
section on the **Claude orchestrator / grok workers** model, and a full reference for skills,
agents, scripts, hooks, and workflows.

---

## What already exists (surveyed by grok, 2026-08-03)

**8 markdown docs, ~192 KB.** Nothing is published anywhere.

| Doc | Role today |
|---|---|
| `README.md` | The de-facto guide — install, features, skills table, statusline, grok fan-out, token economy |
| `docs/grok-fleet.md` | Fleet mechanics, routing, monitoring, stop, security caveats, benchmarks |
| `docs/token-economy.md` | Measured token costs and the three rules |
| `docs/df-research.md` | Research pipeline, quality gate, what was borrowed from hyperresearch |
| `docs/javaducker.md` | 48-tool catalog (conditional) |
| `SCRIPTS.md` | **Distribution mechanism** — full source of every `.sh` (they are gitignored) |
| `CLAUDE.md` / `template/CLAUDE.md` | Behavioural rules loaded into sessions |
| `samples/df-research-example.md` | Worked research output |

**Pages status:** no `gh-pages` branch, no `docs/_config.yml`, Pages API returns 404. Greenfield.

### Gaps the survey identified

1. **Parity is not documented and is currently wrong** — `ascii-architect` exists at repo root but is
   **missing from `template/`**, so host projects never receive it. Root has ~13 skills, template has 29.
2. **No unified script/CLI reference** — 11 scripts with ~40 subcommands between them, documented only
   in scattered prose.
3. **No operator runbook** — nothing stitches fleet → limit-watch → df-research → resume into "what do
   I actually do on day one".

### Separation of concerns (decided 2026-08-03)

`docs/` and host-shipped docs are **different documents with different jobs** — not copies.

| Location | Audience | Ships to host projects? |
|---|---|---|
| `docs/` | The public guide + home documentation (GitHub Pages) | **Never** |
| `template/.claude/docs/` | Operator runbooks for a project using drom-flow | Yes, via `init.sh` |

Verified: `init.sh` copies `template/` only, so the repo's `docs/` has never shipped. The single
coupling was a manual `cp` that made the four operational docs byte-identical duplicates. That
duplication is removed: the operational docs become **host-only runbooks**, and `docs/` is rewritten
as site pages — richer, structured for reading, linking to the runbooks rather than restating them.
No sync script and no front-matter stripping needed, because there is nothing to keep in sync.

---

## EXIT CRITERIA

`scripts/docs-verify.sh` writes `reports/docs-site.json`.

| # | Gate | Criterion |
|---|---|---|
| 1 | `builds` | Jekyll site builds clean from `docs/` with no errors or missing includes |
| 2 | `live` | Pages enabled and the published URL returns 200 with the guide's index |
| 3 | `orchestrator` | The Claude-orchestrator / grok-workers section exists and covers: the division of labour, routing rules, fleet lifecycle, resume-on-limit, and the **measured** token numbers |
| 4 | `reference` | Every skill, script subcommand, hook, and workflow in the repo appears in the reference — verified by **counting against the filesystem**, not by eye |
| 5 | `links` | Zero broken internal links and zero references to files that do not exist |
| 6 | `truth` | No stale claims: skill/hook counts, install manifest, and `.claude/docs/` paths all match reality; the `ascii-architect` parity gap is fixed |
| 7 | `separation` | `docs/` never ships: a fresh host install contains **no file from `docs/`**, and `.claude/docs/` holds only the runbook set. Verified on a throwaway project |
| 8 | `runbook` | `template/.claude/docs/runbook.md` exists and covers the operator's first hour: doctor, fan-out, monitor, stop, research, resume-on-limit |
| 9 | `scripts_tested` | Every new/changed script passes `bash -n` **and** an executed smoke test of each subcommand; no script ships untested |
| 10 | `ship` | `VERSION` upticked; `SCRIPTS.md` round-trip verified; committed and pushed |

**Max iterations: 10.**

---

## Chapter 1: Site skeleton
**Status:** pending

- [ ] `docs/_config.yml` — theme (`just-the-docs` or `minima`), title, baseurl `/drom-flow`, nav order
- [ ] `docs/index.md` — what drom-flow is, the 60-second install, and a map of the guide
- [ ] Decide and document the Pages source: **`main` branch, `/docs` folder** (no `gh-pages` branch to keep in sync)
- [ ] Front matter added to `docs/*.md` for nav; verify the site builds locally (`bundle exec jekyll build`) or accept GitHub's build
- [ ] `scripts/docs-verify.sh` — the eight gates, filesystem-counted

## Chapter 2: The Claude orchestrator / grok workers section
**Status:** pending
**Depends on:** Chapter 1

The centrepiece. `docs/orchestration.md`.

- [ ] **The model** — Claude is the orchestrator (decides, dispatches, adjudicates); grok agents are workers (read, fetch, draft, critique). Claude is the *interruptible* component; grok is not
- [ ] **Why** — with the measured evidence: cache_creation scales with resident context, so a 200k context bills ~200k per cache write. Real numbers from this repo: turns −64%, Claude output −65%, context bytes −97%, quality held
- [ ] **The contract** — filesystem control plane: `task.md` in; `status.json` / `PROGRESS.md` / `result.json` / `output/` out. Model-agnostic, survives compaction and crashes
- [ ] **Routing rules** — what must stay Claude (integration, security-sensitive changes, ambiguous requirements, working-tree writes, adjudicating conflicts) vs what always goes to grok (file reading, breadth research, mechanical transforms, test generation, first drafts, cross-model review, reduction)
- [ ] **Lifecycle diagram** — dispatch → concurrency gate → progress → stall detection → stop → collect → resume (ASCII, via `/ascii-architect`)
- [ ] **Worker anatomy** — the injected fleet preamble, `--check` self-verification, `--best-of-n`, grok-side retry, and why deliberate stops are never retried
- [ ] **Reading results cheaply** — `collect --brief`, verdicts not artifacts, the ≤4 KB rule
- [ ] **Running out of Claude tokens** — limit-watch: the definitive vs 97% predictive trigger, why no live quota meter exists, detached `drain`, hourly ping, resume from a ~230-byte record
- [ ] **Honest limits** — `--sandbox` does not confine writes; X/social sources are signal not evidence; unmetered-grok assumptions
- [ ] Worked end-to-end example with real output

## Chapter 3: Complete reference
**Status:** pending
**Depends on:** Chapter 2

- [ ] `docs/skills.md` — **every** skill: name, description, when to use, grouped (engineering, web quality, product management, grok/research, JavaDucker). Generated from frontmatter so it cannot drift
- [ ] `docs/agents.md` — how Claude sub-agents and grok agents differ, how to define each, and when to use which
- [ ] `docs/scripts.md` — every script and **every subcommand** with flags and env vars, generated from the `case` blocks
- [ ] `docs/hooks.md` — the 8 lifecycle hooks, their events/matchers, and cost (the PostToolUse checks run outside Claude's context)
- [ ] `docs/workflows.md` — the 6 workflows and when each applies
- [ ] `docs/plans.md` — chapter-based plans, closed-loop protocol, resumption
- [ ] `docs/install.md` — install, update, uninstall, the ZIP-safe `SCRIPTS.md` mechanism, and what host projects actually receive
- [ ] Generation, not transcription: a script derives skills/scripts pages from the repo so counts can't go stale

## Chapter 4: Separate site docs from host docs
**Status:** pending
**Depends on:** Chapter 3

- [ ] Confirm and document that `init.sh` ships `template/` only — the repo's `docs/` is site-only
- [ ] Remove the duplication: the four operational docs (`grok-fleet`, `token-economy`, `df-research`,
      `javaducker`) become **host-only**, authored in `template/.claude/docs/`
- [ ] `docs/` is rewritten as site pages that link to the runbooks on GitHub rather than restating them
- [ ] `scripts/docs-verify.sh` proves the separation by installing into a throwaway project and asserting
      no `docs/` file arrives
- [ ] Keep the existing `--update` migration that moves drom-flow docs out of a host project's own `docs/`

## Chapter 5: Host runbook
**Status:** pending
**Depends on:** Chapter 4

- [ ] `template/.claude/docs/runbook.md` — the operator's first hour, terse and copy-pasteable:
      preflight (`grok-fleet.sh doctor --live`), a first fan-out, monitoring, stopping, a research run,
      what to read (verdicts, not artifacts), and what to do when Claude hits its limit
- [ ] Cross-link the runbook from `template/CLAUDE.md` and the `grok-fleet` / `df-research` skills
- [ ] Keep it under ~4 KB — it is host-shipped and read by an agent with a token budget

## Chapter 6: Truth pass
**Status:** pending
**Depends on:** Chapter 3

- [ ] **Fix the parity bug**: mirror `ascii-architect` into `template/.claude/skills/` (or document deliberately why not)
- [ ] Reconcile every count in README and the site against the filesystem (skills, hooks, workflows, scripts)
- [ ] Fix stale path references (`docs/…` → `.claude/docs/…` in host-facing text)
- [ ] Link check across all pages, including references to repo files
- [ ] README becomes a **short front door** that points at the site, rather than a second competing guide

## Chapter 7: Ship
**Status:** pending
**Depends on:** Chapter 6

- [ ] Enable Pages (main `/docs`); confirm the published URL serves the index
- [ ] Verify the separation on a throwaway install (gate 7) and that the runbook shipped
- [ ] Bump `VERSION`; embed any new scripts in `SCRIPTS.md` with round-trip verification
- [ ] Commit and push; confirm the live site reflects the push

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Site scaffolding leaks into host projects | `docs/` is never shipped at all; gate 7 proves it on a real install |
| Docs drift from reality (again) | Skills/scripts pages are **generated**; gate 4 counts against the filesystem |
| Two competing guides (README vs site) | README demoted to a front door in Ch 4 |
| Pages build fails silently on GitHub | Gate 2 fetches the live URL rather than trusting the push |
| Site duplicates `SCRIPTS.md` and they diverge | Site links to `SCRIPTS.md`; it stays the single distribution source |

## Assumptions

- Pages serves from `main` + `/docs`, public repo, default GitHub Jekyll build (no custom Actions needed)
- `docs/javaducker.md` stays conditional — linked from the site, not loaded into sessions
