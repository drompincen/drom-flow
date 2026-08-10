You are writing **one Java class** for a deterministic, dependency-free source-structure
extractor. Read the existing code on disk before you write anything — the contract is defined
by real files, not by this prompt.

## Read these first (absolute paths, read them completely)

- `{{ROOT}}/template/.claude/df/repo-intel/Extractor.java` — the interface you implement and
  every result type you may populate (`FileFacts`, `ImportRef`, `Ref`, `TypeRef`, `DepRef`).
- `{{ROOT}}/template/.claude/df/repo-intel/Lex.java` — masking and scanning helpers. You MUST
  use these; do not write your own comment/string handling.
- `{{ROOT}}/template/.claude/df/repo-intel/GraphModel.java` — `Node`, `Edge`, `symbolId`,
  `fileId`, confidence constants.
- `{{ROOT}}/template/.claude/df/repo-intel/JavaExtractor.java` — the reference implementation.
  Match its structure, its level of commenting, and its defensive style.

## Your file

Write `{{CLASS}}.java` into your working directory. It must:

- be in the **default package** (no `package` line), Java 17 compatible, **zero dependencies**
  (java.* only — no Jackson, no regex-heavy designs where a scan is clearer, no external libs)
- declare `final class {{CLASS}} implements Extractor`
- implement `language()` returning `"{{LANGKEY}}"`, `supports(String relPath)` for {{SUFFIXES}},
  and `extract(String relPath, String source)`
- **never throw** from `extract`. Malformed input must set `facts.error` and return whatever was
  parsed so far. A parser exception on one file must never be able to abort a repository scan.
- never execute, import, evaluate or load the source it reads. It is text.

## What to extract

{{EXTRACT}}

## Rules that the test suite enforces

1. **Mask first.** Call the right `Lex.mask*` helper and scan the MASKED array. A declaration,
   import or call that appears inside a comment or a string literal must never be emitted.
   When you need a literal value (a route path, a module specifier), read it back from the RAW
   source at the same offsets — see how `JavaExtractor` handles annotation arguments.
2. **Pass 1 only.** Anything requiring another file is NOT your job: emit an `ImportRef`, a
   `Ref` or a `TypeRef` and let the resolver decide. Never guess a target.
3. **Node IDs must be stable** — build them with `GraphModel.symbolId(language, type, path,
   qualifiedName, arity)`; arity is `-1` for anything that is not callable. IDs must not contain
   line numbers: moving a function within a file must not change its ID.
4. **Containment** — call `facts.defines(GraphModel.fileId(path), node.id, line)` for top-level
   declarations and `facts.contains(parentId, childId, line)` for members. Do not emit both for
   the same pair.
5. **Receiver types** — when the syntax tells you the declared type of a receiver (a typed
   field, a typed parameter, an annotated variable, a module alias), put it in `Ref.receiverType`.
   When it does not, leave it null. Never fill it with a guess: a wrong `receiverType` becomes a
   confident wrong edge, which the benchmark treats as worse than a missing edge.
6. `Ref.arity` is the argument count at the call site, or `-1` if you cannot tell.
7. Set `facts.namespace` to the module/package identity of the file when the language has one.

## Verify before finishing

A fixture and an independently written ground-truth file already exist:

- fixture: `{{FIXTURE}}`
- ground truth: `{{GROUNDTRUTH}}`

Read both. Walk through the ground truth entry by entry and confirm your code would produce
that declaration or that `ImportRef`/`Ref`/`TypeRef`. Pay special attention to the `negatives`
array — those are the traps; your extractor must not emit them. Write a short file
`SELFCHECK.md` in your working directory listing each ground-truth category and whether your
implementation handles it, plus anything you deliberately left to the resolver.

Do not modify any file outside your working directory.

## Output

`{{CLASS}}.java` and `SELFCHECK.md` in your working directory. End your final message with a
single line beginning `RESULT:` giving the class name, its line count, and the number of
ground-truth entries you believe are covered vs. left to the resolver.
