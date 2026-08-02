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

## 2026-08-01 — Claude is the interruptible component

**Decision:** treat Claude as the component that may run out mid-task, and grok as the one
that keeps going. Fan-outs dispatch detached (`drain`), state is written atomically, and a
cold Claude session resumes from a ≤2 KB `RESUME.md` rather than re-deriving context.

**Why:** Claude tokens are finite here and grok's are not. Verified: killing an agent
mid-flight and resuming yields 4/4 complete, 0 re-run, 0 lost.

## 2026-08-01 — Optimize turns and authoring, not tool output

**Decision:** target Claude turn count and Claude-authored text, not the size of tool results.

**Why:** measurement showed cache reads (turns × context) at 36.4M tokens and Claude output
at 366K, versus only ~27K for all tool results combined. The first delegated attempt cut
turns 64% but output only 28%, because Claude still wrote the dispatch script by hand —
encapsulating that in `bench-audit.sh` took the output cut to 65%.
