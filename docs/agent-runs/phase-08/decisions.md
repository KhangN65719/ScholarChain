# Phase 8 decisions (2026-11-08)

Standing rule from the human: ask (AskUserQuestion) only for decisions that change scope or cost; otherwise take the conservative option and record it here. Adding any new dependency needs the human's approval first.

## Facts established before dispatch
- The `Embedder` port (`modelVersion`, `dimension`, `embed`) already exists in `modules/storage/.../Vectors.scala`. Phase 8 supplies implementations and the pipeline around it; it does not redesign the port.
- Phase 7 already provides: 5 index specs (`VectorIndexes`), `VectorWriter` (validate, de-duplicate, batch, retry, rejected records), `InMemoryVectorBucket` with a query port and 7 metadata filters, `SemanticVectorLoader`, `SummaryFacets`, `RejectedVectors.persist`, S3 object store fake, DynamoDB RunLedger design.
- `modules/rag/` is an empty skeleton (no sources).
- Input run `run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0` has `_SUCCESS`, 41,885 `semantic-summaries` rows in 8 `window=` partitions (Parquet, 8 files), and a planned-not-uploaded `load-manifests/s3-vectors.json`. Per-type row counts are not in any text file.
- Storage has no Parquet reader (Phase 7 D6). The summaries cannot be read by storage today.
- topic and institution summaries do not exist in Spark output, so `topic-institution-summary-v1` has no source (Phase 7 accepted limit). It is created empty and reported as such.

## RULINGS (human, 2026-11-08)
- E1 CLOSED: Option A, `hash-embed-v1`, 384 dimensions, no new dependency.
- E2 CLOSED: Option 1, a spark-engineer JSONL export job. The human also asked the job to print the run's `transformVersion` and a count of `mention-coauthor:v1:` edge IDs, so the Phase 7 unverified-ID item (Phase 7 D4) closes in the same run.

## E1 (CLOSED, see rulings): local embedding approach (proposal, no dependency added yet)
Constraint: offline, no paid API, no Bedrock, behind the `Embedder` port.

| | Option A: `hash-embed-v1` (recommended default) | Option B: `all-MiniLM-L6-v2` via ONNX Runtime |
|---|---|---|
| Model | Signed feature hashing of lowercase word unigrams and bigrams, sublinear TF, L2-normalised. Our own code. | sentence-transformers all-MiniLM-L6-v2 exported to ONNX |
| Dimension | 384 (inside the S3 Vectors 1-4096 range) | 384 |
| License | Own code, no third-party terms | Model weights Apache-2.0; ONNX Runtime MIT; tokenizer library Apache-2.0 (to be confirmed by dependency-updater) |
| Offline | Yes, pure Scala, no download, deterministic bit for bit | Yes after a one-time weights download (~90 MB from a model hub). The download is a network fetch outside this phase's local-only rule. |
| New dependencies | None | `onnxruntime` plus a tokenizer library (with native binaries) in the version ledger; ledger values need dependency-updater and your approval |
| Quality | Lexical overlap only, not semantic. Enough for smoke tests with known neighbours, since summaries are templated from computed data. | Semantic similarity |
| Cost | Zero | Zero money; build and disk cost, slower tests |

Conservative choice taken: Option A is the implementation the work orders specify. Option B is not started. Both use dimension 384, so a later swap needs only a new `embeddingModel` value and new index names (`-v2`), never an in-place change (D7).

## E2 (CLOSED, see rulings): reading the Parquet summaries
- Option 1 (recommended, no new dependency): spark-engineer adds a small Spark export job in `modules/spark/**` that reads `semantic-summaries` (plus `nodes-work` and `researcher-metrics` for the facet lookups) from the run and writes JSON-lines files under the run directory. Storage reads the JSON lines through its existing `TableSource` seam. You run the job once as a `!` command. It reads a finished run and does not rerun the pipeline.
- Option 2: add a Parquet reader dependency to storage (ledger entry, dependency-updater, your approval; pulls Hadoop-style transitive libraries, and the module-boundary note says storage must not depend on distributed jobs).
- Conservative choice taken: Option 1, pending your answer.

## E3 manifest-driven embedding queue (taken, conservative)
README allows "SQS or a manifest-driven batch design". SQS needs AWS, so the local design is a manifest-driven batch: an embedding work manifest (JSON in ordinary S3) lists work items (summary ID, text hash, model version, index), is split into bounded batches, and each batch is embedded and written. The SQS variant is deferred to Phase 12.

## E4 AWS-only steps deferred to Phase 12
Bounded AWS ingestion of at least 10,000 vectors (41,885 summaries are available locally), the real S3 Vectors API adapter, the SQS queue, AWS throughput and query-latency samples, and the AWS cost and object-count report. Local fakes report object and vector counts and local timings, labelled as local.

