# Width-sweep sources — Academic empiricists (ICSE/FSE/ASE/TSE/MSR)

Canonical question: How effective are LLM-generated code review comments at finding real defects compared to human reviewers?

Perspective: Peer-reviewed controlled comparisons, ground-truth defect/correctness labels, replication packages; methods and effect sizes marketing often omits.

---

## S1. Evaluating Large Language Models for Code Review
url: https://arxiv.org/abs/2505.20206
published: 2025-05-26 (arXiv v1)
type: primary
origin: independent
claims:
  - claim: With problem descriptions, GPT-4o correctly classified code correctness (unit-test ground truth) only 68.50% of the time on 492 mixed-correctness code blocks; Gemini 2.0 Flash reached 63.89%.
    quote: "With problem descriptions, GPT4o and Gemini 2.0 Flash correctly classified code correctness 68.50% and 63.89% of the time, respectively, and corrected the code 67.83% and 54.26% of the time for the 492 code blocks of varying correctness."
  - claim: Fully automated LLM code review is unreliable; regression (turning correct code incorrect) reached double-digit rates even with descriptions.
    quote: "While our results were positive, they also show that LLMs exhibit significant error rates, with regression rates reaching up to 23.79% and inaccurate approval decisions of 44.44% (both from Gemini w/o problem descriptions, mixed dataset)."
  - claim: Authors recommend human-in-the-loop rather than replacing human reviewers, given moderate accuracy against executable ground truth.
    quote: "The results indicated that the LLM's assessments had moderate accuracy, and its suggestions were moderately effective. This suggests that a fully automated code review process may be unreliable."
  - claim: Replication package with experiment setup and unit-test evaluation protocol is released.
    quote: "We shared our experiment setup and source code to support practitioners 111https://doi.org/10.5281/zenodo.14962566."

## S2. Code Review Automation: Strengths and Weaknesses of the State of the Art
url: https://arxiv.org/abs/2401.05136
published: 2024-01-10 (arXiv v1; IEEE TSE)
type: primary
origin: independent
claims:
  - claim: After ~105 person-hours of manual inspection of 2,291 predictions from three SOTA code-review automation techniques, ChatGPT (general-purpose LLM) succeeds on only 13% of code-to-comment cases (19/150), underperforming specialized SOTA.
    quote: "Concerning the code-to-comment task, ChatGPT succeeds in 19/150=13% of cases, being less performant than the SOTA."
  - claim: ChatGPT is competitive on implementing reviewer comments (code & comment-to-code) at 55% success vs ~50% for specialized techniques, with complementarity (succeeds on 44% of SOTA failures).
    quote: "ChatGPT performs slightly better than the SOTA in the code & comment-to-code task, being able to address the reviewer's comment in 55/100=55% of cases, as compared to the 50% of the three techniques"
  - claim: Exact-match success rates on original test sets are very low for generating reviewer-like comments (e.g., T5cr 2.11% EM on code-to-comment; CodeReviewer 0% EM on Java code-to-comment in their sample).
    quote: "T5cr [47] | code-to-comment | 354 (2.11%) | 16'426 (97.89%)"
  - claim: ~25% of inspected dataset instances are mining noise that undermines training and evaluation validity.
    quote: "Despite the effort in cleaning the datasets, in our manual analysis we found ~25% of the inspected instances representing noise in the datasets, posing questions on the reliability of the evaluations reported in the literature."
  - claim: ChatGPT struggles to comment code as a human reviewer would; specialized research is still justified.
    quote: "finding that ChatGPT struggles in commenting code as a human reviewer would do."

## S3. Automating Code Review Activities by Large-Scale Pre-training (CodeReviewer)
url: https://arxiv.org/abs/2203.09095
published: 2022-03-17 arXiv; ESEC/FSE 2022 camera-ready (v2 2022-10-11)
type: primary
origin: independent
claims:
  - claim: On code change quality estimation (binary: does this diff need a review comment?), CodeReviewer achieves F1 71.53 and accuracy 73.89, outperforming CodeT5 (F1 64.16) and T5 (F1 63.29)—i.e., roughly three-quarters accuracy as a defect/issue gate, not near-perfect defect finding.
    quote: "CodeReviewer (12) | 78.60 | 65.63 | 71.53 | 73.89"
  - claim: On code refinement given a human review comment, exact match to the human-revised code is only 30.32% (still about 2× T5 and +25% relative over CodeT5).
    quote: "CodeReviewer successfully generates the repaired code exactly the same as ground truth for more than 30% cases, which is two times as the result of T5 and 25% more than CodeT5 relatively"
  - claim: BLEU on review-comment generation remains low (5.32 on test set), underscoring that matching human reviewer wording is hard; human raters scored information 3.60/5 and relevance 3.20/5 for CodeReviewer vs lower for baselines.
    quote: "CodeReviewer (12) | 5.32 | 3.60 | 3.20"
  - claim: Dataset and model released for replication (Microsoft CodeBERT/CodeReviewer).
    quote: "Our dataset, code, and model are released 1. 1https://github.com/microsoft/CodeBERT/tree/master/CodeReviewer"

