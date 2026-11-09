# Work order 07: test-engineer, repair round 1

Owner: test-engineer. Writes only tests/**. Date: 2026-11-08. Inputs: review-round-1.md, decisions D13 to D17, report 05.

## Context

service-engineer repaired modules/services (94 module tests pass). Two root tests fail because they encode the old behaviour, which D13 corrects: Phase10CourseSuite "course 4: supported constraints are accepted" and Phase10WireSuite "frozen request examples are accepted". You are the only writer. Read modules/services/src/main/** to learn the repaired behaviour (new wire fields, 409 JOB_FAILED, 503 OVERLOADED, pool and queue override keys in LocalRuntime). The service-engineer's full report did not reach the orchestrator, so source is the evidence.

## Tasks (tests/** only)

1. Fix the two failing tests to the D13 behaviour: topic, venue and institution filters, a conflicting window and an unappliable `limits.maxResults` get 422 `UNSUPPORTED_CONSTRAINT` with a trace ID. Keep a positive case for the constraints that are truly applied. This corrects expectations. It must not weaken: each changed test still asserts status, error code, and trace ID, and you list every changed expectation in the report.
2. M2: add a test where a slow workflow (`fault.workflow=slow`, small pool) runs concurrently with fast reads. The reads of a missing evidence ID must return 404 (not 504), the evidence breaker must stay closed, and `/readyz` must stay ready. Add a test that a full queue returns 503 `OVERLOADED` with a trace ID.
3. D15: add a test that a failed async job's `/result` returns 409 `JOB_FAILED` with the original code in `details`.
4. m1: a test that an oversized body from an unauthenticated caller is refused promptly (401 or 413) without the server hanging.
5. m2: a test that a request in flight during runtime `close()` gets either a typed error with trace ID or a clean connection failure, never a hang.
6. m4: a test that an empty or too-short cursor secret fails configuration load with a clear error.
7. m6: freeze richer examples in Phase10WireSuite: Location and statusLink on the 202, neighbors, total, truncated, depth, limit, edgeTypes, nextCursor on neighbors, and evidence graphPath, textPointer, content. Make the matcher check types, not only key presence, and match the top-level body rather than any nested object.
8. m7: for an answered query, assert the synthesis prompt in explain has `calledInThisQuery == true`.
9. Tighten the cursor-with-dropped-filter test to exactly 400.
10. Nits: tests for the replayed 202 state, and for 404/405 versus authentication per the service-engineer's documented behavior.

## Constraints

Local only. Edit only tests/**. Do not edit production code, build.sbt, CLAUDE.md, docs/agents.md or .claude/**. Do not weaken or delete tests. Never run two sbt commands at once. Run `sbt quick` after your edits and fix test-side failures. Then run `sbt check` once. A production defect is left failing and reported `BLOCKED-ON service-engineer` with the failing input. No commit. Read-only git only.

## Report

Standard contract, plus: per-suite executed counts from the JUnit XML (all Phase 10 suites, root and module), the list of changed expectations with before and after, new test names mapped to review findings, and the exact commands with exit codes.
