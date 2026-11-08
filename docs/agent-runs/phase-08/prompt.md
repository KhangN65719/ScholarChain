# Phase 8 prompt: create embeddings and populate S3 Vectors

Source: `/Users/winnguyen/scholarchain-data/course-README.md`, lines 1434-1482, from `## Phase 8: create embeddings and populate S3 Vectors` through the last gate bullet, before `## Phase 9: implement the runtime collaboration-analysis agents`. The block below is copied verbatim and wrapped in a four-backtick fence only so its inner code fence survives.

````markdown
## Phase 8: create embeddings and populate S3 Vectors

Purpose:

Build durable semantic retrieval indexes that explain researcher interests, collaboration communities, representative publications, and graph paths while leaving exact graph metrics in deterministic stores.

Why:

Graph traversal answers structural questions exactly. Vector retrieval supplies semantic context about what researchers and communities work on. The final application needs both, but the LLM must never recompute or guess deterministic collaboration metrics from text.

Use this work order:

```text
Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer work:
- Implement a bounded embedding work queue using SQS or a manifest-driven batch design.
- Implement vector write batches through the S3 Vectors API.
- Store authoritative source text and generated profile documents in ordinary S3.
- Store vector key, researcher or community ID, relevant time window, topic or institution IDs, document type, source dataset, model version, text hash, and run ID as compact metadata.
- Detect and skip unchanged text by content hash and embedding model version.
- Record failures and retry counts in RunLedger.

Rag-engineer work:
- Define deterministic rendering templates for researcher profiles, community profiles, collaboration paths, and representative-paper summaries.
- Define chunking policies for permitted abstracts or longer text when used.
- Define embedding model versioning.
- Create retrieval smoke tests with known expected researcher, topic, or community neighbors.
- Create a compact semantic-context format for the synthesis agent.

Required indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Run the local contract tests and a bounded AWS ingestion of at least 10,000 vectors or an instructor-approved smaller corpus when the selected dataset subset does not produce 10,000 meaningful units.
Report throughput, rejected records, duplicate handling, query latency samples, index counts, metadata schema, and embedding model version.
```

Gate:

- The same vector key cannot silently refer to different text.
- The embedding model version is stored.
- Source text or generated profile content can be fetched from the vector result through its ordinary S3 pointer.
- Retrieval smoke tests pass.
- Exact counts, shortest paths, components, and bridge metrics come from graph or metric stores, not vector similarity.
- Cost and object counts are reported.
````

## Scope note (human instruction, 2026-11-08)

- Local only. No AWS calls, credentials, Bedrock, paid embedding APIs, Terraform or deploys. The bounded AWS ingestion, the SQS queue, the real S3 Vectors API calls and the AWS cost and latency report are DEFERRED to Phase 12.
- Embedding sits behind the existing `Embedder` port (`modules/storage/.../Vectors.scala`). The local embedding approach is proposed in `decisions.md` (E1). No new dependency is added without the human's approval.
- Vectors are written through the Phase 7 S3 Vectors adapter (`VectorBucketPort`, `VectorWriter`, `SemanticVectorLoader`) and its local fake (`InMemoryVectorBucket`). The queue is the manifest-driven batch design, not SQS.
- Input: the Phase 6 `semantic-summaries` table of the Spark run `run-v1-4600a8f4-d438-4683-8852-db8743eb23f0` under `/Users/winnguyen/scholarchain-data/phase6-out/spark/` (41,885 rows, 8 window partitions, Parquet). Loaders refuse a run directory without `_SUCCESS`.
- Long text stays in ordinary S3 (local object store fake); vector metadata stays compact references and facets only.
- Routing: storage-engineer (modules/storage/**), rag-engineer (modules/rag/**), test-engineer (tests/**, independent fixtures), build-engineer (build changes only), code-reviewer (read-only, Opus). No inline fixes by the orchestrator. No edits to CLAUDE.md, docs/agents.md or .claude/** (that needs manual mode for factory-engineer).
- Test cadence: agents run `sbt quick`; one `sbt check` before review; never two sbt commands at once. The human runs `sbt clean check` at the gate. Real-data runs are a single paste-ready `!` command with `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false`, logs under `/Users/winnguyen/scholarchain-data/logs/`.
- Read-only git only. No commit.
- Review: fresh read-only code-reviewer (Opus), batched repair rounds, at most 2, re-reviewing only changed items.
- Phase 7 context: accepted at 8cde783 (see `../phase-07/ratification.md`).
