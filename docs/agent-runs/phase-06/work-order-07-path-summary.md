# Work order 07: print path results in summary.txt (`modules/spark/**`)

Issued by: orchestrator, 2026-11-07. Owner: `spark-engineer`. Reason: B3 needs `path-evidence` status counts, `unreachableReason` split and `path-length-estimates` values; they exist only as Parquet, and the human's readback script found neither pyarrow nor duckdb (`readback-paths.py` exit 3). Human chose the fallback: print them in text. Local only; no commit; do not edit `tests/**`; do not weaken tests.

## Task
1. Add to `summary.txt` (with a `<name>-columns [...]` header line, like the other tables) and to the run manifest: per window, `path-evidence` counts by `status` and, for unreachable rows, by `unreachableReason`; and the `path-length-estimates` values per window (every column of that table). Use small aggregations only; no full-table collect (the guarded helper, one row per window or per status).
2. Keep every existing summary line and manifest key; add, do not rename. If an independent `tests/**` test fails because of the shape change, report the exact test and the derivation; do not edit it.
3. List which test proves the new lines before editing; add a module test (a small synthetic run with a known status mix; hand-derive the expected counts).
4. Run `sbt Test/compile` and `sbt check`; report exact counts per module (core, entityResolution, hadoop, sparkAnalytics, root). No other sbt invocation.
5. State that the run ID is expected to change again only if your seed includes the sources digest (it does); the human will gate and re-run.

## Report
Fields as in work order 05, plus: the lines added (exact format) and the module test names.
