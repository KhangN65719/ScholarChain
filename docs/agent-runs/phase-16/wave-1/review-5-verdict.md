# Phase 16 Wave 1 narrow re-review 5 (code-reviewer, Opus), received 2026-11-18, recorded by the orchestrator

Scope: findings N1-N4 of `review-4-verdict.md` and the lines repair batch 4 touched (P16-13). Read-only.

**Verdict: APPROVE.** N1, N2, N3, N4 RESOLVED (N3 by reading only; `p16-wo7.sh` never executed).

- N1: `KnownOptions`/`optionError` (`BenchmarkGen.scala:1134-1174`) refuse unknown options, neither or both vector flags, a non-`yes` value, `--chunkset-manifest` alone; checks before any write (`:1308`), `BENCHMARK ERROR` exit 2 (`:1329-1334`); throwing coverage caught, exit 2 (`:1317-1334`, test `VectorCoverageSuite.scala:364-373`); `vectorCoverage.mode` `none-by-flag`/`checked`; `coverageMismatchOverride.checked` (`:1390`); tests `:273-362`; README `:64`. Planned command (`human-commands.md:36`) unaffected.
- N2: `BenchModel.scala:304-322`; citation `Retrieval.scala:70` (`coauthorLimit = 25`) checked.
- N3: `p16-wo7.sh:192` anchored, matches `smoke-test.sh:289`; definition count `:201`; mutation case `:207-213`; `set -u` only.
- N4: `P16LabelRulesSuite.scala:389-395`; citation `BenchmarkGen.scala:406` checked; README `:147`.

New finding:
- NF-1, LOW, experiment-engineer. `experiments/benchmark/README.md:64` says refused options print `BENCHMARK ERROR: <reason>`; a flag with no value (odd token count) makes `options` return `None` (`BenchmarkGen.scala:1285`) and `cli` prints only `Usage`, exit 2 (`:1289-1291`). Exit code and "nothing written" correct; stated console signal wrong.

Notes (not findings): `VectorCoverage.unknown` unreachable from `cli` (tests only). The reviewer notes `human-commands.md:36`'s `echo "exit: $?"` reports the pipeline status; orchestrator note: the commands set `set -o pipefail` first, so a non-zero sbt status is not masked by `tail`, but sbt's own status (not BenchmarkGen's 2 or 3) is what reaches the shell (README `:64`).

Pending (human): `p16-wo7.sh`; full `run.sh`; `check.sh --skip-sbt --skip-terraform`; the `run.sh` grep; real-data runs; phase-gate `sbt clean check` (record the failing suite name if any, for the two unexplained `quick` exit-1 runs).
