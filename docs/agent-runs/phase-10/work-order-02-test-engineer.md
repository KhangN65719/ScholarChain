# Work order 02: test-engineer (pass A, no sbt)

Owner: test-engineer. Writes only tests/**. Date: 2026-11-08.

## Order

Use the test-engineer agent to write independent, black-box API tests and fixtures for the Phase 10 services from the specification, in parallel with the service-engineer.

## Inputs to read first

CLAUDE.md, docs/agents.md, your role file, docs/agent-runs/phase-09/ratification.md, docs/agent-runs/phase-10/prompt.md, spec.md and decisions.md. Look at the existing layout under tests/ (contracts, integration, end-to-end, adversarial) and follow its conventions and test framework (munit). Look at the Phase 9 rag fixture world so your fixtures match real evidence IDs and researcher IDs.

## Scope

Independent tests under tests/**, driven over real HTTP against `scholarchain.services.runtime.LocalRuntime.start(...)` as defined in spec.md. Cover at least:

- every course-required test (sync query, async query path, invalid JSON, unsupported constraint, missing evidence, idempotent repeat, timeout, downstream failure, unauthorized, health versus readiness, full query-to-evidence lifecycle);
- 401 versus 403 scope behavior, constant-shape error bodies with trace IDs on every error status in spec.md;
- Idempotency-Key reuse with a different body (422), concurrent same-key requests;
- graph neighbors bounds (depth, limit, edgeTypes), cursor tamper and cursor reuse on another node;
- no secret, token or Authorization value in any response body, header or captured log line;
- /healthz makes no downstream call, /readyz reflects a failing port, neither leaks endpoints or secrets;
- user text containing injection-style content is never reflected as an instruction and never reaches a store query as code;
- wire-example contract tests: frozen request and response JSON examples for query, job, evidence, graph, explain and error bodies;
- end-to-end: POST /v1/query then GET /v1/evidence/{id} for every evidence ID the answer cites, then GET /v1/explain/{queryId}.

Phase 11 will do deeper adversarial work. Do not attempt all of it here.

## Constraints

- Do not run any sbt command in this dispatch. Another agent may be running sbt, and two sbt commands must never run at once. Report every test as WRITTEN, NOT EXECUTED. A second, sequential dispatch will execute them.
- Do not edit production code or module-owned tests. If the specification is ambiguous or LocalRuntime is missing something you need, list it as an assumption or `BLOCKED-ON service-engineer`.
- Do not weaken or delete any existing test.
- Local only. No AWS, Docker, network beyond loopback.
- Do not commit. Read-only git only.

## Report

Standard contract. Include: each test file, the test names, which spec item or course-required test each proves, the assumptions you made about LocalRuntime and the fault-injection overrides, and an explicit statement that nothing was executed.
