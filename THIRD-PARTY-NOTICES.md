# Third-party notices

drom-flow is MIT licensed. Material below originates elsewhere and keeps its own licence.
Inclusion does not relicense it.

Similarity figures are measured, not estimated: frontmatter stripped, blank lines removed,
case-folded, compared with `difflib.SequenceMatcher` over lines. `1.0` means the substance is
identical.

---

## Web platform quality skills — MIT

**Upstream:** [addyosmani/web-quality-skills](https://github.com/addyosmani/web-quality-skills)
**Licence:** MIT
**Affected:** `.claude/skills/{accessibility,best-practices,core-web-vitals,performance,seo,web-quality-audit}/`
**Measured similarity to upstream:** **1.0 across all six** — the content is identical.

```
MIT License

Copyright (c) 2026 Addy Osmani

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

MIT permits this use. It requires the notice above to travel with the copies, which is why this
file exists — it was previously missing.

---

## Product management skills — UNRESOLVED LICENCE CONFLICT

**Upstream:** [deanpeters/product-manager-skills](https://github.com/deanpeters/product-manager-skills)
**Licence:** **CC BY-NC-SA 4.0** (Attribution–NonCommercial–ShareAlike)
**Affected:** `.claude/skills/{discovery-process,problem-statement,jobs-to-be-done,customer-journey-map,user-story-mapping,epic-breakdown-advisor,user-story,user-story-splitting,prd-development,roadmap-planning,prioritization-advisor}/` — 11 skills, 31 files
**Measured similarity to upstream:** **0.982 – 0.993**, with 100–235 identical substantive lines
per skill and shared runs of 150–420 consecutive lines.

GitHub reports this repository's licence as `NOASSERTION` because it cannot classify it; the
`LICENSE` file itself is Creative Commons Attribution-NonCommercial-ShareAlike 4.0.

**This conflicts with MIT in two independent ways:**

- **NonCommercial** — MIT grants the right to use and sell; CC BY-NC-SA forbids commercial use.
- **ShareAlike** — derivatives must carry the same licence; MIT cannot.

Attribution alone does not resolve either. Nor does the similarity measurement support a
"independently written, merely similar" reading: at 0.98+ with 400-line identical runs, this is
copied text.

**Status: unresolved.** Options, in the order a maintainer would consider them:

1. Remove the 11 skills from this repository and from `template/`.
2. Obtain written permission from the author to use the material under MIT.
3. Genuinely rewrite them from the underlying public frameworks (Mike Cohn's story format,
   Jobs-to-be-Done, RICE, story mapping — the *ideas* are not copyrightable, this *expression*
   is). A rewrite has to be an actual rewrite; the current files are not one.
4. Relicense drom-flow as CC BY-NC-SA — which would forbid commercial use of the whole project
   and is almost certainly not wanted.

Until this is resolved, these skills ship in `template/` and therefore propagate to every host
project that installs drom-flow.

*This is an engineering assessment of measured textual overlap and stated licence terms, not
legal advice.*
