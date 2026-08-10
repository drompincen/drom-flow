---
title: Native Repository Intelligence (repo-intel)
status: completed
created: 2026-08-08
updated: 2026-08-08
current_chapter: 18
---

# Plan: Native Repository Intelligence (repo-intel)

A private, deterministic, automatically maintained repository-awareness layer inside
drom-flow. Host users never learn it exists; drom-flow's own skills query it instead of
re-discovering repository structure with Grep/Read every session.

**Governing model**

```text
Skills     = behaviour        Graph  = persistent structural memory
Java/JBang = deterministic engine    Source = authoritative truth
Bash       = lifecycle glue          Claude = reasoning
Grok       = optional parallel analysis (never writes facts into the graph)
MCP        = not used
```

**North-star KPI** — Discovery Cost Reduction: fewer exploratory tool calls and fewer raw
source bytes consumed before the correct working set is identified. Node/edge counts are
implementation metrics; discovery cost and task correctness are the product metrics.

---

## Chapter 1: Architecture and installation analysis
**Status:** completed
**Depends on:** none

- [x] `git status`, VERSION (0.9.2), CLAUDE.md, README.md, start-here.md, SCRIPTS.md
- [x] init.sh install/update/check/uninstall/uninstall-check paths, USER_FILES, MANAGED_DIRS
- [x] `.claude/settings.json` hook wiring; edit-log.sh, memory-sync.sh, javaducker-*.sh
- [x] template/ → host copy mechanism (`find template -type f` + chmod of hooks and scripts)
- [x] .gitignore, docs-verify.sh gates (every script/hook/skill must be documented)
- [x] grok fleet control plane (`scripts/grok-fleet.sh` manifest spawn, collect --brief)

**Notes:**
> Distribution is `template/` → `find -type f` → copy. Anything the host needs must live
> under `template/`. `chmod +x` currently covers only `.claude/hooks/*.sh` and `scripts/*.sh`
> — a runtime wrapper elsewhere needs an explicit chmod line.
> `.gitignore` ignores `*.sh` repo-wide, so **no shell asset is tracked by git** (0 of 138
> tracked files are .sh, including init.sh). A fresh clone cannot install drom-flow at all.
> Requirement §72 says assets must not be source-checkout-only — fix in Chapter 9.

## Chapter 2: Engine core — schema, scan, manifest, atomic state
**Status:** completed
**Depends on:** Chapter 1

- [x] Decide runtime: zero-dependency Java, JBang-compatible header, javac-cache fast path
- [x] `GraphModel.java` — versioned schema, node/edge records, stable IDs — [.claude/df/repo-intel/]
- [x] `Json.java` — minimal deterministic reader/writer (no Jackson, no network)
- [x] `RepositoryScanner.java` — `git ls-files -co --exclude-standard` + filesystem fallback
- [x] `ManifestStore.java` — path→(hash,size,mtime,lang,node_ids); add/modify/delete/rename
- [x] `JsonStore.java` — temp file + validate + atomic rename; never clobber a healthy graph

## Chapter 3: Language extractors
**Status:** completed
**Depends on:** Chapter 2

- [x] `ExtractorRegistry.java` — `supports(Path)` dispatch, one extractor per language
- [x] Java extractor — package/import/class/interface/enum/record/field/method/call/annotations
- [x] Python extractor — import forms, class/def/decorator, calls, relative imports
- [x] JS/TS extractor — ESM + CommonJS, class/function/type/enum, exports, calls
- [x] Bash extractor — both function forms, `source`/`.` includes, calls
- [x] Manifest extractor — pom.xml, build.gradle(.kts), package.json, pyproject.toml,
      requirements.txt, go.mod → external package nodes + DEPENDS_ON
- [x] Comment/string masking shared by all extractors (traps in the fixtures depend on it)

**Notes:**
> Java extractor written here as the reference; Python, TypeScript/JavaScript, Bash and
> manifest extractors were authored by grok sub-agents against the `Extractor` interface and the
> independently written fixture ground truth, then corrected here.

