---
title: Install and update merge skills instead of replacing them
status: completed
created: 2026-08-11
updated: 2026-08-11
current_chapter: 6
---

# Plan: Install and update merge skills instead of replacing them

A host project's skills must survive `init`, `--update` and `--uninstall`. Skills are prompts a
team tunes; overwriting one is destroying work, even when a backup exists somewhere.

## What actually happens today (measured, not assumed)

A fixture host with a team-owned skill and a customised copy of a drom-flow skill:

| | fresh install | update | uninstall |
|---|---|---|---|
| host-only skill (`my-team-skill/`) | kept | kept | kept |
| extra file inside it (`NOTES.md`) | kept | kept | kept |
| **customised `planner/planner.md`** | **overwritten** | **overwritten** | removed |

So "don't remove pre-existing skills" is already true for host-owned ones. The real gap is the
**same-named** case: a team edits `planner.md`, and the next update silently replaces it. A copy
lands in `setup-backup/`, but nobody reads a backup they were never told about.

## The policy

Borrowed from package managers, because it is the well-understood answer to this exact problem:

```text
target absent                      -> install it
target identical to what we ship   -> nothing to do
target unchanged since WE wrote it -> update it (this is a normal managed update)
target changed by the host         -> KEEP IT, write the new version alongside, and say so
```

"Unchanged since we wrote it" needs a baseline, so installs record the hash of every skill file
they write. Without a baseline (a host installed before this change), anything differing from the
shipped copy is treated as customised — the conservative direction.

**Scope: `.claude/skills/**` only.** Hooks, scripts and the engine stay on overwrite-with-backup.
Freezing those in place on any local edit would mean a host silently never receiving a bug fix,
which is the opposite failure and a worse one. Skills are behavioural text meant to be tuned;
infrastructure is not.

---

## Chapter 1: Baseline manifest
**Status:** completed
**Depends on:** none

- [x] `.claude/.state/drom-flow-skills.json` — map of skill-file path to the hash drom-flow wrote
- [x] Written on every install and update, for every skill file drom-flow owns
- [x] Missing baseline is a supported state and resolves conservatively (assume customised)

## Chapter 2: Three-way decision on copy
**Status:** completed
**Depends on:** Chapter 1

- [x] Absent -> install, record baseline
- [x] Identical to shipped -> no action, record baseline
- [x] Matches baseline -> update, record new baseline
- [x] Differs from baseline -> **preserve**, write `<file>.dromflow-new`, report it
- [x] `--check` reports which skills would be preserved rather than updated

## Chapter 3: Host-owned skills are never touched
**Status:** completed
**Depends on:** none

- [x] Skill directories drom-flow does not ship are never read, moved or deleted
- [x] Extra files inside a drom-flow skill directory are never deleted
- [x] Gate proves all of it, because "already works" is a claim with a short shelf life

## Chapter 4: Uninstall
**Status:** completed
**Depends on:** Chapter 1

- [x] A skill file the host modified is **kept**, not removed, and reported as kept
- [x] `*.dromflow-new` files are left alone
- [x] Unmodified managed skills are removed as they are today
- [x] `.claude/skills/` is only removed when genuinely empty

## Chapter 5: Say it out loud
**Status:** completed
**Depends on:** Chapter 2

- [x] Install/update output lists preserved skills explicitly — a silent preserve is as bad as a
      silent overwrite, because the host never learns the new version exists
- [x] Documented in README and the install page in one short paragraph

## Chapter 6: Gates
**Status:** completed
**Depends on:** Chapters 2-4

- [x] `scripts/install-verify.sh` writing `reports/install-merge.json`
- [x] Fixture host: host-only skill + extra file + customised managed skill + untouched managed skill
- [x] Assert across install -> update -> update-again -> uninstall
- [x] Assert the unmodified managed skill still receives updates (the policy must not freeze everything)
- [x] Assert `.dromflow-new` appears exactly when a file was preserved, and never otherwise

---

## Risks

- **Preserving too much.** If the baseline is wrong, hosts stop receiving skill improvements
  entirely and never find out why. Mitigated by writing `.dromflow-new` and naming it in the
  output every single time.
- **Scope creep into infrastructure.** Applying this to hooks or the engine would freeze bug
  fixes. Explicitly out of scope, and the gate only covers skills.

---

## Outcome

`scripts/install-verify.sh` -> `reports/install-merge.json`, **5/5**:

| Gate | Result |
|---|---|
| host-owned skill, its extra files and host source survive install/update/uninstall | PASS |
| a host-edited managed skill is kept, `.dromflow-new` offered, and reported | PASS |
| an unmodified managed skill still receives updates (no freezing) | PASS |
| a clean update produces no spurious `.dromflow-new` | PASS |
| uninstall keeps edited skills, reports them, still removes untouched ones | PASS |

The measured starting point is worth keeping: host-owned skills were already safe. The bug was
the same-named case — a team's edited `planner.md` was replaced on every update, with the only
copy in a `setup-backup/` directory nobody was told about.
