# Phase 05 gold-v3 (final gold set): validation, evaluation, known limitation

Recorded by the orchestrator, 2026-11-07, from the human-run logs `/Users/winnguyen/scholarchain-data/logs/gold-v3-validation.txt` (4:23 PM) and `gold-v3-evaluation.txt` (4:25 PM), both read in full. Tools: entity-resolution-engineer's `GoldValidator --mode v3` and `EvaluationRun --compare-with`. Decision (human): `gold-v3.jsonl` is the final gold set. No threshold, rule or policy change was made from any of this.

## Gold set composition
- `gold-v3.jsonl` (SHA-256 `6e5342ddd62c7e0904020a2fb6be37ca5fda6bf985db255ee65ce538e06d2a96`): 230 pairs. 77 pass-2 labels carried unchanged from `gold-v2.jsonl` (blind relabel of the hard-common-name pairs, same 7 / different 67 / unsure 3) and 153 pass-3 labels (blind re-review of the pass-1 pairs, same 89 / different 64 / unsure 0). Totals same 96 / different 131 / unsure 3. No label in the file was made with a suggestion visible.
- Earlier attempts kept unchanged: `gold.jsonl` (pass 1, with suggestions; `1cbbbf15...087e9`), `gold-v2.jsonl` (`927c7623...da613`). Pass-3 subset `bcd449f9db0350e51768d95164047fdafbea0521639be7b3bcd9437c2ec83e1c`; pass-2 subset `560aeb35...9414`; pairs `be3aea1b...3050`; strata key `7a9bcd25...3199`; records digest `646dbc69...4608`.

## Validation: VALIDATION PASSED (0 failed, 1 skipped)
17 PASS lines including pass3-is-subset (153 exactly the subset), subsets-partition (77 + 153 = 230, disjoint and complete), pass3-blind, pass2-carried, no-suggestion-labels (230 rows). SKIP label-totals (no expected totals given; observed 96/131/3). Reviewer khang-nguyen. Snapshots dblp:2026-11-05, openalex:2026-11-07, crossref:2026-11-07.

## Timing of the three passes (validator output)
| Pass | Rows | Span | Median gap | Gaps under 2 s |
|---|---|---|---|---|
| 1 (with suggestions) | 230 | 6.5 min | 0.7 s | 208 |
| 2 (blind) | 77 | 10.5 min | 2.8 s | 30 |
| 3 (blind) | 153 | 5.3 min | 0.6 s | 143 |

Pass 1 vs pass 3 on the 153 pairs (descriptive only): exact agreement 149 of 153 (97.386%), Cohen kappa 0.973 (n 151, 2 unsure excluded, chance agreement 0.516); changes different->same 1, same->different 1, unsure->different 2. Stratum: exact-id 77/77 agree; clear-non-match 72 of 76 agree. Disagreements: 5a23494f (unsure->different), 762217ea (same->different), e2f2b0ad (unsure->different), eb081006 (different->same).

## Known limitation (disclosed by the human)
Pass 3 was fast (median gap 0.6 s, 143 of 152 gaps under 2 s, about 2 s per pair on average), as fast as pass 1. The human states that in pass 3 shared-coauthor evidence was applied even when the author names were clearly different, so the 12 clear-non-match pairs labeled "same" are likely labeling errors, not matcher misses. Consequences, stated plainly:
- Only the 77 pass-2 labels were made at a deliberate pace (median 2.8 s). The 153 pass-3 labels reproduce pass 1 in 97.4% of cases at the same speed; the validator's "reviewed blind" only means no suggestion was shown, not that each pair was carefully examined. The "at least 200 human-reviewed pairs" gate item is therefore met in form (230 human-labeled, no suggestion visible) but weakly in substance for 153 of them.
- Memory and a consistent reviewer rule (shared coauthor implies same) may drive the carried agreement. The agreement figures must not be read as independent confirmation.
- The labels are NOT corrected. The evaluation below uses them as recorded.

## The 12 clear-non-match pairs labeled "same" in gold-v3 (matcher did not accept; likely labeling errors per the human)
Pass-3 labels (from the evaluation log):
1. 05c6330738dfc4f523c0ce2304dcd497, Rong Yu | Siming Wang (REJECTED, deterministic-bibliographic, `rejected.family-name-mismatch`)
2. 06755b8ca8d4ca92eee6616a9a117c24, Chenyang Zhou | Yice Cao (same)
3. 28f6b156e4d694c4cb793cdc89921743, Xin Li 0110 | Liang He 0001 (same)
4. 480545b62bc2b67520e9e0db7c71226c, Xuemin Lin 0001 | Lu Qin 0001 (same)
5. 605df74e7dad02bbe77f6ebbfa15036b, Zhangyong Tang | Hui Li 0037 (same)
6. 67778fa30fa4483d2e81c4c32e72f5a0, Peng Li | Yang Xu (same)
7. 8de930a16bd2d4017feb5d6d129ceede, Wei Wang 0169 | Wangmeng Zuo (same)
8. adcec88cc2041c4fc1f3526bfae1f82b, Zhengyuan Yang | Lei Zhang (same)
9. e97c4bab886f838f489a21e4e420eb26, Rakesh Tamrakar | Wenhong Chen (same)
10. 576d0cd7481bc63b3500ce95fb6026e9, Jinsheng Yang | Jigang Yang (REJECTED, probabilistic, score 0.016)
11. d88236f5592c69fc7940636b45995409, Yiteng Huang | Yanru Huang (REJECTED, probabilistic, score 0.016)
12. eb081006b2c33feb3924090dd1c8dc46, Kuai Yu | Mingkai Chen (CONFLICT, exact-id, `conflict.id:orcid`; pass 1 said different, pass 3 changed it to same)
Items 1-9 are the nine pairs analyzed in `gold-v2-error-analysis.md` (same paper or coauthor pairs with different names, undirected `name-coauthor` blocks); items 10-11 are the two probabilistic ones there. Item 12 is new relative to v2 (pair 762217ea, Shuo Li 0001 | Shaohui Li, with an ORCID conflict, was changed same->different in pass 3 and is now counted as a correct CONFLICT).

