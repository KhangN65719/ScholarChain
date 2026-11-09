# Phase 10 prompt: expose the runtime through RESTful microservices

Source: Phase 10 section of /Users/winnguyen/scholarchain-data/course-README.md (lines 1576-1658), reproduced verbatim below. The scope note follows it. Phase 9 is accepted at 0a86da0.

## Course section (verbatim)

## Phase 10: expose the runtime through RESTful microservices

Purpose:

Create independently scalable services for queries, evidence, graph exploration, jobs, health, and audit access.

Why:

The runtime has fast and slow paths. A simple evidence lookup should not wait behind a large graph expansion or embedding operation. Separate services make resource policies, timeouts, queues, and scaling rules explicit.

Use this work order:

```text
Use the service-engineer agent to implement ScholarChain RESTful microservices in Scala 3.

Required services:

1. Query API
- POST /v1/query
- Validate request.
- Create query ID and idempotency record.
- Start synchronous or asynchronous Graph-RAG workflow.
- Return answer or job reference.

2. Jobs API
- GET /v1/jobs/{jobId}
- GET /v1/jobs/{jobId}/result
- Report state, timestamps, stage, errors, and result link.

3. Evidence API
- GET /v1/evidence/{evidenceId}
- Return provenance, source record, source URI, graph path or text pointer, and compact supporting content.

4. Graph exploration API
- GET /v1/graph/nodes/{nodeId}
- GET /v1/graph/nodes/{nodeId}/neighbors
- Bound depth, edge types, result count, and timeout.

5. Explain and audit API
- GET /v1/explain/{queryId}
- Return QueryPlan, stages executed, prompt versions, model versions, evidence IDs, score components, counters, and verification status.
- Do not return hidden chain-of-thought.

6. Operations endpoints
- GET /healthz
- GET /readyz
- GET /metrics when the deployment model permits it

Required HTTP behavior:
- JSON request and response contracts.
- Path-based API versioning.
- Request IDs and trace IDs.
- Idempotency-Key support for POST operations.
- Pagination or cursoring for graph neighborhoods.
- Timeouts, bounded retries, and circuit breakers.
- Typed error model.
- Authentication and authorization appropriate to the course deployment.
- No secrets or raw credentials in responses or logs.
- Thin routes; application logic belongs in services and ports.

Required tests:
- Successful synchronous query.
- Asynchronous query path.
- Invalid JSON.
- Unsupported query constraint.
- Missing evidence.
- Idempotent repeated request.
- Timeout and downstream failure.
- Unauthorized request.
- Health and readiness distinction.
- Full query-to-evidence lifecycle with fakes.

Run sbt check.
Report route table, status-code mapping, timeout policy, dependency graph, authentication method, and exact test counts.
```

Gate:

- Routes do not embed retrieval algorithms.
- Error responses include trace IDs.
- Health checks do not expose secrets.
- Request and response examples match contract tests.
- At least one end-to-end API test reaches an evidence-backed answer using bounded fixtures.

## Scope note (human and orchestrator, 2026-11-08)

Local only. No AWS calls, credentials, Bedrock, paid APIs, Terraform, image pushes or deploys.

- The services call the Phase 9 rag workflow through its ports, with the deterministic offline stub model and the local fakes. No real store or model is contacted.
- Any container or AWS-only step is built and tested locally where possible. Otherwise it is recorded as deferred to Phase 12 or 14.
- "Authentication appropriate to the course deployment" is implemented locally against a placeholder credential from configuration. No secret value is stored in the repository. Secrets Manager wiring is deferred to Phase 12.
- `GET /metrics` is implemented as an in-process, local-only text endpoint, or deferred with a recorded reason. The deployment model is not yet decided.
- No new dependency is added without the human's approval. The HTTP transport choice is recorded in decisions.md.
- Owners: service-engineer (modules/services/**), build-engineer (build changes only), test-engineer (tests/**, independent fixtures). The orchestrator never edits implementation. No edits to CLAUDE.md, docs/agents.md or .claude/** in this phase.
- Test cadence: agents run `sbt quick` during work and repairs and one `sbt check` before review. Never two sbt commands at once. The human runs `sbt clean check` last.
- Carried from Phase 9 into Phase 11, not in scope here: m5 (claim IDs written by the model) and n1 (abstention-only answer not marked abstained). Phase 12: real model and embedder, live Neptune templates, DynamoDB-backed audit.
- Review: a fresh read-only code-reviewer (Opus), batched repair rounds, at most 2, re-reviewing only changes. Then the human's `sbt clean check`, then stop for acceptance. No commit by agents.