## E5 vector key and text-hash rule (taken, conservative)
The key stays the Spark `summaryId`. The same key with a different text hash or model version is refused (Phase 7 D7 stands), never silently overwritten. Unchanged text with the same model version is skipped by content hash.

## E6 summary source dataset
`source dataset` metadata comes from the existing `source` facet (work source) where Spark has it, and is absent otherwise. Nothing is invented.

## E8 dispatch is sequential (taken)
Rule: never two sbt commands at once. Agents run `sbt quick` and share one working tree, so concurrent agents would collide on sbt and on half-written sources. Order: spark-engineer (export), build-engineer (build edges), storage-engineer, rag-engineer, test-engineer, then repairs. test-engineer still writes from the specification and the work-order contracts, not from the implementers' tests; it just runs after them. This departs from "test-engineer works in parallel" in docs/agents.md on purpose, to honour the sbt rule; no constitutional file is edited.

## E9 ownership split of the pipeline (taken)
- storage-engineer owns the library: `HashEmbedder` (`hash-embed-v1`), JSONL `TableSource`, the manifest-driven embedding work batches, text-hash skip, text and profile documents in ordinary S3, RunLedger records, the `SummaryRenderer` port with a pass-through default, and the added compact metadata keys (`textHash` and the other keys the README lists that Spark can back).
- rag-engineer owns `modules/rag/**`: deterministic rendering templates implementing `SummaryRenderer`, chunking policy, embedding-model versioning, retrieval smoke tests, the compact semantic-context format, and `IngestMain`, the composition point that wires storage and the templates (rag already depends on storage in the build, storage cannot depend on rag).
- build-engineer owns one small edge: `rag % "test->compile"` on root so independent tests under `tests/` can reach rag, plus `slowSuites` entries for any new slow suite.

## F1 PENDING-HUMAN (conservative option taken): metrics appear as text in snippets
Reviewer round 1, gate item 5: Phase 6 `summaryText` holds exact counts, component sizes and hop counts, so embedded documents and context snippets contain them. Options: (a) keep text unchanged, rely on `MetricsNotice`, and require the Phase 9 synthesis agent to take every number from graph or metric stores, never from snippets; (b) strip numbers from rendered text. Taken: (a). Stripping would change the authoritative Spark text and the "no invented values" template property, and similarity still cannot make a snippet number authoritative. Phase 9 must carry the rule into its prompts and claim verification. Human may overrule.

## F2 repair round 1 scope (taken)
All reviewer findings B1 and L1 to L10 go into one batched repair round, routed to owners in sbt-safe order: storage-engineer, test-engineer, rag-engineer, spark-engineer, then one `sbt quick`. The `.fail`-marked test becomes a plain test in the same round. Re-review covers only changed items.

## G1 process gap: storage repair report not delivered (2026-11-08)
The storage-engineer's work order 06 ran to completion and its storage suites passed, but its one `sbt quick` exited 1 on two stale tests in `tests/adversarial/Phase8IngestSuite.scala` (it may not edit tests/**), so the Stop gate stayed open and replaced its final message three times. No `report-06-storage-repair1.md` exists as received. Conservative handling: not fabricated; the re-review checks the repaired storage source against work order 06 directly, and work order 07 (test-engineer) reads that source. The Stop gate stays stale until the single `sbt quick` after all repairs; that is an unverified stop, not a pass.

## G2 process gap: rag repair report not delivered (2026-11-08)
Same mechanism as G1. The rag-engineer's work order 08 left one root test red (`tests/adversarial/Phase8RagSuite.scala:389`, which encoded the pre-L5 refusal), so its `sbt quick` exited 1, its Stop gate stayed open and replaced its final message three times; a request to write the report to the scratchpad was refused by the write hook. No `report-08-rag-repair1.md` exists as received and none is fabricated. Known from its messages: rag module passed 68 tests, 0 failed; the only failure is that root test, routed to test-engineer in work order 09. The re-review checks the repaired rag source against work order 08 directly. Suggested for a constitutional follow-up (factory-engineer, manual mode, not done here): let a blocked owner return its report despite an open Stop gate, or give owners a sanctioned report path.

## H1 round 2 follow-ups left open (taken, conservative)
The round 2 re-review approved with conditions. Its six non-blocking items N1 to N6 (review.md) are not repaired: none affects a gate bullet, and a third repair round is outside the two-round limit. N1 (concurrent same-label runs) is carried to Phase 12 with the persistent ledger; N2, N3 (rag) and N4 (rag `RagTemplatesSuite` `Locale.setDefault`), N5 (storage) are small cleanups for Phase 9 or a follow-up; N6 is watched on the real ingest run (it fails loudly, not silently). Human may route any of them to a follow-up work order before the commit.

