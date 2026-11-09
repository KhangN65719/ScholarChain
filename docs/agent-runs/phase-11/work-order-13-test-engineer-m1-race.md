# Work order 13: test-engineer, repair round 3 (human-approved extension past the cap of 2)

Human approval: given in chat 2026-11-09 ("Option 1, human-approved third repair round"). Recorded in decisions.md D14.

## Failure
The human's `sbt clean check` failed one test: `scholarchain.tests.adversarial.Phase10RepairSuite`, "m1: a 5 MiB body from an unauthenticated client library is refused".
`java.io.IOException: fixed content-length: 187, bytes received: 0`, `Caused by: java.io.IOException: Broken pipe`, at Phase10RepairSuite.scala:287 via Phase10Http.scala:311 (`Http.send`). Full log: /Users/winnguyen/scholarchain-data/logs/phase-11-clean-check.log (search `==> X`). The same test flaked once earlier in an agent `sbt quick`.

## Task
1. Read the test and `Http.send`. Classify: test-side race (server refuses early and closes while the client is still writing, so the client sees a broken pipe instead of the 4xx) or product defect.
2. Race: the fix may accept "413 response OR connection closed before the body was consumed" as a refusal. It must still prove the server never processed the body (for example, no handler or idempotency side effect, no accepted job, no body bytes logged or stored, and the service still healthy afterwards). Do not weaken it into a pass-anything: any other exception or any 2xx/other status must still fail. Do not delete or skip the test.
3. Product defect: do not edit modules/services. Report `BLOCKED-ON service-engineer` with evidence. The orchestrator routes it.
4. Check whether other tests that send large bodies through `Http.send` have the same race (list them, fix only with the same rule).
5. Run `sbt quick` (once, never two sbt at once; stop and report a hang if silent 15+ minutes). Report exact counts and the exit status. Local only, no commit, no edits outside tests/.

## Report
Classification with evidence, the exact change, how the "server never processed the body" proof works, the test that proves the fix item, and the `sbt quick` result.
