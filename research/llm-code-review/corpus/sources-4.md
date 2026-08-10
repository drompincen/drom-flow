# Industrial practitioners — LLM code review vs human reviewers

Perspective: non-vendor eng blogs, OSS maintainer reports, independent benchmarks used in real workflows, and practitioner signal on X (tagged social). Focus: acceptance/address rates, time saved vs bugs missed, operational noise.

---

## S1. How I Taught GitHub Copilot Code Review to Think Like a Maintainer
url: https://angiejones.tech/how-i-taught-github-copilot-code-review-to-think-like-a-maintainer/
published: 2025-11-25
type: primary
origin: independent
claims:
  - claim: An OSS maintainer (Block's goose project) found default Copilot Code Review too noisy; co-maintainers wanted it turned off.
    quote: "I turned it on thinking everyone would love it, but honestly it didn’t go so well. The other maintainers said the reviews were too noisy and most of the comments were of low value. They asked if we could just turn it off."
  - claim: Untuned, only about one in five AI review comments were genuinely useful catches the human would have missed.
    quote: "Only about 1 in 5 comments were actually good catches that the contributor would have missed"
  - claim: After custom instructions, noise dropped and comments became more useful, but tuning is ongoing operational work.
    quote: "After tuning Copilot, the difference was immediate. The noise dropped dramatically, and the comments became more useful."
  - claim: False positives erode trust in automated review.
    quote: "If you’re uncertain whether something is an issue, don’t comment. False positives create noise and reduce trust in the review process."

## S2. AI Code review is always wrong
url: https://www.jessesquires.com/blog/2025/03/04/ai-code-review/
published: 2025-03-04
type: primary
origin: independent
claims:
  - claim: A practicing iOS/macOS engineer reports every AI bot review on his PRs was wrong or nonsensical in experience.
    quote: "I work on a team that has enabled an AI code review tool. And so far, I am unimpressed. Every single time, the code review comments the AI bot leaves on my pull requests are not just wrong, but laughably wrong. When its suggestions are not completely fucking incorrect, they make no sense at all."
  - claim: Contextless AI reviews waste senior reviewer time rather than finding real defects.
    quote: "These contextless, pretend code reviews are wasting my time."
  - claim: Confident-sounding wrong suggestions risk misleading juniors (harm beyond mere noise).
    quote: "But this is worse than being wrong, because the bot writes with so much conviction and assurance. If I were early in my career, perhaps an intern or a student still learning, I might be persuaded by its imitation intelligence and counterfeit confidence."

## S3. Code Review Bench: Towards Billion Dollar Benchmarks (Martian)
url: https://withmartian.com/post/code-review-bench-v0
published: 2026-02-26
type: primary
origin: independent
claims:
  - claim: Independent lab open-sourced a continuous AI code-review benchmark grounded in ~200K+ real PRs and developer act-on behavior (not only vendor self-tests).
    quote: "We're open-sourcing Code Review Bench, a benchmark for code review tools that uses real-world developer behavior to avoid going stale. It has 200K+ PRs and updates daily."
  - claim: Even the best tools miss a large share of known issues offline—review agents do not replace thorough human defect finding.
    quote: "Equally exciting: no tool found more than 63% of the known issues. The best tool still missed a third of the bugs. The verifier is far from solved."
  - claim: High-volume tools can still get real developer action: CodeRabbit’s online recall ~0.54 on a large PR sample.
    quote: "Coderabbit has the highest recall in the online data (0.54) and the most PRs reviewed (5,035). Developers are acting on its comments at a substantial rate despite the volume."
  - claim: Online vs offline rankings diverge; offline gold sets understate precision and overstate recall—benchmarks must be read carefully for production decisions.
    quote: "Every tool's precision is being understated (real finds are scored as false positives) and every tool's recall is being overstated (the denominator of \"bugs that exist\" is too small). The magnitude of this effect is large enough to change rankings."

## S4. Code Review Bench methodology / open-source eval (Martian GitHub)
url: https://github.com/withmartian/code-review-benchmark
published: 2026 (repo; online/offline described in README)
type: primary
origin: independent
claims:
  - claim: Online precision is defined by whether developers actually fix after bot comments (behavioral acceptance proxy).
    quote: "Judge matching — The LLM determines which bot suggestions correspond to actual fixes, producing per-PR precision (what % of the bot's comments were useful?) and recall (what % of real issues did the bot catch?)."
  - claim: Offline ground truth is human-curated golden comments on 50 PRs from major OSS projects (Sentry, Grafana, Cal.com, Discourse, Keycloak).
    quote: "50 PRs from 5 major open-source projects, each with human-verified golden comments — the real issues a reviewer should catch."
  - claim: Static offline sets risk training leakage; continuous online sampling of fresh GitHub PRs is the anti-gaming design.
    quote: "Known limitation: Static datasets risk training data leakage — tools may have seen these PRs during training. That's why we also run the online benchmark."

## S5. CodeRabbit tops independent AI code review benchmark
url: https://www.coderabbit.ai/blog/coderabbit-tops-martian-code-review-benchmark
published: 2026-03-03
type: secondary
origin: derivative-of S3
claims:
  - claim: Vendor summary of Martian online results: top-line F1 ~51.2%; precision ~49.2% (about half of comments acted on); recall framed as highest among tools (~53.5% in summary).
    quote: "Developers act on CodeRabbit's suggestions at a meaningful rate. Its 49.2% precision means roughly one in two comments leads to a code change."
  - claim: Same post’s headline recall figure for that period.
    quote: "In summary: nearly 300,000, PRs, 53.5% recall rate, and #1 in F1 metrics."
  - claim: Even “winning” tools leave roughly half of comments as non-actioned noise in real developer behavior terms.
    quote: "CodeRabbit ranked #1 in F1 score among all 10 tools evaluated, with a 49.2% precision rate meaning roughly one in two comments leads to a code change, combined with the highest recall rate of any tool in the benchmark."

## S6. 60 million Copilot code reviews and counting
url: https://github.blog/ai-and-ml/github-copilot/60-million-copilot-code-reviews-and-counting/
published: 2026-03-05
type: primary
origin: independent
claims:
  - claim: At GitHub scale, Copilot code review accounts for more than one in five code reviews on the platform after ~10× growth since launch.
    quote: "Since our initial launch of Copilot code review (CCR) last April, usage has grown 10X, now accounting for more than one in five code reviews on GitHub."
  - claim: Product metric for signal: agent surfaces actionable feedback in 71% of reviews and stays silent in 29% (noise control via abstention).
    quote: "Silence is better than noise. In 71% of the reviews, Copilot code review surfaces actionable feedback. In the remaining 29%, the agent says nothing at all."
  - claim: Teams trade latency for quality: +6% positive feedback with +16% latency on a model change.
    quote: "In one recent change, adopting a more advanced reasoning model improved positive feedback rates by 6%, even though review latency increased by 16%."
  - claim: Production quality signals used are thumbs and whether flagged issues are resolved before merge—not pure defect discovery vs humans.
    quote: "In production, we track two key indicators: Developer feedback: Thumbs-up and thumbs-down reactions on comments help us understand whether suggestions are helpful. Production signals: We measure whether flagged issues are resolved before merging."

## S7. Code review in the age of AI: Why developers will always own the merge button
url: https://github.blog/ai-and-ml/generative-ai/code-review-in-the-age-of-ai-why-developers-will-always-own-the-merge-button/
published: 2025-07-14
type: primary
origin: independent
claims:
  - claim: GitHub’s developer interviews: self-review with Copilot before opening a PR cut trivial back-and-forth by roughly a third (time saved on nits, not proven defect parity with humans).
    quote: "Self reviews raised the floor: Developers who ran a Copilot review before opening a PR often wiped out an entire class of trivial nit-picks (i.e., trimmed imports, missing tests), cutting out back-and-forth by roughly a third."
  - claim: Practitioners still treat AI as non-replacement for human judgment on trade-offs.
    quote: "AI was no replacement for human judgement: Programming often involves trade-offs. LLMs can inform you about those trade-offs, but someone has to make the call about what path to take based on your organization’s goals and standards."
  - claim: Stated capability boundary: AI strong on mechanical/pattern checks; weak on architecture, mentorship, and values—i.e., defects that matter operationally often still need humans.
    quote: "But they still fall short on: Architecture and trade-offs. Should we split this service? Cache locally? Mentorship. Explaining why a pattern matters and when to break it. Values. Should we build this feature at all?"

## S8. How to Make LLMs Shut Up (Greptile)
url: https://www.greptile.com/blog/make-llms-shut-up
published: 2024-12-18
type: primary
origin: independent
claims:
  - claim: Real product launch feedback: too many comments caused authors to ignore the bot entirely (operational failure mode).
    quote: "When we first launched this product, the biggest complaint by far was that the bot left too many comments. In a PR with 20 changes, it would leave as many as 10 comments, at which point the PR author would simply start ignoring all of them."
  - claim: Pre-filter analysis of comments: ~19% good, ~2% incorrect, ~79% nits—noise dominates raw LLM review output.
    quote: "We analyzed existing Greptile comments and found that ~19% were good, 2% were flat-out incorrect, and 79% were nits - comments that were technically true but not something the dev cared about."
  - claim: Address rate (comments fixed before merge) rose from 19% to 55%+ after embedding-based noise filtering—acceptance is trainable, not inherent to the model alone.
    quote: "Within two weeks of rolling out this feature, existing users saw address rate (percentage of Greptile’s comments that devs address before merging) go from 19% to 55+%. While this is far from perfect, this has been far and away the most impactful technique in reducing the noise produced by the LLM."

## S9. Bringing Code Review to Claude Code (Anthropic)
url: https://claude.com/blog/code-review
published: 2026-03-09
type: primary
origin: independent
claims:
  - claim: Internal Anthropic deployment: share of PRs with substantive review comments rose from 16% to 54% after AI review on nearly every PR (coverage of review depth, not pure bug-catch rate vs humans).
    quote: "We run Code Review on nearly every PR at Anthropic. Before, 16% of PRs got substantive review comments. Now 54% do."
  - claim: Engineers mark <1% of findings incorrect (high precision under their internal feedback process).
    quote: "Engineers largely agree with what it surfaces: less than 1% of findings are marked incorrect."
  - claim: On large PRs (1,000+ lines) 84% get findings averaging 7.5 issues—high volume of findings where humans often skim.
    quote: "on large PRs (over 1,000 lines changed), 84% get findings, averaging 7.5 issues."
  - claim: Vendor anecdote of a real production auth-breaking one-line change caught by AI that the engineer says they would have missed—illustrative, not a controlled comparison.
    quote: "In one case, a one-line change to a production service looked routine and was the kind of diff that normally gets a quick approval. But Code Review flagged it as critical. The change would have broken authentication for the service... It was fixed before merge, and the engineer shared afterwards that they wouldn't have caught it on their own."

## S10. Charlie Marsh on human code review as anti-slop guardrail
url: https://x.com/charliermarsh/status/2042053725183762939
published: 2026-04-09
type: social
origin: independent
claims:
  - claim: Open-source tooling lead (Astral/OpenAI) argues the most effective protection against AI-generated slop remains careful human review—not automated comment bots alone.
    quote: "Tragically I am continuing to find that the most effective guardrail against slop is extremely talented engineers doing very thoughtful, human code review"

## S11. Liran Tal on CodeRabbit / Martian-scale false positives and misses
url: https://x.com/liran_tal/status/2055317397200883796
published: 2026-05-15
type: social
origin: derivative-of S3
claims:
  - claim: Security/DevRel practitioner reading the public benchmark concludes ~half of comments are false positives and ~half of real issues are still missed—and that only helps if humans actually read the bot.
    quote: "from this benchmark: - almost 50% are false positives - almost 50% of real issues are missed do you internalize? and that's if you even actually read and attend to the issues reported"

## S12. Ethan Cohen (ex-Twitter staff eng / CTO) nearly abandoning AI code review
url: https://x.com/beefan/status/2026014312435753079
published: 2026-02-23
type: social
origin: independent
claims:
  - claim: Practitioner reports false positives degrade signal and make review slower; iteration loops feel worse than pre-AI workflow.
    quote: "tbh i'm very close to being done with AI code review. i KNOW that it's just LLM non-determinism but: - the infinite loop of iteration is so much worse than before - false positives make noise:signal harder and slower to parse - the provider feedback loops seem useless"

## S13. Do AI Code Review Tools Work, or Just Pretend? (RedMonk)
url: https://redmonk.com/kholterhoff/2025/06/25/do-ai-code-review-tools-work-or-just-pretend/
published: 2025-06-25
type: secondary
origin: independent
claims:
  - claim: Industry analyst synthesis: effectiveness remains hotly debated; tools may lighten load or just “confidently wing it.”
    quote: "While their promise is significant, whether these tools truly lighten the code review burden, or if they just confidently wing it, remains hotly debated among developers."
  - claim: Quotes practitioner CTO who finds paid AI review worth it if you can dismiss noise (acceptance of FP triage cost).
    quote: "If you’re not doing pair programming and merging direct to your main branch hard-core trunk-based style it’s a no-brainer to turn these reviews on, you can still resolve anything flagged that’s noise."
  - claim: Quotes practitioner experience that wrong/hallucinated reviews waste time on complex work.
    quote: "I’ve had nothing but horrible experiences with AI code review. It suffers from hallucinations, outdated info, insufficient memory/context, etc. It just makes everything up... If you’re the author of some library or if you’re doing anything remotely complex, it’s just infuriating and a waste of time."

---

RESULT: 13 sources catalogued (8 primary, 2 secondary, 3 social); core independent operational signals are S1–S4 and S6–S8; S5/S11 derive from Martian (S3); vendor internal metrics S9 and social S10/S12 are directional not controlled human-vs-LLM defect RCTs.
