# ErExtras (Phase 16, group 1: entity resolution extras)

`scholarchain.evaluation.er.ErExtras` adds four things to the entity-resolution evaluation that `EvaluationRun` already prints. It reads files only. It never touches a network, never calls a model and never writes outside its run directory. Run it after the human has replayed `EvaluationRun` unchanged; its inputs are that replay's frozen artifacts.

The `entity-resolution` module is not a dependency of the `evaluation` project, so `ErExtras` parses the JSON files itself (`ErInputs`) and re-implements the counting rules of `GoldEvaluation`. The tests pin those rules on hand-built fixtures; they do not compare against `GoldEvaluation` itself. A run compares its pooled numbers with the recorded replay instead (next but one section).

## Inputs

| Option | File |
|---|---|
| `--decisions` | `docs/agent-runs/phase-05/gold/eval-v3-r3/decisions.jsonl` (`pair_id`, `outcome`, `path`) |
| `--gold` | `docs/agent-runs/phase-05/gold/gold-v3.jsonl` (`itemId`, `verdict` = `match`, `non-match`, `unsure`) |
| `--strata-key` | `docs/agent-runs/phase-05/gold/private/strata-key.jsonl` (`pair_id`, `stratum`) |
| `--gold-manifest` | `docs/agent-runs/phase-05/gold/GOLD-MANIFEST.json` |
| `--export` | A's Spark JSONL export: `nodes-researcher` (`nodeKind`, `linkStatus`) and `edges-coauthored` (`window`, `endpointAUnresolved`, `endpointBUnresolved`) |
| `--out` | base directory; the run directory `<out>/<run id>` must not exist |

Optional: `--seed` (default 16), `--resamples` (default 1000), `--level` (default 0.95), `--run-id` (default `erextras-<UTC stamp>`), `--suspect-pairs FILE` (one pair id per line, `#` comments allowed). An unknown or repeated option, a missing value or a missing required option exits 2 and writes nothing, as does an unreadable or invalid input (a verdict or outcome the tool does not know, a duplicate pair id).

## Outputs (one run directory, written once, sealed with `SHA256SUMS`)

- `er-extras-report.json`: every number below.
- `er-extras-report.txt`: the same, readable.
- `bootstrap-resamples.csv`: the raw resample statistics (`resample,precision,recall,f1`; empty cell = undefined).
- `run-info.json`: tool version, run id, seed, resamples, export manifest sha256.

The input sha256 values are in the report. Each is also checked against the sha256 that `GOLD-MANIFEST.json` lists for that file (`match`, `MISMATCH` or `not-listed`); a mismatch is a warning, not a refusal.

## Definitions

- **Pooled counts** follow `GoldEvaluation.rates`: a true positive is a gold-same pair the matcher ACCEPTED; a false positive is a gold-different pair it ACCEPTED; recall divides by every gold-same pair, including those left UNRESOLVED, in CONFLICT or with no decision. Gold-unsure pairs are in `n` and in no rate.
- **Per-path recall** (`exact-id`, `deterministic-bibliographic`, `probabilistic`, `llm-adjudicated`, always listed): a pair belongs to the path that produced its decision. `recall` = gold-same pairs ACCEPTED on the path / gold-same pairs decided on the path. This is path-conditional and is not the pooled recall. `contributionToPooledRecall` = accepted on the path / all gold-same pairs, and the contributions sum to the pooled recall. A path that decided no gold-same pair has recall `null`, not 0. Gold-same pairs with no decision belong to no path and are counted separately. Wilson 95% intervals are given for the path recalls (the same formula as `GoldEvaluation.wilson`).
- **Stratified bootstrap** of pooled precision, recall and F1 (`StratifiedBootstrap`): the unit is the gold pair; strata are those of the private strata key; each resample redraws every stratum at its own size, with replacement, from a seeded `scala.util.Random`; percentile interval with the index rule of `Bootstrap.meanCi` (the test shows the two give identical numbers for one stratum and the mean). A resample where the statistic is undefined (for example precision with nothing accepted) is dropped and counted (`resamplesUndefined`). Items are sorted by pair id first, so the file order does not matter. The same seed gives the same CSV.
- **Sensitivity to suspected mislabelled pairs.** `GOLD-MANIFEST.json` claims 12 clear-non-match pairs labelled same are likely labelling errors, but names no pair id. The report therefore states `not-identifiable` with the claimed count, and does not treat any set as the identified one. Two things are shown in addition, both labelled for what they are: the **proxy** set (gold-same pairs of the `clear-non-match` stratum, which by construction are pairs the stratum design expected to be non-matches; this is not an identification, and the manifest claim is a human recollection, not a pair list) and, if `--suspect-pairs` is given, that file's pairs. For each set: `exclude-suspects` (removed from every count and from the resampled strata) and `relabel-suspects-different`, each with its own bootstrap. If the manifest ever carries a top-level array of pair ids under a key containing `suspect` or `mislabel`, it is used as the `manifest-listed` set.
- **Tier S population view**: every row of the export, not a sample. `nodes-researcher`: distribution of `nodeKind`, `linkStatus` and their combination (a row with no value counts under `(absent)`). `edges-coauthored`: unresolved-endpoint rate for endpoint A, endpoint B, either side and both sides, overall and per window. A flag that is absent is left out of its denominator (the denominators are in the report). The flags are the export's own, not checked against gold.

