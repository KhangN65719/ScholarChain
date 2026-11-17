# WO-7b report, storage-engineer (received 2026-11-17; summary of the report as received; author: storage-engineer subagent)

Status: COMPLETE. One bare `sbt quick` at the end: scalafmt and compile clean, `Passed: Total 73, Failed 0, Errors 0` and `Passed: Total 1012, Failed 0, Errors 0`, `[success] Total time: 123 s`. New suites (test-reports): `AwsNeptuneQuerySuite` 19/0, `AwsNeptuneQueryHttpSuite` 7/0, `AwsReadModelsSuite` 20/0 (46 tests, 0 failures or errors). No AWS; only `modules/storage/**` edited.

## Files
New `AwsNeptuneQuery.scala` (adapter, `NeptuneQueryTimeouts`, `DocumentJson`), `AwsReadModels.scala`, three suites. `AwsAdapters.scala` comment only (`DeferredAwsNeptuneQuery` kept as a superseded placeholder).

## Tests per item
- Read models: same export gives the same models as the in-memory path; answers path, candidate, component, community, metrics; provenance (run, source URI, record ID, transform, manifest SHA-256); clear failures (missing export, no `_SUCCESS`, other run, missing table, checksum mismatch, foreign metrics row, untitled and duplicate works); bounds (objects, bytes refused with zero object reads; rows per table and total); temp copy deleted on success and failure.
- Neptune: template and parameters separate; parsing through `CoauthorContext.of`; `coauthors-of` limit 1000 and `edge-evidence` limit 1; row cap; write clauses, literals and second statements never reach the client; error mapping (timeout, throttled, access denied, bad query, 5xx, 4xx, deadline, I/O); no parameter or AWS message in errors or logs. HTTP suite against a loopback server: POST `/opencypher`, SigV4 `.../us-east-2/neptune-db/aws4_request` with signed headers, secret never sent; 403, 400, 429, 503 mapping with no retry; slow endpoint times out; closed port transient.
- rag graph retrieval issues only `coauthors-of` (limits 25 and 1000) and `edge-evidence` (limit 1) through `AllowlistedNeptune`; shortest paths come from the precomputed `path-evidence` table.

## Wiring for services (WO-8b)
`AwsReadModels.live(settings: AwsReadModelSettings, log): AwsReadModels`; `AwsReadModelSettings(region, dataBucket, neptuneHost, graphRunId, neptunePort = 8182, limits = ExportLimits.Default, timeouts = NeptuneQueryTimeouts.Default)`; `AwsReadModels(neptune, paths, components, communities, directory, metrics: Vector[CachedMetrics], metricCache: DynamoTable, workRows: Vector[Row], summary: ExportSummary, close)`. Services maps: `ReadModels(m.neptune, new InMemoryMetricRanking(m.metrics), m.paths, m.components, m.communities, m.directory, new TableWorkCatalog(m.workRows))`. Services must use `m.metricCache` (in-memory, from `researcher-metrics`) rather than the DynamoDB metric cache (believed unfilled by the Phase 14 loader, unverified), call `m.close()` on shutdown, and expect a slow start (S3 download and verification). Also `fromExport` and `assemble`.

## Settings
Storage reads no environment. Export layout `semantic-context/runs/<graphRunId>/export/` with `_SUCCESS`, `export-manifest.json`, `<table>/part-NNNNN.jsonl`; run ID `run:v1:<uuid>` must equal the manifest `sourceRunId`; nine required tables. Limits: 1,000 objects, 128 MiB, 250,000 rows per table, 1,000,000 total. Query timeouts connect 3 s, read 8 s, attempt 9 s, call 10 s, no SDK retry. Failure markers `NEPTUNE_TIMEOUT`, `_THROTTLED`, `_UNAVAILABLE` (transient), `_ACCESS_DENIED`, `_BAD_QUERY`, `_REJECTED`.

## For WO-10
Existing Terraform already grants S3 list/get on `semantic-context/*` and `neptune-db:ReadDataViaQuery`, `GetQueryStatus`, `GetEngineStatus` (runtime role `NeptuneReadOnly`); IAM DB auth on; services-to-Neptune SG rules present; S3 and DynamoDB endpoints present; no KMS. UNVERIFIED (no documentation tool): that `ExecuteOpenCypherQuery` maps to `neptune-db:ReadDataViaQuery` with no separate connect action. Heap risk: the whole export is held in the heap; the image sets no `-Xmx` beyond `MaxRAMPercentage=75`; check `service_memory`; real tier-S export size unmeasured.

## Open items
Live smoke pending (Neptune `id()` semantics against bulk-loaded IDs, real response shape, real export contents). `modules/storage/DESIGN.md` not updated.
