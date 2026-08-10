# Vendor / product proponent sources — LLM code-review effectiveness

Perspective: claims, case studies, and product benchmarks from GitHub Copilot, CodeRabbit, Graphite/Diamond, Cursor Bugbot, Greptile, Augment, Amazon CodeGuru, and related product marketing. Figures are **advertised best-case / vendor-framed metrics** for stress-testing, not independent ground truth.

---

## S1. Building a better Bugbot (Cursor)
url: https://cursor.com/blog/building-bugbot
published: 2026-01 (Version 11 dated January 2026 in post; forum summary 2026-01-16)
type: primary
origin: independent
claims:
  - claim: Cursor reports raising Bugbot resolution rate from ~52% to over 70% via ~40 experiments, more than doubling resolved bugs per PR.
    quote: "Since launch, we have run 40 major experiments that have increased Bugbot's resolution rate from 52% to over 70%, while lifting the average number of bugs flagged per run from 0.4 to 0.7. This means that the number of resolved bugs per PR has more than doubled, from roughly 0.2 to about 0.5."
  - claim: Resolution rate is defined as whether Bugbot-reported issues were fixed by the author by merge time (AI-judged, spot-checked with authors).
    quote: "To solve this problem, we devised a metric called the resolution rate. It uses AI to determine, at PR merge time, which bugs were actually resolved by the author in the final code. When developing this metric, we spot-checked every example internally with the PR author and we found that the LLM correctly classified nearly all of them as resolved or not."
  - claim: Cursor claims newer Bugbot versions caught more bugs without a comparable rise in false positives.
    quote: "We released Version 1 in July 2025 and Version 11 in January 2026. Newer versions caught more bugs without a comparable rise in false positives."
  - claim: Cursor claims Bugbot reviews more than two million PRs per month.
    quote: "Today, Bugbot reviews more than two million PRs per month for customers like Rippling, Discord, Samsara, Airtable, and Sierra AI."

---

## S2. AI Code Review Built for Production | Bugbot by Cursor (product page)
url: https://cursor.com/bugbot
published: unknown (live product marketing; accessed 2026-08)
type: primary
origin: independent
claims:
  - claim: Cursor markets Bugbot as detecting hard logic bugs with a low false-positive rate.
    quote: "Bugbot detects the hardest logic bugs with a low false positive rate."
  - claim: Cursor claims 70%+ of Bugbot flags are resolved before merge.
    quote: "Bugbot optimizes for bugs that get fixed. 70%+ of flags get resolved before merge."
  - claim: Cursor claims more than half of bugs found are ultimately fixed by engineers.
    quote: "More than half of the bugs that we find are ultimately fixed by engineers."
  - claim: Customer testimonial (Maven) claims Bugbot catches implementation errors that human review missed.
    quote: "I've seen it catch implementation errors that received a pass of human code review."
  - claim: Customer testimonial (Discord) claims Bugbot finds real bugs after human approval.
    quote: "Bugbot finds real bugs after human approval. Avoiding one sev pays for itself."

---

## S3. Introducing Graphite Reviewer: your AI code review companion
url: https://graphite.com/blog/graphite-reviewer-launch
published: 2024-09-30
type: primary
origin: independent
claims:
  - claim: Graphite claims Reviewer achieved a sub-3% false-positive rate across tens of thousands of code changes.
    quote: "AI only feels like magic when it’s consistently helpful and trustworthy, and Reviewer has achieved a <3% false-positive rate across tens of thousands of code changes reviewed."
  - claim: Graphite positions Reviewer as catching real bugs with fewer false positives than other AI bots.
    quote: "Other AI bots hallucinate and create noisy comments. Graphite Reviewer is calibrated to catch real bugs and deliver smarter, targeted feedback with fewer false positives."
  - claim: Graphite claims Reviewer provides immediate actionable feedback so authors can fix bugs before human teammates review.
    quote: "Graphite Reviewer provides authors with immediate, actionable feedback, allowing them to squash bugs in their PRs before teammates even start their review. This enables reviewers to focus on the big picture rather than getting caught up in typos and stylistic nits."

---

