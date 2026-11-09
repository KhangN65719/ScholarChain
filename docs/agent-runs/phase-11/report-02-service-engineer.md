# Report 02: service-engineer (work order 02, Phase 10 carry-overs), as received

STATUS: COMPLETE for modules/services. Both repairs are done and every services test passes. `sbt quick` exit code is 1, but only because of the 16 known root tests under tests/ (Phase9ModelSuite and Phase9RepairSuite), already reported BLOCKED-ON test-engineer in report-01. No marker was written, because the run failed.
Owner: service-engineer. Work order: docs/agent-runs/phase-11/work-order-02-service-engineer.md.

## Pre-code test mapping (all in modules/services/src/test/scala/scholarchain/services/Phase11Suite.scala)
- Finding 1, fatal Throwable releases the key and a replay re-runs: "P11-S1: a fatal Throwable releases the key and a replay re-runs" (fatal thrown by a fake `QueryRecordStore.put`).
- Finding 1, rethrown and not stored: "P11-S1: a fatal Throwable is rethrown, never swallowed or stored" (after the throw, `idempotency.claim` returns `Owner`).
- Finding 1, fatal Error inside the workflow: "P11-S1: a fatal Error in the workflow is 503 and the key is free" (503 `DOWNSTREAM_UNAVAILABLE`, replay succeeds).
- Finding 1, async: "P11-S1: a fatal Throwable in an async job fails the job" (job Failed, code INTERNAL, no result, `result()` 409 `JOB_FAILED`, replay of the key returns the same jobId, `replayed = true`, state Failed).
- Finding 2, year context: "P11-S2: only a year in a year context is a year" ("since 2015", "between 2010 and 2015", "in 1999", "2000-2009", "1000 papers", "2015 coauthors", "top 1000 coauthors", "top 1000 coauthors since 2015", plus more count-cue and year-cue cases).
- Finding 2, structured window: "P11-S2: a count is not a year for the structured window" ("top 1000 coauthors" with a window composes to "... from 2000-2009").
- Finding 2, real conflicts: "P11-S2: a real year that conflicts with the window is still 422".
- Finding 2, HTTP: "P11-S2: 'top 1000 coauthors' with a structured window is 200".

## Where the year parse lives
In services: `QueryRequests.compose`, whose `yearToken` regex took any 4-digit number. The rag planner (`Planner.windowsOf`) already reads years in context. No BLOCKED-ON rag-engineer.

## Decisions
- P11-S1: on a fatal Throwable the owner of an idempotency key (1) completes the future exceptionally with a typed `ApiException(INTERNAL)`, so a waiting replay gets a typed 500; (2) releases the key in a `finally`, so a later replay re-runs (alternative of storing a typed failure rejected: it would pin a transient fatal to the key); (3) logs the class name only; (4) rethrows, so `ServiceServer.handle` still answers 500 with the trace ID and rethrows. No partial answer stored.
- InterruptedException is not `NonFatal`, so the old code leaked the key for it too. The new `case t: Throwable` branch covers it.
- P11-S1 async: the job is failed (Failed, INTERNAL) and no longer stays Running forever. A fatal is logged by class and rethrown so the pool replaces the dead worker. An interrupt (shutdown) restores the flag and fails the job. The key of an async submission is NOT released: it holds the stored 202 job reference, as for a NonFatal job failure; a replay returns that reference and the job shows Failed. Releasing async keys would couple `JobService` to the idempotency port (human choice if wanted).
- `Downstream` now logs the class of a fatal Error thrown in a pool thread, then rethrows; the caller still sees the typed 503.
- P11-S2: a 4-digit number is a year only when it is 1900 to 2100, is not directly after a count cue (top, first, most, best, at least, at most, up to, last, limit [of/to]), is not directly before a count noun (coauthors, papers, researchers, publications, similar), and follows a year cue (since, in, from, before, after, until, till, between, during, and, year, for, of) or is part of a range. "for" and "of" were added to the order's cue list because the planner reads them as year cues.

## Wire changes
None to routes, shapes or error codes. Behavior: "top 1000 coauthors" with a structured window is 200 (was 422). A fatal Throwable no longer leaves the key or job stuck. An async job dying fatally shows Failed, with `JOB_FAILED` on the result route.

## Files
Modified: modules/services/src/main/scala/scholarchain/services/QueryService.scala (fatal branch in `QueryService.submit`, companion `log`, fatal handling in `JobService.execute`, `QueryRequests.years` used by `compose`); Resilience.scala (fatal logging in `Downstream`, `Downstream.log`).
Created: modules/services/src/test/scala/scholarchain/services/Phase11Suite.scala.
Changed expectations in existing tests: none.

## Commands and results
- `sbt quick` twice, exit 1 both times. The first failed one Phase11Suite test because munit `intercept` does not catch a fatal Throwable; fixed in the test with try/catch. Second run is final. Counts from `target/test-reports` XML (stdout truncated).
- modules/services suites, failures 0, errors 0: Phase11Suite 8, QueryRequestSuite 10, RepairSuite 11, LocalRuntimeSuite 42, ResilienceSuite 10, ConfigAuthSuite 9, GraphRequestSuite 9, RoutesStructureSuite 3 = 102. Only Phase11Suite and LocalRuntimeSuite were re-read after the final run; the other six counts are from the first run (sources unchanged, second run recompiled only test-classes).
- Root suites failing: Phase9ModelSuite 27 run 10 failed; Phase9RepairSuite 24 run 6 failed (see report-01). Phase10SecuritySuite 14 of 14 passed. Other root suites not individually read.
- `sbt check` not run.

## Assumptions and risks
- A year outside 1900 to 2100 after a year cue, such as "in 1850", is not treated as a year (per the order). With a structured window the composed question would hold both "in 1850" and the window clause, and the planner would read two windows. Widening to cue-preceded numbers 1000 to 9999 would close this.
- A count noun not in the list ("2015 scholars") counts as a year only if cued, as described.
- Phase10WireSuite.scala:56 is a tests/ item, not in this order.
- Fatal-Throwable fault injection is only through direct `QueryService` tests; `LocalRuntime` fault gates have no fatal mode.

## Next gate
BLOCKED-ON test-engineer: update the 16 root tests (report-01). Then rerun `sbt quick`. Then independent review and the human's `sbt clean check`.
