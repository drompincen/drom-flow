# LLM Code Review Comments vs Human Reviewers: Defect-Finding Effectiveness

## Answer

Relative to human reviewers, LLM-generated code review comments are **not demonstrated to match human effectiveness at finding real defects**. The best controlled comparisons show low overlap with human-reported issues (~10% match while recommending ~2.4× more changes) [S9], moderate accuracy against executable correctness oracles (~64–69%) [S1], and industrial useful/acceptance rates that often sit well below deployment targets (e.g., ~60% useful vs 80% target; ~7–8% live acceptance of generated comments) [S4][S10]. Independent multi-tool evaluation finds no tool recovering more than ~63% of known offline issues, with large residual miss rates [S13].

Vendor self-metrics often report high “resolution,” “acceptance,” or low false-positive rates (e.g., ≥70% resolved flags; &lt;3% FP claims) [S14][S16], but these metrics are product KPIs under non-shared FP definitions and are not validated as true-positive defect rates against independent oracles [S13][S10]. Human-in-the-loop and heavily tuned deployments can raise address rates and catch some bugs humans miss in anecdotes [S24][S14][S22], yet field measurements do not show reliable replacement of human defect-finding or consistent review-time savings [S4][S11][S9]. **Bottom line:** LLMs are best evidenced as noisy, complementary assistants—not as peers or substitutes for careful human review on real defects.

## Findings

### 1. Measured defect-finding rate (true positives) vs humans

No peer-reviewed head-to-head scores both human and LLM reviewers on the **same PRs** against an **independent defect oracle** (see Gaps). Proxies:

- On the same PRs, ChatGPT-4 matches only ~10% of quality issues humans report while proposing ~2.4× more changes [S9].
- Against unit-test correctness ground truth, GPT-4o / Gemini classify correctness correctly only ~68.5% / ~63.9% of the time (with problem descriptions); authors conclude fully automated review is unreliable [S1].
- Generating human-like review comments is hard: ChatGPT succeeds on only 13% (19/150) of code-to-comment cases vs specialized SOTA in a large manual inspection study [S2].
- Multi-tool offline gold: no tool found more than 63% of known issues; the best still missed about a third [S13].
- Vendor self-benches report much higher catch on their own sets (e.g., Greptile 82% on 50 known bugs; Augment 55% recall on golden comments) [S18][S19]. These conflict with each other and with Martian, and Martian notes offline gold was seeded from Augment/Greptile datasets and may be biased toward those tools’ issue types [S13].

**Reading:** Where humans are the reference, LLMs recover a small share of human issues. Where known bugs are the reference, independent multi-tool ceilings are mid-range with large misses; vendor-only ceilings are higher but not field-general.

### 2. False-positive / noise rate vs humans

Independent and multi-org evidence clusters toward **high noise**:

- SWR-Bench: top ACR+LLM F1 only ~19%; four of five techniques had precision &lt;10% [S8].
- RevMate (Mozilla & Ubisoft, live): only ~8.1% / ~7.2% of LLM-generated comments accepted by professional reviewers [S10].
- Greptile’s own pre-filter audit: ~19% good, ~2% flat-out incorrect, **79% nits** (technically true but not cared about) [S23].
- OSS maintainer (untuned Copilot Code Review): about 1 in 5 comments were good catches contributors would have missed [S21].
- Martian online behavioral precision for a top tool is framed at ~49% (roughly one in two comments leads to a code change)—still half non-acted [S13][S20].

Vendor claims of very low FP (&lt;3% Graphite; &lt;1% incorrect at Anthropic internal process; “low FP” Bugbot marketing) [S16][S24][S15] conflict with the above (see Disagreements C1). Those figures are self-measured under product-specific labels, not shared gold oracles.

### 3. Differences by defect class

Corpus evidence is **thin and uneven**:

