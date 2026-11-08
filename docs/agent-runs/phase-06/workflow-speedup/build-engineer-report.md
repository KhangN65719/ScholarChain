# build-engineer report: workflow speed-up (`sbt quick`)

Author: build-engineer (archived by the orchestrator, unedited in substance; formatting only). Status: COMPLETE. Work order: `work-order.md` in this directory. Not committed.

## Files

Modified: `build.sbt` only. Not touched: `project/**`, `.scalafmt.conf`, `.claude/settings.json`, `docs/agent-runs`.

## Primary gate: what proves each item

| Gate item | Proof |
|---|---|
| `build.sbt` loads and the whole build compiles | `sbt Test/compile` |
| `check` still runs every test and writes the marker | `sbt check` totals below, plus `scalafmtCheck` and `scalafmtSbtCheck` passing on the edited `build.sbt` |
| Every name in the slow list is a real suite | Reading each suite's package and class, plus suite headers in `phase-06-clean-check-4.log` |
| `quick` skips only the listed suites, keeps the root Spark grouping and the `modules/spark` fork, and does not write the marker | Static reading of the task definition only. The human's `!`-prefixed `sbt quick` run is the real proof, still pending. |

## Commands and results

- `sbt Test/compile`: exit 0, `[success]`.
- `sbt check`: exit 0, `[success] Total time: 1109 s (0:18:29.0)`.
- sparkAnalytics `Total 61, Failed 0, Errors 0`; root `Total 373, Failed 0, Errors 0`; hadoop `Total 207, Failed 0, Errors 0`; entityResolution `Total 389, Failed 0, Errors 0` (read in the earlier output).
- core `Total 41` was NOT seen in this run's output. 41 comes from the earlier log and an earlier build-engineer report this phase.
- Expected 41/389/207/61/373: match, no difference reported.

## Slow list (13 suites, `slowSuites`)

| Group | Suites | Evidence |
|---|---|---|
| Root Spark integration, `scholarchain.tests.analytics` | `SparkCoauthorshipSuite`, `SparkDegenerateInputsSuite`, `SparkGraphMeasuresSuite`, `SparkOutputsSuite`, `SparkPathEvidenceSuite`, `SparkReproducibilitySuite`, `SparkSummariesSuite`, `SparkTemporalWindowsSuite` | Each extends `SparkBase` (30-minute munit timeout, comment "a pipeline run takes about a minute") and drives `Runs.a`, `a3`, `c1` or `c2`. Not measured. |
| `modules/spark`, `scholarchain.spark` | `CollaborationPipelineSuite`, `ConflictingKeysSuite` | 77 to 90 s per pipeline test in both `sbt check` runs |
| `modules/hadoop`, `scholarchain.hadoop` | `MapReduceEndToEndSuite`, `PipelineDriverOutputsSuite`, `PipelineDriverUnknownDatasetSuite` | Real LocalJobRunner jobs; 10 s and 8.7 s tests in the latter two; `MapReduceEndToEndSuite` about 6 s total |

## How `quick` works

- `quickTest`: separate task in all 7 projects via `quickSettings`. It takes `Test / definedTests`, removes the `slowSuites` names and calls `Test / testOnly` with the rest. Root Spark fork group and `modules/spark` `Test / fork := true` still apply. `Test / testOptions` untouched.
- `quickRun` (root): `scalafmtCheck`, `scalafmtSbtCheck`, compile, then `quickTest`. Never writes the marker.
- `quick` is an alias for `root / quickRun`. `check` and `markTestRun` unchanged.

## Assumptions and gaps

- The clean-check-4 log is at `/Users/winnguyen/scholarchain-data/logs/phase-06-clean-check-4.log`, not in the repo. About 200k lines; the agent's shell could not grep it, so it read only early lines.
- Count of tests `quick` excludes is not derivable without running it.
- The three `Spark*` suites under `tests/adversarial` are static scans and stay in `quick`.
- Other `modules/spark` suites total about 60 s and stay in `quick`. `PathsSuite` or `CoauthorshipSuite` could be added to `slowSuites` for more speed.

## Risks

- `testOnly` receives about 100 names. If sbt rejects the form or length, it shows on the first `sbt quick`. `check` is unaffected.
- A new slow suite is not picked up until added to `slowSuites`. A name for a deleted or renamed suite is silently ignored.

## Human `sbt quick` run (recorded by the orchestrator, 2026-11-08)

Command run by the human with `!`: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false /usr/bin/time -p sbt quick`, log at `/Users/winnguyen/scholarchain-data/logs/speedup-quick.log`.

- Result: `[success] Total time: 66 s`, `real 67.45`, `user 153.88`, `sys 26.17`. `sbt check` in the build-engineer run took 1109 s (warm-state differences not controlled).
- `Passed: Total` lines in log order: 41, 181, 389, 42, 267, all Failed 0, Errors 0 (log lines 755, 1118, 1344, 1651, 1652).
- Module attribution is by position and expected totals, not printed in the log: core 41 (full 41), hadoop 181 (207), entityResolution 389 (389), sparkAnalytics 42 (61), root 267 (373).
- Excluded by `quick`: 26 + 19 + 106 = 151 tests. Modules with no slow suite match the full totals exactly.
- The roughly 100-name `testOnly` argument was accepted.

## Human decision (2026-11-08): clean check deferred

The human skipped a separate speed-up `sbt clean check`. Reasons given: `check` and `markTestRun` are unchanged, and the build-engineer's `sbt check` passed on the current sources. Phase 7's final `sbt clean check` is the recorded gate for this `build.sbt` change. The independent review of `build.sbt` goes into Phase 7's `code-reviewer` pass. This is a human acceptance, not a measured result: no `clean check` has run against the `quickTest` additions.

## Pending gates and manual action

- Human `!`-prefixed `sbt quick` for timing and behavior.
- Human `sbt clean check` against final sources. The agent's run is evidence for its turn only.
- factory-engineer: add the literal `sbt quick` to `sandbox.excludedCommands` in `.claude/settings.json`; needs independent review and human ratification.
- Independent review of `build.sbt`.
