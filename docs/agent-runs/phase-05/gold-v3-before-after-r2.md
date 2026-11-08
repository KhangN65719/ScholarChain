# Phase 05 gold-v3 evaluation across policy versions 1.1.0, 1.2.0, 1.3.0 and 1.4.0 (labels unchanged)

Recorded by the orchestrator, 2026-11-07, from the human-run log `/Users/winnguyen/scholarchain-data/logs/gold-v3-evaluation-r2.txt` (5:24 PM; the human pasted the last 45 lines; `[success]`). Same gold (`gold-v3.jsonl`, labels unchanged), same pairs and the same v5 records (v6 byte-identical to v5). Only the matcher changed. The comparison baseline is `eval-v3-after/evaluation-report.json` (sha256 `41f1387e...806dc`, policy 1.2.0), and `--compare-decisions` used `eval-v3-after/decisions.jsonl` (sha256 `6938fa4f...f7e30d`). Thresholds and weights unchanged in all three versions.

## Side by side
| | 1.1.0 (`eval-v3`) | 1.2.0 (`eval-v3-after`) | 1.3.0 (`eval-v3-r2`) |
|---|---|---|---|
| precision / recall / F1 | 1.000 / 0.802 / 0.890 | 1.000 / 0.802 / 0.890 | 1.000 / 0.802 / 0.890 |
| gold same: accepted / rejected / unresolved / conflict | 77 / 14 / 4 / 1 | 77 / 14 / 4 / 1 | 77 / 14 / 4 / 1 |
| gold different: accepted / rejected / unresolved / conflict | 0 / 109 / 14 / 8 | 0 / 109 / 14 / 8 | 0 / 109 / 14 / 8 |
| gold unsure: accepted / rejected / unresolved / conflict | 0 / 1 / 2 / 0 | 0 / 1 / 2 / 0 | 0 / 1 / 2 / 0 |

## Decisions that changed
- 1.1.0 -> 1.2.0: 3 of 230 (06755b8c, 8de930a1, e97c4bab), REJECTED -> REJECTED, reason `rejected.family-name-mismatch` -> `rejected.same-doi-distinct-position` (see `gold-v3-before-after.md`).
- 1.2.0 -> 1.3.0: 1 of 230. `pair:v1:adcec88cc2041c4fc1f3526bfae1f82b` (Zhengyuan Yang | Lei Zhang, clear-non-match, gold=same), REJECTED -> REJECTED, deterministic-bibliographic, score 0.020 -> 0.020, reason `rejected.family-name-mismatch` -> `rejected.same-arxiv-distinct-position`. No pair changed to or from CONFLICT, so the new position-CONFLICT rung changed nothing in this sample.
- Cumulative from 1.1.0 (inferred from the two comparisons, not a direct 1.1.0 vs 1.3.0 run): 4 pairs changed reason code, all REJECTED -> REJECTED, all gold=same clear-non-match pairs that the human considers likely labeling errors; no outcome and no metric changed.

## Reading this honestly
- Nothing moved in the outcomes. The gold sample (which excluded 5,766 same-name agreeing-context pairs) never exercises the failure mode the new rungs target, so this evaluation does not show them working or failing on real data. The arXiv key did join real records on one pair (adcec88c: the DBLP CoRR record `journals/corr/abs-2603-05518` with OpenAlex `W7134253765`), changing only the reason code of an already-REJECTED pair; the position-CONFLICT rung did not fire on any real pair. Their behaviour on the same-name coauthor failure mode is covered by synthetic fixtures only.
- The evaluator's uncertainty text now includes the finding-2 disclosure ("Pooled precision does not measure the probabilistic path when the export left out pairs whose context agrees ... A high pooled precision therefore says nothing about the probabilistic or deterministic-bibliographic accept paths") and the export manifest line "includeAgreeingContext false; 5766 same-name agreeing-context pairs were excluded from the sample".
- The label-change block against the 1.2.0 report is unchanged (4 of 153 pass-3 labels changed; 2 among the 20 listed pairs, 2 among the 133 not listed), as expected since labels are the same.
- The tool flagged the policy version difference (1.2.0 against 1.3.0), as expected.

## Policy 1.4.0 (last repair round): `eval-v3-r3`, 5:56 PM
Human-run log `/Users/winnguyen/scholarchain-data/logs/gold-v3-evaluation-r3.txt` (the human pasted the last 45 lines; `[success]`), compared against `eval-v3-r2` (report sha256 `b4876fcb...5987`, decisions sha256 `05726ed4...9bbf`). Pooled precision 1.000, recall 0.802, F1 0.890 and the same decision matrices as 1.1.0, 1.2.0 and 1.3.0. Decisions changed 1.3.0 -> 1.4.0: **0 of 230** (no outcome, path, score or reason changed). So the same-record ORCID CONFLICT rule changed nothing in this sample: no frozen-sample pair has an agreeing ORCID on two slots of one record without an identifier. The rule is covered by synthetic fixtures only (`SameRecordIdAgreementSuite`, `AdversarialErSameRecordSuite`). Cumulative since 1.1.0: 4 pairs changed reason code (REJECTED -> REJECTED), no outcome or metric changed. The uncertainty text carries the finding-2 disclosure and the export-manifest line (5,766 excluded same-name agreeing-context pairs).

## Outputs
Archived byte-identical under `docs/agent-runs/phase-05/gold/` (`eval-v3-after/` 1.2.0, `eval-v3-r2/` 1.3.0, `eval-v3-r3/` 1.4.0) with their logs and the full `sbt check` outputs under `gold-support/logs/`, all listed in `GOLD-MANIFEST.json`. The originals are outside the repo:
`/Users/winnguyen/scholarchain-data/gold/phase5/eval-v3-r2/{decisions.jsonl,evaluation-report.txt,evaluation-report.json}`; log `/Users/winnguyen/scholarchain-data/logs/gold-v3-evaluation-r2.txt`. Archived byte-identical under `docs/agent-runs/phase-05/gold/eval-v3-r2/` (with the log and `sbt-check-full.txt` under `gold-support/logs/`) and listed in GOLD-MANIFEST.json from the checksums the human printed.
