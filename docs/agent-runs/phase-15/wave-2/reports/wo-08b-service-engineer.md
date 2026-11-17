# WO-8b report, service-engineer (received 2026-11-17; summary of the report as received; author: service-engineer subagent)

Status: COMPLETE. `sbt quick`: scalafmt and compile clean, `Passed: Total 73 ... Passed 73`, `Passed: Total 1012 ... Passed 1012`, `[success] Total time: 118 s`. Only `modules/services/**` written. No AWS.

## Files
`runtime/AwsBackend.scala` rewritten: `ReadModels` gains `metricCache: Option[DynamoTable] = None` and `close`; `ReadModelSource.pending` removed; `ReadModelSource.live` from `AwsReadModels.live` with a `from(loader, info)` test seam; mapping as specified; metrics agent uses `models.metricCache`; close at most once (`AwsWiring.once`); a build failure after the read models exist closes what was built and rethrows. `runtime/AwsRuntime.scala`: `start` wraps `compose` so any later failure closes once; default backend `AwsBackend.live(ReadModelSource.live)`. New `AwsLiveReadModelsSuite` (10); `AwsRuntimeSuite` exit-3 BLOCKED test replaced.

## Counts (test-reports)
`AwsLiveReadModelsSuite` 10/0/0; `AwsRuntimeSuite` 18/0/0 (the WO-8 "19" was a miscount); `AwsSettingsSuite` 10/0/0; `RetrievalModeRequestSuite` 7/0/0. The agent notes the sbt group totals (73, 1012) equal WO-7b's although 10 tests were added, and cannot explain it from the output; per-suite reports are the reliable counts.

## Behavior
`backend.build` (export download and verification) runs before either server starts; the port refuses connections until then; `/healthz` on 8080 and 8081 with no dependency call; Terraform needs a long health-check start period. One start-up line on `scholarchain.services.startup` (run ID, transform, export version, manifest SHA-256, objects, bytes, rows per table, works without title; no bucket, host or key). Startup failure: `RUNTIME_RESULT=FAILED reason=READ_MODELS_FAILED storage-code=<ErrorCode>` (or `aws-call-failed-transiently`, or `exception=<Class>`), exit 3; bad setting exits 2.

## Bug found and fixed
WO-8 built `SemanticRetrieval` with the default expected model (`hash-embed-v1`, 384) while using the Titan embedder, which threw `EmbeddingInvalid` at start with `SCHOLARCHAIN_BEDROCK=on`; now `EmbeddingModels.TitanTextV2D256`. Exposed by the new partial-failure test.

## Settings
No new variables; `SCHOLARCHAIN_NEPTUNE_HOST`, `_NEPTUNE_PORT`, `_DATA_BUCKET`, `_GRAPH_RUN_ID`, `AWS_REGION` feed `AwsReadModelSettings`; `SCHOLARCHAIN_TABLE_PREFIX` metric-cache table no longer read.

## Heap
Whole export in memory, bounded by the 128 MiB export limit; parsed JSON costs several times the file size; tier-S export size unmeasured; `MaxRAMPercentage=75`. Recommendation 4096 MiB at 1 vCPU or more for role `both`; 2048 MiB floor only if the export is well under 50 MiB; measure at smoke.

## Open items
Live smoke pending (Neptune `id()` semantics, export contents); vector index naming (workflow suffix vs the loader's generation-1 index names) unverified, check at smoke before relying on `vector`/`fused`; `/v1/evidence` and `/v1/explain` need role `both`; composition duplicated with `LocalRuntime`; Bedrock deadlines exceed the slow 10 s deadline (raise `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` for the smoke).
