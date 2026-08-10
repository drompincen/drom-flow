# Critic: c3 (overclaiming: statements stronger than their evidence, hedges dropped, correlation stated as cause)

## O1 [blocking]
sentence: "Relative to human reviewers, LLM-generated code review comments are **not demonstrated to match human effectiveness at finding real defects**. The best controlled comparisons show low overlap with human-reported issues (~10% match while recommending ~2.4× more changes) [S9], moderate accuracy against executable correctness oracles (~64–69%) [S1], and industrial useful/acceptance rates that often sit well below deployment targets (e.g., ~60% useful vs 80% target; ~7–8% live acceptance of generated comments) [S4][S10]. Independent multi-tool evaluation finds no tool recovering more than ~63% of known offline issues, with large residual miss rates [S13]."
problem: The lead frames heterogeneous non-comparable proxies as one tier of “best controlled comparisons” for **LLM review comments vs human defect-finding**. Only [S9] is a same-PR human-overlap study. [S1] is block-level correctness *classification* (with problem descriptions), not generated review comments on PRs. [S4] is usefulness of best-practice URL tips vs an internal 80% bar, not defect catch vs humans. [S10] is live *acceptance* of generated comments, not true-positive defect rate. [S13] has **no human baseline**—≤63% of offline known issues does not speak to human effectiveness. Hedges that these are different constructs are dropped; the stack overclaims how strongly the corpus answers the canonical human comparison.
fix: Keep [S9] as the primary human-overlap control. Relabel [S1]/[S4]/[S10]/[S13] as distinct proxy classes (correctness gate; scoped best-practice usefulness; acceptance; multi-tool recall vs incomplete gold) and state explicitly that none of the latter is a head-to-head human defect-finding score. Do not present Martian offline recall as evidence “relative to human reviewers.”

## O2 [blocking]
sentence: "LLMs are best evidenced as noisy, complementary assistants—not as peers or substitutes for careful human review on real defects."
problem: The Answer opens with the careful epistemic claim “**not demonstrated** to match,” then closes by asserting positive ontology (**are** noisy assistants) and **non-parity** (“not as peers”). The corpus’s central gap is the missing human-vs-LLM independent-oracle design (Findings §1; Gaps; C5). Absence of demonstrated parity is not evidence of demonstrated non-parity or of a settled “assistant-only” effectiveness class; that drops the hedge the draft itself elsewhere respects.
fix: Align the bottom line with the opening hedge, e.g. “best *supported role* in-corpus is complementary / human-in-the-loop; **parity or substitution is not established** (not: ‘are not peers’).” Avoid stating non-equivalence as a measured fact.

## O3 [major]
sentence: "### 1. Measured defect-finding rate (true positives) vs humans"
problem: Section title claims **true-positive defect rates vs humans**, then lists [S1] correctness classification accuracy and [S2] 13% human-*like comment* success (code-to-comment style match)—neither is a TP defect-find rate against humans or an independent defect oracle. Placing them under this heading overclaims what those studies measure relative to the canonical question.
fix: Rename the section to something like “Proxies for defect-finding (mostly not human TP rates)” and tag each bullet with the actual construct (human-issue overlap / correctness classification / human-like wording / offline known-issue recall / vendor self-bench).

## O4 [major]
sentence: "Where humans are the reference, LLMs recover a small share of human issues."
problem: “LLMs” (plural, class-wide) is generalized from essentially one controlled same-PR result ([S9] ChatGPT-4 ~10% human-issue match). Other bullets in the section are not human-reference recovery rates. Class-level causal/summary language is stronger than the single-system evidence.
fix: Scope the reading to the measured system/study: e.g. “Where humans are the reference, **the one same-PR ChatGPT comparison ([S9])** recovers only ~10% of human-reported quality issues; class-wide human-overlap rates are unmeasured.”

## O5 [major]
sentence: "Martian online behavioral precision for a top tool is framed at ~49% (roughly one in two comments leads to a code change)—still half non-acted [S13][S20]."
problem: This sits under “### 2. False-positive / noise rate vs humans.” Treating non-acted comments as noise/FP equates behavioral non-action with false positivity—the exact construct error the draft correctly attacks in C4 (acceptance/resolution ≠ defect TP/FP). “Half non-acted” is reported as if it strengthens a high-noise conclusion without the hedge that authors may ignore true bugs, defer fixes, or disagree on priority.
fix: Move or reframe as “act-on / behavioral precision (not validated FP).” Explicitly say non-action ≠ false positive, consistent with C4.

