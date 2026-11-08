# Work order 07: storage-engineer repair round 1 (batched)

Evidence: report-06-test-round2.md. Do not edit tests/** (they define the expected behavior); do not weaken any test. Edit modules/storage/** only.

Fix:
1. Rows.provenance/SparkGraphProjector.provenanceProps: missing or null provenance gives MalformedRow, never NPE (check every other Rows accessor for the same null path).
2. DynamoRunLoader.metricsFrom: inverted/invalid window gives MalformedRow (move construction inside the guarded block; cover putMetrics and load).
3. Vectors: a changed embedding or metadata under an existing key is REFUSED with a clear rejected-vector reason (identical rewrite stays an idempotent no-op). Decision (conservative, PENDING-HUMAN D7): refuse; a new embedding model means a new index or new key. Apply in the writer and the in-memory bucket contract; note in code that the real PutVectors is an upsert so the AWS adapter must read-before-write or compare a content hash when it is built.
4. RetryPolicy.delayAfter: overflow-safe, non-negative, capped.
5. S3RunLoader.load: byte bound uses actual file sizes (or the max of declared and actual); a manifest declaring less than the real size cannot pass the bound.
6. ObjectKeys.requireValid: refuse trailing slash and any empty segment.
7. Add a path-evidence table reader/validator in storage: legacy `coauthor:v1:` IDs on mention endpoints are refused as in edges/edge-support; carry the Phase 6 column `unreachableReason`; and consume `semantic-summaries.nodeKind` if not already. Add module tests for it (your own module tests; do not duplicate test-engineer's).

Run `sbt quick` only (never two sbt at once). Report exact counts per module suite and, if the full root group still has failures, list each failing test with cause. Update any module doc. Read-only git. No commit. No AWS.
