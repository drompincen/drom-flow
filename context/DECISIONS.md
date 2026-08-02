# Architecture Decisions

<!-- Format:
## [Date] Decision Title
**Context:** Why this decision was needed
**Decision:** What was decided
**Consequences:** Trade-offs accepted
-->

## 2026-08-01 — Grok sub-agents via CLI + filesystem, not the API

**Decision:** drive grok sub-agents through the **grok CLI binary** with a filesystem control plane,
rather than the xAI HTTP API.

**Why:** reuses the user's existing `grok login` session (no key to store or leak); the CLI already
provides agentic tool use, sessions, permission modes, and structured output; and a file-based
contract (`task.md` in, `status.json`/`result.json` out) survives context compaction, session
restarts, and crashes, while being inspectable by hand.

**Consequence:** the control plane must live on a Windows-visible mount (`/mnt/<drive>`), because
grok.exe is a Windows process and cannot see WSL-native paths.

## 2026-08-01 — Engine routing between Claude and grok sub-agents

**Decision:** Claude sub-agents own anything needing repo context, memory, hooks, multi-file
coherence, or writes to the working tree — plus all final integration. Grok sub-agents own wide,
independent, well-specified units with verifiable outputs, and cross-model second opinions.
**Both engines never write the same files in one phase:** grok writes only inside its `output/`.

**Why:** it keeps the parallel fan-out safe from write conflicts and plays each engine to its
strength. Cross-model review demonstrated real value — a grok reviewer correctly rejected a
plausible-but-wrong claim about regression handling that a single-model pipeline would likely
have accepted.
