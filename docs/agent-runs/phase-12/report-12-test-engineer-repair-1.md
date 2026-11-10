# Report 12: test-engineer (work order 12, repair round 1: M10, L9, L10), as received (condensed)

STATUS: COMPLETE. tests/** only. `sbt quick` ended `[success] Total time: 87 s`; `Passed: Total 62, Failed 0, Errors 0` and `Passed: Total 991, Failed 0, Errors 0`. `sbt check` not run (ordered). Slow Spark integration suites are skipped by quick and stay pending.
Runs: `sbt quick` twice, one at a time (the order said once). The first passed with the same totals (62 and 991, Failed 0) but the interleaved log hid the `BODY-NOT-CONSUMED` lines, so a file write to target/phase10-body-measurements.txt was added and the run repeated; reported totals and measurements are from the second run.
Files modified: tests/adversarial/Phase10Http.scala, Phase10EarlyCloseSuite.scala, AdversarialMissingFieldSuite.scala.

## M10: fixed
Tests: new "streamOversized: a server that reads 4 MiB of the body, then answers, FAILS (M10)" (fake server reads exactly 4 MiB, answers 401, holds; asserts typed 401, writer got at least 4 MiB through, and that `assertBodyNotConsumed` throws); new "streamOversized: the limit sits between a never-reading and a 4 MiB reader (M10)" (baseline below 16 MiB, margin below 4 MiB). Existing real-service tests ("m1: a streamed 64 MiB body to an anonymous /v1/query is not consumed" and the wrong-scope one) and all existing negative controls (full read, answer then read, hold, reset) kept and passing.
Threshold: `assertBodyNotConsumed` no longer uses a fixed declared/4. A new `BodyBaseline` object measures once per JVM how many bytes the loopback buffers take from a 64 MiB body when a fake answers 401 and never reads; limit = baseline plus 2 MiB margin, for both `atAnswer` and `afterGrace`, capped at declared/4. Linux buffer reasoning stays in the scaladoc as a documented upper bound, not the threshold.
Measured (bytes; atAnswer, afterGrace): baseline fake never reads 131072, 2555904; real service anonymous 401 720896, 720896; real service wrong-scope 403 917504, 917504; fake reads 4 MiB 5177344, 6029312; fake reads everything then answers 67108864, 67108864; fake answers then reads 131072, 67108864. Limits this run: atAnswer 2228224, afterGrace 4653056.
Margins: real service atAnswer 0.69 to 0.87 MiB under its limit; the 4 MiB reader's atAnswer 2.8 MiB over, so it fails clearly; the 4 MiB reader's afterGrace only about 1.4 MiB over, so the grace check alone is thinner and `atAnswer` carries detection. One run's sample; the baseline moves with machine buffering; not repeated.
How it can fail: real-service tests fail if the service consumes more than baseline plus 2 MiB; the control fails if the limit is raised above what a 4 MiB reader writes; the calibration fails if its fake does not answer 401.

## L9: fixed
Replay-guard reset case needs "the connection failed on the replayed head: java.net.SocketException" instead of the empty needle. Partial-body case (7 of 500 bytes then reset) must name either "EOF after 7 of 500 body bytes" or "the connection failed on the replayed head"; a new clean-close variant asserts the exact "EOF after 7 of 500 body bytes".
## L10: fixed
The AdversarialMissingFieldSuite comment now says ZeroAuthorWorks separates a zero-author work from a single-author one.

## Assumptions and risks
The reset needle assumes the JDK reports the reset as a SocketException; held on macOS and Java 21; Linux unverified. `assertBodyNotConsumed` appends to target/phase10-body-measurements.txt (harmless build output).
Blocked-on: none. Manual action: none.
