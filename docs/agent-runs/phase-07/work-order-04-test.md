# Work order 04: test-engineer (independent Phase 7 tests and fixtures)

Owner: test-engineer. Output: tests/** only. No production code, no module tests, no build files. Local only, no AWS.

Work from the specification, not by copying the storage-engineer's tests: docs/agent-runs/phase-07/prompt.md (gate and required tests), service-limits.md, report-01-domain.md (core ID rules), report-02-spark.md, and the public API in modules/storage/src/main (read signatures to compile against; do not mirror its test cases). Check where existing independent suites live (tests/integration, tests/adversarial) and that tests/ is wired in build.sbt; if not, BLOCKED-ON build-engineer with evidence.

Cover adversarially (independent of modules/storage tests):
1. Cross-module ID agreement: IDs produced by Spark-shaped rows built through core StableIds appear identically in Neptune export, DynamoDB items and S3 manifests.
2. Legacy `coauthor:v1:` mention-endpoint IDs rejected in every loader path (edges, edge-support, path-evidence), and ID of the same pair in either endpoint order maps to one edge.
3. Idempotent reload across all four adapters under repeated and interleaved loads; a changed payload under the same key is refused, not overwritten.
4. Vector metadata: boundary cases at 2 KB, 50 keys, 1,024-char keys, 500-vector batches, unicode/multibyte sizing (bytes not chars), long text smuggled via nested or numeric-looking values.
5. Neptune: null chars, newline/quote/comma injection in CSV, openCypher parameter injection, edge without supporting works, joint count mismatch.
6. DynamoDB: 400 KB boundary in bytes, conditional-write races (second writer loses), TTL only on disposable tables.
7. `_SUCCESS` absence, partial/empty run directories, bounds exceeded writes nothing.
8. Core MentionCoauthor properties: order independence, collision across kinds, malformed IDs.
9. Provenance preserved end to end (runId, snapshotId, sourceRecordId, transformVersion).

Execute your tests with `sbt quick` (never two sbt at once) and report exact pass and fail counts. A written but unexecuted test is not evidence. If a test finds a production defect, do not fix it: report it with a minimal reproduction and the owner. Do not weaken or delete tests; do not mark failing tests ignored to get green: report failures honestly. Add any new slow suite name to your report for build-engineer. Read-only git. No commit.
