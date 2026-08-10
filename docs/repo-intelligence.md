---
title: Repository intelligence
nav_order: 8
---

# Repository intelligence

drom-flow keeps a **deterministic structural map of your repository** up to date automatically,
and its skills consult it before they go looking through your source.

There is nothing to install, initialise, configure or maintain. If you have drom-flow, you have
this.

## What it changes

Without it, every session re-discovers the same repository:

```text
question -> grep -> read -> grep again -> read more -> infer -> repeat next session
```

With it:

```text
question -> one bounded structural query -> a short list of the files that actually matter
         -> read those, verify, act
```

Measured on real repositories, with the working set known in advance:

| Repository | Files | Exploratory tool calls | Source bytes read | Working set found |
|---|---:|---|---|---:|
| spring-petclinic (Java/Spring) | 131 | 97 → 13 (**−86.6%**) | 952 KB → 106 KB (**−88.9%**) | 100% |
| axios (TypeScript/JS) | 464 | 138 → 11 (**−92.0%**) | 3.01 MB → 69 KB (**−97.7%**) | 83% |

The method is a scripted surrogate — a grep-then-read baseline against a single bounded graph
query — and it is written out in full at the top of `scripts/repo-intel-bench.sh` so you can
disagree with it precisely.

## What it understands

| Language | Extracted |
|---|---|
| Java | packages, imports (including wildcards and static), classes, interfaces, enums, records, fields, constants, constructors, methods, calls, `extends`/`implements`, Spring stereotypes, `@*Mapping` endpoints, `@KafkaListener` topics |
| Python | modules, all import forms including relative, classes, functions, methods, decorators, dataclasses, enums, inheritance, calls, FastAPI/Flask-style routes |
| TypeScript / JavaScript | ESM and CommonJS imports, named/default/namespace/type imports, re-exports, classes, interfaces, type aliases, enums, functions, methods, fields, calls, Express-style routes |
| Bash | both function forms, `source` and `.` includes, constants, exports, cross-file calls through the include graph |
| Manifests | `pom.xml`, `build.gradle(.kts)`, `package.json`, `pyproject.toml`, `requirements.txt`, `go.mod` → external dependencies |

Every relationship carries **provenance** (file, line, which resolver produced it) and a
**confidence**: `EXTRACTED` when the syntax says so outright, `INFERRED` when a repository-wide
resolution pass established it, `AMBIGUOUS` when more than one target is genuinely plausible.
Nothing uncertain is ever promoted to certain — on the test corpus, **0 of 489 confident edges**
were wrong.

## What it does not do

It does not run your code. It never executes, imports, evaluates or loads anything in your
repository — it reads text and parses syntax. It does not send your source anywhere: graph
construction is entirely local and involves no model, no API and no network. It does not index
`.env` files, private keys, binaries, oversized files, or anything reached by a symlink that
leaves the repository.

And it does not replace reading the source. It narrows scope; the file is still the truth.

## Where the graph lives

By default, inside the project it describes:

```text
<project>/.claude/.state/repo-intel/
```

which is gitignored, disposable, and removed cleanly by `init.sh --uninstall`.

If you need it elsewhere — a synced or slow filesystem, a read-only checkout, or a policy about
generated files in the working tree — point it somewhere else:

```bash
# environment, one session
export DROMFLOW_REPO_INTEL_STATE=/var/tmp/repo-intel/myproject

# or persistently, in .claude/.state/drom-flow.conf
REPO_INTEL_STATE=/var/tmp/repo-intel/myproject
```

Absolute, `~`-relative and project-relative paths all work. Uninstall follows the relocation.

## When something is missing

If there is no JVM, no JBang, no network to fetch one, or the engine simply cannot run, drom-flow
**silently falls back** to ordinary search and read. You lose the speed-up; you do not lose
Claude Code. The failure is recorded once and not retried on every query.

Same for the graph itself: absent, stale, corrupt or built by an older version — it is rebuilt or
repaired automatically at the next structural request. You are never asked to do anything about it.

## For drom-flow maintainers

The engine is Java on JBang, dependency-free by design (see
[`.claude/docs/repo-intel.md`](https://github.com/drompincen/drom-flow/blob/main/template/.claude/docs/repo-intel.md)
for internals, the private command surface, and the schema). Release gates live in
`scripts/repo-intel-verify.sh` and are reported to `reports/repo-intel.json`.
