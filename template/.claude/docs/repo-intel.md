# repo-intel — internals (maintainers only)

Private subsystem. Host users never invoke it and it is not documented to them as a feature.
This file exists so drom-flow maintainers can reason about it.

## Shape

```text
install/update -> managed runtime under .claude/df/repo-intel/
      |
SessionStart hook -> metadata check only -> detached intake if needed
      |
PostToolUse hook -> append one line to the dirty journal -> return (no JVM, ~20 ms)
      |
skill query -> run wrapper -> ensureCurrent() -> incremental refresh -> bounded answer
```

## Runtime selection (`.claude/df/repo-intel/run`)

1. **cached classes** — warm path, a bare JVM start, no compiler, no network
2. **javac** — compiles once into `<state>/classes`, then path 1 forever
3. **jbang** — used when there is a JVM but no compiler, or no JVM at all (JBang provisions one)
4. **jbang bootstrap** — one attempt, recorded in `jbang-bootstrap-failed`, never retried in a loop
5. **unavailable** — structured JSON, exit 3, cached for `DROMFLOW_REPO_INTEL_RETRY_SECS` (6 h)

`RepoIntel.java` carries a JBang header and `//SOURCES` lines, so `jbang run RepoIntel.java`
works directly. Java 21 is the baseline.

**Windows JVM under WSL** is a first-class case: a JDK installed on Windows cannot see WSL paths.
The wrapper detects it, translates every path with `wslpath -w`, and — because that JVM also
cannot reach WSL's git — computes the file list itself and passes it via
`DROMFLOW_REPO_INTEL_FILELIST`. The engine still validates every supplied path.

## Why no Tree-sitter

Evaluated (2026-08, evidence retained in the research report). `io.github.bonede:tree-sitter`
is the only binding that publishes Linux **and** Windows natives inside its jars with ready
grammars for all five languages, but every binding still requires first-time Maven resolution —
which is exactly what fails in the locked-down environments where graceful degradation matters
most. The official `io.github.tree-sitter:jtreesitter` additionally needs JDK 23 FFM plus host
native libraries. Hand-written scanners keep the engine at **zero dependencies**, so it runs
where a resolver cannot. The `Extractor` interface exists so a Tree-sitter backend can be added
per language later without touching anything else.

## Passes

**Pass 1, per file, no repository knowledge.** Mask comments and string literals (`Lex`), walk
the structure, emit nodes, containment, imports, supertype names by name, and call candidates.
Nothing is resolved. Literal values that masking destroyed (route paths, topic names) are read
back from the raw source at the same offsets.

**Pass 2, repository-wide (`Resolver`).** Build qualified-name, simple-name, file and namespace
indexes, then resolve. Resolution is syntax-directed: an import binding, a declared type, a
package, an include. A bare simple-name coincidence is never enough to emit a `CALLS` edge —
that rule is what keeps the false-positive rate at zero on the trap corpus.

## Identity

```text
repository       repo:<name>
file             file:<repo-relative-path>
symbol           <lang>:<type>:<path>:<qualified-name>(<arity>)
external symbol  pkg:<ecosystem>:<name>
```

Line numbers are metadata, never identity: moving a method within a file must not churn the graph.

## State

```text
<state>/graph.json      nodes + edges, canonically sorted
<state>/manifest.json   path -> hash, size, mtime, language, node ids
<state>/facts.json      cached pass-1 facts per file
<state>/metadata.json   engine + schema version, timings, parser coverage, failures
<state>/dirty           append-only journal written by the PostToolUse hook
<state>/classes/        compiled engine
<state>/query-log.jsonl rotated diagnostics
```

`facts.json` is what makes incremental refresh **provably** equal to a clean rebuild: only
changed files are re-parsed, but pass 2 always re-runs over every file's facts. It has to —
adding a second class with the same simple name must be able to turn a previously confident edge
ambiguous, and that is only true if resolution sees the whole repository.

Rebuild triggers: schema change, engine version change, **engine source fingerprint change**
(`DROMFLOW_REPO_INTEL_ENGINE_STAMP`, so an edited extractor cannot keep serving an old graph),
missing state, corrupt state, or status not `ready`.

Writes are temp-file plus atomic rename, with the previous graph kept as `.bak`. A refresh that
fails validation is discarded and the healthy graph stays in service.

## Private command surface

`ensure`, `rebuild`, `stats`, `verify`, `symbol`, `search`, `explain`, `callers`, `callees`,
`dependencies`, `dependents`, `neighbors`, `impact`, `path`. Flags: `--root`, `--state`,
`--limit`, `--depth`, `--max-bytes`, `--force`, `--no-ensure`.

Not user commands. There is deliberately no `/repo-map` skill.

## Budgets

25 nodes, 40 edges, 15 KB of pretty-printed JSON. Enforced against the printed form, and
`truncated` is set whenever the cap bites.

## Gates

`bash scripts/repo-intel-verify.sh` → `reports/repo-intel.json`. Twelve gates: fixture
correctness, incremental≡rebuild, stable identity, bounded output, security, edit-hook latency
and no-JVM-on-edit, freshness including external edits, failure isolation, install/upgrade/
uninstall, graph validation, relocatable state, impact usefulness.

`bash scripts/repo-intel-bench.sh <repo> <task-key>` → `reports/repo-intel-bench.json` for
discovery cost.
