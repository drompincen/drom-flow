# QA Closed Loop — Process Graph Visual Quality Pipeline

## Overview

A fully automated closed-loop QA pipeline that captures PNG screenshots of BPMN process diagrams, runs geometric quality checks, spawns multi-agent fix swarms, and iterates until all diagrams pass both automated and visual inspection.

**Result**: 134 issues → 0 issues in 15 iterations. 28/28 diagrams confirmed clean.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    ORCHESTRATION LAYER                           │
│                                                                 │
│  claude-flow workflow: qa-closed-loop                            │
│  Script: ./scripts/qa-orchestrate.sh                            │
│  Protocol: scripts/qa-claude-loop.md                            │
│  Hooks: .claude/hooks (9 hooks, HNSW intelligence)              │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 1: CAPTURE                                               │
│                                                                 │
│  Tool: Playwright (headless Chromium)                           │
│  Test: testing/tests/qa-screenshot-audit.spec.js                │
│                                                                 │
│  For each of N samples × 4 view modes:                          │
│    1. Navigate to app, select diagram                           │
│    2. Click view mode (before/split/after/overlay)              │
│    3. Take full-page PNG screenshot → qa-png/                   │
│    4. Run geometric checks IN THE BROWSER:                      │
│       - No node overlaps (pairwise bbox intersection)           │
│       - All arrows orthogonal (H/V segments only)               │
│       - Arrows avoid non-connected nodes                        │
│       - Labels attached near their connection paths             │
│       - Flow completeness (no orphan/dead-end nodes)            │
│       - Distinct gateway exit ports                             │
│       - Correct arrow entry direction                           │
│       - ≤2 arrow crossings                                      │
│       - ≥20px node spacing                                      │
│    5. Write qa-png/qa-report.json                               │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 2: ANALYZE                                               │
│                                                                 │
│  Tool: node scripts/qa-analyze.js                               │
│                                                                 │
│  1. Parse report, categorize issues by type                     │
│  2. Identify CODE fixes vs JSON fixes                           │
│  3. Generate 6-chapter fix plan + JSON fix manifest             │
│  4. Output: plans/qa-chapters/                                  │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 3: VISUAL INSPECTION (Claude multimodal)                 │
│                                                                 │
│  Claude reads actual PNG files and checks:                      │
│    - Arrowheads attached to lines                               │
│    - Arrows perpendicular to node edges                         │
│    - Labels readable, layout logical                            │
│    - Business stakeholder could understand the diagram          │
│                                                                 │
│  Catches rendering bugs automation misses.                      │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 4: MULTI-AGENT FIX                                       │
│                                                                 │
│  Claude spawns PARALLEL agents via Agent tool:                  │
│    ┌─ Agent "routing-fixer" → js/routing.js code fixes          │
│    ├─ Agent "gateway-fixer" → gateway port assignment            │
│    ├─ Agent "crossing-reducer" → JSON node repositioning         │
│    └─ Agent "flow-fixer" → add missing connections               │
│                                                                 │
│  Each agent reads full source, makes targeted edits,            │
│  returns summary. Claude reviews before proceeding.             │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 5: RE-CAPTURE → back to Phase 1 with iteration N+1      │
│  PHASE 6: CONFIRM → copy clean PNGs to qa-png/confirmed/       │
└─────────────────────────────────────────────────────────────────┘
```

## How to Invoke

### Quick Start
```bash
# Ensure app is running
jbang ProcessGraph.java &

# Run one iteration
./scripts/qa-orchestrate.sh --iteration 1 --max 5

# Claude takes over for visual inspection + fixes
# Then: "Run the QA loop"
```

### Claude Commands
- **"Run the QA loop"** — Executes the full pipeline
- **"Run the QA loop --iteration N"** — Resume from iteration N

### Programmatic (claude-flow)
```bash
# Create workflow
npx @claude-flow/cli@latest workflow create --name qa-closed-loop

