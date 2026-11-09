# Work order 05: code-reviewer, round 1 (read-only)

Owner: code-reviewer (Opus). No writes, no shell. Date: 2026-11-08.

## Order

Use the code-reviewer agent to review the Phase 10 gate: RESTful microservices over the Phase 9 workflow. Verify every candidate finding against the actual files before reporting it. Return APPROVE or REQUEST CHANGES with exact file:line, concrete failing input, impact and owning agent. Batch every finding into one repair round. Do not edit anything.

## Evidence

Read: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-09/ratification.md, docs/agent-runs/phase-10/{prompt,spec,decisions}.md, work orders 01 to 04, reports 01a, 01b, 02, 02a, 03, 04. The diff is not available through git because the Phase 10 files are untracked. Inspect directly:
- modules/services/src/main/** and modules/services/src/test/** (the implementation, 79 module tests, RoutesStructureSuite)
- tests/adversarial/Phase10*.scala, tests/contracts/Phase10WireSuite.scala, tests/end-to-end/Phase10EndToEndSuite.scala
- build.sbt (one added Test-only edge, `services % "test->compile"`), project/DependencyVersions.scala (must be unchanged)
- the rag and storage ports the services call, to check they are used as ports and not bypassed.

Test evidence (orchestrator-attested from agent reports, not independently run by you): `sbt check` exit 0 once (the final `Passed: Total` lines were not read); after the last test edit `sbt quick` exit 0. 79 services module tests, 86 black-box tests (82 plus 4 added in the pass B), all 0 failures. Mark anything you cannot verify as pending.

## Review order (course gate first)

1. Gate: routes embed no retrieval or ranking logic; every error response includes a trace ID; health and readiness expose no secrets or endpoints; request and response examples match contract tests; at least one end-to-end test reaches an evidence-backed answer from bounded fixtures.
2. Spec conformance: route table, status-code mapping, typed error body, headers, idempotency semantics (same key and body replays, different body 422, concurrency), job states, cursor tamper evidence and binding, depth, limit and edgeTypes bounds, async path, 409 on unfinished result.
3. Security: bearer token handling (constant-time compare, 401 versus 403, scopes), no token or Authorization or Idempotency-Key value in responses or logs, no secret in config defaults or repository, body size and content-type limits, user text never becoming store query code, injection data passed as untrusted, no hidden chain-of-thought in /v1/explain.
4. Phase 9 invariants carried through: F1 (numbers from graph or metric stores), system-rendered claim text not rewritten by the services, evidence bundle passed through intact, abstention preserved.
5. Resilience: timeouts, bounded retries only on idempotent reads (max 2), circuit breaker behavior, thread or executor bounds, resource leaks on close, shutdown.
6. Module boundaries and ownership: services depends only on core, rag, storage; no AWS SDK; no new dependency; no edits outside owners' paths; JSON only uPickle; no constitutional files changed.
7. Tests that would pass even if the behavior were broken. The test-engineer left these weak assertions: contract examples with null (key presence only); the explain prompt-ID check satisfied unconditionally; 404 and 405 collapsed in the error-shape test; cursor with dropped filter accepts 200 or 400. Judge whether any of them hides a real defect.
8. Claims in reports not supported by source. The service-engineer's full report text did not reach the orchestrator, so verify the route table, status mapping, timeout policy and deferred items from source.

## Scope reminders

Local only. Deferred to Phase 12 or 14: Secrets Manager injection, container image, ALB and ECS health wiring, SQS or Step Functions, DynamoDB-backed idempotency and jobs, real model. Carried to Phase 11: m5 and n1. Do not flag those deferrals as defects unless the code misrepresents them.

## Report

Standard contract plus a ranked findings list (blocker, major, minor, nit), each with severity, file:line, failing input, impact and owner, and a verdict.
