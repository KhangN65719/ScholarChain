# Work order 09: test-engineer, one `sbt check` before review (run only, no edits)

Owner: test-engineer. Scope: run one verification and report. Edit nothing under tests/** or elsewhere. Local only, no commit.

Why: the Phase 12 Step 1 changes (hadoop production fixes, flipped and inverted tests, the P-10 helper work) are verified so far only by `sbt quick`, which skips `slowSuites`. The human asked for one agent `sbt check` before the review. It also refreshes `.claude/.last-test-run`, so the stop hook stops reporting a stale marker.

## Procedure
1. Run exactly `sbt check` once (the bare two-token form; the sandbox exclusion matches only that text). Never run two sbt commands at once. If it is silent for 15 minutes, report a hang with the last output.
2. Read the result from the sbt output and from the JUnit XML under target/test-reports if the output is truncated. Report the exact `Passed: Total N, Failed F, Errors E` line of every group, the `[success]` or error line, the total time and the exit status.
3. If any test fails, do not fix or weaken it. Report each failing test by exact name and file, the first assertion message, and whether it touches the Step 1 changes. Name the owner (hadoop-engineer for modules/hadoop, test-engineer for tests/**, others by module).
4. Confirm whether the Spark and other `slowSuites` ran under `check`, and report their counts separately from the quick set.

## Report
Contract fields. Include the per-group counts, any failure details and the owner for each, and whether the marker was refreshed (state it only if you observed it; a passing run is expected to write it).
