# Phase 16 Wave 1 re-review 4 (code-reviewer, Opus), received 2026-11-18, recorded by the orchestrator

Scope: items changed in repair batch 3 (re-review 3 findings 1-6). Read-only.

**Verdict: APPROVE.** Findings 1-6 RESOLVED. No HIGH or MEDIUM. Four new LOW findings (N1-N4); none blocks closing finding 1 or running BenchmarkGen after the Wave 1 commit. Repair or accept-open is the human's decision.

## Findings 1-6
1. RESOLVED. Inputs and coverage decided in `cli` before any write; `generateAndWrite` (`BenchmarkGen.scala:1309`) is the only route to load and write; exit 2 on missing inputs or a throwing coverage computation (`:1263-1269, 1282-1287`); exit 3 `BENCHMARK REFUSED` (`:1288-1294`); override recorded (`:1338-1347`); tests `VectorCoverageSuite.scala:214-277`; README `:63`. Without `--vector-export`: coverage `unknown`, writes, exit 0, manifest `vectorCoverage.basis: "no vector export given"`, console `VECTOR_COVERAGE not checked` (see N1).
2. RESOLVED. `LocalExportStore.scala:40-46`; tests `LocalExportStoreSuite.scala:127-150` (positive control via `list`).
3. RESOLVED for researcher lookup (`BenchModel.scala:306-309`); new claim in N2.
4. RESOLVED. `summaryOverlapComplete` (`BenchmarkGen.scala:1135-1146`), README `:63`, excluded from mismatch (`:1150`), test `VectorCoverageSuite.scala:165-186`.
5. RESOLVED. Regexes checked against `scripts/smoke-test.sh` (lines 289, 291, 298, 301, 304-410); mutation cases discriminate. Residual N3.
6. RESOLVED. `P16LabelRulesSuite.scala:389-406` derived from the fixture and oracle; padded label fails size, truncated label fails oracle. README `:145` present. Residual N4.

## New findings (LOW)
- N1, experiment-engineer. `BenchmarkGen.scala:1236-1240` accepts any `--flag value`: a misspelt or absent `--vector-export` takes the `unknown` path, writes v1, exits 0; `--chunkset-manifest` without `--vector-export` ignored; `coverageMismatchOverride {accepted:false, failedChecks:[]}` reads like a clean pass; README silent on no-vector behaviour; no test for a throwing coverage computation; under `sbt runMain` with `fork := true` (`build.sbt:442`) the shell may see sbt's exit 1, not 2 or 3 (unverified). Required: refuse unknown options; require `--vector-export` or an explicit recorded no-vector flag; test the throwing case; document exit codes and the sbt wrapping. Planned command (`human-commands.md:36`) passes both flags, so risk is low.
- N2, experiment-engineer. `BenchModel.scala:304-306` says community and cross-edge limits follow runtime limits; no runtime member limit of 30 found in `modules/`; `MaxGroupSize` (`:317-320`) calls itself a generator choice. Cite or reword.
- N3, test-engineer. `p16-wo7.sh:192` strips any quoted `BODY_OUT=` reassignment (`BODY_OUT="/dev/stdout"` would pass). Anchor to the exact definition and require it once, or state the limit.
- N4, test-engineer. `P16LabelRulesSuite.scala:389-393`: `labels.nonEmpty` still depends on the generator asking an even N (private `rankLimits`, `BenchmarkGen.scala:406`; `plan.md:54` names no N). State the dependency in the comment.

## Pending (human)
`p16-wo7.sh` (finding-5 mutation cases never run); full `run.sh`; `check.sh --skip-sbt --skip-terraform`; the `run.sh` grep; real-data runs (including the override path over real tables); phase-gate `sbt clean check`.
