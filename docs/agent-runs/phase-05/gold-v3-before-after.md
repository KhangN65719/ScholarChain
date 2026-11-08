# Phase 05 gold-v3 evaluation: before and after repair round 1 (labels unchanged)

Recorded by the orchestrator, 2026-11-07, from the human-run log `/Users/winnguyen/scholarchain-data/logs/gold-v3-evaluation-after.txt` (5:04 PM; the human pasted the last 40 lines; `[success]`). Same gold (`gold-v3.jsonl`, labels unchanged), same pairs and the same v5 records (v6 output is byte-identical to v5: `diff -rq` empty). Only the matcher changed: policy `scholarchain.er.policy/1.1.0` (before, `eval-v3/`) -> `1.2.0` (after, `eval-v3-after/`), adding the same-DOI plus differing-author-position rule. No threshold, weight or label changed.

## Side by side
| | Before (policy 1.1.0) | After (policy 1.2.0) |
|---|---|---|
| precision | 1.000 | 1.000 |
| recall | 0.802 | 0.802 |
| F1 | 0.890 | 0.890 |
| gold same: accepted / rejected / unresolved / conflict | 77 / 14 / 4 / 1 | 77 / 14 / 4 / 1 |
| gold different: accepted / rejected / unresolved / conflict | 0 / 109 / 14 / 8 | 0 / 109 / 14 / 8 |
| gold unsure: accepted / rejected / unresolved / conflict | 0 / 1 / 2 / 0 | 0 / 1 / 2 / 0 |

## Decisions that changed (matcher comparison, descriptive only)
3 of 230 decisions changed (outcome, path, score or reasons); no pair appears in only one file. All three are REJECTED -> REJECTED on the deterministic-bibliographic path, score 0.020 -> 0.020; only the reason changed from `rejected.family-name-mismatch` to `rejected.same-doi-distinct-position`; all three are clear-non-match pairs with gold label "same" (among the 12 pairs the human considers likely labeling errors):
- 06755b8ca8d4ca92eee6616a9a117c24 (Chenyang Zhou | Yice Cao)
- 8de930a16bd2d4017feb5d6d129ceede (Wei Wang 0169 | Wangmeng Zuo)
- e97c4bab886f838f489a21e4e420eb26 (Rakesh Tamrakar | Wenhong Chen)

## Reading this honestly
- No outcome changed and no metric moved. The gold sample contained no pair on which the new rule changes an outcome: the sampled pairs that match the rule were already rejected by the family-name rule. The scenario the rule was written for (two same-name coauthors of one paper from two sources merged by the probabilistic path, hand-calculated score about 0.978) is covered only by synthetic fixtures, because the export excluded the 5,766 same-name agreeing-context pairs. This evaluation therefore does not show the fix working or failing on real data.
- For the three changed pairs, the new reason (same DOI, differing author position) says the two mentions are different authors of one work, which is consistent with the human's view that "same" was a labeling error. That is descriptive; the labels are not corrected.
- Policy version difference between the two reports was flagged by the tool (WARNING: 1.1.0 against 1.2.0), as expected.
- The comparison's label-change block, run against the v3 report's own error list: 4 of 153 pass-3 labels changed relative to pass 1; 2 of them are among the 20 pass-3 pairs listed in the v3 error section (762217ea, eb081006) and 2 are not (5a23494f, e2f2b0ad). The earlier v2-to-v3 comparison counted the same 4 changes against the v2 error list (22 listed pairs, all 4 changes among them); the two counts differ only because the reference error list differs.
- The uncertainty section now includes: "Export manifest: includeAgreeingContext false; 5766 same-name agreeing-context pairs were excluded from the sample", and the by-pass text still states that 153 labels are a fast blind re-review and 77 were deliberate.

## Outputs (outside the repo)
`/Users/winnguyen/scholarchain-data/gold/phase5/eval-v3-after/{decisions.jsonl,evaluation-report.txt,evaluation-report.json}`. Copied byte-identical into `docs/agent-runs/phase-05/gold/eval-v3-after/` and listed in GOLD-MANIFEST.json together with the evaluation log and `v6-pipeline.txt` under `gold-support/logs/` (checksums printed by the human's `find ... shasum`).
