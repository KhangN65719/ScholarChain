# Phase 05 gold labeling: pass 1 and pass 2 (evidence)

Recorded by the orchestrator, 2026-11-07, from the validator log `/Users/winnguyen/scholarchain-data/logs/gold-v2-validation.txt` (human run, read in full; validator = entity-resolution-engineer's `GoldValidator --mode v2`). Gold files stay outside the repo (see the preservation proposal in er-gold-validation-evaluation-report.md). Not committed.

## Which labels came from which pass
- `gold.jsonl` = attempt 1 / pass 1, all 230 pairs, labeled with the Codex suggestion CSV loaded and revealed: 95 same, 131 different, 4 unsure; 64 changed from the suggestion (human-reported). Kept unchanged.
- `gold-v2.jsonl` = 230 rows. 153 rows are pass-1 labels carried unchanged (same 89, different 62, unsure 2); 77 rows are pass-2 blind relabels (same 7, different 67, unsure 3), shown without suggestion, stratum or pass-1 label. Final totals 96 same / 129 different / 5 unsure.
- The 77 pass-2 pairs = every hard-common-name pair (77); every pair Codex marked "unsure" (49) is among them. The other 153 pairs (exact-id 77, clear-non-match 76) were NOT re-reviewed; their labels were made with suggestions at pass-1 pace.

## Timing of both passes (validator output, reported, not a pass/fail criterion)
- Pass 1 (230 rows, with suggestions): 2026-11-07T18:53:15Z to 18:59:44Z, span 6.5 min, median gap 0.7 s, 208 gaps under 2 s. The human judged this does not support "human-reviewed".
- Pass 2 (77 blind relabels): 21:42:39Z to 21:53:11Z, span 10.5 min, median gap 2.8 s, 30 gaps under 2 s (about 8 s per pair on average). More deliberate than pass 1, but 30 of the gaps are still under 2 s.

## Validator result: VALIDATION PASSED (0 failed, 1 skipped)
14 PASS lines including entry-count 230, loads-as-GoldLabel, unique ids both ways, gold-agrees-with-pairs, human-review-fields, dblp-snapshot dblp:2026-11-05, pass-field, pass1-file, pass2-is-subset (77 exactly the subset), subset-recomputed (77), pass2-blind (no suggestion on pass-2 rows), pass1-carried (153 equal to gold.jsonl), strata-key (76/77/77). SKIP label-totals (no expected totals given; observed 96/129/5). Kinds researcher 215, work 15. Reviewer khang-nguyen only. One row has a note. Snapshots: crossref:2026-11-07, dblp:2026-11-05, openalex:2026-11-07.

## Agreement (descriptive only; not a threshold input)
Exact agreement 72 of 77 (93.506%). Cohen kappa over same/different 1.000 on n=72 (5 pairs excluded for unsure; chance agreement 0.847, so the kappa is not informative about the same/different split: no pass-1/pass-2 same-vs-different flips occurred). Changes pass 1 -> pass 2: different->unsure 3, unsure->different 1, unsure->same 1. All 5 disagreements are in hard-common-name; they involve "unsure": 14eb1bd6... (unsure->different), 26037732... (different->unsure), 35516eb7... (unsure->same), 394ff8b1... (different->unsure), 65622c4e... (different->unsure). Caveat: the reviewer had seen all 77 pairs hours earlier (49 with a visible Codex "unsure"), so agreement partly reflects memory.

## Input checksums (SHA-256, as printed by the validator)
pairs.jsonl `be3aea1b754a10ce71bdbeeec0050cba168a19f0a6d9893c754ff10df15e3050`; gold-v2.jsonl `927c7623216288c72ac2c71e361214181d18963c1447058b9a334d6bbd7da613`; gold.jsonl `1cbbbf15d885080dfad03fe14baee165ac8dc6997607edf7d807b0e767f087e9`; pass2-subset.txt `560aeb35f6eaaf4a727dcc69d2d767c929fd659455dbf008f1cdd78785419414`; private/strata-key.jsonl `7a9bcd25ce6deabf2fc2e0c97eecb0327cf813a500305de6bc320d7c3cae3199`; scholarchain-codex-suggestions.csv `e65f1a411dd461553c9855a3da279d770a02efa724cb53c0cdd510ae9faa1b0b`. Outputs: `/Users/winnguyen/scholarchain-data/gold/phase5/eval-v2/gold-validation.txt` and `.json`.

## Honest limits
The evaluation set mixes 153 carried pass-1 labels (made with suggestions, fast) and 77 blind pass-2 labels. The evaluation report must show them separately; metrics on the carried rows should be read as "labels made with an external suggestion visible". Single reviewer. Stratified, not random.