## S4. Best AI pull request reviewers in 2025 (Graphite guide)
url: https://graphite.com/guides/best-ai-pull-request-reviewers-2025
published: ~2025 (page says "Last modified 10 months ago" relative to access; 2025 guide title)
type: primary
origin: independent
claims:
  - claim: Graphite claims Diamond/Agent achieved a 96% positive feedback rate and 67% implementation rate of suggested changes.
    quote: "With a focus on reducing false positives, Diamond has achieved a 96% positive feedback rate on AI-generated comments and a 67% implementation rate of suggested changes."
  - claim: Graphite frames the 67% implementation rate as evidence developers find feedback actionable and valuable.
    quote: "Tools like Graphite Agent have demonstrated a 67% implementation rate of suggested changes, indicating that developers find the feedback actionable and valuable."
  - claim: Graphite markets competitors (e.g. CodeRabbit) as having higher false-positive rates (vendor comparative claim).
    quote: "However, CodeRabbit has been noted to have the highest false-positive rate among AI code reviewers, which can lead to developers spending time reviewing suggestions that may not be relevant or actionable."

---

## S5. Building an agentic memory system for GitHub Copilot
url: https://github.blog/ai-and-ml/github-copilot/building-an-agentic-memory-system-for-github-copilot/
published: 2026-01-15
type: primary
origin: independent
claims:
  - claim: GitHub reports memory usage improved Copilot code review precision by 3% and recall by 4% on an evaluation set.
    quote: "When we ran Copilot code review on the pull requests in our evaluation set, memory usage led to 3% increase in precision and 4% increase in recall."
  - claim: GitHub A/B test reports 77% positive feedback on Copilot code review comments with memory vs 75% without.
    quote: "Copilot code review: 2% increase in positive feedback on comments (77% with memories vs 75% without). This means automated code review is yielding improved quality assurance."
  - claim: GitHub reports both A/B lifts as highly statistically significant.
    quote: "Both increases are highly statistically significant, with p-value <0.00001"
  - claim: GitHub frames positive-feedback lift as improved quality assurance from automated code review.
    quote: "This means automated code review is yielding improved quality assurance."

---

## S6. Copilot code review: Better coverage and more control (GitHub Changelog)
url: https://github.blog/changelog/2025-05-28-copilot-code-review-better-coverage-and-more-control/
published: 2025-05-28
type: primary
origin: independent
claims:
  - claim: GitHub claims Copilot now generates 80% more comments per PR, including bug detection / correctness coverage.
    quote: "Copilot now generates 80% more comments per pull request, expanding coverage to include new review dimensions such as: Code quality (e.g., maintainability, best practices); Correctness (e.g., bug detection, error handling); Clarity and style (e.g., spelling, API design); Robustness and accessibility (e.g., performance, security, accessibility)"
  - claim: GitHub claims these changes help developers catch more issues earlier with less manual effort.
    quote: "These changes help developers catch more issues earlier and improve code quality with less manual effort."

---

## S7. AI Code Review Benchmarks 2025 (Greptile)
url: https://www.greptile.com/benchmarks
published: 2025 (evaluation conducted July 2025 per methodology section)
type: primary
origin: independent
claims:
  - claim: Greptile's self-published benchmark claims an 82% overall bug-catch rate on 50 real-world bugs, ahead of Bugbot (58%), Copilot (54%), CodeRabbit (44%), and Graphite (6%).
    quote: "Greptile led with an 82% catch rate, 41% higher than Bugbot (58%). The rest stack clearly: Bugbot and Copilot in the mid-50s, CodeRabbit at 44%, and Graphite at 6%."
  - claim: Catch criteria required explicit line-level identification of the known bug (not summary-only).
    quote: "A bug counted as \"caught\" only when the tool explicitly identified the faulty code in a line-level comment and explained the impact."
  - claim: Greptile notes scoring considered only detection of the original bug; false positives did not affect catch rate.
    quote: "Scoring considered only detection of the original bug; false positives, style suggestions, and unrelated comments did not affect the catch rate."
  - claim: By severity (vendor table), Greptile claims 58% critical / 100% high / 89% medium / 87% low catch rates.
    quote: "Critical | Greptile 58% | Bugbot 58% | Copilot 50% | CodeRabbit 33% | Graphite 17%"