# Execute
npx @claude-flow/cli@latest workflow execute --id <workflow-id>
```

## Tools Used

| Tool | Purpose | Layer |
|------|---------|-------|
| `mcp__claude-flow__workflow_create` | Define pipeline steps | Orchestration |
| `mcp__claude-flow__workflow_execute` | Run pipeline | Orchestration |
| `mcp__claude-flow__hooks_init` | Set up learning hooks | Intelligence |
| `mcp__claude-flow__hooks_post-task` | Record outcomes for learning | Intelligence |
| `scripts/qa-orchestrate.sh` | Bash automation (capture+analyze+report) | Automation |
| `scripts/qa-analyze.js` | Generate fix plans from report | Analysis |
| Playwright | Headless browser screenshots + DOM inspection | Capture |
| Agent tool (coder) | Parallel code/JSON fix agents | Fix |
| Read tool (PNG) | Claude visual inspection (multimodal) | QA |

## Iteration History

| Iter | Pass | Issues | Key Fix |
|------|------|--------|---------|
| 1 | 3/28 | 134 | Baseline |
| 2 | 6/28 | 102 | V-H-V cross-lane routing |
| 3 | 11/28 | 47 | Same-lane entry + gateway ports |
| 4 | 13/28 | 25 | safeMidY avoidance + flow filter |
| 5 | 15/28 | 20 | Missing connections + node repositioning |
| 6 | 11/28 | 88 | REGRESSION (reverted bad detour) |
| 7 | 15/28 | 20 | Stable after revert |
| 8-9 | 15/28 | 20 | Incremental JSON adjustments |
| 10 | 14/28 | 34 | REGRESSION (reverted loopBack) |
| 11 | 15/28 | 20 | Stable |
| 12 | 15/28 | 19 | Route hints + x-range separation |
| 13 | 16/28 | 16 | Decorator node filter + manual fixes |
| 14 | 17/28 | 15 | Manufacturing after mode clean |
| 15 | 28/28 | 0 | Full x-range separation + lane reassignment |

## Issue Categories Fixed

| Category | Count | Severity | Fix Type |
|----------|-------|----------|----------|
| wrong-entry-direction | 60 | high | Code (V-H-V routing) |
| shared-gateway-port | 18 | high | Code (preAssignGatewayPorts) |
| shared-arrow-origin | 18 | high | Code (resolveGatewayOutPort) |
| flow-incomplete | 24 | high | JSON (add connections) + Test (filter) |
| excessive-crossings | 14 | medium | JSON (x-range separation) |
| arrow-through-node | 12 | high | Code (safeMidY) + JSON (repositioning) |
| node-crowding | 5 | low | JSON (spacing adjustments) |

## Code Changes

### js/routing.js (~200 net new lines)
- `crossLaneDown/Up` — H-V-H → V-H-V routing pattern
- `straightVert` — L-bend direction reversed for correct entry edge
- `straightHorizLeft` — new function for leftward connections
- `safeMidY` — horizontal segment node avoidance
- `preAssignGatewayPorts` — pre-computes distinct ports per gateway
- `resolveGatewayOutPort` — enhanced with usedPorts + _gatewayPortMap
- `gatewayPortRoute` — out-right now routed (was returning null)
- `computeLabelPosition` — directional offset for yes/no branches

### js/layout.js (~15 lines)
- `detectDirection` — 1.5x threshold for horizontal vs vertical

### Test changes
- `checkFlowCompleteness` — skip version-only nodes in split/overlay
- `checkFlowCompleteness` — skip decorator node types

### JSON changes (all 8 sample files)
- Node x-coordinate repositioning
- Before/after version x-range separation
- Missing connections added
- Route hints for loopback connections
- Lane reassignment for crossing reduction

## Folder Structure

```
qa-png/
├── *.png              ← Current iteration screenshots
├── qa-report.json     ← Automated check results
├── fixed/             ← PNGs that passed automated checks
└── confirmed/         ← PNGs that passed automated + visual
                         (28/28 = fully verified clean)

plans/qa-chapters/
├── ch1-arrow-routing.md
├── ch2-node-overlaps.md
├── ch3-label-placement.md
├── ch4-swimlane-compliance.md
├── ch5-json-adjustments.md
├── ch6-summary.md
└── json-fix-manifest.json

scripts/
├── qa-orchestrate.sh  ← Master orchestration script
├── qa-analyze.js      ← Report → fix plan generator
└── qa-claude-loop.md  ← Claude protocol document
```

## Key Learnings

1. **V-H-V routing is superior to H-V-H for cross-lane arrows** — Always entering from top/bottom matches the user's mental model of "going to another lane."

2. **safeMidY for horizontal segments is essential** — Without it, V-H-V paths cut through intermediate nodes.

3. **Same-lane horizontal routing should NOT detour** — Attempts to route around same-lane nodes (loopBack, safeMidY on horizontal) created more regressions than they fixed. The solution is repositioning nodes in JSON.

4. **X-range separation is the key to split/overlay crossings** — When before and after versions occupy non-overlapping x-ranges, their arrows can't cross each other.

5. **Visual inspection catches what automation misses** — Arrowhead detachment, rendering glitches, and "does this make business sense?" are not automatable.

6. **Regressions happen fast, revert faster** — Iterations 6 and 10 regressed. Immediate revert + different approach was better than trying to fix the fix.