## O6 [major]
sentence: "Functional/defect-oriented comments dominate volume in live generation but have **lower** acceptance than refactoring/style (functional ~4.8–5.2% accepted vs refactoring ~18% in RevMate) [S10]—so “findings” volume overstates defect catch relative to nits."
problem: The “so…” clause treats lower acceptance of functional comments as evidence that volume **overstates defect catch**. That confuses acceptance with true-positive defect detection (again C4). Harder functional defects can be real and still rejected, deferred, or costly to fix; lower acceptance could understate catch quality, not prove volume overstates it. Causal leap from correlation (comment class × acceptance) to a claim about defect-catch overstatement.
fix: Stop at the acceptance stratification. Replace the “so…” with: “acceptance is a biased proxy for defect value; functional volume ≠ functional true-positive rate ([S10]; see C4).”

## O7 [major]
sentence: "**Better evidenced: Side B for industry-at-large.** Side A is self-measured product metrics with COI and non-shared FP definitions. Side B includes multi-org live acceptance, multi-tool offline F1, vendor-honest pre-filter audits, and independent behavioral precision."
problem: “Industry-at-large” is a population claim. Side B is a handful of protocols/orgs (SWR-Bench techniques, Mozilla/Ubisoft RevMate, Greptile pre-filter audit, Martian act-on, one maintainer blog). Side A includes large-scale product telemetry and at least one serious internal deployment (Anthropic). Preferring Side B’s *metric construct* and independence is defensible; declaring it better evidenced **for all industry** overclaims external validity and drops the audit’s narrower phrasing (“multi-tool picture” / definition-dependent).
fix: Replace “industry-at-large” with a scoped claim: e.g. “Better evidenced **for independent multi-tool / multi-org live settings under shared non-vendor protocols**”; note that some high-tuning internal deployments report much lower marked-incorrect rates under their own labels.

## O8 [major]
sentence: "**Better evidenced: Side B for “does not replace humans / does not reliably shrink review cost.”** Primary industrial A/B and pre/post designs outweigh vendor framing."
problem: “Outweigh” states a decisive synthesis as if the industrial designs measure the same intervention as modern general-purpose LLM defect reviewers. The main A/B ([S4] AutoCommenter) targets best-practice URL tips, not general bug finding; Beko ([S11]) is one org pre/post with possible confounds. Vendor “less manual effort” claims are weak, but elevating scope-mismatched A/B to a general law that LLM review “does not reliably shrink review cost” overclaims transportability. Correlation of bot presence with longer PR time at Beko is also not isolated as causal load from FP triage.
fix: Scope Side B to: “no controlled industrial result in-corpus shows **net** review-time reduction for the studied bots; AutoCommenter (scoped tips) null on time; Beko pre/post longer closure (causation unproven).” Soften “outweigh” to “are higher-quality *on time outcomes for those deployments*” and keep ROI unresolved (already noted).

## O9 [major]
sentence: "**Tradeoff pattern:** High-recall / high-volume tools raise act-on counts and noise; high-precision / low-recall tools comment rarely [S13]."
problem: States a clean causal tradeoff. [S13]/corpus notes that offline data suggest “comment more → lower precision,” while **online data complicate that story** (high-volume tools can still see substantial act-on). The draft drops that hedge and treats volume as raising “noise” without defining noise independent of non-act-on.
fix: Restore the Martian hedge: offline pattern vs online complication; phrase as a **possible** precision–volume association under incomplete gold, not a settled production law that high volume “raises noise.”

## O10 [major]
sentence: "Net time savings are unproven and sometimes reverse under load."
problem: “Reverse under load” invents a mechanism (load) not established in [S11]/[S4]. Beko reports longer mean PR closure after bot introduction; that is not identified as “under load,” nor proven caused by review load vs other process changes. Causal language stronger than the pre/post association.
fix: Write “unproven; in one industrial pre/post ([S11]) mean PR closure time **increased** (cause not isolated). Google AutoCommenter A/B found no significant time change ([S4]).”

## O11 [minor]
sentence: "industrial useful/acceptance rates that often sit well below deployment targets (e.g., ~60% useful vs 80% target; ~7–8% live acceptance of generated comments) [S4][S10]"
problem: “Often” + “deployment targets” pluralizes a pattern. Only [S4] cites an explicit deployment target (80%). [S10] ~7–8% acceptance is not shown as missing a stated target; it is a live acceptance rate. Two unlike rates are yoked to imply frequent failure-to-target.
fix: Split: “e.g. AutoCommenter useful ~60% vs 80% deployment target [S4]; RevMate live acceptance of generated comments ~7–8% [S10] (not a published target miss).” Avoid “often … targets” unless more target-linked cases are cited.

## O12 [minor]
sentence: "OSS maintainer (untuned Copilot Code Review): about 1 in 5 comments were good catches contributors would have missed [S21]."
problem: Under a section that claims “Independent and multi-org evidence **clusters** toward **high noise**,” a single maintainer blog is listed parallel to multi-org and bench results without marking N=1 / selection. That slightly inflates how clustered/strong the noise conclusion is.
fix: Tag as “single-practitioner report (untuned defaults)” and keep it out of the “clusters” warrant, or move to §8 practitioner signal only.

RESULT: blocking=2 major=8 minor=2
