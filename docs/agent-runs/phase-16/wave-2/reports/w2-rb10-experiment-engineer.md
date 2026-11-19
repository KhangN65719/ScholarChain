# W2-RB10: experiment-engineer report (review 12 finding D, docs/comments only)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only comments and docs: `experiments/h-answers/README.md:77`; `experiments/manifests/experiment-h-answers.manifest` comment lines 48-57. No `.scala`, sbt, AWS, commit or value change; JSON copy untouched. Files not re-read after editing.

| | Before | After |
|---|---|---|
| Primary count | 10 ranking-stratum | 10 + 2 answerable free-form ranking (`labels-v1.jsonl` 53, 54) = 12 |
| Primary arithmetic | 10 × 2 × 0.0275 = 0.55 → 2.64 | 12 × 2 × 0.0275 = 0.66 → 2.75 |
| Repeat count | about 4 | about 4, range 2-5 (ranking stratum 2-3 of 10; free-form 0-2 of its 2, among 3 picked of 6) |
| Repeat arithmetic | +0.22 → 2.86, headroom 0.14 | +0.22 → 2.97, headroom 0.03 |
| Upper bound | none | 5 → +0.275 → 3.03; the USD stop (before each call) ends the run and cuts the repeat; spend cannot exceed 3.00 |
| Rule 6 scope | by stratum | by evidence (`Synthesis.scala:112-113`, `shape.rankingEntries`) |
| Planner caveat | none | lines 53-54 and a bridge-analysis question producing a ranking fact depends on the planner (not verified) |
| Wording | README "once or twice"; manifest "twice" | both "once or twice and then accepted" |

Assumptions: 0.0275 per call and 2.09 projection not re-derived; line references from the work order, not checked. Risk: headroom about 0.03; at the upper bound the repeat is cut (intended USD-stop behaviour).