## Chapter 4: Cross-file resolution and confidence
**Status:** completed
**Depends on:** Chapter 3

- [x] `Resolver.java` — qualified-name, simple-name, file, package, import indexes
- [x] Resolve calls/references/inheritance/imports across files
- [x] EXTRACTED / INFERRED / AMBIGUOUS only; provenance (file, line, resolver) on every edge
- [x] Never collapse ambiguity into certainty — emit AMBIGUOUS or omit

## Chapter 5: Incremental refresh and freshness
**Status:** completed
**Depends on:** Chapter 4

- [x] `IncrementalUpdater.java` — remove facts for changed/deleted files, reparse only changed
- [x] `ensureCurrent()` — metadata + dirty journal + external change detection (git, size/mtime, hash)
- [x] Schema/engine version compare → no-op | incremental | rebuild
- [x] Failure preserves the last valid graph and records why

## Chapter 6: Internal query API
**Status:** completed
**Depends on:** Chapter 5

- [x] `GraphQuery.java` — ensure, stats, symbol, search, neighbors, callers, callees,
      dependencies, dependents, impact, path, explain, verify
- [x] Deterministic ranked text search (no embeddings, no vector store)
- [x] Bounded output: ≤25 nodes, ≤40 edges, ≤15 KB JSON by default, explicit `truncated`
- [x] `GraphValidator.java` — schema, unique ids, edge endpoints, relations, confidences, paths

## Chapter 7: Runtime wrapper and graceful degradation
**Status:** completed
**Depends on:** Chapter 6

- [x] `.claude/df/repo-intel/run` — javac-cache → jbang → jbang bootstrap → unavailable
- [x] Cache compiled classes under `.claude/.state/repo-intel/classes/`
- [x] No JVM without a reason; record `unavailable` with a reason and stop retrying
- [x] Windows/WSL: spaces in paths, executable bit, cache location

**Notes:**
> This machine turned out to have a **Windows JDK reached from WSL** — it cannot see WSL paths
> and cannot run WSL's git. The wrapper detects that case, translates every path with
> `wslpath -w`, and hands the file list over via `DROMFLOW_REPO_INTEL_FILELIST`.

## Chapter 8: Lifecycle hooks
**Status:** completed
**Depends on:** Chapter 7

- [x] `repo-intel-mark.sh` — PostToolUse dirty marker, pure bash, no JVM, <100 ms
- [x] `repo-intel-session.sh` — SessionStart: metadata check only, background intake, quiet
- [x] Register both in `template/.claude/settings.json` without disturbing existing hooks

## Chapter 9: Install, upgrade, uninstall
**Status:** completed
**Depends on:** Chapter 8

- [x] init.sh: chmod the runtime wrapper, MANAGED_DIRS entries, state seeding, intake marking
- [x] Upgrade detects engine/schema change and marks intake-required — no user action
- [x] Uninstall removes managed assets + state, leaves host source and user files intact
- [x] Fix `*.sh` gitignore so drom-flow's own shell assets are actually distributed

## Chapter 10: Skill integration
**Status:** completed
**Depends on:** Chapter 9

- [x] Internal protocol block (short) — planner, architect, debugger, reviewer, refactorer
- [x] implementer + orchestrator: use when structural, skip when trivial
- [x] CLAUDE.md: one concise resident rule, no command surface
- [x] No `/repo-map` command, no user-facing feature page

## Chapter 11: Test suite
**Status:** completed
**Depends on:** Chapter 10

- [x] Fixture corpora with independent ground truth (grok-authored): java, python, ts/js, bash, manifests
- [x] Security fixture (built locally): .env, symlink escape, traversal, binary, oversized, malformed, spaces
- [x] `scripts/repo-intel-verify.sh` — one gate per release criterion, JSON report
- [x] incremental ≡ clean rebuild for add/edit/delete/rename
- [x] install / upgrade / uninstall in a throwaway fixture host project
- [x] failure isolation: no java, no jbang, blocked maven, corrupt graph, read-only state

## Chapter 12: Benchmarks
**Status:** completed
**Depends on:** Chapter 11

