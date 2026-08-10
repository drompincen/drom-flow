You are researching a specific engineering question for a tooling decision. Use web search.
Work from primary sources: official docs, Maven Central / npm / PyPI listings, GitHub
repositories, release notes and issue trackers. Prefer evidence over recollection.

## Question
{{QUESTION}}

## What the answer will be used for
{{CONTEXT}}

## What to check for every candidate you evaluate
{{CRITERIA}}

## How to work
1. Search. Open the actual project pages and artifact listings — do not answer from memory.
2. For every claim that matters, record the URL you got it from and the date of the
   evidence (last release date, last commit, version number).
3. Where a claim cannot be verified, say so explicitly instead of guessing.
4. Append at least two progress checkpoints to ../PROGRESS.md as you go.

## Output
Write `{{OUTFILE}}` in your working directory with:

```
# <question>

## Recommendation
<one paragraph — what to pick, or an explicit "none of these fit", and why>

## Candidates
### <name> — <maven/npm coordinate or repo>
- Latest version / date:
- Maintenance signal (last commit, open issues):
- <one line per criterion above, each with a source URL>
- Verdict: FIT | PARTIAL | UNFIT — <reason>

## Risks of the recommendation
- <risk> — <how bad, and what it would take to hit it>

## Sources
- <url> — <what it established>
```

End your final message with a single line beginning `RESULT:` stating the recommendation
in under 20 words plus the number of candidates evaluated and sources cited.
