# Work order 09: code-reviewer, round 2 (read-only, changed items only)

Owner: code-reviewer (Opus), a fresh context. No writes, no shell. Date: 2026-11-08.

## Order

Use the code-reviewer agent to re-review only what changed since review-round-1.md, plus anything the repair newly touched. Verify each item against the actual files. Return APPROVE or REQUEST CHANGES with file:line, failing input, impact and owner. A re-review never fixes and never approves its own work.

## Evidence to read

CLAUDE.md, docs/agents.md, docs/agent-runs/phase-10/{review-round-1,decisions,hang-record}.md, work orders 06, 07, 08, reports 05 (summary only: the service-engineer's full repair report never reached the orchestrator, so verify from source), 06, 07. Source: modules/services/src/main/** and src/test/**, tests/adversarial/Phase10*.scala, tests/contracts/Phase10WireSuite.scala, tests/end-to-end/Phase10EndToEndSuite.scala, build.sbt (Test edge at the root `.dependsOn`, and `sparkJvmOptions` with the new `-XX:+ExitOnOutOfMemoryError`).

## Items to verify (round 1 ids)

- M1 / D13: every constraint the workflow cannot apply (topic, venue, institution filters; limits.maxResults; a window conflicting with a year in the question) returns 422 UNSUPPORTED_CONSTRAINT with a trace ID, and nothing is accepted and silently ignored. Check the echo fields (`filtersApplied`, `windowApplied`) are not misleading now. Check that `QueryService.run` still passes the question as data only.
- M2 / D14: separate bounded call pools per service, bounded HTTP executors, a full queue gives 503 OVERLOADED with Retry-After, a slow or stuck workflow call cannot delay an evidence read or count against the evidence breaker, `LocalWorkflowPort`'s lock behavior, shutdown order and resource release.
- m1: no unbounded body drain; the comment at the router is now true. m2: no retry after interrupt; a typed error with trace ID is always written for any Throwable. m3 / D15: failed job result is 409 JOB_FAILED with the original code in details, enum updated, documented. m4: cursor secret validated at config load, no secret in messages. m5 / D16: wording corrected, traversal documented as a bounded read-through deferred to Phase 12. Nits: unused enum value, replayed 202 state, fingerprint documentation, rejected-job record, authenticate-before-404/405 under /v1.
- Test side: each changed expectation in report 06 is a correction (still asserting status, code, trace ID) and not a weakening; new tests can fail (read their assertions against the source); the matcher change (`conformsTop`, typed values) is sound; the timing-dependent M2 and OVERLOADED tests are not flaky by construction; no test deleted.
- Build: the only build.sbt changes are the Test-only edge and the OOM flag with its comment; no heap change, no dependency or ledger change, no slowSuites change.
- Regressions: anything the repair newly touched, including auth, idempotency, cursor handling, shutdown and the Phase 9 invariants (AnswerResponse passed through untouched, no claim text rewritten, abstention preserved).

## Evidence status for the verdict

Orchestrator-attested, not run by you: `sbt quick` passed after repair (root 912, services 94; build-engineer's later run `[success]`). A full `sbt check` on the repaired sources has NOT completed: the agent-run check stalled 50 minutes in a Spark suite with an OutOfMemoryError (hang-record.md) and was stopped. Treat the full check as PENDING, to be supplied by the human's tee'd run. Do not flag the stall as a Phase 10 defect: modules/spark and tests/integration are unchanged since 0a86da0 (spark-engineer diagnosis, read-only). Mark anything you cannot verify as pending.

## Scope reminders

Local only. Deferrals to Phase 12 or 14 as before. Carried to Phase 11: m5 (model-written claim IDs) and n1 of Phase 9. The unowned untracked files stay out of the commit (D17).

## Report

Standard contract, plus per round-1 finding: RESOLVED, PARTIAL or OPEN with evidence, then any new findings (blocker, major, minor, nit) with owner, then the verdict.
