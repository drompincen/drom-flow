---
title: Install and updates
nav_order: 9
---

# Install, update, uninstall

## Why scripts ship as text

GitHub routes ZIP downloads through `codeload.github.com` when a repo contains `.sh` files, which some
corporate firewalls block. So **`*.sh` is gitignored** and every script's full source lives in
[`SCRIPTS.md`](https://github.com/drompincen/drom-flow/blob/main/SCRIPTS.md). Generate them once:

```bash
claude "Read start-here.md and follow the setup instructions"
```

`SCRIPTS.md` is the real distribution channel — a script change that isn't re-embedded there never
reaches anyone.

## Install / update / uninstall

### Your skills are yours

drom-flow ships skills, and teams tune them. On update, a skill file you have edited is **kept** —
the new version lands beside it as `<name>.dromflow-new` and the run tells you which files it
preserved. A skill you have not touched updates normally, so the policy preserves without freezing.
Skills drom-flow does not ship are never read, moved or deleted, and `--uninstall` leaves both
your own skills and any you edited in place.

This applies to `.claude/skills/` only. Hooks, scripts and the engine still update in place with a
backup, because freezing those on a local edit would silently deny you every future fix.

Shell assets ship as **text** (`*.sh.txt`) so ZIP downloads and corporate mail/proxy filters cannot strip them; `init` materialises runnable `*.sh` on install and update.

```bash
bash /path/to/drom-flow/init.sh.txt              # install into the current project
bash /path/to/drom-flow/init.sh.txt --check .    # dry run
bash /path/to/drom-flow/init.sh.txt --update .   # upgrade
bash /path/to/drom-flow/init.sh.txt --uninstall .
```

**Never overwritten:** `CLAUDE.md`, `context/MEMORY.md`, `context/DECISIONS.md`,
`context/CONVENTIONS.md`, `scripts/orchestrate.sh`, plans, reports. Overwritten files are backed up to
`setup-backup/<timestamp>/` first.

`--update` also **merges** new guidance sections into an existing `CLAUDE.md` rather than replacing it,
and **preserves third-party hooks** in `.claude/settings.json` (a tool like hyperresearch registering
its own hook is not clobbered).

## What a host project receives

| Path | Contents |
|---|---|
| `CLAUDE.md` | behavioural rules, parallelism, closed-loop and plan protocol |
| `.claude/skills/` | the skills marked *ships* in [Skills](skills.md) |
| `.claude/hooks/` | lifecycle hooks (see [Hooks](hooks.md)) |
| `.claude/docs/` | **operator runbooks — gitignored**, including `runbook.md` |
| `scripts/` | the fleet, research, audit and watcher scripts |
| `workflows/`, `context/`, `drom-plans/`, `reports/` | protocol, memory, plans, run output |

**This site is never installed.** `docs/` is the public guide and stays in the repo; host projects get
`.claude/docs/` instead — terse, operational, and gitignored so it never pollutes your tree.
