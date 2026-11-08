# entity-resolution-engineer report: gold validation and evaluation tooling

Author: entity-resolution-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. Edits only under modules/entity-resolution/**; no commit, no LLM, no credentials. Nothing has been run on the real files yet; no real-data metrics exist in this report.

## Added
- `eval/validation/GoldValidator.scala` (14 independent checks, main), `eval/assess/{PairDecisions,GoldEvaluation,EvaluationRun}.scala`, tests GoldValidatorSuite (25) and GoldEvaluationSuite (25).
- Evaluator had no real-data entry point before; `EvaluationRun` decides the 230 pairs via `RecordIndex.fromCore(graph-export/works/v1)` and `Policy.v1` (equivalent to `Matcher.resolve` because `Matcher.decide` ignores candidate features/block key). Decisions are written before gold and the strata key are opened. `PairDecisions.decide` takes only pairs, index, policy, run info. OfflineStubModel only; in-band pairs stay UNRESOLVED. No threshold flags.
- Report: three-way table incl. unsure, per-kind P/R/F1, Wilson 95% intervals, per-stratum, band table, errors by pair_id (false accepts first), sweep (accept 0.90-0.98, reject 0.20-0.50), uncertainty statement.
- New packages for test-engineer: `scholarchain.er.eval.validation`, `scholarchain.er.eval.assess` (suggested: add validation to the LabelingLeakageSuite "does not read matcher output" list; check `eval/assess` is referenced only from the eval tree).

## Agent's `sbt check`
First run exit 1 (own guard flagged "label"/"stratum" in a PairDecisions doc comment; reworded). Second run `[success]`: core 41, tests 190, entity-resolution 201, hadoop 188; 620 passed, 0 failed, 0 errors; scalafmt passed; output read from the saved 887-line file. Not the recorded gate.

## Commands for the human (see chat message / run order)
shasum of pairs.jsonl, gold.jsonl, private/strata-key.jsonl, private/export-manifest.json, suggestions CSV (expected CSV sha `e65f1a411dd461553c9855a3da279d770a02efa724cb53c0cdd510ae9faa1b0b`, from three gold rows); GoldValidator run; EvaluationRun run; outputs to `/Users/winnguyen/scholarchain-data/gold/phase5/eval/`. Expected validator details: 230 entries; 95/131/4; recomputed 64 changed with 0 stored flags disagreeing; strata 76/77/77; kinds researcher 215, work 15 (from manifest). Expected evaluation: decided 230 of 230; records digest equal to manifest recordsSha256 (`646dbc69...4608`); 226 scored (4 unsure excluded).

## Observations (partial reads: 3 gold rows, 2 pairs rows, manifest, listing)
- Review pace: pairs.jsonl created 12:45, gold.jsonl last written 12:59; first `reviewedAt` 18:53:15Z. 230 labels in roughly 6-14 minutes, consistent with 166 unchanged (Enter on a revealed suggestion) plus 64 overrides. The validator prints the exact span, median gap and count of gaps under 2 s. Bears on "gold labels are human-reviewed"; the human reads those numbers.
- Mixed snapshot: gold row snapshot `dblp:2026-11-05+openalex:2026-11-07`. Registry still has DBLP 2026-11-01 (`registered_snapshot_match: false`) and OpenAlex 2026-10-14.
- Circularity: DBLP homonym suffixes (e.g. "Wei Wang 0049" vs "0159") appear in names and in suggestion reasons, so DBLP-DBLP labels may mostly measure agreement with DBLP's own disambiguation.

## Risks
n=230 (226 scored, 4 unsure); stratified sample, not population rates; Wilson intervals ignore stratification and author clustering; recall over candidate pairs only; one reviewer who saw suggestions (anchoring); non-DOI work-ID conflict ruling still pending (affects the 15 work pairs); untested: GoldValidator.main/readSources/digests/save, real-scale time and memory.

## Preservation proposal (nothing copied)
`docs/agent-runs/phase-05/gold/`, about 0.7-0.9 MB: pairs.jsonl (316,960 B), gold.jsonl (212,987 B), suggestions/scholarchain-codex-suggestions.csv, key/strata-key.jsonl (81,340 B), key/export-manifest.json (1,487 B), eval/{gold-validation.txt,.json, decisions.jsonl, evaluation-report.txt,.json}, GOLD-MANIFEST.json (SHA-256, bytes, rows, role, producing command, seed `scholarchain-er-phase5-v1`, snapshots). Verbatim byte-identical copies. export-manifest.json holds local absolute paths and gold.jsonl the reviewer handle `khang-nguyen`: human decides before any public push. Strata key may sit beside gold now (blinding only mattered during labeling); a second round needs a new export/seed/key. Anything tuned from this set is no longer held-out. Licenses: DBLP, OpenAlex, ORCID CC0-1.0; Crossref NOASSERTION (facts reusable). Human to confirm no Crossref free-text affiliations are in pairs.jsonl.
