# Phase 7 prompt: implement storage adapters and loading

Source: `target/course-README.md` (downloaded by the human from https://raw.githubusercontent.com/0x1DOCD00D/ScholarChain/main/README.md), lines 1350-1432, from `## Phase 7: implement storage adapters and loading` through the last gate bullet, before `## Phase 8: create embeddings and populate S3 Vectors`. The block below is copied verbatim and wrapped in a four-backtick fence only so its inner code fence survives.

Decision D1 is CLOSED by this verbatim copy. The earlier version of this file held a paraphrase; the work orders 01-12 were written from that paraphrase plus the human's scope note. Differences the verbatim text adds or sharpens, for the re-review: S3 Vectors "support metadata filters for researcher, year window, source, topic, institution, community, and document type when permitted by service limits"; Neptune relationship list (AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY) and node list (Researcher, Work, Venue, Institution, Topic, selected context nodes); researcher collaboration metrics for bounded runtime lookup; gate bullet "AWS integration tests are bounded and separately enabled" (human ruling D6 on 2026-11-08: opt-in env var).

````markdown
## Phase 7: implement storage adapters and loading

Purpose:

Load each collaboration data product into the AWS store designed for its access pattern.

Why:

A graph database, an operational key-value database, an object store, and a vector index are not interchangeable products with different logos. Each store should have a narrow, defensible role.

Use this work order:

```text
Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer.

Required adapters:

1. Ordinary S3
- Raw, curated, graph, semantic-context, reports, and logs prefixes.
- Versioned object keys and run manifests.
- Content hashes and source references.

2. Neptune
- Bulk loader manifests for Researcher, Work, Venue, Institution, Topic, and selected context nodes.
- Load AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY relationships as required by the implemented graph model.
- Store coauthor-edge properties including jointPublicationCount, firstCollaborationYear, lastCollaborationYear, selected time window, and evidence pointer.
- Store researcher collaboration metrics needed for bounded runtime lookup when appropriate.
- Use idempotent identifiers and parameterized openCypher queries.
- Give runtime services read-only graph credentials.

3. DynamoDB
- RunLedger table.
- EntityLinkCache table.
- QuerySession table.
- EvidenceBundle table.
- ArtifactRegistry table.
- Optional ResearcherMetricCache table when justified by access patterns.
- Explicit partition and sort keys.
- Conditional writes for idempotency.
- TTL only where data is genuinely disposable.

4. S3 Vectors
- Create and query vector-bucket and index abstractions.
- Store embeddings with stable vector keys and compact filterable metadata.
- Store long text or generated profiles in ordinary S3 and keep only references in vector metadata.
- Support metadata filters for researcher, year window, source, topic, institution, community, and document type when permitted by service limits.
- Batch writes, retry transient failures, and record rejected vectors.

5. Optional OpenSearch
- Only when the team implements hybrid lexical and vector retrieval or a measured hot tier.
- Define synchronization from durable S3 and S3 Vectors data.
- Do not make OpenSearch an undocumented second source of truth.

Required vector indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Required tests:
- Serialization and deserialization.
- Idempotent reload.
- Missing and malformed metadata.
- Duplicate vector keys.
- Neptune coauthor-edge endpoint and evidence validation.
- DynamoDB conditional-write behavior.
- Local fakes or test containers where possible.
- Bounded AWS integration tests behind an explicit profile.

Run sbt check and the bounded storage integration suite.
Report schemas, keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy, service limits captured from current documentation, and cleanup procedures.
```

Gate:

- Long text is not stuffed into vector metadata.
- Neptune graph identifiers match Spark output identifiers.
- COAUTHORED_WITH edges can be traced to supporting work evidence.
- DynamoDB keys support stated access patterns.
- Loaders are idempotent.
- AWS integration tests are bounded and separately enabled.
- The reviewer approves schema and failure-handling behavior.
````

## Scope note (human instruction, 2026-11-08)

- Local only. No AWS calls, credentials, Terraform, deploys or live operations.
- Ports, adapters, loaders and contract tests run against local fakes. The bounded AWS integration suite is DEFERRED behind an explicit opt-in (human ruling D6: env var `SCHOLARCHAIN_AWS_IT=1`, skipped otherwise, with a test that it is skipped by default).
- Optional OpenSearch is skipped.
- Service limits are captured from current AWS documentation pages and cited (see `service-limits.md`).
- Prerequisite from Phase 6 ratification: core `CoauthorEdge` contract change (domain-engineer), then Spark adapts (spark-engineer). Done.
- Inputs: Phase 6 local outputs under `/Users/winnguyen/scholarchain-data/phase6-out/spark/`. Loaders refuse a run directory without `_SUCCESS`. Phase 6 columns to consume: `semantic-summaries.nodeKind`, `path-evidence.unreachableReason`.
- Test cadence: agents run `sbt quick`; the orchestrator runs `sbt check` once before review; the human runs `sbt clean check`.
- Rulings of 2026-11-08 after the first morning summary: D1 verbatim (this file), D5 Spark transformVersion bump, D6 AWS-IT opt-in gate, D10 storage uses core StableIds for authored/published-in edge IDs; D2, D3, D7, D8, D9, D11, D12 accepted as recorded.
