You are splitting one oversized Java file into smaller files. This is a **pure mechanical
refactor**: the observable behaviour after the split must be byte-for-byte identical to before.
A refactor that "improves" something is a failed refactor.

## The file
{{FILE}}

It is {{LINES}} lines. The project rule is **under 500 lines per file**.

## Context you must read first
- `{{ROOT}}/template/.claude/df/repo-intel/Extractor.java` — the interface and result types
- `{{ROOT}}/template/.claude/df/repo-intel/Lex.java` — shared scanning helpers
- `{{ROOT}}/template/.claude/df/repo-intel/GraphModel.java` — Node, Edge, symbolId

## How to split
{{PLAN}}

## Hard constraints
1. **Default package.** No `package` line in any file. All new types are package-private
   (`final class Foo`), never `public`.
2. **Java 21, zero dependencies.** `java.*` only.
3. **No behaviour change.** Same methods, same logic, same order of operations, same output.
   Do not rename anything that another file references. Do not "fix" anything you notice — if
   you spot a real bug, write it in NOTES.md and leave the code alone.
4. Every file you produce must be **under 500 lines**.
5. Moved methods keep their signatures. If a moved method was `private static`, it becomes
   `static` on the new class and callers qualify it (`Helper.method(...)`).
6. State that was instance state stays with the class that owns the lifecycle. Prefer moving
   **stateless static helpers** and **self-contained parsing routines** over anything that
   touches mutable fields.
7. Keep the existing comments with the code they explain. Do not strip them, do not add
   decorative banners, do not restate what the code says.

## Verify before you finish
Compile it yourself — the whole engine, not just your files:

```
cd {{ROOT}}/template/.claude/df/repo-intel && javac --release 21 -nowarn -d /tmp/split-check *.java
```

(If `/tmp` is not writable from your side, use a directory inside your working directory.)
It must compile with **zero errors**. If it does not, fix it and compile again. Do not hand back
code that does not compile.

## Output
Write the complete set of resulting `.java` files into your working directory — the rewritten
original AND every new file. Also write `NOTES.md` listing: each new file with its line count
and what moved into it, plus anything you deliberately left alone.

End your final message with a single line beginning `RESULT:` giving the original line count, the
new file names with their line counts, and whether the full-engine compile succeeded.
