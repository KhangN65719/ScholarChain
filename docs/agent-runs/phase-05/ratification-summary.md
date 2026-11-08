# Phase 5 (entity resolution): summary for ratification

Prepared by the orchestrator, 2026-11-07, for the human architect. This is a summary of evidence, not a ratification; ratification and commits belong to the human. Nothing was committed.

## Gate evidence
- Recorded human gate runs: twelve `sbt` runs listed in `gate-sbt-clean-check.md`. The final repair-round run (eleventh, `sbt clean check`, 5:56 PM) printed four summary lines, all `Failed 0, Errors 0`: 41, 240, 366, 207. The final run (twelfth, `sbt check`, 6:01 PM) ended `[success]` with hadoop 207.
- `.claude/.last-test-run`: confirmed by the human's `ls -l`: `-rw-------  1 winnguyen  staff  0 Nov  7 18:01`, i.e. modified at 6:01 PM, the completion time of the final `sbt check` (6:01:27 PM). The file is empty by design (the hook compares its modification time). The orchestrator cannot read the mtime itself (the guard refuses `ls` on that path). Whether it is newer than every source file was not compared directly; no Scala source was edited after the eleventh run and only docs under `docs/agent-runs/` changed after the final check.
- Dataset checks: Python unit tests 90 OK; `datasets.py validate` passes; `register` digests unchanged. Phase 4 pipeline v6 byte-identical to v5 (`diff -rq` empty).
- Four independent review rounds (review-verdict-round1/round2/round3.md and the final re-review in this session): the last found no code defects; its three LOW findings were evidence items, fixed.

## What Phase 5 delivered
- Larger real-data input: a DBLP subset extracted from the official 2026-11-05 dump (3,472 works = 1,972 seed + 1,500 coauthor; per-name cap 200, one definition for extractor, receipt and validator), enriched with OpenAlex 396, Crossref 251 and ORCID 100 rows; provenance pins DBLP 2026-11-05 and the other sources 2026-11-07.
- Hadoop repairs: DBLP named-entity handling, non-Latin folding that keeps original characters, `MatchCandidate` moved to core with no wire change, per-dataset output handling, snapshot validation and stricter tool arguments, Crossref free-text affiliations no longer read.
- Entity resolution: matcher with policy `scholarchain.er.policy/1.4.0` (accept 0.95, reject 0.35, adjudication band [0.70, 0.95); thresholds hand-set and provisional, never tuned on the gold set): authoritative-ID conflicts, ORCID-only researcher conflicts, DOI mismatch conflicts for works, agree-but-contradicts CONFLICT, same-DOI/arXiv differing-position rule, position and same-record CONFLICT; strict-schema LLM adjudication with an offline stub only (no HTTP client, no credentials, no paid call); gold-set evaluator separate from the matching code, with structural leakage tests.
- Gold set: `gold-v3.jsonl`, 230 blind human labels (same 96, different 131, unsure 3), archived byte-identical with `GOLD-MANIFEST.json`; evaluations under policies 1.1.0 to 1.4.0 archived; labeling tool, exporter, validators, comparison tooling.

## Results (gold-v3, unchanged across all four policy versions)
Precision 1.000 [0.952, 1.000], recall 0.802 [0.711, 0.869], F1 0.890. Only the exact-id path accepts (77 of 77, including 15 works); precision on that path holds by construction. Four pairs changed reason code (REJECTED to REJECTED) across the repairs; no outcome and no metric changed. The LLM path decided 0 pairs (11 eligible, stub abstained).

## Known limitations (disclosed; see open-items.md)
- Gold quality: only the 77 pass-2 labels were made at a deliberate pace; pass 3 (153 labels) was fast (median 0.6 s); 12 clear-non-match pairs labeled "same" are likely labeling errors and were not corrected; one reviewer; labels may be anchored by earlier exposure to suggestions. Human ruling: the gate "gold labels are human-reviewed" is met with this disclosed limitation.
- Coverage: the export excluded 5,766 same-name agreeing-context pairs, so pooled precision does not measure the probabilistic or deterministic-bibliographic accept paths; the sample is stratified, not random; recall is over candidate pairs only. The newest rules (arXiv key, position CONFLICT, same-record CONFLICT) are exercised by synthetic fixtures only; the real sample showed one reason-code change.
- Thresholds, priors and weights are hand-set and unvalidated; calibration needs a fresh held-out sample after a deliberate re-review.
- Matcher: two same-name coauthors listed in a different order by two sources can be falsely REJECTED by the position rule.
- Hadoop: candidate generation pairs authors with their own coauthors (undirected `name-coauthor` blocks); `candidate-features` fails if blocking emits nothing; no resolved-entity counter; cross-script names not matched.
- Evidence: the hadoop-engineer repair-round-2 report body never arrived (only its observed results are recorded); untracked files cannot be diffed against HEAD (formatter collateral inside new files, "no test weakened", "weights unchanged" rest on tests and agent statements); the dataset `retrieved: 2026-11-07` is inferred from the dump file's mtime and the release-specific DBLP URL is UNVERIFIED.

## Deferred rulings (human, later phase)
1. Non-DOI work-level identifier disagreements (OpenAlex work ID, Semantic Scholar paper ID, repository URL, arXiv ID): keep producing CONFLICT; unruled.
2. Agreeing aggregator ID on two slots of one record with no DOI and no arXiv id: stays REJECTED (with a DOI or arXiv id it is already CONFLICT).

## Before committing (human)
Include the untracked sources: `datasets/scripts/{slicetool,subsettool,test_slicetool,test_subsettool}.py` (`subsettool.py` imports `slicetool.py`), `datasets/subset/`, `modules/core/.../pairing/`, `modules/entity-resolution/src/{main,test}/scala/scholarchain/`, the new hadoop and tests/adversarial suites, and `docs/agent-runs/phase-05/` (including `gold/` and `gold-support/`). Leave out `docs/agent-runs/.DS_Store`; the retired `datasets/slice/` files are untracked leftovers (delete or leave out). The repo is private, so the reviewer handle and local paths in the archive stay.
