# ScholarChain HTTP API reference

Author: documentation-engineer, work order WO-B2 (Phase 17). Read from `modules/services/src/main/scala/scholarchain/services/` only. No request was sent and no test was run; every behaviour below is "as read in the cited source line". Example payloads are shapes, not recorded outputs. Recorded smoke runs against a deployed runtime, with request ids, status counts and evidence-item counts (no `curl` examples), are in `docs/releases/phase-15/smoke-tn.md`; experiment figures are in `experiments/RESULTS.md` (counts only, no ids).

Related: [architecture](architecture.md), [data lineage](data-lineage.md).

Paths below are relative to `modules/services/src/main/scala/scholarchain/services/`.

## 1. Two services, one contract

| Service | Routes | Default port | Source |
|---|---|---|---|
| fast | evidence, graph, explain, `/metrics`, `/healthz`, `/readyz` | 8080 | `Routes.scala:19-72`; `runtime/AwsRuntime.scala:17-18` |
| slow | `POST /v1/query`, jobs, `/healthz`, `/readyz` | 8081 | `Routes.scala:112-138`; `runtime/AwsRuntime.scala:17-18` |

The runtime role (`fast`, `slow` or `both`) is chosen at start (`AwsRuntime.scala:12-16`). No public ingress is defined in Terraform (`infra/terraform/README.md`, "Consolidation (runtime)"); a client reaches the services from inside the VPC. A public base URL does not exist, so none is given here.

## 2. Authentication and authorization

- Static bearer tokens, `Authorization: Bearer <token>`. Two scopes, a token carries exactly one: `query` and `read` (`Auth.scala:6-9`). Tokens are compared by SHA-256 digest in constant time (`Auth.scala:15-36`).
- Token configuration: `auth.queryToken` and `auth.readToken`, required, 16 to 256 printable characters, and they must differ (`ServiceConfig.scala:93-108`). Environment names follow `SCHOLARCHAIN_SERVICES_<KEY>` with dots and camel case turned into underscores, for example `SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN` (`ServiceConfig.scala:176-185`). The values come from Secrets Manager at runtime and are never in the repository (`AwsRuntime.scala:29-32`).
- Every `/v1` path is authenticated first, even when no route matches, so an anonymous caller always gets 401 and cannot map routes with 404 or 405 (`Http.scala:128-159`). Paths outside `/v1` keep 404 and 405 without a token.
- `/healthz` and `/readyz` need no token (`Routes.scala:13-16`). `/metrics` needs the `read` scope (`Routes.scala:60-63`).
- No token value reaches a response or a log; the headers `authorization`, `proxy-authorization`, `idempotency-key`, `cookie`, `set-cookie` and `x-api-key` are redacted (`Auth.scala:64-76`).

## 3. Common request and response behaviour

