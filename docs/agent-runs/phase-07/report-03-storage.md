# Report 03: storage-engineer (condensed as received)

Status: COMPLETE (local scope). `sbt Test/compile` once; `sbt quick` final [success] 66 s. Storage module 60 passed, 0 failed (NeptuneSuite 23, DynamoSuite 10, VectorSuite 14, S3Suite 11, DeferredAdapterSuite 2, AwsIntegrationSuite 0 run / 4 ignored via munitIgnore). Other groups' counts were truncated in its output (tail: 44 and 267, 0 failures). An earlier quick run had 2 NeptuneSuite failures (check order in projector); fixed in code, no test weakened. Disclosure: the test-per-gate-item map was fixed during design, tests written after main code compiled.

Files (all modules/storage/src): main Limits, Support, ObjectStore (S3 ports, in-memory + local-fs fakes, `S3RunLoader`, `_SUCCESS` gate), Neptune (projector, openCypher CSV export/reader, bulk-load port, read-only parameterized query port, in-memory Neptune), DynamoDb (6 table specs, conditional writes, idempotent writer, run loader), Vectors (5 index specs, validation, writer with batches <=500 and bounded backoff, rejected-vector JSONL, `SemanticVectorLoader`), AwsAdapters (deferred seam). Tests: Fixtures, S3Suite, NeptuneSuite, DynamoSuite, VectorSuite, AwsIntegrationSuite.

Gate map: no long text in vector metadata (VectorSuite x3); Neptune IDs = Spark IDs (NeptuneSuite, against hand-built fixtures using `StableIds.coauthorEdgeFor`); edges trace to works (NeptuneSuite x6); DynamoDB keys vs access patterns (DynamoSuite "every stated access pattern..."); idempotent loaders (S3, Neptune, Dynamo, Vector suites).

Assumptions/risks: TableSource port, no Parquet reader; AUTHORED/PUBLISHED_IN edge IDs derived locally (`authored:v1:`, `published-in:v1:`); newline/CR/tab replaced by space in Neptune strings (counted); topic-institution-summary-v1 has no Spark source (created empty); S3 1,024-byte key limit unverified; Neptune fake upsert semantics unverified vs live; no TTL expiry simulation; fixtures not a real Spark run.

BLOCKED-ON build-engineer: Parquet reader dependency (needs ledger approval); AWS SDK v2 (s3, dynamodb, s3vectors) when deferral lifts. Optional: add AwsIntegrationSuite to `slowSuites`.
