# Phase 05 gold-set evaluation on gold-v2 (record)

Run by the human, 2026-11-07 3:55 PM, with `scholarchain.er.eval.assess.EvaluationRun` (entity-resolution-engineer's tool). Log: `/Users/winnguyen/scholarchain-data/logs/gold-v2-evaluation.txt` (read in full by the orchestrator). Outputs: `/Users/winnguyen/scholarchain-data/gold/phase5/eval-v2/{decisions.jsonl,evaluation-report.txt,evaluation-report.json}`. Policy `scholarchain.er.policy/1.1.0` (accept 0.95, reject 0.35, band [0.70, 0.95)); thresholds are the shipped provisional values, not tuned on this set. No code, threshold or rule was changed from this evaluation (human instruction).

Inputs (SHA-256): gold-v2 `927c7623...da613`, pairs `be3aea1b...3050`, records directory digest `646dbc69...4608`, strata key `7a9bcd25...3199`. The records digest equals the export manifest's recordsSha256 prefix `646dbc69...4608`.

## Headline numbers (verbatim from the log)
- Pooled (230 pairs; 5 unsure excluded): same -> ACCEPTED 77, REJECTED 14, UNRESOLVED 4, CONFLICT 1; different -> REJECTED 108, UNRESOLVED 13, CONFLICT 8, ACCEPTED 0; unsure -> REJECTED 2, UNRESOLVED 3. Precision 1.000 [0.952, 1.000]; recall 0.802 [0.711, 0.869]; F1 0.890. False positives: 0.
- By pass: pass 1 (153 carried, made with suggestions, NOT re-reviewed): precision 1.000, recall 0.865 [0.779, 0.921], F1 0.928. Pass 2 (77 blind): 0 same accepted; recall 0.000 [0.000, 0.354] on 7 same pairs (3 REJECTED, 4 UNRESOLVED); different 54 REJECTED + 13 UNRESOLVED.
- By stratum: exact-id 77/77 accepted (precision and recall 1.000, by construction of the stratum rule); clear-non-match 76 pairs: same 11 rejected + 1 conflict, different 54 rejected + 8 conflict; hard-common-name 77 pairs: same 3 rejected + 4 unresolved, different 54 rejected + 13 unresolved.
- Researcher (210 scored): precision 1.000, recall 0.765, F1 0.867, coverage 0.876, unresolved-rate 0.081, conflict-rate 0.043. Paths: exact-id accepted 62 (TP 62, FP 0); deterministic-bibliographic rejected 18 (TN 9, FN 9, NPV 0.500); probabilistic rejected 104 (TN 99, FN 5, NPV 0.952); llm-adjudicated 0. Work (15 scored): all 15 accepted via exact-id; precision, recall, F1 1.000; fewer than 10 false positives/negatives available.
- Sensitivity: every accept-threshold row 0.90 to 0.98 is identical (62 accepted, precision 1.000, recall 0.765 for researchers); the reject sweep changes only the unresolved rate (0.210 at 0.20 down to 0.067 at 0.45 and 0.50).
- Adjudication band: UNRESOLVED in band by gold: same 4, different 6, unsure 1. "eligible for adjudication 11, abstained by offline-stub/abstain-1: 11, model calls 11."

## Correction of a premise
The LLM-adjudicated path was not "empty": 11 pairs were eligible in the band and the offline stub (no model, no credentials) abstained on all 11, so none was decided and they stayed UNRESOLVED. The path shows 0 decided pairs.

## Findings for a later phase (not acted on)
Only the exact-id path accepts; deterministic-bibliographic rejected 9 pairs labeled same (NPV 0.500) with `rejected.family-name-mismatch`; the LLM path decided 0 pairs; affiliation and citation features are missing in every listed error. Error analysis with evidence per pair: see the error-analysis report (entity-resolution-engineer) archived next to this file.

## Limits (from the report)
n = 230, 5 unsure excluded; stratified, not random; Wilson intervals ignore stratification and author clustering; recall over candidate pairs only; single reviewer who saw suggestions on 153 carried labels (not re-reviewed); DBLP homonym suffixes mean many DBLP-DBLP labels follow DBLP's own disambiguation. A threshold change chosen from this sweep would be tuned on the evaluation set and needs a separate sample.
