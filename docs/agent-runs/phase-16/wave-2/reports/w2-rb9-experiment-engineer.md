# W2-RB9: experiment-engineer report (review 11 finding A, docs/comments only)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** No `.scala` edit, no sbt, no AWS, no commit. Files: `experiments/h-answers/README.md:77`; `experiments/manifests/experiment-h-answers.manifest` comment lines 51-52 (now 51-56). JSON copy untouched; no value changed; manifest digest changes (no approval record yet).

README:77 now: only an answer whose 3 attempts all fail abstains `synthesis-failed` and leaves the repeat pass; a reply rejected once or twice then accepted is answered, can be picked for the repeat (`AnswerEval.scala:285-296`) and cost up to 3 calls again; the repeat takes 20 questions round-robin over the strata of eligible questions (`Subsets.stratified`); with about 7 answerable strata, about 3 per stratum, so about 3 ranking-stratum + about 1 free-form ranking ≈ 4 (estimate); 4 × 2 extra × 0.0275 ≈ 0.22 → 2.64 + 0.22 ≈ 2.86 USD worst case, under 3.00 with about 0.14 headroom; the USD stop is checked before each call and may end the run early; the 20-question repeat is the part it would cut. Manifest comments say the same, marked ESTIMATE.

Derivation: eligible = label not an abstention and primary answer usable and not abstained; shuffled with the seed; `Subsets.stratified(…, stratum, 20)` (`RetrievalData.scala:139-161`) takes one per stratum in turn. 6 answerable generated strata + free-form ≈ 7; 20/7 ≈ 3 each. Strata in `labels-v1.jsonl` not counted (grep refused). Sensitivity (not in files): each extra ranking question in the repeat adds 0.055; 6 would reach ≈ 2.97.
