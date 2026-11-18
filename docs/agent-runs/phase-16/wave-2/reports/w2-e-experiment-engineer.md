# W2-E report (experiment-engineer, ErExtras), received 2026-11-18 (night), archived as received

**Status: COMPLETE** (pending independent review and the human's run).

**Test-to-gate list** (`experiments/evaluation/src/test/scala/scholarchain/evaluation/er/`):
- Per-path recall (exact-id, deterministic-bibliographic, probabilistic, llm-adjudicated): `ErStatsSuite` (per-path counts on a hand-built fixture; a path with no pairs reports null, not zero; path contributions sum to pooled recall with undecided gold-same pairs counted apart; unknown path listed after the four); `ErRunSuite` checks the JSON report.
- Stratified bootstrap 95% CI for precision, recall, F1: `ErStatsSuite` (strata sizes fixed; replay of the documented algorithm; agreement with `Bootstrap.meanCi` for one stratum; undefined resamples dropped; deterministic per seed; independent of row order; point values equal the pooled tally); `ErRunSuite` (same seed identical CSV, different seed different).
- Sensitivity: `ErStatsSuite` (exclude and relabel scenarios; suspect ids missing from gold); `ErRunSuite` (manifest claim with no pair ids gives "not-identifiable"; a suspect-pairs file).
- Tier S population view: `ErRunSuite` (link-status distribution; unresolved endpoint rates overall and per window; missing table; window fallback).
- Write-once run directory with `SHA256SUMS`: `ErRunSuite` (each digest verified; second run with same id refused; exit 3 incomplete export; exit 2 bad arguments or inputs with nothing written; manifest sha match, mismatch, not-listed).

**Files** (all new; no Wave 1 file edited): `experiments/evaluation/src/main/scala/scholarchain/evaluation/er/{ErData,ErStats,ErPopulation,ErExtras}.scala`; `experiments/evaluation/src/test/scala/scholarchain/evaluation/er/{ErFixtures,ErStatsSuite,ErRunSuite}.scala`; `experiments/evaluation/er-README.md`.

**API.** `scholarchain.evaluation.er.ErExtras` (`main`, `cli(args: Vector[String]): Int`). Required: `--decisions`, `--gold`, `--strata-key`, `--gold-manifest`, `--export`, `--out`. Optional: `--seed` (16), `--resamples` (1000), `--level` (0.95), `--suspect-pairs`, `--run-id`. Exit 0 complete; 2 refused arguments, inputs or existing run id; 3 missing required export table (run directory kept INCOMPLETE). Outputs: `er-extras-report.json`, `er-extras-report.txt`, `bootstrap-resamples.csv`, `run-info.json`, `SHA256SUMS`. Reusable: `Tally`, `PathRecall.compute`, `StratifiedBootstrap`, `ErBootstrap.pooled`, `Sensitivity`, `ErPopulation.read`.

**sbt.** `sbt Test/compile` clean. Bare `sbt quick` exit 0, `[success] Total time: 160 s`, completed 4:51:20 AM; scalafmt checks passed; last totals `Passed: Total 77, Failed 0` and `Passed: Total 1126, Failed 0`. From the test reports: `ErStatsSuite` 17, `ErRunSuite` 15, 0 failures. Slot released.

**Assumptions and risks.**
- `GOLD-MANIFEST.json` claims 12 clear-non-match pairs labelled same but names none: reported "not-identifiable" with the claimed count 12. A labelled proxy set (gold-same pairs in the `clear-non-match` stratum) is added, explicitly not an identification.
- `llm-adjudicated` recall is expected null (offline stub abstains).
- Counting rules re-implemented and checked against hand-built fixtures only, not against `GoldEvaluation`.
- Field names and formats read from the first lines of the real data files only.
- Under sbt the shell sees sbt's exit status; read the printed `ERREXTRAS RESULT` / `ERREXTRAS INCOMPLETE` / `ERROR` line and check the run directory.

**DEFERRED-AFTER-WAVE-1-COMMIT:** none. **BLOCKED-ON:** nothing.

**Paste-ready human command** (not run; ran by: ____):
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/experiments/runs/phase16 /Users/winnguyen/scholarchain-data/logs; sbt "evaluation/runMain scholarchain.evaluation.er.ErExtras --decisions /Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/gold/eval-v3-r3/decisions.jsonl --gold /Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/gold/gold-v3.jsonl --strata-key /Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/gold/private/strata-key.jsonl --gold-manifest /Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/gold/GOLD-MANIFEST.json --export /Users/winnguyen/scholarchain-data/rehearsal/a-export --out /Users/winnguyen/scholarchain-data/experiments/runs/phase16" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-erextras.log | tail -n 40; echo "exit: $?"
```
Expect `ERREXTRAS RESULT: run directory ...` and a sealed `SHA256SUMS`.
