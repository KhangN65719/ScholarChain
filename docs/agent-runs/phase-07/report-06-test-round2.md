# Report 06: test-engineer round 2 (condensed as received)

Status: PARTIAL. `sbt quick` FAILED: root default group `Failed: Total 415, Failed 7, Errors 0, Passed 408` (65 s); forked Spark group 44/0. No marker written. No test weakened, deleted or ignored. Other groups' totals not read.

Files (tests/adversarial): Phase7MentionCoauthorSuite (18/18), Phase7StorageFixtures, Phase7NeptuneSuite (58: 57 pass, 1 fail), Phase7DynamoSuite (35: 34, 1), Phase7VectorSuite (33: 30, 3), Phase7RunLoadSuite (23: 21, 2).

Defects (owner storage-engineer):
1. NPE when a row has no provenance: `Rows.provenance` (Support.scala:121) returns null; `SparkGraphProjector.provenanceProps` (Neptune.scala:115) throws NPE instead of MalformedRow.
2. `DynamoRunLoader.metricsFrom` (DynamoDb.scala:601-604) builds `YearWindow` outside try; inverted window escapes as IllegalArgumentException instead of MalformedRow.
3. Vector writer/in-memory bucket overwrites a changed embedding or metadata under an existing key (dedupes only within one call); work order required refusal. Real PutVectors is an upsert: design call.
4. `RetryPolicy.delayAfter` (Vectors.scala:275) overflows: `RetryPolicy(100, 1L<<50, 1L<<55).delayAfter(14)` returns Long.MinValue. Low.
5. `S3RunLoader.load` trusts manifest-declared `bytes`; real file size not used for the byte bound.
6. `ObjectKeys.requireValid` accepts trailing-slash key (`"a/b/"`) via `split('/')`. Low.

Gaps: no path-evidence loader in storage (legacy-ID check covers edges and edge-support only); AwsIntegrationSuite ignored; tests are fixture-based; fakes' semantics are local.
