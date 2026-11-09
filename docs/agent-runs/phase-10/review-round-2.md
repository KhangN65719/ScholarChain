# Review round 2: code-reviewer (Opus), 2026-11-08

Author: code-reviewer. Verdict: APPROVE. No blockers, no majors. Condensed by the orchestrator. The reviewer ran no commands and no tests.

## Round 1 findings

All RESOLVED: M1 (D13: refusal in `resolve`, before any idempotency claim; echo reduced to mode and window; QueryService.scala:117-167, 218-232, 247-252, 292-302, 335), M2 (D14: separate bounded fast and slow call pools and HTTP executors, OVERLOADED with Retry-After, cancel on timeout, `lockInterruptibly`; LocalRuntime.scala:155-180, Http.scala:168-184, 412-417, Resilience.scala:96-101, 178-186, Ports.scala:178-183), m1 (Http.scala:207, 263-279), m2 (Resilience.scala:141-147, Http.scala:357-397; caveat: servers stop before the pools, so an in-flight request at close() usually sees a cut connection, which work order 07 accepts), m3 (D15: QueryService.scala:532-543, Errors.scala:9-20), m4 (ServiceConfig.scala:52-55, 98-118, GraphService.scala:72-82), m5 (D16: QueryService.scala:315-321, GraphService.scala:160-165), m6, m7, cursor test (exactly 400), and the round 1 nits. Test changes are corrections, not weakenings. Phase 9 invariants hold.

## New findings (nits, none blocks the gate)

- service-engineer: QueryService.scala:417-421, the idempotency owner path catches only NonFatal; after a fatal Throwable the key's future is never released, so replays of that key get 504 until restart (eviction only takes done futures, Ports.scala:360).
- service-engineer: QueryService.scala:210, 222, any standalone 4-digit number counts as a year, so "top 1000 coauthors" sent with a window gets 422. Conservative, but undocumented.
- test-engineer: Phase10WireSuite.scala:56, `"sharesOrcidWith":null` should be `0` (the field is always an Int, ResearcherDirectory.scala:32).

Disposition: not repaired in this phase (repair round 2 not needed). Carried to Phase 11 with the human's agreement at acceptance.

## Pending (reviewer could not verify)

- Full `sbt check` on the repaired sources (the agent-run check stalled and was stopped; hang-record.md).
- build.sbt differs from HEAD only by the approved changes. Orchestrator check, run read-only after the review: `git diff --stat -- build.sbt project/DependencyVersions.scala project/plugins.sbt project/build.properties` shows only build.sbt, 13 insertions and 4 deletions. `git diff -- build.sbt` shows exactly two changes: the `-XX:+ExitOnOutOfMemoryError` flag with an extended comment in `sparkJvmOptions`, and the `services % "test->compile"` root edge with its comment. `project/DependencyVersions.scala` is unchanged. No heap change, no dependency or ledger change.

## Wire changes recorded from source (the service-engineer's repair report never arrived)

Query echo reduced to `{mode, window}` (`filtersApplied`, `windowApplied`, `filters`, `maxResults` removed). Filters, `limits.maxResults` and a window conflicting with the question's years are 422 UNSUPPORTED_CONSTRAINT. New 503 OVERLOADED with `Retry-After: 1`. New 409 JOB_FAILED on a failed job's result, original code in `details`. A replayed 202 reports the job's current state. `IdempotencyInProgress` removed from the error enum. Cursor secret must be at least 16 UTF-8 bytes with no control characters. Every `/v1` path authenticates before 404 or 405.