- Functional/defect-oriented comments dominate volume in live generation but have **lower** acceptance than refactoring/style (functional ~4.8–5.2% accepted vs refactoring ~18% in RevMate) [S10]—so “findings” volume overstates defect catch relative to nits.
- Google AutoCommenter targets best-practice URL tips (not general bugs); ~40% estimated resolution; covers 68% of historical human best-practice-URL comments [S4].
- Greptile’s vendor severity table claims mid-to-high catch by severity band on its own bench [S18]; not independently replicated.
- Security/static hybrid work shows LLMs still fail on long context, cascaded constraints, and weak semantic understanding; enterprise static warnings themselves can exceed 90% FP when incomplete cases are counted [S12].
- CodeReviewer-era models struggle on multi-component changes limited to single hunks [S2].

**Not established:** stratified LLM-vs-human catch rates for security, concurrency, distributed failures, and performance regressions on matched PRs.

### 4. Human-only vs LLM-only vs human-in-the-loop

- Controlled stance: DL/LLM review is a further quality check **on top of** humans, not a replacement or time-saver, because humans must still inspect and validate bot claims [S9].
- Google AutoCommenter A/B (~half of developers): **no** statistically significant change in total review duration, active review time, or comment-response iterations [S4].
- Beko industrial bot: 73.8% of bot comments labeled resolved, yet mean PR closure time rose (~5h52m → ~8h20m); human comment volume drop was **not** significant [S11].
- GitHub interviews: self-review with Copilot before opening a PR cut trivial nit back-and-forth by roughly a third—floor-raising on style/process, not defect parity [S25].
- Address rate is highly tunable: Greptile reports address rate rising 19% → 55%+ after noise filtering [S23]; OSS maintainer reports large quality jump after custom instructions [S21].
- Vendor positioning: bots catch issues before teammates review so humans focus on “big picture” [S16][S17]—framing, not controlled residual-defect RCTs.

**Reading:** Human-in-the-loop with tuning can improve signal and author self-fix; LLM-only is not supported as equivalent to human review. Net time savings are unproven and sometimes reverse under load.

### 5. Precision–recall / usefulness at production settings

- Independent multi-tool: offline recall ceiling ≤~63% of known issues; online F1 for leading tools ~half (e.g., CodeRabbit summary of Martian: ~51% F1, ~49% precision, ~53–54% recall framing) [S13][S20].
- SWR-Bench top F1 ~19% with precision often &lt;10% [S8].
- Production product KPIs (not defect oracles): Cursor resolution rate 52%→70%+ (~0.5 resolved “bugs” per PR by their AI-judged metric) [S14]; Graphite 96% positive feedback / 67% implementation [S17]; GitHub actionable feedback in 71% of reviews (silence in 29%) and thumbs/resolution tracking [S22]; Anthropic &lt;1% marked incorrect, substantive comments on 54% of PRs after rollout [S24].
- Google comment-resolution ML (implementing *human* comments, not finding defects): authors apply ML edit for 7.5% of all reviewer comments [S6]—productivity on human findings, not LLM defect discovery.

**Tradeoff pattern:** High-recall / high-volume tools raise act-on counts and noise; high-precision / low-recall tools comment rarely [S13]. No independent production setting shows LLM tools dominating human precision–recall on real defects without collapsing into nits or heavy filtering.

### 6. Durability across models, languages, and codebases

- Online vs offline rankings **disagree**; incomplete gold understates precision and overstates recall enough to change rankings [S13].
- Correctness accuracy **flips** across datasets for GPT-4o vs Gemini [S1].
- Vendor tool order flips across Greptile, Augment, and Martian protocols [S18][S19][S13].
- Static offline sets risk training leakage; continuous online sampling is proposed as anti-gaming [S13].
- Dataset mining noise (~25% of inspected instances; only ~64% of sampled CodeReviewer training comments “valid”) undermines durability of older benchmark-trained results [S2][S5].
- Longitudinal industrial snapshots exist (Beko, AutoCommenter) but are point-in-time relative to rapidly changing models [S11][S4].

**Reading:** Reported effectiveness is protocol-, tool-, and time-sensitive. Corpus does **not** support stable effect sizes across model generations and orgs.

### 7. Confounding factors

