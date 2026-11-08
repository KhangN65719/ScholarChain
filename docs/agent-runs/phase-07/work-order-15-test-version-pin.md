# Work order 15: test-engineer (stale `spark-analytics/1.0.0` pin in slow suites)

Evidence: orchestrator `sbt check` (finished 06:36:35, 1111 s, exit 1): `Failed: Total 558, Failed 1, Errors 0, Passed 557` (other group 63/0). Sole failure: `scholarchain.tests.analytics.SparkOutputsSuite` "every output row carries a complete derived provenance", `tests/integration/SparkOutputsSuite.scala:216`: expected `spark-analytics/1.0.0`, obtained `spark-analytics/1.1.0` (expected set at line 215, asserted on `runId` at 217). Cause: human ruling D5 bumped `RunContext.transformVersion` to 1.1.0 (work order 12); the test hard-codes the old literal. The slow suites in tests/integration are not run by `sbt quick`.

Task (tests/** only; do not weaken, delete or ignore):
1. Fix SparkOutputsSuite.scala:215: read the expected version from `RunContext.transformVersion` (single source of truth) instead of a literal, keeping the assertion at least as strong (the full provenance must still equal it exactly; also assert it is not `spark-analytics/1.0.0` is NOT required).
2. Read every other file in tests/integration and tests/adversarial that touches Spark outputs or provenance (the slow suites: SparkOutputsSuite, SparkCoauthorshipSuite, any Spark*Suite, anything with `transformVersion`) and replace any other literal `spark-analytics/1.0.0` the same way, so `sbt check` shows no second failure. List the files you read and changed.
3. Run `sbt quick` once (never two sbt at once) and report. The orchestrator then runs one more `sbt check`. Read-only git. No commit. No AWS.
