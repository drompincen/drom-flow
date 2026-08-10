# Canonical question

Relative to human code reviewers, how effective are LLM-generated code review comments at identifying true defects (real bugs, security flaws, or correctness issues) versus false positives, when both review the same or comparable code changes?

# Sub-questions

1. What is the measured defect-finding rate (true positives for real defects) of LLM-generated code review comments versus human reviewers on the same or matched pull requests/changes? | evidence: controlled study, academic paper, industrial field experiment, or shared evaluation dataset with ground-truth defects | falsified by: studies where LLMs match or exceed human true-positive defect detection rates under matched conditions and comparable review effort/context

2. What is the false-positive / noise rate of LLM review comments compared to humans (comments that are incorrect, trivial, or not defect-related)? | evidence: study or dataset scoring comment validity/usefulness against ground truth or expert labels | falsified by: evidence that LLM comments have equal or lower false-positive rates than humans at similar defect-recall levels

3. How do LLMs and humans differ by defect class (security, concurrency, logic, API misuse, style-only, maintainability) in what they catch and miss? | evidence: stratified evaluation studies, bug-injection benchmarks, security-focused review benchmarks, labeled comment taxonomies | falsified by: results showing no meaningful class-level differences (LLMs and humans catch the same defect types at similar rates)

4. How does “effectiveness” change when human reviewers use LLM suggestions (human-in-the-loop) versus fully automated LLM comments alone versus humans alone? | evidence: A/B or field experiments measuring defects found, merge outcomes, and residual bugs under the three modes | falsified by: results showing automated LLM-only comments equal human-in-the-loop and human-only defect detection with no material quality or residual-defect gap

5. What is the precision–recall (or F1 / usefulness) tradeoff for LLM review tools at production settings used by teams, and how does that compare to typical human review outcomes? | evidence: tool evaluation papers, vendor-independent benchmarks, production telemetry with labeled outcomes | falsified by: production or independent benchmarks showing LLM tools dominate human precision–recall without tuning that collapses to style/nitpicking

6. How durable are LLM review findings over time—do reported effectiveness numbers hold across model generations, languages/frameworks, and codebases outside the original evaluation set? | evidence: multi-model replications, cross-project studies, longitudinal industrial reports, out-of-distribution evaluations | falsified by: consistent replication across models, languages, and orgs with stable effect sizes and no large distribution shift

7. What confounding factors bias comparisons (diff size, reviewer seniority, time budget, PR quality, static-analysis baseline, prompt/tooling, ground-truth labeling method)? | evidence: methodology sections of comparative studies, meta-analyses, critique papers, replication packages | falsified by: well-controlled multi-site studies that neutralize major confounds and still show a clear, consistent winner

8. What do independent practitioners and industrial deployments report about real defect catch rate, comment acceptance, and residual production incidents when adopting LLM code review? | evidence: practitioner postmortems, open engineering blogs with metrics, non-vendor case studies, incident analyses | falsified by: multiple independent deployments reporting sustained, measured increases in real-defect catch with reduced residual incidents and high comment acceptance without heavy human filtering

# Search perspectives

1. Academic empiricists (ICSE/FSE/ASE/TSE, MSR) — peer-reviewed controlled comparisons, ground-truth defect labels, replication packages; surfaces methods and effect sizes marketing omits

2. Vendor / product proponents (GitHub Copilot, CodeRabbit, Amazon CodeGuru, Cursor, Graphite, etc.) — claims, case studies, and product benchmarks; surfaces best-case advertised metrics to stress-test later

3. Skeptics and critical methodologists — replications, “LLM hype” critiques, papers on false positives in automated review, comments that style nits inflate “finding” rates; surfaces failure cases and measurement critique

4. Industrial practitioners (non-vendor eng blogs, SRE/incident writeups, open-source maintainer reports) — acceptance rates, time saved vs bugs missed in real workflows; surfaces operational reality beyond lab or marketing numbers

5. Security researchers and appsec reviewers — CWE/vuln-focused review benchmarks, SAST+LLM hybrid evaluations; surfaces whether “defects” include security issues humans often prioritize

6. Non-English / regional and non-Big-Tech sources (CN/EU/JP industry reports, local OSS communities, regulators/standards notes on AI-assisted SDLC) — different tooling stacks and evaluation norms; surfaces evidence not dominated by US vendor press cycles

# Likely failure mode

Marketing and product-blog metrics will dominate search results: vendor case studies and syndicated press releases report high “issues found” or “comment acceptance” without distinguishing real defects from style nits, without human-matched controls, and often without ground truth. Academic papers may use small, synthetic, or single-language benchmarks that do not transfer to production PRs; figures stale quickly as models change. Without adversarial filtering (skeptic replications, security-stratified labels, practitioner residual-bug data), a naive synthesis will overstate LLM effectiveness by counting noisy comments as defects found.
