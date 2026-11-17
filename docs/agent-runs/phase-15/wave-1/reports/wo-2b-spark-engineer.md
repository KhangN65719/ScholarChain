# WO-2b report, spark-engineer (received 2026-11-17, preserved as received; author: spark-engineer subagent)

Status: READY-FOR-SBT. Nothing compiled or run. No sbt, no AWS, no commit. BLOCKED-ON infra-engineer.

## Existing setting
Yes: `--partitions <n>` on `scholarchain.spark.CollaborationPipeline`. No production change.
- Parsed in `modules/spark/src/main/scala/scholarchain/spark/Config.scala`: default and range line 179 `number(found, "--partitions", 8, 1, 10000)` (default 8); valued option line 121; usage line 107; `PipelineArgs.partitions` lines 57, 204.
- Applied in `CollaborationPipeline.scala:229` `session.conf.set("spark.sql.shuffle.partitions", ...)`; line 230 disables adaptive execution.
- Recorded in the run manifest `partitioning.shufflePartitions` (line 1253) and `configJson` (line 924); `configJson` seeds the run ID, so a higher value gives a different run ID and the default keeps the current one.
- `Export.write` hard-codes `repartition(1)` / `repartition(window)`; `JsonlExport` has none. Neither needs changing for B.
- The pipeline does not pass it: `scripts/emr/run-s-pipeline.sh:334-335` passes only `--er-run`, `--hadoop-root`, `--out-root`. Every S run used 8.

## BLOCKED-ON infra-engineer
Append `--partitions "$shuffle_partitions"` to the `run_spark_submit` call at `scripts/emr/run-s-pipeline.sh:334-335`; source the value from a new `SHUFFLE_PARTITIONS=8` key in `scripts/emr/spark-yarn.conf` read with `conf_value SHUFFLE_PARTITIONS 8 '^[0-9]+$'`.

## Risks for Experiment B (outside spark territory)
1. `scripts/emr/lib-emr.sh:500` sets `spark.dynamicAllocation.enabled false`; line 505 fixes `spark.executor.instances` (default 2 from `EXECUTOR_INSTANCES`). More partitions add queued tasks, not YARN container requests. That EMR managed scaling reacts to YARN container and memory pressure is from memory, UNVERIFIED. `EXECUTOR_INSTANCES` is already a key in `spark-yarn.conf`.
2. `run-s-pipeline.sh` header (lines 46-51): all data lives on the primary node's disk through `file://` paths and "breaks on a second node". Executors on scaled-out nodes could not read inputs or write outputs. Not verified on a cluster.

## Change
Only `modules/spark/src/test/scala/scholarchain/spark/CollaborationPipelineSuite.scala` (reformatted by the formatter hook afterwards):
- `runPipeline` gained `partitions: String = "4"` (default keeps existing runs identical).
- `private lazy val wide` runs the clean fixture with 16 partitions.
- New test `--partitions defaults to 8 and accepts 1 to 10000 only` (default 8; 64 and 10000 accepted; 0, 10001 and a non-number refused naming `--partitions`).
- New test `a higher --partitions value gives the same logical tables and more tasks` (exit 0; manifest records 16 in `partitioning` and `config`; run ID differs; all 25 tables equal both ways with `exceptAll` after dropping `provenance`; `counts`, `invariants`, `algorithms` equal; total tasks in `stage-metrics.json` higher).

## Open items
- `sbt quick` not yet run.
- Likeliest failure: a table column other than `provenance` that depends on the run ID.
- The new test adds a fourth full pipeline run; report to build-engineer for `slowSuites` if `sbt quick` slows noticeably.
- `scripts/emr/**` not edited; no account IDs.

## sbt quick result (spark-engineer, received 2026-11-17)
- Bare `sbt quick` once: `[success] Total time: 114 s (0:01:54.0), completed Nov 16, 2026, 6:56:36 PM`; no `[error]`. Group summaries `Passed: Total 73, Failed 0, Errors 0, Passed 73` and `Passed: Total 1012, Failed 0, Errors 0, Passed 1012`.
- Spark module group 73 passed (ten non-slow suites: CanonicalBuilder 7, Coauthorship 14, DriverGuard 2, GraphAlgorithms 7, JsonlExport 22, OutputPolicy 4, Paths 8, Summaries 1, Temporal 1, YarnReadiness 7).
- `CollaborationPipelineSuite` is in `slowSuites` (`build.sbt:123`) and did NOT run; the two new tests are unproven. Its report file is the old one (14 tests, 2026-11-16T18:08:33). `ConflictingKeysSuite` also skipped.
- No edits. Marker covers the quick set only.

## sbt check result (spark-engineer, received 2026-11-17)
- Bare `sbt check` once, exit 0: `[success] Total time: 1318 s (0:21:58.0), completed Nov 16, 2026, 7:19:18 PM`; no `[error]`.
- `CollaborationPipelineSuite`: `tests="16" errors="0" failures="0" skipped="0"` (14 + 2), report stamped 2026-11-16T19:10:31, 375.725 s. Both new tests passed.
- Last two group lines: `Passed: Total 95, Failed 0, Errors 0, Passed 95` (spark group by inference: 73 quick + 16 + `ConflictingKeysSuite` 6) and `Passed: Total 1133, Failed 0, Errors 0, Passed 1133`. Other group lines not read (898,940-line console log).
- No edits. Marker written against the sources at that time.
- The BLOCKED-ON infra item (`--partitions` pass-through) was addressed by WO-1b (orchestrator note).