---

## S8. We benchmarked 7 AI code review tools on large open-source projects (Augment Code)
url: https://www.augmentcode.com/blog/we-benchmarked-7-ai-code-review-tools-on-real-world-prs-here-are-the-results
published: 2025-12-11
type: primary
origin: independent
claims:
  - claim: Augment's vendor benchmark ranks Augment Code Review #1 with 65% precision, 55% recall, 59% F-score on 50 PRs across five large OSS repos.
    quote: "Augment Code Review | 65% | 55% | 59%"
  - claim: Same vendor table places Cursor Bugbot at 49% F-score, Greptile 45%, CodeRabbit 39%, GitHub Copilot 25%.
    quote: "Cursor Bugbot 60% 41% 49%; Greptile 45% 45% 45%; ... CodeRabbit 36% 43% 39%; ... GitHub Copilot 20% 34% 25%"
  - claim: Augment defines correctness as matching golden comments a competent human reviewer would catch.
    quote: "A review comment is considered correct if it matches a golden comment: a ground-truth issue that a competent human reviewer would be expected to catch. Golden comments reflect real correctness or architectural problems, not stylistic nits."
  - claim: Augment claims it is the only tool in the evaluation that consistently meets a senior-engineer review standard via context retrieval.
    quote: "Augment Code Review is the only tool in this evaluation that consistently meets that standard. Our Context Engine enables recall far above the rest of the field, and its precision keeps the signal high."

---

## S9. Code Review Bench: Towards Billion Dollar Benchmarks (Martian)
url: https://withmartian.com/post/code-review-bench-v0
published: 2026-02-26
type: primary
origin: independent
claims:
  - claim: Independent Code Review Bench reports no tool found more than 63% of known issues offline — best tool still missed ~1/3 of bugs.
    quote: "Equally exciting: no tool found more than 63% of the known issues. The best tool still missed a third of the bugs. The verifier is far from solved."
  - claim: Online data: Graphite characterized as highest precision / lowest recall; CodeRabbit highest online recall (0.54) with most PRs reviewed.
    quote: "Graphite has the highest precision and lowest recall in both benchmarks — it comments rarely, but when it does, it's usually right. ... Coderabbit has the highest recall in the online data (0.54) and the most PRs reviewed (5,035)."
  - claim: Offline gold set was built from Augment and Greptile vendor datasets, introducing possible bias toward those tools' bug categories.
    quote: "When we built the offline benchmark, we started with Augment and Greptile's previously published datasets of PRs with known issues. ... The offline benchmark may be biased toward the kinds of issues those tools prioritize."
  - claim: Martian notes vendor-published benchmarks are well-resourced but potentially biased toward the vendor's tool.
    quote: "This is why benchmarks have historically been either academic (rigorous but under-resourced and eventually stale) or vendor-published (well-resourced but potentiallybiased toward the vendor's tool)."

---

## S10. CodeRabbit tops independent AI code review benchmark
url: https://www.coderabbit.ai/blog/coderabbit-tops-martian-code-review-benchmark
published: 2026-03-03
type: secondary
origin: derivative-of S9
claims:
  - claim: CodeRabbit advertises #1 F1 (51.2%), highest recall (~15% above next), and 49.2% precision on Martian online benchmark across nearly 300k PRs.
    quote: "Their leaderboard shows CodeRabbit has the highest recall of any tool, almost 15% more than the next closest tool. In plain terms: CodeRabbit finds more real bugs than anyone else. CodeRabbit also tops the overall chart with the highest F1 score (balance of precision/recall), with a 51.2% score, more than any other code review tool."
  - claim: CodeRabbit states 49.2% precision means roughly one in two comments leads to a code change.
    quote: "Its 49.2% precision means roughly one in two comments leads to a code change."
  - claim: CodeRabbit summary line cites 53.5% recall and #1 F1 on ~300k PRs based on real-world developer signals.
    quote: "In summary: nearly 300,000, PRs, 53.5% recall rate, and #1 in F1 metrics. Not on a curated lab test using a static list of known bugs but based on real-world developer signals."
  - claim: CodeRabbit acknowledges offline Martian results were less favorable for high-volume tools like itself.
    quote: "In the 50-PR offline comparison mentioned in Code Review Bench, CodeRabbit showed a lower F1 score than the online analysis that included comments accepted by devs in real PRs."