## Check against the recorded replay

Because the counting rules are re-implemented, the tool compares its pooled numbers with the recorded replay, `docs/agent-runs/phase-05/gold/eval-v3-r3/evaluation-report.txt` ("pooled over researcher and work", gold rows include unsure). On the replay's inputs they **must be equal**: same 96, different 131, unsure 3 (accepted 77, rejected 14, unresolved 4, conflict 1 for same; accepted 0, rejected 109, unresolved 14, conflict 8 for different; unsure 0, 1, 2, 0), TP 77, FP 0, precision 1.000, recall 0.802, F1 0.890. The tool prints one line, adds it to `er-extras-report.txt` under the pooled block and records it in `er-extras-report.json` as `referenceCheck`:

```
ER_REFERENCE_CHECK status=MATCH source=docs/agent-runs/phase-05/gold/eval-v3-r3/evaluation-report.txt expected[TP=77 FP=0 same=96 different=131 unsure=3 precision=1.000 recall=0.802 F1=0.890] actual[...]
```

`status=MATCH` is the expected result of the paste-ready run on the Phase 5 files (`decisions.jsonl`, `gold-v3.jsonl`, `strata-key.jsonl`). `status=DIFFERS` means the re-implementation does not reproduce `GoldEvaluation` or the inputs are not those of the replay (compare the input sha256 values in the report with the ones in `evaluation-report.txt`); read the `actual[...]` part, do not use the report as evidence until the difference is explained. The check is a comparison, never a refusal. A fixture test (`ErRunSuite`, "the pooled numbers are compared with the recorded replay") pins the comparison itself; the real reproduction on the Phase 5 files is a human step and is **unverified** until that line is read.

## Exit codes

0 complete. 2 refused arguments or inputs, or an existing run directory (nothing written; or, for an existing run directory, the first run is untouched). 3 a required export table is missing: the run directory is kept (status `INCOMPLETE`), because a rate over a missing table says nothing. Under `sbt "evaluation/runMain ..."` the shell sees sbt's own status, not 2 or 3; read the printed `ERREXTRAS RESULT`, `ERREXTRAS INCOMPLETE` or `ERROR` line and check that the run directory exists.

## Threats to validity (carry into the write-up)

- Gold is 230 pairs on the Phase 4 subset, not tier S; the strata are by design, so the pooled numbers depend on the stratum mix and are not population rates. The bootstrap resamples pairs and ignores clustering by author.
- `llm-adjudicated` decided no pair in the replay (the adjudicator is the offline stub that always abstains), so its recall is undefined, not good or bad.
- The exact-id stratum is selected by the rule the exact-id path accepts on, so its accepts agree by construction (see `GoldEvaluation.uncertainty`).
- One reviewer; no inter-rater agreement.

## Run (human)

Raw output goes to `/Users/winnguyen/scholarchain-data/experiments/runs/phase16`; see the work order report for the paste-ready command. Not run by an agent.

## Tests

`experiments/evaluation/src/test/scala/scholarchain/evaluation/er/`: `ErStatsSuite` (counting rules, per-path recall, bootstrap algorithm and determinism, sensitivity), `ErRunSuite` (readers, manifest, population view, the command line, write-once run directory and `SHA256SUMS`), `ErFixtures` (the hand-built data).

## Deferred until after the Wave 1 commit

None needed so far. `experiments/benchmark/README.md` and `build.sbt` are unchanged by this work.
