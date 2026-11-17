# WO-8 report, service-engineer (received 2026-11-17; summary of the report as received; author: service-engineer subagent)

Status: READY-FOR-SBT; code and tests written, nothing compiled. **BLOCKED-ON storage-engineer** for live AWS answering: storage has no Neptune openCypher query adapter (`AwsAdapters.scala`: `DeferredAwsNeptuneQuery` only) and the directory, path, component, community, ranking and work read models exist only as in-memory fakes built from the Spark export. `AwsRuntime` against AWS exits 3 with `BLOCKED-ON storage-engineer`; single replacement point `ReadModelSource.pending` supplying `ReadModels(neptune, ranking, paths, components, communities, directory, works)`.

## Files (`modules/services/src/`)
Modified `main/.../{Ports,QueryService,Http,Routes}.scala`. Created `RequestLog.scala`, `runtime/{AwsSettings,AwsBackend,AwsRuntime}.scala`; tests `AwsSettingsSuite` (10), `RetrievalModeRequestSuite` (7), `AwsRuntimeSuite` (19).

## Behavior
- `POST /v1/query` optional `retrievalMode` `graph|vector|fused`; absent or null is `fused`; unknown, wrong case or non-string is 400 `INVALID_REQUEST`; response shape unchanged, mode not echoed; a two-argument `WorkflowPort` runs `fused` and refuses other modes.
- `GET /healthz`: no token, no dependency call, 200 `{"status":"live","service":"fast|slow"}`; fast 8080, slow 8081 (`SCHOLARCHAIN_FAST_PORT`/`_SLOW_PORT`); `/readyz` probes dependencies, not for the target group.
- Latency log, logger `scholarchain.services.latency`, one JSON line per `POST /v1/query`: `event`, `time`, `requestId`, `mode`, `executionMode`, `status`, `totalMs`, `stagesMs` (six stages, number or "skipped"); no question text.
- Dry forms: `AwsRuntime --help`, `--check-config` (ends `CONFIG=OK`), no AWS.

## Settings
Required: `AWS_REGION`, `SCHOLARCHAIN_TABLE_PREFIX`, `SCHOLARCHAIN_DATA_BUCKET`, `SCHOLARCHAIN_VECTOR_BUCKET`, `SCHOLARCHAIN_NEPTUNE_HOST`, `SCHOLARCHAIN_GRAPH_RUN_ID` (`run:v1:<uuid>`), `SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN`, `..._AUTH_READ_TOKEN` (Secrets Manager via ECS secrets). Optional: `SCHOLARCHAIN_VECTOR_DIM` 256, `SCHOLARCHAIN_BEDROCK` off/on (now read; `on` needed for vector and fused modes, enables Titan query embedding and allows the bedrock answer model), `SCHOLARCHAIN_NEPTUNE_PORT` 8182, `SCHOLARCHAIN_ANSWER_MODEL` stub, `SCHOLARCHAIN_BEDROCK_MODEL_ID` (required if bedrock), `SCHOLARCHAIN_BEDROCK_MAX_CALLS` 10 (1-100), `SCHOLARCHAIN_QUERY_RUN_ID`, `SCHOLARCHAIN_RUNTIME_ROLE` both, `SCHOLARCHAIN_BIND_HOST` 0.0.0.0, `SCHOLARCHAIN_SERVICES_AUTH_CURSOR_SECRET`, `SCHOLARCHAIN_SERVICES_<KEY>`.

## Open items
1. Storage blocker above.
2. Query records, jobs and idempotency are in-process: `/v1/evidence` and `/v1/explain` work only with role `both` in one task; D, E, F use `/v1/query`.
3. Async jobs log no stage timings.
4. Composition duplicated between `LocalRuntime` and `AwsRuntime`.
5. Bedrock defaults (30 s/40 s) exceed the slow 10 s deadline; raise `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` for the smoke run.
6. Formatter hook failed on every edit (parallel writers, one sbt lock error); clean format unverified.

## Unverified
Nothing compiled; live AWS wiring only constructed; `WorkflowResult.timings` not re-read after rag's final change; existing implementers of `WorkflowPort` and constructors of `QueryRequest`/`ParsedQuery`/`QueryOutcome` (including `tests/**`) assumed to compile because only defaulted members were added.