---

## S11. How Graphite builds reliable AI code review at scale (Braintrust customer story)
url: https://www.braintrust.dev/customers/graphite
published: unknown (accessed 2026-08)
type: secondary
origin: independent
claims:
  - claim: Graphite/Braintrust story headlines a 90%+ target acceptance rate for Diamond comments.
    quote: "90%+ Target acceptance rate"
  - claim: Graphite treats acceptance rate (developer commits suggested change) as the most important metric for actionable, relevant comments.
    quote: "Acceptance rate tracks comments that result in developer action. When a developer sees a Diamond comment and commits the suggested change, this signals that the feedback was both accurate and valuable. Graphite considers this their most important metric because it directly measures whether comments are actionable and relevant."
  - claim: Reported product-side improvement after systematic evals: 5% reduction in negative rules for custom rule detection.
    quote: "For Diamond's custom rule detection feature, where customers define specific coding standards they want enforced, the team observed a 5% reduction in negative rules generated."

---

## S12. Amazon CodeGuru Reviewer Updates: New Java Detectors and CI/CD Integration with GitHub Actions
url: https://aws.amazon.com/blogs/aws/amazon_codeguru_reviewer_updates_new_java_detectors_and_cicd_integration_with_github_actions/
published: 2021-06-24
type: primary
origin: independent
claims:
  - claim: AWS markets CodeGuru Reviewer as detecting hard-to-find defects/bugs in Java and Python via ML and automated reasoning.
    quote: "CodeGuru Reviewer helps you detect potential defects and bugs that are hard to find in your Java and Python applications, using the AWS Management Console, AWS SDKs, and AWS CLI."
  - claim: AWS frames PR comments as an additional reviewer pair of eyes for code quality and security analysis in CI/CD.
    quote: "During a pull-request (PR) review, all the CodeGuru recommendations will appear as a comment, as if you had another pair of eyes on the PR."
  - claim: Security detectors claimed trained on over 100,000 Amazon and open-source repositories (ML-based review lineage predating modern LLM PR bots).
    quote: "These detectors are built with machine learning and automated reasoning techniques, trained on over 100,000 Amazon and open-source code repositories, and based on the decades of expertise of the AWS Application Security (AppSec) team."

---

## Notes for synthesis (vendor perspective caveats)

- **Self-win pattern:** Greptile (S7) and Augment (S8) each publish offline-style benchmarks where their product ranks first; Martian (S9) explicitly flags vendor-published benchmarks as potentially biased and notes offline gold sets started from Greptile/Augment data. CodeRabbit (S10) is a derivative write-up of Martian online leaderboard numbers.
- **Metric definitions differ:** “resolution rate” (Cursor), “acceptance/implementation rate” (Graphite), “catch rate” of a single known bug (Greptile), precision/recall vs golden comments (Augment), developer act-on rate online (Martian/CodeRabbit), comment volume lift (GitHub). These are **not** interchangeable measures of “finding real defects vs humans.”
- **Best-case advertised headline numbers (for stress-testing):**
  - Cursor: 70%+ flags resolved; resolution 52%→70%; ~0.5 resolved bugs/PR
  - Graphite: <3% FP; 96% positive feedback; 67% implementation; 90%+ target acceptance
  - Greptile: 82% bug catch (own bench)
  - Augment: 65% P / 55% R / 59% F1 (own bench)
  - CodeRabbit via Martian online: ~51% F1, ~49–54% precision/recall framing; “finds more real bugs”
  - GitHub: 80% more comments/PR; memory +3% P / +4% R; 77% positive comment feedback
  - Martian ceiling: no tool >63% of known issues offline
- **CodeGuru** (S12) is older ML/static-analysis lineage; quantitative head-to-head vs human defect find rates were not advertised in the primary post opened here.

RESULT: 12 sources catalogued (11 independent origins, 1 derivative-of S9); 10 primary, 2 secondary.