- Request id: an incoming `X-Request-Id` matching `[A-Za-z0-9._-]{1,128}` is kept; anything else is replaced by a UUID (`Http.scala:49-56`). Every response carries `X-Request-Id`, `X-Trace-Id`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` (`Http.scala:336-347`).
- Path ids must match `[A-Za-z0-9:_.=-]{1,300}` or the request is 400 `INVALID_REQUEST` (`Http.scala:50, 60-66`). The path is percent-decoded per segment; a malformed target is 400 (`Http.scala:236-243`).
- Query strings: a duplicate or empty key is 400 (`Http.scala:254-268`). Target length limit `http.maxUriChars`, default 4096 (`Http.scala:313-317`; `ServiceConfig.scala:125`).
- Body: read only by a handler, never for an unauthenticated caller, and never past `http.bodyLimitBytes`, default 65536 (range 256 to 1048576); over the limit is 413 (`Http.scala:288-307`; `ServiceConfig.scala:124`). The body must be `application/json` or `+json`, else 415 (`Http.scala:98-106`).
- Success bodies are `application/json; charset=utf-8`; `/metrics` is `text/plain; version=0.0.4` (`Http.scala:27-43`).

### Error body

All errors share one shape; messages are fixed text and never echo input, tokens, stack traces or downstream failure text (`Errors.scala:5-54`):

```json
{"error": {"code": "NOT_FOUND", "message": "No such evidence", "traceId": "...", "requestId": "...", "details": []}}
```

| HTTP | `code` | When (source) |
|---|---|---|
| 400 | `INVALID_JSON` | body not valid UTF-8 JSON (`QueryService.scala:99-112, 208-217`) |
| 400 | `INVALID_REQUEST` | unknown field, wrong type, bad id, bad query parameter, invalid cursor, bad `retrievalMode` (`QueryService.scala:93-97, 174-187, 221-232`; `GraphService.scala:45-49, 99-136`) |
| 401 | `UNAUTHORIZED` | no valid bearer token; header `WWW-Authenticate: Bearer` (`Auth.scala:38-44`) |
| 403 | `FORBIDDEN` | valid token, wrong scope (`Auth.scala:57-61`) |
| 404 | `NOT_FOUND` | no such route outside the auth check, evidence, node, query, job; `/metrics` when disabled (`Http.scala:140-145`; `Routes.scala:65-70`) |
| 405 | `METHOD_NOT_ALLOWED` | path exists, method does not; `Allow` header lists the methods (`Http.scala:146-155`) |
| 409 | `RESULT_NOT_READY` | job not finished; `Retry-After: 1` (`QueryService.scala:753-759`) |
| 409 | `JOB_FAILED` | job ended failed; the job's own error code is the one entry of `details` (`QueryService.scala:741-752`; `Errors.scala:9-11`) |
| 413 | `PAYLOAD_TOO_LARGE` | body over the limit (`Http.scala:291-297`) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | content type not JSON (`Http.scala:98-106`) |
| 422 | `UNSUPPORTED_CONSTRAINT` | filters, `limits.maxResults`, bad `window`, conflicting window, `mode` not `sync` or `async`, async disabled (`QueryService.scala:126-139, 141-161, 163-173, 189-206, 356-414`) |
| 422 | `IDEMPOTENCY_KEY_REUSED` | same `Idempotency-Key` with a different request fingerprint (`Ports.scala:408-413`) |
| 500 | `INTERNAL` | unexpected failure (`Http.scala:355-385`) |
| 503 | `DOWNSTREAM_UNAVAILABLE` | a dependency failed after retries (`Resilience.scala:206-217`) |
| 503 | `CIRCUIT_OPEN` | breaker open; `Retry-After: 1` (`Resilience.scala:169-176`) |
| 503 | `OVERLOADED` | call pool, job queue or idempotency table full; `Retry-After: 1` (`Resilience.scala:193-201`; `QueryService.scala:710-719`; `Ports.scala:416-425`) |
| 504 | `TIMEOUT` | dependency or deadline exceeded (`Resilience.scala:186-192`) |

A full HTTP transport queue drops the connection without a response and increments `http_rejected_total` (`Http.scala:437-446`).

## 4. Routes

Authentication column: scope needed. "none" means no token.

### 4.1 Fast service

| Method and path | Auth | Source |
|---|---|---|
| `GET /v1/evidence/{id}` | read | `Routes.scala:28-33` |
| `GET /v1/graph/nodes/{id}` | read | `Routes.scala:34-39` |
| `GET /v1/graph/nodes/{id}/neighbors` | read | `Routes.scala:40-53` |
| `GET /v1/explain/{id}` | read | `Routes.scala:54-59` |
| `GET /metrics` | read | `Routes.scala:60-71` |
| `GET /healthz` | none | `Routes.scala:14` |
| `GET /readyz` | none | `Routes.scala:15` |

Deadline for the four `/v1` reads: `fast.deadlineMillis`, default 2000 (range 20 to 60000) (`Routes.scala:26`; `ServiceConfig.scala:131`).

#### `GET /v1/evidence/{id}`

- Path `id`: an evidence id of an answer that is still indexed (`Ports.scala:129-138`).
- 200 body (`EvidenceService.scala:10-39`): `evidenceId`, `evidenceType`, `sourceUri`, `workId` or null, `chunkId` or null, `graphPath` (array of strings) or null, `textPointer` (the `sourceUri` for a text chunk, else null), `score`, `confidence` or null, `uncertainty` or null, `content` (cut to `evidence.maxContentChars`, default 500, range 16 to 5000) and `contentTruncated`, `sourceRecord` (`sourceRecordId`, `snapshotId`, `source`), `provenance` (the core Provenance object), `queryId`, `bundleId`.
- Errors: 404 `NOT_FOUND` "No such evidence" (`EvidenceService.scala:50-52`); plus the common errors.

#### `GET /v1/graph/nodes/{id}`

- 200 body (`GraphService.scala:140-155`): `nodeId`, `nodeType` (`AuthorMention` or `Researcher`), `displayName`, `nodeKind`, `orcid`, `linkStatus`, `idRule`, `memberCount`, `mentionCount`, `sharesOrcidWith`, `provenance`.
- 404 "No such node" (`GraphService.scala:176`).

#### `GET /v1/graph/nodes/{id}/neighbors`

Query parameters; any other key is 400 (`GraphService.scala:96-136`):

| Parameter | Meaning | Limit |
|---|---|---|
| `depth` | hops | positive integer, capped at `graph.maxDepth` (default 2, range 1 to 4); default 1 |
| `limit` | page size | positive integer, capped at `graph.maxLimit` (default 50); default `graph.defaultLimit` (20) |
| `edgeTypes` | comma list of edge types | an unknown type is 400; only `COAUTHORED_WITH` is traversed, any other request returns an empty result |
| `cursor` | page cursor | HMAC-SHA256 signed, bound to node, depth, window and edge types; invalid is 400 "The cursor is not valid for this request"; a cursor does not survive a restart unless `auth.cursorSecret` is configured |
| `window` | window label | `all` or `<start>-<end>` in canonical form; invalid is 400; default `graph.defaultWindow` (`all`) |

A value above a maximum is capped and reported in the response; it is not an error. Traversal bounds: `graph.maxFanout` 100 per origin and `graph.maxExpansions` 25 origins; reaching either sets `truncated` (`GraphService.scala:178-213`; `ServiceConfig.scala:141-145`). Order is distance, then joint publication count descending, then neighbor id.

200 body (`GraphService.scala:244-269`): `nodeId`, `window`, `depth`, `limit`, `edgeTypes`, `neighbors` (each: `neighborId`, `distance`, `via`, `edgeType`, `edgeId`, `jointPublicationCount`, `firstCollaborationYear`, `lastCollaborationYear`, `evidencePointer`), `total`, `truncated`, `nextCursor` or null. 404 "No such node" when the start node is unknown.

#### `GET /v1/explain/{id}`

- Path `id`: a query id (`AnswerResponse.queryId`).
- 200 body (`EvidenceService.scala:71-135`): `queryId`; `status` (`answered` or `abstained`); `abstained`; `abstentionReason`; `queryPlan`; `planDetails`; `resolutions`; `stages`; `prompts` (every known prompt with `id`, `version`, `hash`, `schemaId`, `calledInThisQuery`); `models`; `evidence` (`bundleId`, `evidenceIds`); `scoreComponents` (per item `evidenceId`, `evidenceType`, `score`, `confidence`); `counters` (`modelCalls`, `promptTokens`, `completionTokens`, `tokensEstimated`, `latencyMillis`, `evidenceItems`, `textChunkItems`); `verification` (`status`, `report`); `flags`; `versions` (`graphRunId`, `queryRunId`, `metricVersions`, `evidenceRunIds`, `schemaHashes`). No model reasoning is stored or shown (`EvidenceService.scala:66-70`).
- 404 "No such query".

#### `GET /metrics`

Prometheus text. Returns 404 "Metrics are not enabled" unless `metrics.enabled` is true (default false) (`Routes.scala:64-70`; `ServiceConfig.scala:152`). Content includes `http_requests_total`, `http_rejected_total`, `downstream_calls_total`, `jobs_total` and `breaker_state{port=...}` (0 closed, 1 half-open, 2 open). Sources: `http_requests_total` at `Http.scala:401`; `http_rejected_total` at `Http.scala:445`; `downstream_calls_total` at `Resilience.scala:93`; `jobs_total` at `QueryService.scala:663-720`; `breaker_state` at `OpsService.scala:50-57`.

#### `GET /healthz` and `GET /readyz` (both services)

- `/healthz`: 200 `{"status":"live","service":"fast|slow"}`; calls no dependency (`OpsService.scala:17-18`).
- `/readyz`: 200 `{"status":"ready","service":...,"components":{name:"up"}}` when every probe is up, otherwise 503 with `"status":"not-ready"` and component states `up`, `timeout`, `circuit-open` or `down` (`Routes.scala:9-11`; `OpsService.scala:20-48`). Probes: fast evidence, graph, explain; slow workflow, jobs, idempotency (`AwsRuntime.scala:142-161`). Deadline `ready.deadlineMillis`, default 1000.
- For an internal load balancer, `/healthz` is the target-group check; `/readyz` is not meant for it (`AwsRuntime.scala:17-25`). Neither port listens until the read models are loaded, so a connection is refused before that (same comment).

### 4.2 Slow service

| Method and path | Auth | Source |
|---|---|---|
| `POST /v1/query` | query | `Routes.scala:119-124` |
| `GET /v1/jobs/{id}` | read | `Routes.scala:125-131` |
| `GET /v1/jobs/{id}/result` | read | `Routes.scala:132-137` |
| `GET /healthz`, `GET /readyz` | none | `Routes.scala:138` |

Query deadline: `slow.deadlineMillis`, default 10000 (range 20 to 300000) (`Routes.scala:99`; `ServiceConfig.scala:132`). Decision P16-34 (carried in `docs/evaluation.md` section 6) says about 60000 is needed for Haiku answers. The smoke overlay `infra/terraform/environments/course/services-smoke.tfvars:26` sets `slow_deadline_millis = 60000`; the Terraform default (0) and `services-session.tfvars` do not, so they keep 10000.

#### `POST /v1/query`

Headers: `Content-Type: application/json` (required); `Authorization` with the `query` token; optional `Idempotency-Key` (`[A-Za-z0-9._:=-]{1,128}`, else 400); optional `Prefer: respond-async` (`Routes.scala:74-109`; `QueryService.scala:242-248`).

Request body, a JSON object; any other top-level key is 400 (`QueryService.scala:88-90, 221-222`):

| Field | Type | Rule |
|---|---|---|
| `question` | string | required; trimmed; not blank; at most `query.maxQuestionChars` (default 1000); no control characters other than newline, carriage return and tab |
| `window` | object or null | `{"startYear": int, "endYear": int}`, years 1 to 9999, start not after end, no other key; refused with 422 when it conflicts with a year the planner reads in the question; added to the question text only when the question names no window |
| `retrievalMode` | string or null | `graph`, `vector` or `fused`; default `fused`; anything else 400 |
| `mode` | string or null | `sync` or `async`; else 422. Without it, `Prefer: respond-async` selects async when `query.asyncEnabled` is true (default), else sync. Explicit `async` with async disabled is 422 |
| `filters` | object or null | schema accepts `topic`, `venue`, `institution` (strings up to 200 chars) but any filter present is refused 422 "not supported" (decision D13) |
| `limits` | object or null | schema accepts `maxResults`, but its presence is refused 422 "not supported" |

Example request shape:

```json
{"question": "Who are the top coauthors of researcher:v1:... since 2015?", "retrievalMode": "fused", "mode": "sync"}
```

Responses:

- 200, sync (`Routes.scala:101-103`; `QueryService.scala:443-453`): the core `AnswerResponse` JSON (`schemaVersion`, `queryId`, `answer`, `claims[]` each with `claimId`, `text`, `evidenceIds`, `verificationStatus`, `uncertainty`, `provenance`; `evidence` bundle with `bundleId`, `items[]`, `uncertainty`, `provenance`; `provenance`) plus `abstained` (bool), `abstentionReason` (string or null) and `request` (`mode`, `window`). Field names are those of the Scala case classes in `modules/core/.../Contracts.scala:402-492` serialized by upickle; the exact JSON encoding of enums (`evidenceType`, `verificationStatus`) was not read here. UNVERIFIED.
- 202, async (`Routes.scala:104-109`; `QueryService.scala:458-465`): body `{"jobId","state","createdAt","statusLink"}` and header `Location: /v1/jobs/{jobId}`. `state` is `queued` for a new job; an idempotent replay reports the job's current state.
- Idempotency: the fingerprint is SHA-256 of the raw body bytes plus the resolved mode; the same bytes under the same key replay the stored result, reordered keys or different whitespace is 422 `IDEMPOTENCY_KEY_REUSED`; a replay waits at most the request deadline, else 504 (`QueryService.scala:416-422, 534-583`). Table size `idempotency.entries`, default 1000.
- Errors: 400, 401, 403, 413, 415, 422 (listed in section 3); 503 `OVERLOADED` with `Retry-After: 1` when the job queue is full (`jobs.queueCapacity` 16, `jobs.executorSize` 2); 503 and 504 from the workflow dependency.
- A query that abstains still returns 200 with `abstained: true`; the experiments counted such answers (`experiments/RESULTS.md` 3.D).

#### `GET /v1/jobs/{id}`

200 body (`QueryService.scala:620-635`): `jobId`, `state` (`queued`, `running`, `succeeded`, `failed`), `createdAt`, `updatedAt`, `stage`, `error` (`{code, message}` or null), `queryId` or null, `resultLink` (`/v1/jobs/{id}/result` when succeeded, else null). 404 "No such job". Jobs are held in memory, bounded by `jobs.retained` (default 1000); the oldest finished job is dropped first (`Ports.scala:347-357`).

#### `GET /v1/jobs/{id}/result`

200: the same body as a sync query response. 409 `RESULT_NOT_READY` while queued or running (`Retry-After: 1`); 409 `JOB_FAILED` with the original code in `details`; 404 "No such job" (`QueryService.scala:734-759`).

## 5. Limits summary

Defaults and ranges are from `ServiceConfig.scala:119-152`. A configured value outside its range, or an unknown key, fails startup (`ServiceConfig.scala:166-174`).

| Limit | Default | Range |
|---|---|---|
| Body bytes | 65536 | 256 to 1048576 |
| Target characters | 4096 | 64 to 65536 |
| HTTP threads / queue | 8 / 64 | 1 to 64 / 1 to 10000 |
| Question characters | 1000 | up to the rag maximum |
| Fast / slow / ready deadline (ms) | 2000 / 10000 / 1000 | 20 to 60000 / 20 to 300000 / 20 to 30000 |
| Retries (idempotent reads) / backoff (ms) | 2 / 20 | 0 to 2 / 0 to 1000 |
| Breaker threshold / open time (ms) | 5 / 5000 | 1 to 100; 10 to 600000 |
| Call pool / queue | 16 / 32 | 1 to 128 / 1 to 10000 |
| Graph depth / page / max page / fanout / expansions | 2 / 20 / 50 / 100 / 25 | 1 to 4 / 1 to max / 1 to the storage page maximum / 1 to the Cypher maximum / 1 to 200 |
| Evidence content characters | 500 | 16 to 5000 |
| Job executor / queue / retained | 2 / 16 / 1000 | 1 to 16 / 1 to 1000 / 1 to 100000 |
| Idempotency entries / query records retained | 1000 / 500 | 1 to 100000 each |

Live-AWS limits and Region availability are in `docs/service-limits.md` (WO-B3), not repeated here.

## 6. Not implemented or not exposed

- No public endpoint, no API gateway, no user accounts: two static scope tokens only.
- Filters and `limits.maxResults` on `/v1/query` are refused (422) (`QueryService.scala:392-397`).
- No separate vector, rank or synthesis service endpoints (P15-47; `infra/terraform/README.md`, "Consolidation (runtime)"). No OpenSearch route.
- No endpoint lists or searches evidence, queries or researchers; lookups are by id only. A researcher-by-name lookup is not a route.
- An evidence or explain lookup works only while the answer is in the services' in-memory index (`records.retained`) or until restart (`Ports.scala:83-125`).
- No OpenAPI file exists in the files I read. This document is the route reference.

## 7. Replay status

NOT REPLAYED. `docs/releases/phase-15/smoke-tn.md` holds no `curl` examples: its smokes were `ecs run-task` runs of the load-client image against the internal ALB (`smoke-tn.md:5`), and it records their results. `scripts/smoke-test.sh` is the script that calls the endpoints with `curl` (`scripts/README.md`, "`smoke-test.sh` coverage"). The README's API examples (WO-B1) carry their own replay marks.

## 8. Sources read

`Routes.scala`, `Http.scala`, `Auth.scala`, `Errors.scala`, `ServiceConfig.scala`, `QueryService.scala` (includes job service), `EvidenceService.scala`, `GraphService.scala`, `OpsService.scala`, `Ports.scala`, `Resilience.scala`, `runtime/AwsRuntime.scala`; `modules/core/.../Contracts.scala` (lines 67-90, 402-506); `infra/terraform/README.md` (first 301 lines). Read for the repair round (Phase 17 review B12a): `infra/terraform/environments/course/services-smoke.tfvars` (whole file), `infra/terraform/environments/course/variables.tf` (lines 491-498, `slow_deadline_millis`), `infra/terraform/environments/course/main.tf` (line 91, by search), `docs/releases/phase-15/smoke-tn.md` (headings, line 5 and the smoke rows, by search), `scripts/README.md` (the "`smoke-test.sh` coverage" section, line 243 onward), and `Http.scala`, `Resilience.scala`, `QueryService.scala`, `OpsService.scala` by search for the metric names. Not read: `Metrics.scala`, `RequestLog.scala`, `LocalRuntime.scala`, `AwsSettings.scala` beyond variable names, `AwsBackend.scala`.