- **Metric construct:** Acceptance/resolution ≠ defect true positive; refactoring comments are accepted more than functional ones [S10]; “resolved” can coexist with longer cycle time and irrelevant comments [S11]; Cursor uses AI to judge “resolved” [S14].
- **Gold incompleteness:** Offline sets miss real issues, distorting P/R [S13].
- **Dataset noise / BLEU-EM:** Textual similarity and exact match poorly measure issue coverage [S8][S9][S2].
- **Vendor self-bench COI:** Publishers rank their own tools first; Martian flags bias and gold seeding from vendor datasets [S13][S18][S19].
- **Scope mismatch:** Best-practice tips (AutoCommenter) ≠ general defects [S4]; quality-estimation F1 ~0.72 is a binary “needs comment?” gate, not bug recall [S3].
- **Human–model agreement:** On static-analysis warning actionability, human–model α ~0.15 vs human–human 0.80 [S7].
- **Tuning / filtering:** Defaults vs months of custom instructions and noise filters materially change “effectiveness” [S21][S23].
- **Diff/context limits:** Single-hunk views fail on multi-component defects [S2]; broader PR context did not improve human-issue match for ChatGPT in one study [S9].

### 8. Practitioner and industrial deployment signal

- Scale adoption: Copilot code review &gt;1 in 5 reviews on GitHub after large growth [S22]; Bugbot marketed at millions of PRs/month [S14]—adoption ≠ defect superiority.
- Positive internal narratives: Anthropic reports large rise in PRs with substantive comments and rare “incorrect” marks; one auth-breaking one-liner anecdote [S24].
- Negative/mixed independent practice: default bots too noisy (maintainers want them off) [S21]; senior engineers report systematically wrong comments that waste time [S26]; analyst synthesis: effectiveness remains hotly debated [S29].
- **Social signal (not primary evidence):** Practitioners argue careful human review remains the best anti-slop guardrail [S27]; that ~50% FP / ~50% miss on public benches only helps if humans attend [S28]; that FP noise slows parsing and iteration [S30].

## Disagreements

### C1. Precision / false-positive burden

- **Side A (very low FP):** Graphite &lt;3% FP; Diamond 96% positive feedback; Anthropic &lt;1% marked incorrect; Bugbot “low FP” marketing [S16][S17][S24][S15].
- **Side B (high noise):** SWR-Bench precision often &lt;10%; RevMate ~7–8% acceptance; Greptile 79% nits; Martian ~50% act-on even for strong tools; maintainer ~1/5 good catches [S8][S10][S23][S13][S21].
- **Better evidenced: Side B for industry-at-large.** Side A is self-measured product metrics with COI and non-shared FP definitions. Side B includes multi-org live acceptance, multi-tool offline F1, vendor-honest pre-filter audits, and independent behavioral precision.

### C2. Absolute defect catch rate

- **Side A:** Greptile 82% catch; Augment 55% recall / 59% F; Cursor rising resolved bugs/PR [S18][S19][S14].
- **Side B:** Martian ≤63% of known issues; SWR-Bench F1 ~19%; human-issue match ~10%; correctness classification ~64–69% [S13][S8][S9][S1].
- **Better evidenced: Side B as a cross-tool upper-bound / human-overlap picture.** Martian is the main multi-tool bench not self-ranking its own product first and flags vendor-bench bias. Absolute “true” field rate vs humans remains unresolvable without shared gold scored for both (C5).

### C3. Time saved vs added load

- **Side A:** Catch earlier / less manual effort; self-review cuts nit back-and-forth ~1/3 [S17][S25].
- **Side B:** Not a valid alternative or time saver; review duration unchanged (Google A/B) or worse (Beko); practitioners report wasted time [S9][S4][S11][S26].
- **Better evidenced: Side B for “does not replace humans / does not reliably shrink review cost.”** Primary industrial A/B and pre/post designs outweigh vendor framing. Net ROI (FP triage cost vs escaped-defect value) is **not** resolved.

### C4. Does high acceptance/resolution mean real defects?

