# Critic: c2 (weak sourcing: claims resting on secondary, derivative, social or undated sources)

## O1 [blocking]
sentence: "Martian online behavioral precision for a top tool is framed at ~49% (roughly one in two comments leads to a code change)—still half non-acted [S13][S20]."
problem: The specific ~49% figure and the “one in two comments leads to a code change” framing appear in the corpus only in derivative vendor restatement [S20] (CodeRabbit’s Martian write-up; sources-2 V-S10 / sources-4 P-S5). Martian primary [S13] documents CodeRabbit online recall ~0.54 and Graphite as highest-precision/lowest-recall, but does not supply the 49.2% precision or “one in two” language. Dual-citing [S13] next to that number launders a secondary/derivative source as independent multi-tool evidence—exactly the kind of support a decision-maker would over-weight.
fix: Drop [S13] from this sentence (or keep [S13] only for recall/qualitative ranking). Attribute the ~49% / “one in two” line exclusively to [S20] as a secondary restatement, or replace with a number/quote that actually appears in the Martian primary post and cite only [S13].

## O2 [blocking]
sentence: "**Side B (high noise):** SWR-Bench precision often &lt;10%; RevMate ~7–8% acceptance; Greptile 79% nits; Martian ~50% act-on even for strong tools; maintainer ~1/5 good catches [S8][S10][S23][S13][S21]."
problem: Within this bullet, the Martian-linked “~50% act-on even for strong tools” clause is backed only by [S13] in the shared cite list, but the corpus’s primary Martian extract does not state ~50% act-on/precision for a top tool. That ~50% / ~49% act-on framing is from derivative [S20] (and social [S28], which restates the same chain). As written, Side B of C1 presents a derivative number as if it were primary Martian evidence.
fix: Either (a) cite [S20] explicitly and label it secondary/derivative for the ~50% act-on claim, or (b) rephrase to what [S13] actually supports (e.g., high-volume tools still leave a large un-acted share; CodeRabbit online recall 0.54; Graphite highest precision/lowest recall) without inventing a ~50% precision figure from Martian primary.

## O3 [major]
sentence: "Production product KPIs (not defect oracles): Cursor resolution rate 52%→70%+ (~0.5 resolved “bugs” per PR by their AI-judged metric) [S14]; Graphite 96% positive feedback / 67% implementation [S17]; GitHub actionable feedback in 71% of reviews (silence in 29%) and thumbs/resolution tracking [S22]; Anthropic &lt;1% marked incorrect, substantive comments on 54% of PRs after rollout [S24]."
problem: The Graphite 96% positive feedback / 67% implementation pair rests solely on [S17], which the Sources list dates as “~2025” (undated / only approximate). Putting undated quantitative KPIs in the same findings bullet as dated industrial posts ([S14], [S22], [S24]) overstates the temporal reliability of those Graphite figures for production comparison.
fix: Move the 96%/67% pair out of the main findings bullet into an explicitly undated-vendor-guide caveat, or replace with a dated Graphite primary that carries those figures (if none exists in corpus, keep the claim only under Disagreements Side A with “undated guide [S17]” in the sentence). Do not leave undated [S17] as the sole warrant for a Findings §5 quantitative KPI.

## O4 [major]
sentence: "**Side B:** ~10% human-issue overlap; 13% human-like comment success; moderate correctness accuracy; AutoCommenter useful 60% vs 80% target; human–model α 0.15 on related judgment; social/practitioner insistence on human review [S9][S2][S1][S4][S7][S27]."
problem: [S27] is typed social (X). It is then bundled into C5 Side B, which the report immediately judges “**Better evidenced: Side B for systematic comparison.**” Social insistence is not systematic comparison evidence; mixing it into the warrant list for the “better evidenced” side blurs the report’s own primary-vs-social hierarchy and lets a social source help tip a decision-relevant disagreement.
fix: Remove [S27] from this Side B citation cluster. Keep the academic/industrial cites ([S9][S2][S1][S4][S7]) as the Side B warrant. If Marsh’s view is needed, point only to the already-hedged “Social signal (not primary evidence)” paragraph and do not use [S27] under “Better evidenced.”

## O5 [minor]
sentence: "Independent multi-tool: offline recall ceiling ≤~63% of known issues; online F1 for leading tools ~half (e.g., CodeRabbit summary of Martian: ~51% F1, ~49% precision, ~53–54% recall framing) [S13][S20]."
problem: Prose correctly flags “CodeRabbit summary of Martian,” but the citation still pairs [S13] with numbers (~51% F1, ~49% precision, ~53.5% recall) that the corpus records only under secondary [S20]. Offline ≤~63% is primary [S13]; the online F1/precision package is not. Dual citation still invites treating the derivative package as primary Martian.
fix: Split the citations: offline ceiling [S13] only; online F1/precision/recall package [S20] only, retaining the “CodeRabbit summary / secondary” label in prose (or quote Martian primary tables/figures if they contain those exact values).

## O6 [minor]
sentence: "Negative/mixed independent practice: default bots too noisy (maintainers want them off) [S21]; senior engineers report systematically wrong comments that waste time [S26]; analyst synthesis: effectiveness remains hotly debated [S29]."
problem: [S29] is secondary (RedMonk analyst synthesis). Placing “effectiveness remains hotly debated” in the same industrial-deployment bullet as primary practitioner posts treats derivative commentary as parallel deployment signal. The claim is soft, so impact is limited, but sourcing grade is still weak for §8.
fix: Drop [S29] from this bullet, or rephrase to “secondary industry analysis notes the debate is unresolved [S29]” and keep it out of the “independent practice” primary cluster; rely on [S21][S26] (and controlled studies elsewhere) for the mixed/negative practice signal.

## O7 [minor]
sentence: "Testimonials—bots catch bugs after human pass [S15][S24]; tool leaderboards “find more real bugs” **among tools**, not vs humans [S20]."
problem: [S15] is an undated product marketing page (“accessed 2026-08”); [S20] is secondary/derivative. Both are used only as Side A (weaker) of C5, which is appropriate in role, but the sentence presents testimonials and leaderboard slogans without flagging undated/derivative status in-line—readers scanning Disagreements may still treat them as dated primary evidence of defect catch.
fix: Tag in prose: “undated product-page testimonials [S15]; vendor secondary leaderboard restatement [S20]; dated internal anecdote [S24].” Prefer [S24] alone if a single primary anecdote is enough for Side A.

## What was checked (clean where noted)
- Answer thesis and controlled-comparison effect sizes ([S9], [S1], [S4], [S10], [S13]) rest on dated primary academic/industrial sources—not on social or secondary alone.
- §8 “Social signal (not primary evidence)” correctly fences [S27][S28][S30] and does not use them as the Answer’s load-bearing warrant.
- Sources list correctly labels [S20]/S29] secondary and [S27][S28][S30] social; independence note on Martian is present.
- Vendor self-benches ([S14][S16][S18][S19][S23][S24]) are first-party dated posts used as self-metrics (COI is a different mandate; not scored here except where undated or derivative).
- No objection manufactured for merely *citing* social/secondary when the report already down-ranks them and does not rest the bottom-line answer on them.

RESULT: blocking=2 major=2 minor=3
