# Phase 10 shared specification

Shared by the service-engineer and the test-engineer. Owner: orchestrator, derived from the course section and decisions.md. Where the Phase 9 code or the core contracts already fix a shape, they win. Inspect `modules/core`, `modules/rag` and `modules/storage` first, and report any mismatch with this file as an assumption.

## Route table

| Method and path | Service | Scope | Success | Notes |
|---|---|---|---|---|
| POST /v1/query | slow | query | 200 answer, or 202 job reference | Validates body, creates queryId, honors Idempotency-Key, mode sync or async |
| GET /v1/jobs/{jobId} | slow | read | 200 | state, createdAt, updatedAt, stage, error, resultLink |
| GET /v1/jobs/{jobId}/result | slow | read | 200 answer, 409 if not finished | 404 unknown job |
| GET /v1/evidence/{evidenceId} | fast | read | 200 | provenance, source record, sourceUri, graph path or text pointer, compact content. 404 if missing |
| GET /v1/graph/nodes/{nodeId} | fast | read | 200 | 404 unknown node |
| GET /v1/graph/nodes/{nodeId}/neighbors | fast | read | 200 | query: depth, edgeTypes, limit, cursor. Bounded by configuration. Returns nextCursor |
| GET /v1/explain/{queryId} | fast | read | 200 | QueryPlan, stages, prompt and model versions, evidence IDs, score components, counters, verification status. No chain-of-thought |
| GET /healthz | both | none | 200 | liveness only, no dependency calls |
| GET /readyz | both | none | 200 or 503 | dependency readiness through ports. Body names components and states, never endpoints or secrets |
| GET /metrics | fast | read | 200 text, or 404 when disabled | D7 |

## Status-code mapping

400 invalid JSON or invalid request field. 401 missing or invalid token. 403 wrong scope. 404 unknown resource. 405 wrong method. 409 job result not ready. 413 body over the configured limit. 415 non-JSON content type on POST. 422 unsupported query constraint, or Idempotency-Key reused with a different body. 429 only if the implementation adds rate limiting. 500 unexpected. 503 downstream failure or open breaker. 504 timeout.

## Headers

Every response carries `X-Request-Id` and `X-Trace-Id`. An incoming valid `X-Request-Id` is echoed, otherwise one is generated. Every error body includes the trace ID.

## Typed error body

```json
{"error":{"code":"UNSUPPORTED_CONSTRAINT","message":"...","traceId":"...","requestId":"...","details":[]}}
```

Codes are an enum, one per condition above. Messages must not echo secrets, tokens, stack traces or raw downstream errors.

## Query request

Fields: `question` (required, non-empty, bounded length), optional `window` (startYear, endYear), optional `filters` (topic, venue, institution), optional `mode` ("sync" or "async"), optional `limits`. Unknown filter keys, a window with start after end, and a disallowed mode are 422 `UNSUPPORTED_CONSTRAINT`. User text is passed to the rag workflow as data. It never becomes a query string, path or header on any store.

## Graph neighbors

`depth` defaults to 1 with a configured maximum. `limit` has a configured maximum. `edgeTypes` is validated against the core edge-type enum. A cursor is opaque, signed or otherwise tamper-evident, and bound to the node and filters it was issued for.

## Local runtime entry point (contract for black-box tests)

`scholarchain.services.runtime.LocalRuntime.start(overrides: Map[String, String] = Map.empty): LocalRuntime` binds each service to an ephemeral loopback port and exposes `baseUrl` (or `fastUrl` and `slowUrl` when separate), the placeholder tokens for each scope, and `close()`. It wires the rag workflow, the offline stub model and the storage local fakes over a bounded fixture world. If the service-engineer must change this signature, they report it so the test-engineer can follow.

## Fault injection for tests

The local runtime accepts overrides that make a named port slow, failing, or empty, so that timeout, downstream failure, open breaker, missing evidence and not-ready cases can be driven over HTTP without editing production code between tests. Naming of the override keys is the service-engineer's, documented in the report.

## Required tests (course list)

Successful synchronous query. Asynchronous query path. Invalid JSON. Unsupported query constraint. Missing evidence. Idempotent repeated request. Timeout and downstream failure. Unauthorized request. Health and readiness distinction. Full query-to-evidence lifecycle with fakes.

## Gate items and the test that must prove each

The service-engineer lists these in the report before writing implementation code.

1. Routes do not embed retrieval algorithms.
2. Error responses include trace IDs.
3. Health checks do not expose secrets.
4. Request and response examples match contract tests.
5. At least one end-to-end API test reaches an evidence-backed answer using bounded fixtures.
