# Report 03: storage-engineer (as received, archived by the orchestrator)

**Status: COMPLETE** (quick set only). Owner: storage-engineer. Work order: work-order-03-storage. The real-data run is not done.

## Test-to-item list (given before implementation code)
All new tests in `modules/storage/src/test/scala/scholarchain/storage/`.
- 1 HashEmbedder: HashEmbedderSuite "an exact known vector" (SHA-256("abc") puts -1.0 in bucket 234), "equals an independent reading of the specification", "unit length and deterministic", "features are unigrams and adjacent bigrams", "NFKC and case", "empty and featureless text is refused".
- 2 JSONL source: JsonlExportSourceSuite: round trip, no `_SUCCESS`, changed or shorter file, file edited after open, three row-count mismatches, bad manifest, path escape, three bounds, malformed line, unknown table.
- 3 manifest and batches: EmbeddingIngestSuite "work manifest is content hashed, immutable and deterministic", "batches are bounded", "an interrupted run continues from the ledger", "a rerun of a failed execution redoes only the failed batch".
- 4 renderer: "renderer output is what is hashed, stored and embedded".
- 5 skip and changed text: "unchanged text and model are skipped", "changed text ... refused and reported", "a new embedding model ... refused", "duplicates".
- 6 textRef: "textRef resolves", "tampered, missing, swapped or misdirected document is detected".
- 7 metadata: "metadata is compact ...", "full-length real IDs", "textHash is required on the ingest path".
- 8 RunLedger: "ledger: run and batch records", "ledger: a failed batch is recorded", "retries ...".
- 9 five indexes: "all five indexes exist", "an empty export still creates all five".
- 10 report and timings: "report counts ... and local timings".
- 11 query smoke: "query smoke: filtered neighbours ... and the text is fetchable".
- 12 idempotent reload: "unchanged text ... a rerun writes nothing", "a completed execution rerun ... only resumes", "a JSON-lines export runs end to end and reloads with zero writes".
- Gate: same key never refers to different text = changed-text, changed-model and duplicate tests; model version stored = metadata test; text fetchable = textRef round-trip and tamper tests; exact metrics not from vectors = "vector metadata and query results carry no graph metrics" (structural check only: no metric key allowed in metadata; `VectorMatch` returns key, distance, metadata); cost and object counts = "report counts" (local; AWS cost deferred).

## Files
Created (main, `modules/storage/src/main/scala/scholarchain/storage/`): `HashEmbedder.scala`, `JsonlExportSource.scala`, `SemanticDocuments.scala`, `EmbeddingWork.scala`, `EmbeddingIngest.scala`. Created (test): `HashEmbedderSuite.scala`, `JsonlExportSourceSuite.scala`, `EmbeddingIngestSuite.scala`, `ExportFixtures.scala`.
Modified: `Support.scala` (three new `ErrorCode` values `RowCountMismatch`, `MissingText`, `TextHashMismatch`); `Vectors.scala` (`textHash` and `templateVersion` allowed metadata keys; `SemanticVectorLoader.metadataFor` extracted from `record`, output unchanged).
No Phase 7 test changed, weakened or deleted. Nothing outside `modules/storage` touched. No dependency added.

## Commands and results
- Run 1 `sbt quick`: compile failure (`upickle.core.LinkedHashMap` type), fixed. Run 2: 1 failure (munit `intercept` rethrows `InterruptedException`), fixed in the test. Run 3: `[success] Total time: 75 s`, scalafmt passed, marker written.
- Storage from JUnit XML in `modules/storage/target/test-reports`: 163 passed, 0 failed, 0 errors, 14 suites; 45 new (8 + 11 + 26); 118 Phase 7 tests unchanged.
- Log last two lines: `Passed: Total 56, Failed 0, Errors 0` and `Passed: Total 512, Failed 0, Errors 0`; not matched to modules (middle truncated). Core, hadoop, entity-resolution totals not read.
- `sbt check` not run, per the work order.

## Decisions
1. `textHash` is not in global `VectorMetadata.Required` (would break Phase 7 `VectorSuite` fixtures). New `VectorMetadata.RequiredForIngest` = `Required` + `textHash`, `embeddingModel`, `documentType`; `VectorMetadataIngest.validate` enforces it on every Phase 8 write; `textHash` must be 64 lowercase hex.
2. A document is stored only for a vector written or already present; a rejected embed leaves no orphan. A vector rejected after its document is stored (e.g. retry exhaustion) leaves an immutable document behind.
3. A failed batch is recorded and the run continues; the run ends `FAILED`, `COMPLETED_WITH_REJECTS` or `COMPLETED`.

