# W2-S report (service-engineer), received 2026-11-18 (night), archived as received

**Status: COMPLETE.** Both items done. Two bare `sbt quick` runs passed. The flake cannot be reproduced on demand; the cause and fix rest on reading the code.

**Cause of the `AwsRuntimeSuite` failure** (obtained 3 lines, expected 1, line 241)
- The suite kept one shared `lines` queue and `launch()` cleared it. Every runtime a test starts stays up until `afterAll`, and each writes to that same queue.
- `ServiceServer.handle` in `Http.scala` writes the `query_request` line after `ex.close()` and after the response is sent (lines 391-421); the client gets its answer before the line exists.
- Lines from earlier tests' requests (the 4 retrievalMode posts, the async test's 202) could land in a later test's capture after `lines.clear()`.
- `eventuallyLines(1)` returned as soon as the queue held one line, stale or not, then counted whatever was there. Under a 22-minute `sbt check` load, late lines can pile up to 3.
- Item 1 does not change what the test captures: attempt lines go to a separate logger; the stub model leaves no attempt record.
- Production code is not at fault: each query request writes exactly one line.
- Evidence: code reading only; not reproduced (the suite passed in both runs, 18 tests, 0 failures).

**Fix** (test isolation, assertion not weakened)
- Each `launch()` owns its own capture queue (`Started.lines`).
- `eventuallyLines(s, n)` waits for `n` lines, then settles 200 ms so a duplicate would still be counted.
- The test still asserts `logged.size == 1`, and also exactly one `query_request` line with this response's `x-request-id`.

**Item 1: attempt log lines**
- `QueryService` takes an optional constructor parameter `attemptSink: String => Unit`, default `QueryService.logAttempt` (INFO on logger `scholarchain.services.attempts`).
- `run()` calls `logAttempts(result)`, one line per `result.attemptLogLines` entry; sync and async both go through `run()`; a failing sink is swallowed.
- Lines come from rag's `ModelAttempt.logLine`: query ID, prompt ID, attempt number, accepted, stop reason, output tokens, rejection codes; no prompt or answer text.
- The `RequestLog` JSON line is untouched.

**Tests** (`QueryAttemptLogSuite.scala`, 3 tests, passed): "one line per attempt, in order, with the query ID and no text"; "a result with no attempt record writes no line"; "a failing sink never fails the query". Flake fix: the existing `AwsRuntimeSuite` test.

**Files** (all under `modules/services/`; no Wave 1 file touched): modified `src/main/scala/scholarchain/services/QueryService.scala`, `src/test/scala/scholarchain/services/AwsRuntimeSuite.scala`; created `src/test/scala/scholarchain/services/QueryAttemptLogSuite.scala`.

**sbt results** (bare `sbt quick`)
- Run 1: `Passed: Total 77, Failed 0, Errors 0`, `Passed: Total 1126, Failed 0, Errors 0, Passed 1126`, `[success] Total time: 163 s`, completed 4:37:32 AM.
- Run 2: `Passed: Total 1126, Failed 0, Errors 0, Passed 1126`, `[success] Total time: 159 s`, completed 4:40:29 AM.

**DEFERRED-AFTER-WAVE-1-COMMIT:** none.

**Open items**
- Two passing runs do not show a load-only flake gone; the next `sbt check` under load is the real evidence.
- Attempt lines are plain text on `scholarchain.services.attempts`, not JSON; whether the logger name reaches the ECS output (`load-tests/lib/service_log.py`, deployed logging config) not checked.