## H2 order of the human's runs (recommended)
Real-data export, then real-data ingest, then `sbt clean check` last. If the real data exposes a defect (unverified `nodes-work` / `researcher-metrics` schemas, the N6 rank check, `OutputPolicy`), the repair changes sources and would invalidate a clean check run earlier. Run one sbt command at a time.

## H3 real-data export result (human run, 2026-11-08 14:33, log `phase-08-export.log`)
`SPARK-EXPORT RESULT: SUCCESS`, `[success] Total time: 7 s`. `export-manifest.json`: exportVersion `spark-jsonl-export/1.0.0`, sourceRunId `run:v1:4600a8f4-d438-4683-8852-db8743eb23f0`, `sourceSuccessSeen` true, transformVersion `spark-analytics/1.1.0`, `semantic-summaries` rows 41885. All three tables exported; the real `nodes-work` and `researcher-metrics` schemas had no unsupported column type. `edges-coauthored`: total 98,661; `mention-coauthor:v1:` 79,178; `coauthor:v1:` 19,483; other prefix 0; `coauthor:v1:` with a `mention:v1:` endpoint: false (0). This closes the Phase 7 unverified-ID item (Phase 7 D4): the new run carries the core mention-edge IDs and the loader will not reject it. Row counts of `nodes-work` and `researcher-metrics` were not read from the console (cut by `tail -n 30`); they are in the manifest and the log.

## H4 real-data ingest result and repair round 2 (human run 2026-11-08 14:35, log `phase-08-ingest.log`)
The ingest wrote all vectors: researcher-profile-v1 35,160, collaboration-community-v1 6,663, paper-abstract-v1 20, collaboration-path-v1 42, topic-institution-summary-v1 0 (no Spark source); 41,885 documents (62,086,569 bytes), 41,886 objects (85,204,197 bytes) with the manifest; `rejected=0 unmapped=0 failedBatches=0 retries=0`; local timing 23,287 ms (1,798.6 vectors per second, in-process fakes, not AWS); every `fetchText` hash verified. The run ended `PHASE8-INGEST RESULT: FAILED a sample query failed`: in the collaboration-path sample the 12-word display-prefix query (`In window all the shortest collaboration path from Ying Zhang 0001 to`) stops before the distinguishing endpoint, so another path summary beat the source row (rank 2 of 5 in the wide sample). This is reviewer finding N6 coming true; it is a sample-selection defect, not an ingest or data defect.
Human ruling (2026-11-08): repair round 2 approved with the constraint not to weaken the sample check. Acceptable: query from the full summary text, or from the distinguishing endpoint fields, or accept the source row only when it ties for the top score and report the tie size; state which was chosen; add a test with tied path prefixes. Work order 11 plus the forwarded constraints. Re-review covers only this change.
KNOWN LIMITATION (record for Phase 9 and 12): `hash-embed-v1` is lexical. Templated summaries of one type share opening words, so near-identical rendered text gives near-identical vectors and ties or prefix collisions between distinct rows are expected. The embedder cannot separate them; a prefix of a summary is not a reliable query. A semantic embedder (Option B, ONNX; needs ledger approval) is a Phase 12 or later item, and retrieval quality on real data beyond identity checks is unproven.

## H5 real-data ingest rerun (human run 2026-11-08 14:46, log `phase-08-ingest-2.log`, same output directory)
`PHASE8-INGEST RESULT: SUCCESS`, `[success] Total time: 18 s`. Same counts as the first run: 35,160 / 6,663 / 20 / 42 / 0 vectors, `rejected=0 unmapped=0 failedBatches=0 retries=0 duplicates=0`, objects unchanged (41,886 objects, 85,204,197 bytes), local timing 14,063 ms (2,978.4 vectors per second, in-process fakes, not AWS), local cost $0. All four SAMPLE and four WIDE-SAMPLE checks: source row rank 1, top-score tie size 1, `fetchText: textHash verified`. The collaboration-path sample that failed in the first run now passes (query = full 1,047-character rendered text). Wide samples among 35,160 profile rows and 6,663 community rows put the source row first (distance 0.0000) with the next hits at 0.0555 and 0.0881. Observation: two samples print a distance of `-0.0000` (cosine in float rounding, harmless). paper-abstract-v1 holds only 20 vectors because Spark emits only 20 `work` summaries; the 10,000-vector README target is met in total locally (41,885 vectors across indexes) but the real AWS ingestion remains deferred to Phase 12 (E4). Retrieval quality beyond these identity checks is unproven (known limitation, H4).

## E7 logs
The Bash guard refuses redirection and pipes (Phase 7 D8), so agent sbt output is not captured under `/Users/winnguyen/scholarchain-data/logs/`. Only human `!` commands write logs there.