## S4. AI-Assisted Assessment of Coding Practices in Modern Code Review (AutoCommenter)
url: https://arxiv.org/abs/2405.13565
published: 2024-05-22 (arXiv); AIware '24 (ACM)
type: primary
origin: independent
claims:
  - claim: Industrial LLM-backed system at Google for best-practice (not general defect) comments; after calibration, target useful ratio for deployment was 80%; independent raters initially found only 60% useful on early-adopter feedback sample.
    quote: "The useful ratio from the rater evaluation was 60%, slightly higher than the 54% from the developer feedback on the same comments, but well below our target of 80% for wider deployment."
  - claim: Estimated comment-resolution rate (authors changed code so the violation was no longer predicted) is about 40%, not near-universal defect remediation.
    quote: "Therefore, we estimate that the comment-resolution rate is about 40%, which is significantly larger than the ratio of comments with explicit positive feedback to all comments."
  - claim: AutoCommenter's URL set covers 68% of historical human best-practice-URL comments but is dominated by a few tips (top-85 URLs = 90% of model comments); 66% of top-50 tips are beyond traditional linters.
    quote: "The set of URLs used by AutoCommenter covers 68% of historical human comments with a best practice URL."
  - claim: A/B experiment on ~half of developers found no significant change in review duration, active time, or comment-response iterations—productivity claims need careful measurement.
    quote: "We did not detect any statistically significant change in any of the following: total duration of code reviews, time developers actively spent on the code review, the number of comment-response iterations between the author and the reviewer."

## S5. Too Noisy To Learn: Enhancing Data Quality for Code Review Comment Generation
url: https://arxiv.org/html/2502.02757
published: 2025-02 (arXiv; SANER-area empirical SE)
type: primary
origin: independent
claims:
  - claim: Even after prior heuristic/SVM cleaning of the CodeReviewer benchmark, only 64% of sampled training comments are "valid" (clear actionable improvement suggestions); ~36% remain noise—consistent with Tufano et al.'s ~32% noise in the test set.
    quote: "we find that only 64% of the sampled comments in the training set of CodeReviewer benchmark are valid."
  - claim: LLM-based cleaning raises valid-comment precision of retained data to 66–85% and, when used for fine-tuning, yields BLEU-4 gains of 7.5–13% overall and 12.4–13.0% specifically on valid test comments, despite 25–66% smaller training sets.
    quote: "the models fine-tuned on the cleaned datasets achieved BLEU-4 scores 7.5% - 13% higher than those trained on the original dataset, with a 12.4% - 13.0% increase specifically on valid comments in test sets."
  - claim: Cleaned models also improve information score up to 24% and relevance score up to 11% vs original—i.e., training noise materially degrades apparent "effectiveness" of generated review comments.
    quote: "the quality of comments generated from the cleaned models is significantly improved, with up to a 24% increase in information score and an 11% increase in relevance score."
  - claim: Replication package with cleaned datasets released.
    quote: "we provide a replication package that includes the cleaned datasets, experimental results, and scripts.111https://zenodo.org/records/13150598"

## S6. LLaMA-Reviewer: Advancing Code Review Automation with Large Language Models through Parameter-Efficient Fine-Tuning
url: https://arxiv.org/abs/2308.11148
published: 2023-08-22 arXiv; ISSRE 2023
type: primary
origin: independent
claims:
  - claim: PEFT (LoRA / zero-init prefix) on LLaMA (6.7B) with <1% trainable parameters matches specialized code-review models on public datasets for review necessity, comment generation, and refinement—without domain pre-training from scratch.
    quote: "Notably, even with the smallest LLaMA base model consisting of 6.7B parameters and a limited number of tuning epochs, LLaMA-Reviewer equals the performance of existing code-review-focused models."
  - claim: Positions LLM code review as matching specialist models on standard benchmarks, not clearly exceeding human defect-finding; evaluation uses the CodeReviewer and Tufano pipelines (human-comment similarity / task metrics, not independent defect oracles).
    quote: "An extensive evaluation of LLaMA-Reviewer is conducted on two diverse, publicly available datasets."
  - claim: Code and PEFT plugins open-sourced for replication.
    quote: "To foster continuous progress in this field, the code and all PEFT-weight plugins have been made open-source."

