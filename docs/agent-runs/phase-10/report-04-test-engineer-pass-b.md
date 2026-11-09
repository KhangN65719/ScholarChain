# Report 04: test-engineer, pass B (as received, 2026-11-08)

Author: test-engineer. Archived by the orchestrator without edits. Status: COMPLETE for test execution; the phase gate is pending.

Commands, one at a time:
1. `sbt check`: exit 0 `[success]`. Final `Passed: Total` lines not read (the guard refused tail and grep on the saved log).
2. `sbt quick`: exit 1. Phase10GraphBoundsSuite failed one test.
3. `sbt quick` after the test fix: exit 0. Marker current on these sources.

Per-suite Phase 10 results, from the JUnit XML:

| Suite | Executed | Failures | Errors |
|---|---|---|---|
| Phase10ApiContractSuite | 24 | 0 | 0 |
| Phase10CourseSuite | 20 | 0 | 0 |
| Phase10SecuritySuite | 14 | 0 | 0 |
| Phase10GraphSuite | 12 | 0 | 0 |
| Phase10WireSuite | 7 | 0 | 0 |
| Phase10EndToEndSuite | 5 | 0 | 0 |
| Original six-suite total | 82 | 0 | 0 |
| Phase10GraphBoundsSuite (new, final run) | 4 | 0 | 0 |
| Black-box total | 86 | 0 | 0 |

Other group totals seen in the `check` console, Failed 0 and Errors 0: services group (LocalRuntimeSuite) 79; three further groups 207, 253 and 389.

77 versus 82: 82 is the real count of the original six suites. Pass A's 77 was a miscount.

Test fix: the new suite sent `limit` as a 20-digit number and expected 200. The service answers 400 INVALID_REQUEST because it accepts 1 to 18 digits. Judged valid typed handling, not a production defect. The test now expects a typed 400 for that input. Capping assertions still run on 100, 100000 and 2147483647.

Vacuity review:
- All ten course items and gate items 2 to 5 can fail, checked against Auth.scala, Resilience.scala, OpsService.scala, Routes.scala, QueryService.scala and GraphService.scala.
- Fault-injection keys in Phase10Http.scala match LocalRuntime.
- Gate item 1 (routes embed no retrieval) is proven only by the module-owned RoutesStructureSuite (3 tests, passed). The black-box suites cannot prove it.
- Weak assertions left as they are: contract examples with null accept any type (key presence only); the end-to-end explain check on planner and synthesis prompt IDs is satisfied unconditionally because ExplainViews lists every prompt and `calledInThisQuery` is not asserted; the one-error-code-per-status test collapses 404 and 405 label variants; the cursor-bound-to-filters test accepts 200 or 400 when the filter is dropped.
- Loose `<= 1000` and `<= all.size` bounds in Phase10GraphSuite are now covered by Phase10GraphBoundsSuite (graph.maxLimit=1, graph.maxDepth=1, plus a control that depth 2 reaches further).

Production defects: none. No BLOCKED-ON.

Files created: tests/adversarial/Phase10GraphBoundsSuite.scala. Modified: none outside it. Nothing deleted or weakened.

Residual risk, not covered: Secrets Manager injection, container image and ALB health wiring (Phase 12 or 14); non-loopback network or real AWS adapters; /metrics content beyond a 200 text response; durability across processes (idempotency and jobs are in-memory fakes); sustained or large-scale load (8 to 16 threads only).

Manual action required: the human's `!`-prefixed `sbt clean check`. Next gate: independent review.
