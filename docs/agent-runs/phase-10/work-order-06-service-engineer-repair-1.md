# Work order 06: service-engineer, repair round 1

Owner: service-engineer. Writes only modules/services/**. Date: 2026-11-08. Input: docs/agent-runs/phase-10/review-round-1.md and decisions D13 to D17.

## Findings to repair (all in your owned paths)

1. **M1 (D13).** Refuse every constraint the workflow cannot apply with 422 `UNSUPPORTED_CONSTRAINT`: topic, venue and institution filters; `limits.maxResults` unless you can honor it by passing it to the workflow; a structured window that conflicts with a year in the question. Keep the fields in the schema and keep validating them. Drop or correct the `filtersApplied` and `windowApplied` echo fields only where they become meaningless, and document any wire change. Update your module tests to match.
2. **M2 (D14).** Separate bounded call pools per service (fast read service and slow service), bounded queues for them and for the HTTP executors, and a full queue gives 503 `OVERLOADED` so the dead `RejectedExecutionException` branch becomes live. A slow or stuck workflow call must not delay an evidence read or count against the evidence breaker. Address the `lock.synchronized` stuck-thread case in `LocalWorkflowPort` as far as is possible inside modules/services; if it needs a change in rag, report `BLOCKED-ON rag-engineer` and do not edit it.
3. **m1.** Remove the unbounded body drain, or cap it at a small constant, and make the comment at Http.scala:117-120 true.
4. **m2.** No retry after an interrupt. Always write a typed error body with a trace ID in a `finally` for any Throwable, including shutdown.
5. **m3 (D15).** Failed job result: 409 `JOB_FAILED`, original code in `details`. Add to the error enum.
6. **m4.** Validate the cursor secret at configuration load (non-empty, minimum length from configuration or a documented constant).
7. **m5 (D16).** Correct the wording at QueryService.scala:278-282 and document in the code that traversal in GraphService is a bounded read-through deferred to Phase 12.
8. **Nits.** Remove or use `IdempotencyInProgress`; make a replayed 202 report the current job state or document why not; document that the idempotency fingerprint is byte-exact; do not leave a Failed record for a job rejected at submission; decide and document whether 404 and 405 before authentication is intended (conservative default: authenticate first for `/v1/**`).

## Constraints

Local only. No new dependency, no edits to build.sbt, tests/**, modules/core, modules/rag, modules/storage, CLAUDE.md, docs/agents.md or .claude/**. Do not weaken or delete any test. Changed expectations in your own module tests are allowed when D13 to D15 require them, and you must list each one in the report. You are the only writer in this round. The root black-box tests under tests/** will fail where they expect the old behaviour (filters 200, failed-job status, and any other behaviour you change): that is expected, and the test-engineer repairs them in the next dispatch. Do not edit them. Never run two sbt commands at once. Run `sbt quick` after the repair. Do not run `sbt check`. No commit. Read-only git only.

## Report

Standard contract, plus per finding: what changed, file and line, which module test proves it. List every wire change (field added, removed or changed), the exact `sbt quick` counts per group, and which root tests under tests/** fail and why, by suite and test name.
