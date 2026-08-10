You are building a **test fixture repository** plus an **independent ground-truth file**
for a deterministic source-structure extractor. The extractor parses source syntax only —
it never executes code. Your fixture must therefore be syntactically valid but is never run.

## Language / stack
{{LANG}}

## What to produce, in your working directory

1. A directory named `{{FIXTURE}}/` containing the fixture source tree described below.
2. A file named `ground-truth.json` at the top of your working directory (NOT inside
   `{{FIXTURE}}/`) listing every relationship the extractor must find, plus deliberate
   traps it must NOT report.

## Fixture source tree

{{LAYOUT}}

## Required constructs — every one of these must appear at least once

{{CONSTRUCTS}}

## Deliberate traps (these make the fixture valuable)

{{TRAPS}}

## ground-truth.json format

Write this exact shape. Paths are relative to `{{FIXTURE}}/`. Use fully qualified names
where the language has them (Java: `com.acme.Foo.bar`; Python: `pkg.module.Class.method`;
JS/TS: `path/to/file.ts:Class.method`; Bash: `script.sh:function_name`).

```
{
  "fixture": "{{FIXTURE}}",
  "language": "{{LANGKEY}}",
  "declarations": [
    { "file": "a/B.java", "qualified_name": "com.acme.B", "type": "class" },
    { "file": "a/B.java", "qualified_name": "com.acme.B.run", "type": "method" }
  ],
  "relations": [
    { "relation": "IMPORTS",    "from_file": "a/B.java", "to": "com.acme.util.Helper" },
    { "relation": "EXTENDS",    "from": "com.acme.B", "to": "com.acme.Base" },
    { "relation": "IMPLEMENTS", "from": "com.acme.B", "to": "com.acme.Runner" },
    { "relation": "CALLS",      "from": "com.acme.B.run", "to": "com.acme.util.Helper.help",
      "from_file": "a/B.java", "cross_file": true }
  ],
  "negatives": [
    { "relation": "CALLS", "from": "com.acme.B.run", "to": "com.acme.other.Helper.help",
      "why": "same simple name, different package, never imported here" }
  ]
}
```

Rules for ground truth:
- `declarations` — one entry per top-level and nested declaration you created. Types are
  drawn from: repository, module, package, file, class, interface, enum, record, type,
  function, method, constructor, field, constant, test, config.
- `relations` — only relationships a **deterministic syntactic** analyser can find. Include
  every import, every extends/implements, and every call you deliberately made resolvable.
  Set `"cross_file": true` when caller and callee live in different files.
- Do NOT list relationships that need type inference, runtime reflection, dynamic dispatch
  on unknown types, or execution to resolve. If you wrote such a construct as a trap, list
  it under `negatives` instead.
- `negatives` — every trap. These are the cases where a naive extractor reports a confident
  edge that is actually wrong. Be thorough here; this is the highest-value part of the file.

## How to work

1. Write the fixture source files first. Keep them small and readable — 10-40 lines each.
2. Then read back **every file you wrote** and derive the ground truth from what is actually
   on disk, not from what you intended to write. Mismatched ground truth makes the fixture
   worthless.
3. Verify `ground-truth.json` parses as JSON (count your braces and commas).
4. Append at least two progress checkpoints to ../PROGRESS.md as you go.

## Output

End your final message with a single line beginning `RESULT:` giving the fixture name,
the number of source files written, and the counts of declarations / relations / negatives.