## S7. Resolving Code Review Comments with Machine Learning
url: https://storage.googleapis.com/gweb-research2023-media/pubtools/7525.pdf
published: 2024 (ICSE-SEIP '24); related Google Research blog 2023-05-23
type: primary
origin: independent
claims:
  - claim: Production ML system at Google that proposes code edits to address human reviewer comments—not generating defect-finding comments—sees authors apply the ML-suggested edit for 7.5% of all reviewer comments.
    quote: "In deployment, code-change authors at Google address 7.5% of all reviewer comments by applying an ML-suggested edit."
  - claim: Impact is productivity (time to resolve comments), not a claim that ML finds more defects than humans; estimated hundreds of thousands of engineer hours annually at Google scale.
    quote: "The impact of this will be to reduce the time spent on code reviews by hundreds of thousands of engineer hours annually at Google scale."
  - claim: Distinguishes lab benchmark success from production: research often reports lab metrics without production exposure.
    quote: "Typically such results are predicated on the fast and impressive improvement of ML models of code, but are usually anchored on \"lab\" results: measurements of success metrics over evaluation benchmarks, with limited exposure to production use."

## S8. Can LLMs Replace Manual Annotation of Software Engineering Artifacts?
url: https://arxiv.org/abs/2408.05534
published: 2024-08-10 arXiv; MSR 2025
type: primary
origin: independent
claims:
  - claim: On ten SE annotation tasks (including static-analysis warning actionability), human–model agreement sometimes matches human–human agreement, but on static-analysis warning labeling human–model Krippendorff's α drops to ~0.15 vs human–human 0.80—LLMs are unreliable substitutes for defect-related judgment without filtering.
    quote: "For the static analysis warnings rating task, we have only two human raters, and they are in strong agreement (Krippendorff's α 0.80). However, human-model (0.15) and model-model (0.12) agreements are low"
  - claim: Model–model agreement (cheap, automated) correlates with human–model agreement (Spearman 0.65, p<0.05) and can triage whether a task is suitable for LLM annotation at all.
    quote: "the mean model-model agreement of the top 3 models is positively correlated with the mean human-model agreement, with a Spearman correlation of 0.65 (p<0.05)."
  - claim: Confidence-based partial replacement of one human rater can save substantial effort without statistically significant agreement drop on suitable tasks; replacing all humans remains unsafe.
    quote: "Overall, for seven of the ten tasks, we can safely replace one human rater with a model."
  - claim: First MSR-style controlled study of mixed human–LLM evaluation methodology for SE artifacts.
    quote: "Overall, our work is the first step toward mixed human-LLM evaluations in software engineering."

---

## Cross-source notes (academic empiricist reading)

- **Ground-truth defect labels are rare.** S1 is among the few that score LLM "review" decisions against unit tests. Most papers (S2–S3, S5–S6) evaluate comment/refinement similarity to *human* comments mined from GitHub—which conflates style, process, and defects and is polluted by ~25–36% noise (S2, S5).
- **Effect sizes marketing omits.** Exact match for automated review comments is typically single-digit % (S2); quality-estimation F1 ~0.72 (S3); industrial apply rates for ML-resolved comments ~7.5% (S7); useful-comment resolution ~40% even after calibration (S4); correctness classification ~64–69% (S1).
- **ChatGPT/general LLMs underperform specialized models at writing human-like review comments** (S2: 13% code-to-comment) but can match or slightly beat them at implementing human comments (S2: 55%).
- **No peer-reviewed head-to-head shows LLM reviewers finding more real defects than human reviewers under controlled ground-truth defect oracles.** Closest proxies: S1 (unit tests as correctness oracle), S3/S6 (quality estimation / necessity prediction), S4 (best-practice violations only).
- **Derivative risk:** Industry blogs (Google Research post on comment resolution) restate S7; vendor "AI code review" whitepapers typically lack independent ground-truth designs and should not be counted as corroboration of S1–S8.

RESULT: 8 sources found (8 primary, 0 secondary); all independent primary peer-reviewed or archival SE research with methods/effect sizes.
