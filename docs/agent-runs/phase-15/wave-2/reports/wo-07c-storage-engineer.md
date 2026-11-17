# WO-7c report, storage-engineer (received 2026-11-17; summary as received; author: storage-engineer subagent)

Status: size fix done and tested; **BLOCKED-ON spark-engineer** (missing tables) plus a run-ID question.

## Measurement (local export manifest and `ls -l`)
26 files (24 data + `_SUCCESS` + manifest), 324,156,384 bytes by the manifest (1,952 bytes under the human's figure, unexplained). Not needed by the read models: `edges-coauthored` 102,184,837; `edge-support` 80,898,695; `semantic-summaries` 66,744,102; `edges-authored` 15,591,453; `edges-published-in` 1,562,980; `nodes-venue`, `nodes-institution`, `nodes-topic` < 1 MB each. Needed and present: `researcher-metrics` 42,601,664; `nodes-researcher` 10,500,970; `nodes-work` 2,889,324 (53.4 MiB). **Required but absent from the export:** `path-evidence`, `components`, `component-membership`, `communities`, `community-membership`, `community-cross-edges` (`ExportTables.all` in `modules/spark/.../JsonlExport.scala` exports only 11 tables; WO-7b fixtures built all nine, so tests passed).

## Fix
`AwsReadModels.withSource` downloads only `_SUCCESS`, the manifest and the nine required tables; the byte bound counts those listed bytes (refused with zero reads); object bound on the whole listing; a missing required table refused with zero reads. `JsonlExportSource.open(..., only)` verifies only chosen tables; rows decoded through a `BufferedReader`; `foreachRow` streams; `researcher-metrics` folded into the cache row by row. `ExportSummary` gains defaulted `skippedTables`, `skippedBytes`. `ExportLimits.Default` unchanged (128 MiB). Trade-off: the hop-edge cross-check against `edges-coauthored` in `PathEvidence.read` no longer runs (table not loaded); row provenance checks unchanged.

## Tests
`sbt quick`: 73/0/0 and 1041/0/0, `[success] Total time: 115 s`; `AwsReadModelsSuite` 25/25; `JsonlExportSourceSuite` 15/15; `ExportHeapMeasureSuite` skipped (gated by `SCHOLARCHAIN_S_EXPORT_DIR`). New tests: unused tables never downloaded or verified; byte bound counts loaded tables only; missing required table refused from the listing; manifest digest kept when tables are skipped; selective open and streaming.

## Heap
Not measured (needs the env var). Human command (no AWS): `SCHOLARCHAIN_S_EXPORT_DIR=/Users/winnguyen/scholarchain-data/rehearsal/s-export sbt -J-Xmx1g "testOnly scholarchain.storage.ExportHeapMeasureSuite"` (and `-J-Xmx768m`); prints one `HEAP-MEASURE` line. Estimate: retained 200-300 MiB, peaks < 600 MiB; if retained <= 400 and peak <= 800 MiB, 2048 MiB task memory suffices; keep 4096 provisional.

## Blockers
1. spark-engineer: the six tables are not exported (re-export and re-upload needed), or a human decision to make them optional (path, component and community answers absent).
2. Run ID: the local export's `sourceRunId` is `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`; WO-10 set `graph_run_id` to run 6 `run:v1:192de0fd-...`; the runtime refuses a mismatch.
3. service-engineer: no required change (optional: log skipped tables and bytes).

Files: `modules/storage/.../AwsReadModels.scala`, `JsonlExportSource.scala`, tests `AwsReadModelsSuite.scala`, new `ExportHeapMeasureSuite.scala`. `DESIGN.md` not updated.