## Evaluation of gold-v3 (policy 1.1.0, accept 0.95, reject 0.35, band [0.70, 0.95); thresholds not tuned)
- Pooled: same -> ACCEPTED 77, REJECTED 14, UNRESOLVED 4, CONFLICT 1; different -> REJECTED 109, UNRESOLVED 14, CONFLICT 8; unsure -> REJECTED 1, UNRESOLVED 2. Precision 1.000 [0.952, 1.000], recall 0.802 [0.711, 0.869], F1 0.890 (identical to v2).
- By pass: pass 2 (77): 0 same accepted of 7 (3 rejected, 4 unresolved), recall 0.000 [0, 0.354]. Pass 3 (153): precision 1.000, recall 0.865 [0.779, 0.921], F1 0.928.
- Researcher (212 scored, 3 unsure excluded): precision 1.000, recall 0.765, F1 0.867, decided-recall 0.816, coverage 0.873, unresolved 0.085, conflict 0.042. Paths: exact-id accepted 62 (FP 0); deterministic-bibliographic rejected 18 (TN 9, FN 9, NPV 0.500); probabilistic rejected 105 (TN 100, FN 5, NPV 0.952); llm-adjudicated 0 decided. Work (15): all accepted via exact-id, 1.000.
- Strata: exact-id 77/77 accepted (by construction); clear-non-match recall 0.000 [0, 0.243]; hard-common-name recall 0.000 [0, 0.354].
- Adjudication band: 11 eligible (gold same 4, different 7, unsure 0); offline stub abstained on all 11; 11 model calls (stub). Path decided 0 pairs.
- Sensitivity: accept 0.90-0.98 identical (62 accepted); reject sweep changes only the unresolved rate (0.212 at 0.20 down to 0.071 at 0.45 and 0.50). No change recommended.
- Comparison against v2 (report sha256 `694e8d71...e7bd`): pooled precision, recall, F1 and the same-row identical; labels changed in 4 of 153 pass-3 pairs, all 4 among the 22 pass-3 pairs listed as errors in the v2 report; 0 changes among the 131 not listed. Note: the human had seen the v2 error list before pass 3, so this concentration cannot be read as independent evidence about label quality either way.

## What this evaluation does not measure (review round 1, finding 2; human rulings 2026-11-07)
- The export used `includeAgreeingContext:false`: 5,766 same-name pairs with agreeing context were excluded (`private/export-manifest.json`). The exact-id stratum is selected by shared-identifier agreement, the same rule the exact-id path accepts on, and all 77 accepts come from it. Pooled precision 1.000 therefore does not measure the probabilistic or deterministic-bibliographic paths; no probabilistic accept was ever sampled.
- Gold-review gate (human ruling): met with a disclosed limitation. 230 blind human labels by the reviewer; only the 77 pass-2 labels were made at a deliberate pace; pass 3 was fast.
- Review round 1 found a matcher flaw this sample could not reveal (same-name coauthors of one paper merged across sources, finding 1); the repair (policy 1.2.0) is done; before/after numbers on unchanged labels are in `gold-v3-before-after.md` (metrics identical; 3 of 230 decisions changed in reason code only; the fix is not demonstrated on real data).

## Sensitivity bound (descriptive, not a result)
The 12 pairs above are all counted as matcher misses in the numbers. If all 12 were instead "different", recall would be 77/84 = 0.917. This is only a bound: choosing which labels to revise after seeing matcher output is the selective correction the human wanted to avoid, so gold-v3 stays as recorded and the reported recall is 0.802.

## Findings carried to a later phase
Only the exact-id path accepts; deterministic-bibliographic NPV 0.500 (9 rejects labeled same); LLM path decided 0 pairs (band non-empty, stub abstained); `affiliation.compat` and `citation.shared` missing in every error line; candidate generation pairs authors with their own coauthors (`name-coauthor` blocks). A properly deliberate re-review of the 153 carried pairs, and a fresh held-out sample, would be needed before any threshold calibration. Thresholds stay provisional.
