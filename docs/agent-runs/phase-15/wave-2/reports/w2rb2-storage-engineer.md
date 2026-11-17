# Wave 2 repair batch 2, storage-engineer B1a and M1 (received 2026-11-17; author: storage-engineer subagent; summary as received)

Status: COMPLETE. Final bare `sbt quick` exit 0; storage `Passed: Total 488, Failed 0, Errors 0, Passed 487, Skipped 1` (skip = gated `ExportHeapMeasureSuite`); `AwsReadModelsSuite` XML 03:07:37 28/0/0/0 (was 25). Root 1041, services 163, rag 411, spark 77 passed (earlier runs; unchanged sources). Two earlier `sbt quick` runs exited 1 on a storage test-compile error (old `overlayManifest` return type; positional `exportRunId` landing on `neptunePort`), hidden by truncated output.

Files (`modules/storage/src/`): `main/.../AwsReadModels.scala` (`AwsReadModelSettings.exportRunId` last field, empty default refused by `live` via `requireExportRunId` `^run-v1-[0-9a-f-]{36}$` before any AWS call; `exportPrefixOf`, `slugOf`; `fromExport(exportRunId: Option[String])`, `None` maps `slugOf(graphRunId)` for local/test callers only; `graphRunId` still checks manifest `sourceRunId` and pins metrics); `test/.../AwsReadModelsSuite.scala`; `test/.../ExportHeapMeasureSuite.scala` (overlay tables as zero-byte `<table>/part-00000.jsonl`, matching manifest entries; production required-table check unchanged).

Tests: prefix from slug; key layout; mismatched `sourceRunId` refused (`MalformedManifest`); invalid export run id refused before any read (empty, colon form, short, traversal, uppercase, over-long; `live` refuses, no object read). M1 test unrun (needs env var).

HEAP-MEASURE (human): `SCHOLARCHAIN_S_EXPORT_DIR=<dir> sbt "storage/testOnly scholarchain.storage.ExportHeapMeasureSuite"`; prints a `HEAP-MEASURE` line (load time, retained after GC, peak, max, objects, bytes, skipped tables, rows per table). Project name assumed.

Risks: B1b must set `exportRunId` by name in `ReadModelSource.storageSettings` (until then `live` fails closed). A direct `fromExport` caller omitting `exportRunId` gets the slug from `graphRunId` (wrong for a deployed run). Formatter flagged collateral earlier; no sign later.
