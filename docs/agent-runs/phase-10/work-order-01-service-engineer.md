# Work order 01: service-engineer

Owner: service-engineer. Writes only modules/services/**. Date: 2026-11-08. Phase 9 accepted at 0a86da0.

## Order

Use the service-engineer agent to implement ScholarChain RESTful microservices in Scala 3, as specified in docs/agent-runs/phase-10/prompt.md (the verbatim course section and the scope note), docs/agent-runs/phase-10/spec.md (route table, status mapping, error body, local runtime entry point) and docs/agent-runs/phase-10/decisions.md (D1 to D12).

## Inputs to read first

CLAUDE.md, docs/agents.md, your role file, docs/agent-runs/phase-09/ratification.md, the three Phase 10 files above, and the existing code you will call: modules/core (contracts, enums, JSON), modules/rag (workflow ports, the offline stub model, audit and evidence shapes), modules/storage (read ports and local fakes, audit writes). Inspect modules/services as it stands.

## Constraints

- Local only. No AWS calls, credentials, Bedrock, paid APIs, Terraform, Docker, image pushes or deploys.
- Transport is the JDK `com.sun.net.httpserver.HttpServer`. Add no dependency. Do not touch build.sbt or project/**. If you need a build change, return `BLOCKED-ON build-engineer` with the evidence and stop that item.
- JSON is uPickle through the core contracts. No second JSON library.
- Services depend on core, rag and storage only. Do not edit modules/core, modules/rag or modules/storage. If a port or contract is missing, return `BLOCKED-ON` with the owner and the exact need.
- Routes are thin: parse, authenticate, authorize, validate, call a service method, map the result. No retrieval, ranking or verification logic in routes.
- Every material claim in a query response must carry the evidence bundle the rag workflow produced. Do not rewrite claim text. Phase 9 rule F1 holds: numbers come from the graph or metric stores through the workflow.
- No secrets, tokens or credentials in responses, logs or the repository. Tokens come from configuration. Only placeholder values for local runs. Redact `Authorization` and `Idempotency-Key` values in logs.
- Policy values (timeouts, retry count, breaker thresholds, body limit, depth and limit maxima, question length, executor size) come from configuration, not literals.
- Do not edit tests/**. The test-engineer works from the spec in parallel. Write your own module tests under modules/services/src/test.
- Do not weaken or delete any existing test.
- Do not commit. Read-only git only.

## Process

1. Before any implementation code, put in your report a list naming the specific test that proves each of the five gate items in spec.md and each of the ten course-required tests.
2. Implement. Provide `scholarchain.services.runtime.LocalRuntime` as specified, with fault-injection overrides, and document the override keys.
3. Run `sbt quick` during work. Run `sbt check` once at the end. Never run two sbt commands at once. Report exact pass and fail counts per suite group and the exact commands.
4. A new slow suite, if any, is reported to the orchestrator for build-engineer to add to `slowSuites`. Do not edit build.sbt.

## Report

Standard contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE). Also include: route table as implemented, status-code mapping, timeout policy, dependency graph, authentication method, exact test counts, the gate-item to test list, the LocalRuntime override keys, and what is deferred to Phase 12 or 14.
