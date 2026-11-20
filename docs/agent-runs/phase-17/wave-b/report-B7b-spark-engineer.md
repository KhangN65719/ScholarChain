# WO-B7b report (spark-engineer), as received 2026-11-19 (condensed by the orchestrator; citations kept)

STATUS: BLOCKED-ON (partial). Read-only; nothing run.
- **Command** (from `docs/agent-runs/phase-06/spark-owner-report.md`, "Manual action"; human-run in Phase 6 validation V3, V6, V8, V11 and V13): `sbt "sparkAnalytics/runMain scholarchain.spark.CollaborationPipeline --er-run <ER run>/snapshot=<id> --hadoop-root <Hadoop run>/snapshot=<id> --out-root <dir outside the repo> [--event-log-dir <dir>]"`, with `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false`. No AWS. Wiring: `build.sbt:361-386`; master `local[2]` (`Config.scala:77, 194`).
- **Inputs are not in the repository:** an ER run (`accepted-links/v1/*`, `manifest.json`, `_SUCCESS`) and a Hadoop snapshot (`stage=graph-export/*`, `stage=team-expansion/*`) (`Inputs.scala:10-57`). They come from `PipelineTool` and then `ErBatch`. The Phase 6 copies are local to the human's machine only.
- **Outputs:** `<out-root>/run=run-v1-<uuid>/` holding 25 Parquet tables, manifests, metrics and `summary.txt`. The out-root must be outside the repository and outside `target` (`Config.scala:244-256`).
- **Expected:** `SPARK-PIPELINE RESULT: SUCCESS`, exit 0, 13 invariants (`CollaborationPipeline.scala:936-1111`).
  - Phase 6 observed values (run `fc4bbd1a`, V13): 17,569 researcher nodes, 98,661 coauthor edges, 41,885 summaries, 0 integrity violations.
  - These came from the old subset and old code, so they are not a fixed replay target.
  - Run time about 380 to 421 s on the laptop.
- **Clean-checkout alternative:** `CollaborationPipelineSuite` and `SparkRoundTripSuite` drive the real pipeline over built-in worlds. They are `slowSuites`, so only `sbt check` runs them.
- **Risks:** the Phase 6 command's validity on the current code is assumed, not run. The run is heavy (`-Xmx2g`, about 400 s).
- **BLOCKED-ON:** a no-prior-step Spark sample needs a committed ER and Hadoop output fixture (dataset-engineer) or a local wrapper (infra-engineer). Human decision.

## Orchestrator decision (P17-21)
Same as P17-20. The README documents:
- the Spark `runMain` command with placeholder paths and its prerequisite inputs (not in the repository);
- the clean-checkout replay through `sbt check` (`CollaborationPipelineSuite`, `SparkRoundTripSuite`);
- Phase 6 figures only as cited historical results.

Everything is marked NOT REPLAYED until H-8. "No single clean-checkout Hadoop, ER and Spark sample command" goes in Known limitations. No fixture or wrapper is built in the final week unless the human asks.
