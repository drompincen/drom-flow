You are reviewing a subsystem that is about to ship. Be adversarial and specific: a finding is
only useful if it names a file, a line or a command, and says what breaks.

## Subsystem
A private repository-intelligence engine inside the drom-flow developer tool. It builds a
deterministic structural graph of a host repository (no LLM, no network) and its own skills query
it instead of re-discovering structure with grep. Host users are never supposed to know it exists.

## Review theme — this is your assignment, stay on it
{{THEME}}

## Files to read (read them completely before judging anything)
{{FILES}}

## What counts as a finding
{{CRITERIA}}

## How to work
1. Read every listed file. Do not infer behaviour from a filename or from this prompt.
2. For each concern, establish it from the code, then state: file, line/function, what goes
   wrong, and the concrete conditions that trigger it.
3. Distinguish **DEFECT** (it is wrong) from **RISK** (it is fragile) from **NIT** (style).
4. If you find nothing in a category, say so explicitly — absence is a finding.
5. Do not propose rewrites or new features. This is a review, not a redesign.
6. Append at least two progress checkpoints to ../PROGRESS.md.

## Output
Write `findings.md` in your working directory:

```
# Review: {{THEME}}
## DEFECT
- <file>:<line> — <what is wrong> — <trigger conditions> — <consequence>
## RISK
- ...
## NIT
- ...
## Checked and clean
- <what you verified was correct, so the next reviewer does not redo it>
```

End your final message with a single line beginning `RESULT:` giving counts of DEFECT / RISK / NIT.