- **Side A:** Resolution/implementation rates as value proxies [S14][S17][S22][S23].
- **Side B:** Acceptance higher for style/refactor than functional defects; resolved labels coexist with longer cycle times; online/offline disagree; BLEU/EM/acceptance ≠ issue coverage [S10][S11][S13][S8][S9].
- **Better evidenced: Side B on construct validity.** Acceptance is a biased proxy; no calibrated mapping to true-positive defect rate in-corpus.

### C5. Can LLMs match/exceed humans on defects humans care about?

- **Side A:** Testimonials—bots catch bugs after human pass [S15][S24]; tool leaderboards “find more real bugs” **among tools**, not vs humans [S20].
- **Side B:** ~10% human-issue overlap; 13% human-like comment success; moderate correctness accuracy; AutoCommenter useful 60% vs 80% target; human–model α 0.15 on related judgment; social/practitioner insistence on human review [S9][S2][S1][S4][S7][S27].
- **Better evidenced: Side B for systematic comparison.** Only a handful of studies measure overlap with human judgments or executable gold. Corpus does **not** support LLM superiority or parity; no multi-site RCT gives “LLM finds X% of what humans find.”

### C6. Trustworthiness of vendor self-benchmarks

- **Side A:** Treat Greptile/Augment/Graphite self-wins as rankings [S18][S19][S17].
- **Side B:** Martian warns vendor bias; gold seeding; rank instability across protocols [S13].
- **Better evidenced: Side B.** For synthesis, vendor self-wins get lower weight than independent multi-tool and live multi-org studies.

## What we could not establish

- Head-to-head **human vs LLM** on the **same PRs** with an **independent defect oracle**, reporting precision, recall, and complementary find sets.
- Multi-site **production escape rates** (bugs that ship) under human-only vs human+LLM vs LLM-first policies.
- A **shared definition of false positive** (wrong vs nit vs out-of-scope vs acceptable tradeoff) applied across tools.
- Calibrated conversion from **acceptance / resolution / thumbs-up** to true-positive **defect** detection.
- Magnitude of gold-set incompleteness correction factors usable in synthesis ([S13] flags bias; no correction factors in corpus).
- **Defect-class mix** (security, concurrency, distributed, performance vs style) broken out LLM vs human.
- Senior vs junior human baselines; cost model of engineer-minutes triaging FP vs value of rare sev-class catches.
- How much “effectiveness” is **defaults vs months of tuning** [S21][S23]—no meta-analysis.
- Generalizability across languages, monorepo/polyglot, proprietary/OSS, regulated domains; temporal stability as models ship monthly.
- Whether confident-wrong advice **harms juniors** beyond anecdote [S26].
- Selection bias in “teams that still read the bot” for online act-on metrics.

## Sources

[S1] Evaluating Large Language Models for Code Review — https://arxiv.org/abs/2505.20206 — 2025-05-26 — primary

[S2] Code Review Automation: Strengths and Weaknesses of the State of the Art — https://arxiv.org/abs/2401.05136 — 2024-01-10 — primary

[S3] Automating Code Review Activities by Large-Scale Pre-training (CodeReviewer) — https://arxiv.org/abs/2203.09095 — 2022 — primary

[S4] AI-Assisted Assessment of Coding Practices in Modern Code Review (AutoCommenter) — https://arxiv.org/abs/2405.13565 — 2024-05-22 — primary

[S5] Too Noisy To Learn: Enhancing Data Quality for Code Review Comment Generation — https://arxiv.org/html/2502.02757 — 2025-02 — primary

[S6] Resolving Code Review Comments with Machine Learning — https://storage.googleapis.com/gweb-research2023-media/pubtools/7525.pdf — 2024 (ICSE-SEIP) — primary

[S7] Can LLMs Replace Manual Annotation of Software Engineering Artifacts? — https://arxiv.org/abs/2408.05534 — 2024-08-10 — primary

[S8] Benchmarking and Studying the LLM-based Code Review (SWR-Bench) — https://arxiv.org/html/2509.01494v1 — 2025-09-01 — primary

[S9] Studying Quality Improvements Recommended via Manual and Automated Code Review — https://arxiv.org/html/2602.11925v1 — 2026 (ICPC ’26) — primary