- [x] `scripts/repo-intel-bench.sh` — A/B discovery cost, graph vs grep-and-read baseline
- [x] Intake time, incremental time, warm query latency, hook latency
- [x] Impact recall against curated change requests
- [x] Record real numbers only — no invented percentages

**Notes:**
> Large-repo benchmarking exposed a quadratic scope lookup in three extractors (linear scan per
> call site). A 9,655-file repository never finished indexing; after replacing it with a cursor
> over scopes sorted by start it completes in 191 s, and that time is IO-bound on this mount
> (reading the same files at all takes 236 s).

## Chapter 13: Real-repository proof
**Status:** completed
**Depends on:** Chapter 12

- [x] Three materially different real repositories (Java/Spring, TypeScript, mixed)
- [x] Fixed battery of orientation/impact tasks with and without repo intelligence
- [x] Retain measurements in reports/

## Chapter 14: Skill-adoption behaviour test
**Status:** completed
**Depends on:** Chapter 10

- [x] Curated task set labelled structurally-complex vs trivial
- [x] Independent judges (grok fleet) read the shipped skill text and decide use/no-use
- [x] Gate: ≥90% use on complex, ≤10% unnecessary use on trivial

## Chapter 15: Documentation
**Status:** completed
**Depends on:** Chapter 13

- [x] README — capability only, four lines, no mechanics
- [x] docs/ site pages updated so docs-verify gates still pass
- [x] `.claude/docs/repo-intel.md` — maintainer internals (gitignored in host projects)
- [x] SCRIPTS.md + VERSION bump + context/DECISIONS.md entry

## Chapter 16: Final review and scorecard
**Status:** completed
**Depends on:** Chapters 11-15

- [x] Adversarial review of the §90 checklist (grok fan-out, parallel)
- [x] Fix significant findings
- [x] Publish the release scorecard with measured values

**Notes:**
> Five parallel grok reviewers found real defects, all fixed: init.sh truncated
> `drom-flow.conf` (losing a relocated graph path), uninstall re-rooted absolute state paths,
> Windows absolute paths were treated as project-relative, the read path had no symlink/size
> re-check after admission, the three state files were not written transactionally, and the
> skill text claimed "never stale" and treated a zero-result exit code as a failure.

## Chapter 17: Configurable graph location
**Status:** completed
**Depends on:** Chapter 7

- [x] `.claude/hooks/repo-intel-path.sh` — one resolver shared by the wrapper and both hooks
- [x] Order: `DROMFLOW_REPO_INTEL_STATE` env -> `REPO_INTEL_STATE` in `.claude/.state/drom-flow.conf`
      -> default `<project>/.claude/.state/repo-intel`
- [x] Default stays **inside the host project** so the graph travels with the repository and
      uninstall removes it cleanly
- [x] Absolute, `~`-relative and project-relative paths all resolve; spaces in paths tested
- [x] A relocated graph is still found by SessionStart, the dirty marker and query-time ensure
- [x] Uninstall removes a relocated state directory too, and says so

## Chapter 18: Full documentation — README + GitHub Pages
**Status:** completed
**Depends on:** Chapter 15

- [x] README: capability-level section plus a reference table of what ships, no mechanics
- [x] `docs/repo-intelligence.md` — full HTML-rendered page on the GitHub Pages site
- [x] Link it from `docs/index.md` and cross-link hooks/scripts/skills pages
- [x] `docs/hooks.md`, `docs/scripts.md`, `docs/skills.md` updated so `docs-verify.sh` gate 4
      (every hook, script and skill documented) still passes
- [x] Maintainer internals in `.claude/docs/repo-intel.md` (gitignored in host projects)
- [x] `docs-verify.sh` runs green, including the separation gate that keeps `docs/` out of hosts

---

## Release scorecard — acceptance gates

Success is not "we built a graph". Success is "Claude reaches the right files and
relationships faster, with less context and fewer exploratory tool calls, while the user
does nothing differently."

