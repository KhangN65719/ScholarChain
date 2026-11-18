# Phase 16 Wave 1 re-review 3 (code-reviewer, Opus), received 2026-11-18, recorded by the orchestrator

Scope: items changed in repair batch 2 (re-review 2 findings A to I). Read-only.

**Verdict: REQUEST CHANGES.** 1 MEDIUM, 6 LOW (one process-only).

## Re-review 2 findings
RESOLVED: A, B (test-expectation change judged a justified behaviour change, overcount labelled; residual same-id merges within one scope disclosed as approximate), E, G, H. PARTIAL: D (`BenchModel.scala:304-306` unchanged), F (exit policy, finding 1), I (finding 2).

## Findings
1. MEDIUM, experiment-engineer. `BenchmarkGen.scala:1279-1300`: write-once outputs (`RunOutput.write` refuses overwrite, `ExportIo.scala:182-183`) are written before `VECTOR_COVERAGE MISMATCH` is printed, and `cli` returns 0; labels' `vectorStatus`/`vectorAvailable`/`vectorItems` depend on the deleted set (`:225-237, 837-841`). Silent downgrade at `:1252-1256`: a given `--vector-export` with a missing directory or chunk-set manifest becomes `unknown` with exit 0. Required: check coverage before any write; on any false manifest check refuse to write and exit non-zero unless an explicit override flag is given and recorded in the manifest; a given `--vector-export` with missing inputs is an error; tests for exit code and no file written.
2. LOW, storage-engineer. `LocalExportStore.scala:33-45`: in-root but invalid keys (`a/../b`, `./x`, `x//y`, `x/`, absolute in-root) are accepted, listed by `listSizes` (`:120-122`), rejected by `get` via `ObjectKeys.requireValid` (`ObjectStore.scala:87-93`), so `list()` throws. Call `ObjectKeys.requireValid(keyPrefix + name)` in the constructor; extend the test at `LocalExportStoreSuite.scala:126`.
3. LOW, experiment-engineer. `BenchModel.scala:304-306` still claims the limits mean a label never asks for more than the runtime holds; false for researcher lookup. Reword.
4. LOW, experiment-engineer. `overlap` (`BenchmarkGen.scala:1130-1136`) uses summary ids and subjects, ignoring `graphResearchersWithVectorSubject` (`VectorCoverage.scala:234-237`). Rename (for example `summaryOverlap`) or base on researcher coverage; state the definition in `README.md:63`.
5. LOW, test-engineer. `p16-wo7.sh:190-196`: the `$BODY_OUT` audit matches by name only (misses a read via the literal path `.smoke-body`, `smoke-test.sh:289`) and allows `-o "$BODY_OUT"` on any command. Refuse any other line containing `.smoke-body` and anchor `-o` to the `curl` line, or state the limits.
6. LOW, test-engineer. `P16LabelRulesSuite.scala:389-392`: exact `labels.size == 1` depends on the generator's private `rankLimits` (`BenchmarkGen.scala:406`), contradicting the suite header (`:13-14`); derive from a cited spec or assert non-empty with every label N=10 and mark the dependency. `tests/scripts/README.md`: no repair-batch-2 paragraph (two `6.smoke.static.p16-11.body-file-*` cases, G changes).
7. LOW, process, orchestrator. Storage `sbt quick` run 1 exited 1 for an unknown reason; later passing runs (2:32:07, 2:37:02) cover the current Scala sources, assuming no `.scala` edit after 2:37:02.

## Pending (human)
`p16-wo7.sh`; full `run.sh`; `check.sh --skip-sbt --skip-terraform`; `grep -n 'RESPONSE_HEAD\|bounded-query-answer' tests/scripts/run.sh`; per-project totals and slow suites of a full check; real-data runs; slice-2 test for finding 14.

## Orchestrator record for finding 7
Storage run 1's exit 1 is unexplained. It coincided with test-engineer's concurrent Scala edits to `P16LabelRulesSuite.scala` (the formatter-collateral event, `repair-batch-2.md`). Both later `quick` runs passed with those edits in place. Recorded as an unexplained transient; the human's `sbt clean check` at the phase gate is the covering evidence.
