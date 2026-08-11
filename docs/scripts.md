---
title: Scripts
nav_order: 5
---

# Scripts

Every script and subcommand, read out of the `case` dispatch blocks. Sources live in
Shell assets ship as text (`*.sh.txt`) and are materialised into runnable `*.sh` by init; the generated `*.sh` is gitignored.

### `bench-audit.sh`

drom-flow — encapsulated audit fan-out.

### `check-parity.sh`

drom-flow — parity check for the delegated audit benchmark.

### `codex-fleet.sh`

drom-flow — codex sub-agent fleet: filesystem-controlled fan-out to the codex CLI.

Subcommands: `clean`, `collect`, `doctor`, `resume`, `spawn`, `status`, `stop`

### `codex-verify.sh`

drom-flow — gates for the codex sub-agent runner.

### `ddb-introspect.sh`

drom-flow — read an EXISTING DynamoDB estate into the model the amazon-dynamodb skill consumes.

### `df-research-audit.sh`

drom-flow — df-research quality audit (gate 3).

### `df-research-verify.sh`

drom-flow — df-research exit gates. Sourced by df-research.sh.

### `df-research.sh`

drom-flow — df-research: deep research on the grok fleet.

Subcommands: `doctor`, `run`, `verify`

### `docs-gen.sh`

drom-flow — generate the reference pages from the repo itself.

### `docs-verify.sh`

drom-flow — gates for the documentation site.

### `fleet.sh`

drom-flow — one front door to the sub-agent runners.

Subcommands: `analysis`, `audit`, `author`, `breadth`, `bulk`, `code`, `codex`, `collect`, `doctor`, `grok`, `implement`, `refactor`, `research`, `resume`, `review`, `route`, `search`, `social`, `spawn`, `status`, `stop`, `sweep`, `test`, `web`

### `grok-fleet.sh`

drom-flow — grok sub-agent fleet: filesystem-controlled fan-out from WSL to grok CLI (Windows).

Subcommands: `checkpoint`, `clean`, `collect`, `doctor`, `drain`, `resume`, `spawn`, `status`, `stop`, `verify`

### `grok-resume.sh`

drom-flow — resume support for the grok fleet.

### `grok-verify.sh`

drom-flow — closed-loop verifier for the grok sub-agent fleet.

### `install-verify.sh`

drom-flow — gates for install/update/uninstall behaviour around host-owned content.

### `limit-verify.sh`

drom-flow — gates for the token-limit wake-up loop. Sourced by limit-watch.sh.

### `limit-watch.sh`

drom-flow — Claude usage-limit watcher.

Subcommands: `arm`, `calibrate`, `check`, `disarm`, `ping`, `status`, `verify`

### `mk-task.sh`

drom-flow — generate a grok task.md from a template, so dispatching N units costs

### `orchestrate.sh`

drom-flow orchestration script template

### `repo-intel-bench.sh`

drom-flow — measures what repository intelligence is FOR: discovery cost.

### `repo-intel-verify.sh`

drom-flow — release gates for repository intelligence.

### `test-resume.sh`

drom-flow — gate 7: survive Claude token exhaustion.

### `token-audit.sh`

drom-flow — Claude token audit + delegation gates.

Subcommands: `gates`, `ledger`, `mark`, `measure`