## Schemas and keys
- Vector key = Spark `summaryId`.
- Work manifest: `semantic-context/embedding-work/v=v1/run=<run segment>/manifest-<sha256 first 16>.json`; fields `manifestVersion`, `sourceRunId`, `sparkTransformVersion`, `embeddingModel`, `dimension`, `items`, `provenance`; item = `summaryId`, `index`, `documentType`, `textHash`, `templateVersion`, `embeddingModel`, `sourceRef`; no clock, no batch size; items ordered by index then `summaryId`.
- Document: `semantic-context/documents/v=v1/run=<segment>/<sha256(summaryId)[0:16]>-<textHash[0:16]>.json`; body `schema`, `summaryId`, `runId`, `documentType`, `templateVersion`, `textHash`, `text`; object `sourceRef` = `spark-run:<runId>/semantic-summaries#<summaryId>`; key ~120 chars so `textRef` fits the 200-char limit. `fetchText` checks `textRef` lies under `semantic-context/`, document `summaryId` equals vector key, `sha256(text)` equals vector `textHash`; mismatch = `TextHashMismatch`, missing = `MissingText`.
- Rejected records: `logs/vector-rejects/...` via existing `RejectedVectors.persist`.
- Indexes: `researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1`; all five created on every ingest, dimension 384, cosine; `topic-institution-summary-v1` stays empty (no Spark topic or institution summaries; nothing invented).
- Fixture counts (tests only): 6 vectors in 4 indexes (3,1,1,1,0), 6 documents, 1 manifest.

## Metadata schema (set by the ingest path unless noted)
`summaryType`, `subjectId` (Spark row); `textRef` (document key); `runId` (row provenance, must equal export's run); `embeddingModel`; `textHash` (sha256 of rendered text); `templateVersion` (renderer); `documentType` (= `summaryType`); `storageTransformVersion`, `sparkTransformVersion`; `evidenceWorkCount` (Num), `evidenceWorkIds`, `evidenceNodeIds` (Strs, at most 5); `window`, `windowStart` (Num), `windowEnd` (Num), `nodeKind` when present; `communityId` (researcher-metrics lookup, or `subjectId` for communities); `source` (Strs, nodes-work lookup); `year` (Num, works only, nodes-work lookup); `topic`, `institution` never set (E6). Limit test green; full-length-ID test passes.

## Batches, retries, idempotency
- Batch size configurable, default 500 (the PutVectors limit); above 500 or below 1 = `BoundExceeded`; one index per batch, manifest order.
- Retry: default `RetryPolicy(5, 200 ms, 5000 ms)`, capped exponential backoff via `VectorWriter`; exhaustion = `RetryExhausted` rejected records, never a drop.
- Ledger per execution: run item (`RUN#embed:<sourceRunId>:<label>`, `META`), one `STAGE#batch#<index>#<ordinal>` item per batch (statuses `STARTED`, `FINISHED`, `FINISHED_WITH_REJECTS`, `FAILED`; counts, `retries`, `attempt`, failure message), a `STAGE#summary` totals item. `putIfAbsent` for new items, `putIfVersion` for changes. Rerun with the same label resumes: only `FINISHED` batches skipped, others get a higher `attempt`.
- Idempotency: immutable content-hashed keys for manifest and documents; same text hash and model version skipped; changed text or model under an existing key refused as `ChangedVectorContent` (Phase 7 D7 stands); a rerun made zero PutVectors calls and no new objects.
- Counts reported separately: written, skipped (unchanged and resumed), rejected, duplicates, unmapped, retries, failed batches, per index and total.

## `hash-embed-v1` as implemented (scaladoc in `HashEmbedder`)
1. NFKC, then lowercase with `Locale.ROOT`. 2. Split on `[^\p{L}\p{N}]+`, drop empties. 3. Features: word unigrams plus adjacent bigrams joined by one space. 4. SHA-256 of the UTF-8 feature; first 8 bytes as unsigned big-endian integer mod 384 = bucket; low bit of byte 8 = sign (0 is +1, 1 is -1). 5. Weight `1 + StrictMath.log(count)`, added in ascending feature-string order in double precision. 6. Divide by L2 norm, narrow to float. Featureless text or a cancelled zero vector = `EmbeddingInvalid`. Deterministic across runs; depends on the JDK's Unicode tables only for rare characters.

## Local timings
`LocalTimings`: label "LOCAL: in-process fakes, not AWS", total ms, per-batch timings, `vectorsPerSecond`. Tests used a fake clock; no real throughput measured. Real-data numbers come from the human's run.

## Assumptions and risks
- Real export not run; tested on hand-built fixtures following report-01. Real `nodes-work` and `researcher-metrics` schemas unverified.
- Real metadata size with 64-hex IDs asserted against the limit in one fixture only; an oversize real row is rejected and reported, not written.
- The run holds all rows, plans and manifest items in memory, bounded by `LoadBounds` (1,000,000 rows); 41,885 is fine.
- A crash between vector write and ledger `FINISHED` is safe on resume (content-hash skip).
- Two writers on one execution ID unsupported; conditional writes make the second fail.
- `slowSuites` needs no change (new suites under a second).

## Deferred to Phase 12
AWS S3 Vectors API adapter (real PutVectors is an upsert, so the adapter must read before write); SQS; bounded AWS ingestion of at least 10,000 vectors; AWS throughput and latency samples; AWS cost and object-count report.

## Manual action
None. Orchestrator runs `sbt check` once before review. The real-data entry point belongs to rag-engineer's `IngestMain`, calling `new EmbeddingIngest(...).run(JsonlExportSource.open(dir), source.sourceRun, IngestConfig(startedAt = ...))`.

## Blocked-on
None. Next gate: independent review.
