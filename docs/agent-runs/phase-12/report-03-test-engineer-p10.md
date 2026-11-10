# Report 03: test-engineer (work order 03, P-10 F1 to F6), as received (condensed)

STATUS: COMPLETE. F1 to F6 fixed. One optional item skipped (metrics-counter check). `sbt quick` ran once and passed. `sbt check` not run.
Owner: test-engineer. Work order: docs/agent-runs/phase-12/work-order-03-test-engineer-p10.md.

Files modified (tests/** only, no production edits): tests/adversarial/Phase10Http.scala, Phase10RepairSuite.scala, Phase10EarlyCloseSuite.scala. Phase10ApiContractSuite.scala not touched (same `assertOversizedRefused` signature).

## Commands
- `sbt Test/compile` failed once: `HttpClient.close()` is Java 21 only and the project compiles for Java 17. Fixed with a client-owned executor and `shutdownNow()`. Then passed.
- `sbt quick` once, exit 0.

## Results
`Passed: Total 62, Failed 0, Errors 0` then `Passed: Total 989, Failed 0, Errors 0`, `[success] Total time: 88 s`. Root group is up 23 from 966: 3 tests added in Phase10RepairSuite, 20 in Phase10EarlyCloseSuite. Only those two totals were seen (460 KB of interleaved output, grep on the saved file blocked by the guard). `sbt quick` skips `slowSuites`, so the Spark integration suites stay pending until the human's clean check.

## Did the early-close branch run?
Yes, in the guard tests (each asserts exactly 2 connections: reset, then replay). All passed, so the replay path has run end to end against `RawHttp.rawHeadAnswer` with the fake server. NOT observed: whether the real-service 5 MiB test took the branch, and whether the "401 then reset" fake took it. Their `EARLY-CLOSE-REPLAY` and `EARLY-CLOSE-PROBE` lines were not found in the log. Both pass on either race outcome. The new `streamOversized` tests against the real service passed.

## Proving test per finding
- F1: `RawHttp.streamOversized` (writer thread, 64 KiB chunks, main thread reads). `assertBodyNotConsumed` checks bytes written at the answer and after a grace period. Real-service tests: "m1: a streamed 64 MiB body to an anonymous /v1/query is not consumed" (401) and "... from a wrong-scope caller ..." (403). Negative controls on fake servers: never reads (passes); 401 then reset (tolerated only because the answer was read and the writer saw the reset); reads whole body then answers (fails); answers then reads body (fails); resets with no answer (helper throws). Threshold: declared/4 = 16 MiB of 64 MiB declared. A non-reading server leaves at most the loopback buffers plus the JDK 64 KiB drain (about 0.5 MiB on macOS, at most about 10 MiB on Linux with autotuning); a reading server moves 64 MiB within the 500 ms grace. 64 MiB declared instead of 5 MiB so the margin holds on Linux.
- F2: `rawHeadAnswer` returns a `Resp`; `assertReplayRefused` uses `assertTypedErrorWith` (the Api-based `assertTypedError` now delegates to it). Tests: "replay guard: ... an untyped 401 body FAILS" (must mention X-Request-Id), "... a non-JSON 401 FAILS" (text/plain), and "m1: the replayed head gets the full typed 401, 403 and 413 answers".
- F3: `postOversized` takes the early-close fallback only for 401 and 403; doc comment now describes the 4 MiB `oversizeDrain` for 413. Test: "replay guard: a 413 gets no early-close fallback" (IOException thrown, 1 connection). Behaviour change: an early close on a 413 now fails the test.
- F4: `isEarlyClose` accepts only "broken pipe", "connection reset", "fixed content-length". Test: "a non-IO exception and a bare IOException are not early closes".
- F5: `RawHttp.readResponse` frames by bytes, handles chunked (extensions, trailer, `Transfer-Encoding: gzip, chunked`, split UTF-8), `identity` with Content-Length, unframed to EOF. Hang, mid-body reset and garbage fail as typed failures. 5 s timeouts, close in `finally`.
- F6: two "401 then reset" tests (both accept either race outcome); refused-connection test asserts a ConnectException in the cause chain; timeout test shuts its client executor; six replay-guard tests (typed-401 control passes; early close then 200, typed 500, hang, reset, untyped 401 each fail; each asserts the branch ran).

## Skipped
The optional /v1/query metrics-counter check under `metrics.enabled`: the `/metrics` format and counter name are not in evidence, and `/metrics` returned 404 in the default runtime.

## Assumptions and risks
- Some OSes drop an unread answer on a TCP reset. The "401 then reset" fakes wait 200 ms so the main thread has read the 401. The race against the real service is not eliminated, but a loss fails closed.
- The `EARLY-CLOSE-REPLAY` and `EARLY-CLOSE-PROBE` `println` diagnostics are left in.
- No existing test weakened or deleted.
Manual action required: none. Blocked-on: none.