| # | Metric | Launch gate | Target |
|---|--------|------------:|-------:|
| 1 | User manual repo-intel steps | **0** | **0** |
| 2 | Critical benchmark relationships found | ≥90% | ≥95% |
| 3 | False confident edges (EXTRACTED/INFERRED) | <5% | <2% |
| 4 | Critical impact files surfaced | ≥90% | ≥95% |
| 5 | Exploratory tool-call reduction | ≥30% | ≥50% |
| 6 | Initial discovery source-byte reduction | ≥30% | ≥50-70% |
| 7 | Warm query latency | <2 s | <500 ms-1 s |
| 8 | Edit-hook latency | <100 ms | <50 ms |
| 9 | Incremental vs clean rebuild equivalence | **100% fixtures** | **100%** |
| 10 | Automatic install/upgrade scenarios | **100% pass** | **100%** |
| 11 | Normal drom-flow survives engine failure | **100%** | **100%** |
| 12 | Complex tasks use graph appropriately | ≥90% | ≥95% |
| 13 | Trivial tasks avoid unnecessary graph work | ≥90% | ≥95% |

Additional hard gates carried from the success criteria:

- **Invisible UX** — no repo-intel/JBang/Tree-sitter/bootstrap/index/graph command is ever
  required. Intake, schema change, stale detection and refresh are automatic. If the engine
  cannot run, drom-flow falls back silently to normal search/read.
- **Correctness** — ≥95% of deterministic declarations/imports/inheritance in the fixture
  corpus; ≥90% of deliberately resolvable cross-file call/reference relationships.
  Uncertain relationships must be AMBIGUOUS, unresolved, or omitted — never confident.
- **Bounded output** — ≤25 nodes, ≤40 edges, ≤15 KB JSON by default, truncation flagged.
- **Automatic freshness** — every structural operation runs `ensureCurrent`; hook-driven and
  external (IDE/git checkout) changes are both detected.
- **No JVM on edit** — explicitly tested: PostToolUse starts no Java process.
- **Stable identity** — re-index of an unchanged repo yields identical node IDs and does no
  parsing work; moving lines within a method does not change symbol identity.
- **No quality regression** — the graph narrows scope; source is still read and verified
  before consequential recommendations.
- **Security** — no source execution, no host class loading, no project imports, no external
  symlink traversal, no secret indexing, no source leaving the machine.
- **Failure isolation** — no java / no jbang / blocked maven / malformed source / unsupported
  language / corrupted graph / parser exception / read-only state all degrade, never break.
- **JavaDucker coexistence** — repo-intel is first-line structural; JavaDucker stays the
  semantic/history source; enabling both causes no duplicate broad searches.
- **Maintainer observability** — diagnostics answer: graph version, last intake, last
  refresh, parser coverage, file/node/edge counts, failed files, query latency, fallback reason.
- **Real-repository proof** — three materially different real repositories, not only fixtures.

## Agent Spawn Plan

Grok sub-agents (unmetered, filesystem control plane) do the parallel verbose work; Claude
authors the engine and reads verdicts only.

- Wave 1 (spawned): `fx-java`, `fx-python`, `fx-tsjs`, `fx-bash`, `fx-manifests` fixture
  corpora with independent ground truth + `research-treesitter` dependency evaluation
- Wave 2: skill-adoption judges (one agent per task batch)
- Wave 3: adversarial review of the final implementation (one agent per review theme)

## Risks

- **Fixture ground truth is authored by a model** — mitigated by deriving it from files on
  disk, by hand-checking every mismatch, and by treating disagreements as engine-or-truth
  investigations rather than automatic engine failures.
- **Tree-sitter bindings may not be viable offline** — the engine is written zero-dependency
  first, so a negative research result costs nothing.
- **Over-integration** — skills that call the graph for trivial edits waste tokens; gate 13
  exists to catch exactly that.
- **A/B discovery benchmark is a scripted surrogate** for live agent behaviour; the method
  must be stated plainly rather than presented as a live Claude experiment.

## Open Questions

- None blocking. Tree-sitter viability is being researched in parallel and does not gate v1.