[S10] Impact of LLM-based Review Comment Generation in Practice (RevMate) — https://arxiv.org/html/2411.07091v1 — 2024-11-11 — primary

[S11] Automated Code Review In Practice (Beko) — https://arxiv.org/html/2412.18531v2 — 2024-12-28 — primary

[S12] Reducing False Positives in Static Bug Detection with LLMs: An Empirical Study in Industry — https://arxiv.org/html/2601.18844v1 — 2024–2025 — primary

[S13] Code Review Bench: Towards Billion Dollar Benchmarks (Martian) — https://withmartian.com/post/code-review-bench-v0 — 2026-02-26 — primary  
*(Independence note: single origin; also mirrored in vendor/practitioner corpus copies—counted once.)*

[S14] Building a better Bugbot (Cursor) — https://cursor.com/blog/building-bugbot — 2026-01 — primary

[S15] AI Code Review Built for Production | Bugbot by Cursor — https://cursor.com/bugbot — accessed 2026-08 — primary

[S16] Introducing Graphite Reviewer — https://graphite.com/blog/graphite-reviewer-launch — 2024-09-30 — primary

[S17] Best AI pull request reviewers in 2025 (Graphite) — https://graphite.com/guides/best-ai-pull-request-reviewers-2025 — ~2025 — primary

[S18] AI Code Review Benchmarks 2025 (Greptile) — https://www.greptile.com/benchmarks — 2025 — primary

[S19] We benchmarked 7 AI code review tools on large open-source projects (Augment) — https://www.augmentcode.com/blog/we-benchmarked-7-ai-code-review-tools-on-real-world-prs-here-are-the-results — 2025-12-11 — primary

[S20] CodeRabbit tops independent AI code review benchmark — https://www.coderabbit.ai/blog/coderabbit-tops-martian-code-review-benchmark — 2026-03-03 — secondary *(derivative restatement of [S13] online numbers)*

[S21] How I Taught GitHub Copilot Code Review to Think Like a Maintainer — https://angiejones.tech/how-i-taught-github-copilot-code-review-to-think-like-a-maintainer/ — 2025-11-25 — primary

[S22] 60 million Copilot code reviews and counting — https://github.blog/ai-and-ml/github-copilot/60-million-copilot-code-reviews-and-counting/ — 2026-03-05 — primary

[S23] How to Make LLMs Shut Up (Greptile) — https://www.greptile.com/blog/make-llms-shut-up — 2024-12-18 — primary

[S24] Bringing Code Review to Claude Code (Anthropic) — https://claude.com/blog/code-review — 2026-03-09 — primary

[S25] Code review in the age of AI: Why developers will always own the merge button — https://github.blog/ai-and-ml/generative-ai/code-review-in-the-age-of-ai-why-developers-will-always-own-the-merge-button/ — 2025-07-14 — primary

[S26] AI Code review is always wrong — https://www.jessesquires.com/blog/2025/03/04/ai-code-review/ — 2025-03-04 — primary

[S27] Charlie Marsh on human code review as anti-slop guardrail — https://x.com/charliermarsh/status/2042053725183762939 — 2026-04-09 — social

[S28] Liran Tal on CodeRabbit / Martian-scale false positives and misses — https://x.com/liran_tal/status/2055317397200883796 — 2026-05-15 — social *(restates [S13])*

[S29] Do AI Code Review Tools Work, or Just Pretend? (RedMonk) — https://redmonk.com/kholterhoff/2025/06/25/do-ai-code-review-tools-work-or-just-pretend/ — 2025-06-25 — secondary

[S30] Ethan Cohen nearly abandoning AI code review — https://x.com/beefan/status/2026014312435753079 — 2026-02-23 — social

RESULT: claims_with_citations≈90; unique_sources_listed=30; independence_clusters_respected=Martian[S13]×1, Cursor-resolution[S14–S15], Graphite-FP[S16–S17], Cihan-eval[S1], Tufano-SOTA[S2]; contradiction_clusters_presented=6
