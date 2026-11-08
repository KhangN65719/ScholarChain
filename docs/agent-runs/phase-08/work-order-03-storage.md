# Work order 03: storage-engineer, embedding work batches and S3 Vectors ingestion (local)

Owner: storage-engineer (modules/storage/**). Phase 8, decisions E1, E3, E4, E5, E6, E8, E9. LOCAL ONLY: no AWS calls, credentials, Bedrock, paid embedding APIs, SQS, Terraform or deploys, and NO new dependency. Everything runs against the Phase 7 local fakes. AWS-only steps are deferred to Phase 12; mark each as such in code docs and in your report.

## Starting point (Phase 7, do not redesign)
`Vectors.scala` already has: `Embedder` port, `VectorIndexes` (5 required indexes), `VectorWriter` (validate, de-duplicate, batch, retry, rejected records), `VectorBucketPort` and `InMemoryVectorBucket` with a query port and 7 filters, `SemanticVectorLoader`, `SummaryFacets`, `RejectedVectors.persist`. Read the existing storage sources, `docs/agent-runs/phase-07/decisions.md`, `service-limits.md` and the S3 and DynamoDB adapters before you design.

Input contract: the spark-engineer's JSONL export, described in `docs/agent-runs/phase-08/report-01-spark-export.md` (read it; the JSON shape, manifest fields and determinism rules there are authoritative). The real export has not yet been run by the human, so test against hand-built fixtures that follow that shape. `build.sbt` already has the root `rag % "test->compile"` edge (report-02).

## Required work
1. `HashEmbedder` implementing `Embedder`: model version `hash-embed-v1`, dimension 384. Algorithm (specification, so independent tests can check it): lowercase and Unicode-NFKC normalise; split on non-letter and non-digit characters; features are word unigrams and adjacent-word bigrams; each feature is hashed with a fixed, documented, platform-independent hash (for example SHA-256 of the UTF-8 bytes, first 8 bytes) to a bucket in 0..383 and a sign of +1 or -1 from another bit; weight is 1 + ln(count) (sublinear TF); the vector is L2-normalised; empty or featureless text gives a documented result (refuse with an `EmbeddingInvalid` error, never a zero vector under cosine). Output is deterministic bit for bit across runs and JVMs. No randomness, no clock, no locale dependence.
2. A JSONL `TableSource` over the export directory (`export-manifest.json` plus part files). Verify `_SUCCESS`, row counts and sha256 against the manifest before use. Refuse a mismatch with a clear error. Bounded by the existing `LoadBounds`.
3. A manifest-driven embedding work queue (E3), replacing SQS locally:
   - an `EmbeddingWorkManifest` (JSON, stored immutably in ordinary S3 under a versioned key with content hash) that lists work items: summary ID (the vector key), index, document type, text hash (sha256 of the rendered text), template version, embedding model version, source reference;
   - split into bounded batches (batch size configurable, default within the S3 Vectors PutVectors limit already in `Limits`); process batches in order; a batch is embedded and written with the existing `VectorWriter`;
   - resumable: a rerun over the same manifest skips finished work (see 5) and an interrupted run can continue.
4. A `SummaryRenderer` port: row to (document text, template version). Storage ships a pass-through default (`spark-summary/1`: the Spark `summaryText` unchanged). rag-engineer will implement richer templates against this port; storage must not depend on rag. The renderer output is what is hashed, stored and embedded.
5. Unchanged-text skip (README): skip a work item when the vector already exists with the same text hash AND the same embedding model version. A changed text hash or model version under an existing key is NOT silently overwritten: the Phase 7 refusal (D7, `ChangedVectorContent`) stands and is reported as a rejected record with a clear reason, so "the same vector key cannot silently refer to different text" holds. Report skipped, written, rejected and duplicate counts separately.
6. Text in ordinary S3 (local object store): store each rendered document (the authoritative text or generated profile) under a versioned, content-hashed key in the `semantic-context` prefix, with a source reference (summary ID, run ID, template version). The vector's `textRef` must resolve to it: provide a `fetchText(vectorMatch)` helper that follows `textRef` through the ObjectStore and verifies the stored hash equals the vector's `textHash`. Long text never goes into vector metadata.
7. Compact metadata. Add the README's missing keys where something backs them: `textHash` (required on every vector written in Phase 8), and keep `embeddingModel`, `runId`, `documentType`, `window`/`windowStart`/`windowEnd`, `source`, `communityId`, `year`, `subjectId` (the researcher or community ID). Optionally `templateVersion`. `topic`/`institution` stay unset because Spark has no source data for them (E6): do not invent them. Update `VectorMetadata.Required`/`Allowed` deliberately and keep the filterable-metadata byte limit tests green. Do not weaken or delete a Phase 7 test; if a Phase 7 test must change because `textHash` becomes required, explain why in your report and tell the orchestrator before changing it.
8. RunLedger: record, per run and batch, started/finished, counts, failures and retry counts through the existing DynamoDB RunLedger design and its in-memory fake, with conditional writes for idempotency. A failure is recorded, never swallowed.
9. All five indexes are created. `topic-institution-summary-v1` stays empty because Spark has no topic or institution summaries; say so in the report (no fabrication).
10. A library entry point `EmbeddingIngest` (run an export through the queue into a `VectorBucketPort`, an `ObjectStore`, a ledger) that rag-engineer's `IngestMain` will call. Give it local timings (wall clock per batch, vectors per second) in a report object so the Phase 12 AWS run can be compared; label them local.
11. Query smoke inside storage tests: after an ingest of a fixture, `query` with filters returns the expected neighbours and `fetchText` returns the text for the hit.
12. Feature tests for every behavior change, in `modules/storage/src/test`: HashEmbedder determinism and exact known vector for a fixed string, dimension, normalisation, empty text; JSONL source integrity checks (bad hash, bad count, missing `_SUCCESS`); manifest batching and resume; unchanged skip; changed text refused and reported; textRef round trip and tamper detection; metadata size and no long text; RunLedger records on failure and retry; idempotent reload gives zero writes. Do not edit `tests/**` (test-engineer owns it).

## Before writing code
List which specific test proves each item above and each Phase 8 gate bullet (same key never refers to different text; model version stored; text fetchable through the S3 pointer; exact counts/paths/components/bridge metrics are NOT answered by vector similarity; cost and object counts reported). Put the list in your report.

## Commands
`sbt quick` for work and repairs. Do not run `sbt check` (the orchestrator runs it once before review). Never run two sbt commands at once. No real-data run: the human runs it later; if you want a real-data entry point, it belongs to rag-engineer's `IngestMain`.

## Reports must include
Schemas, keys, index list and counts, metadata schema (every key, type, who sets it), batch sizes, retry policy, idempotency strategy, local object counts and bytes (S3 documents, vectors per index), and the exact spec of `hash-embed-v1` as implemented. Deferred to Phase 12 and listed: the AWS S3 Vectors API adapter, SQS, bounded AWS ingestion of at least 10,000 vectors, AWS throughput and latency samples, AWS cost.

## Not allowed
modules/rag, tests/**, build.sbt, project/**, core contracts (report `BLOCKED-ON domain-engineer` if a core change is needed), CLAUDE.md, docs/agents.md, .claude/**. A new dependency. Weakening or deleting a test.

## Report
Role-file contract. The orchestrator archives it as `report-03-storage.md`.
