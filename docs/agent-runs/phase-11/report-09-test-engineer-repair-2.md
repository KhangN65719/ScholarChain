# Report 09: test-engineer (work order 11, repair round 2), as received (condensed)

STATUS: COMPLETE. Edits in tests/** only. No production code, no commit, no `sbt check`.

## Files
tests/adversarial/Phase11WindowAgreementSuite.scala (N-1); tests/adversarial/Phase10Http.scala (N-2: new `StreamSwapLock`, `LogTap` changed); tests/ADVERSARIAL_COVERAGE.md (N-3 and window-rules section).

## Command
Bare `sbt quick`, once, in the background (no other sbt). Exit 0, `[success] Total time: 75 s`, completed Nov 9 2026 1:31:49 AM, marker written. Final total "Passed: Total 962, Failed 0, Errors 0, Passed 962" (was 960). Spark group "Passed: Total 62, Failed 0, Errors 0". Root group counts read from JUnit XML. Spark integration suites (slow) were not exercised by this run; pending the human's `sbt clean check`.

## Per-item before and after
- N-1. Before: no window with start year below 1000 in the table; over-refusal undetectable. After: `edgeTable` from the six windows (1,2009), (500,1500), (999,2009), (1,9999), (1000,2009), (2000,9999), each in six shapes (36 rows): no-window question; matching padded window "in 0001-2009"; matching padded "between 0001 and 2009"; conflicting "in 2000-2009"; conflicting padded range with end+1 (end-1 for 9999); conflicting "since 2005". The agreement test runs `table ++ edgeTable` under the same rule (422 UNSUPPORTED_CONSTRAINT, or 200 with the echo equal to the applied window from `/v1/explain` `queryPlan.window` and no `comparisonWindow`). Two tests added: "N-1: the edge table has all six windows in six shapes each" (6 windows, 36 rows), and "N-1: a window with no window in the question is applied, never refused" (each of the six requires 200, echo equal, planner-applied equal; a 422 is reported as over-refusal). Suite XML: 5 tests, 0 failures, 0 errors (was 3).
- N-3. ADVERSARIAL_COVERAGE.md: Phase11FaultSuite 13 of 13 (XML tests=13). The "always" sentence rewritten as a tested guarantee over the table shapes (zero-padding fix, six windows, over-refusal test, 5 of 5 and 962 totals), stating it is not a proof because the services copy of the planner reader can still drift. "Log capture (repair 2, N-2)" paragraph added.
- N-2 (best effort). `StreamSwapLock`, a shared fair binary Semaphore in Phase10Http.scala (so a tap closed on another thread still releases it). `LogTap` takes it in its constructor and releases it in an idempotent `close()` with `finally`. Acquisition times out after 120 s with an AssertionError, so a never-closed tap fails the next suite loudly. No LogTap-user assertion changed. Phase10SecuritySuite 14, Phase11ConfigSuite 9, Phase11FaultSuite 13 pass, 0 failures. Grep blocked, so not every LogTap user was searched; none hit the 120 s error in this run.

## N-2 finding on JVM overlap (from build.sbt)
- No `Test / parallelExecution` or `concurrentRestrictions` setting; sbt defaults (parallel) apply. `fork` set only for sparkAnalytics (`Test / fork := true`, line 327). Root runs its "default" group `Tests.InProcess` (line 219); only Spark-named suites go to a forked group. services (lines 338-348), rag, storage and core are not forked and run in the sbt JVM.
- `quickTest.all(allProjects)` can run root suites and services module suites at the same time in one JVM. The quick output showed `[http-fast-*]` and `[http-slow-*]` lines interleaved across services and root suites and "[t-1] ERROR ... uncaught throwable thread=t-1 type=java.lang.InterruptedException" in the middle of unrelated suites, showing they share one stream. That does not show that a System.err swap by Phase11RepairSuite overlapped a root LogTap.
- tests/ suites are now serialized against each other; the module-side private `PrintStreamLock` in Phase11RepairSuite is unreachable from tests/, so a root LogTap and the services redirect can still nest.
- N-2 is RESIDUAL. Fixes belong to other owners: service-engineer (a lock shared via core or a services test helper that tests/ also references; the services test classpath cannot see tests/) or build-engineer (`Test / fork := true` for services, or a `Tags` restriction).

## Failing tests
None.

## Assumptions and risks
Edge-table expectations follow report-08 (zero-padded clause, `.toInt` in the planner). Spark integration suites and AWS-profile tests pending. Coverage-matrix count citations were edited after the run. The hadoop-engineer owner of the four `.fail` defects is still the test-engineer's assumption.

## Next gate
Independent re-review of the N-1 tests and `StreamSwapLock`; route residual N-2; the human's `sbt clean check`.
