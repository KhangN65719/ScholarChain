# Phase 10 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-08.
- Basis: review-round-1.md (code-reviewer, Opus, REQUEST CHANGES) and review-round-2.md (APPROVE after one repair round). Also decisions.md, spec.md, validation.md, hang-record.md, and the work orders and reports.
- Phase gate: the human ran `sbt clean check`, which finished at 10:01:35 PM with exit 0 and [success] in 1501 s. It ran after the last source change (the build.sbt OOM flag), and no OOM recurred. Log: phase-10-clean-check.log.
- Transport: the JDK HttpServer (jdk.httpserver, part of Java 21). No new dependency or ledger entry (human ruling).

## Delivered
- modules/services: REST services over the Phase 9 workflow, with local runtime wiring.
- The services use the deterministic offline stub and the local fakes.
- They implement the contracted routes, typed error codes with trace IDs, body limits, timeouts, overload (503), idempotency and graceful shutdown.
- build.sbt changes: a test-only root edge `services % "test->compile"`, and a fail-fast OutOfMemoryError flag for the forked Spark test JVM.
- Tests: the services module, plus adversarial, contract (tests/contracts, added to the test sources) and end-to-end suites.

## Accepted limits and deferrals
- Hang: an agent `sbt check` stalled on a Spark fork OOM (hang-record.md). The cause is unproven. It was mitigated with the fail-fast flag, and the heap stays at 2g by human decision.
- Carried to Phase 11:
  - An idempotency key stays unreleased after a fatal Throwable, so replays of that key get 504 until restart.
  - Any standalone 4-digit number counts as a year, so "top 1000 coauthors" with a window gets 422.
  - Phase10WireSuite.scala:56 has a frozen example that should use 0 instead of null.
- Shutdown: a request in flight at close() may see its connection cut rather than a typed error body. The work order accepts either outcome.
- Reports: the service-engineer's initial and repair reports arrived only as summaries. The wire changes were recorded from source.
- Deferred to Phase 12 and 14: the container image and services entrypoint, Secrets Manager injection, a real model, and AWS deployment.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
