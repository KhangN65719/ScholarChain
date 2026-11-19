# Phase 16 review 12 verdict (re-review of review 11 finding A and B)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES.** Finding A RESOLVED (with D); finding B RESOLVED; one new LOW finding D.

- A: wording matches `AnswerEval.scala:285-296` and `Subsets.stratified` (`RetrievalData.scala:146-161`) in `experiments/h-answers/README.md:77` and `experiment-h-answers.manifest:51-56`. Reviewer's count of `labels-v1.jsonl`: 8 unanswerable (41-48, `abstain:true`); free-form 6 answerable (49-54), 55-56 `abstain:true`; 7 answerable strata (researcher-lookup 7, coauthor-lookup 8, ranking 10, shortest-path 4, community-component 6, temporal-comparison 5, free-form 6); 20 picks → 3 per stratum, one gets 2; ranking stratum 2-3; free-form 3 of 6, two with `operation:"ranking"` (53, 54), so 0-2. "About 4" fair; range 2-5. Arithmetic as written correct. Manifest values `:78-95` equal the JSON copy; comment-only change pending a diff.
- B: `human-commands.md:58` uses `--run-id h4-diagnostic-4` and `phase16-h4-diagnostic-4.log`; arguments match `experiments/h-answers/README.md:43`; line 56 records `-3` exists.
- **D. LOW, experiment-engineer.** `README.md:77`, manifest `:49-55`: rule 6 applies on a ranking fact (`Synthesis.scala:112-113`), not by stratum; the primary worst case counts 10 and omits the 2 answerable free-form ranking questions while the repeat estimate counts them. Consistently: primary 12 → 0.66 → 2.75; + 0.22 → about 2.97 (headroom about 0.03); upper bound 5 → about 3.03, over 3.00. Spend cannot exceed the ceiling (USD stop before each call), but "under 3.00 with about 0.14 headroom" is unsupported. Fix: count 12 and state about 2.97 with the upper case hitting the USD stop (repeat cut), or explain the exclusion. Not verified whether lines 53-54 and the bridge-analysis question at line 21 produce a ranking fact (planner-dependent).
- Observation (optional): manifest `:52` "rejected twice and then accepted" vs README `:77` "once or twice".

Reviews 10-12 not closed until D. Observation C recorded as skipped by the orchestrator. Pending: manifest diff; human `sbt clean check`; commit and a new approval record; `h4-diagnostic-4`.

---

Orchestrator note (2026-11-18): the manifest diff was supplied (`git diff -- experiments/manifests/experiment-h-answers.manifest`): one hunk at `@@ -45,6 +45,16 @@`, ten added lines, every one a `#` comment; no value line changed. D routed to experiment-engineer (W2-RB10).
