# Report 16: test-engineer (work order 16, repair round 2: N2), as received (condensed)

STATUS: COMPLETE. tests/** only (tests/adversarial/Phase10Http.scala, Phase10EarlyCloseSuite.scala); no production edits. `sbt check` not run; slow Spark integration suites are skipped by quick and stay pending.

## Proof per item
1. Calibration fake no longer reads: the `BodyBaseline` fake answers 401 then only sleeps (100 ms steps, up to 30 s) until its socket closes; the `while in.read() >= 0` loop is gone. Docstrings updated; new `limitAtAnswer` and `limitAfterGrace` helpers are used by `assertBodyNotConsumed` and the control tests. Measured baseline: 131072 at answer, 720896 after grace (before: 2555904 after grace); now equal to the real service's value. Fails if the fake does not answer 401.
2. New control "streamOversized: a server that answers, then reads 4 MiB, FAILS the after-grace check (N2)": fake answers 401, waits 200 ms so the client samples `atAnswer` first, reads exactly 4 MiB, holds; grace 1000 ms. Asserts typed 401, `atAnswer < limitAtAnswer`, `afterGrace >= limitAfterGrace`, at least 3 MiB written between samples; then `assertBodyNotConsumed` must throw an AssertionError containing "after the grace period" and not "when the answer arrived". Can fail if the after-grace limit rises above what a 4 MiB reader writes, if the at-answer check fires first, if the fake reads too little, or if nothing throws.
3. Existing controls kept and passing (real 401 and 403, full read, answer-then-read-all, hold, reset, 4 MiB-before-answer, margin tests). 4. Cap stays declared/4 (16 MiB) as an upper bound only.

## Commands and results
`sbt quick` once (margins all at least 1 MiB, so one run was allowed): `Passed: Total 62, Failed 0, Errors 0`; `Passed: Total 992, Failed 0, Errors 0` (991 plus the one new test); `[success] Total time: 81 s`, completed Nov 9, 2026, 7:13:25 PM. Exit status not captured directly; inferred from `[success]`. target/phase10-body-measurements.txt is append-only; this run is the last 8 lines (15 to 22).

## Measured (bytes). Limits: at-answer 2228224, after-grace 2818048 (baseline plus 2 MiB; cap 16 MiB)
| Case | atAnswer | afterGrace | Margin |
|---|---|---|---|
| Baseline (holds, never reads) | 131072 | 720896 | n/a |
| Real service, anonymous 401 | 983040 | 983040 | 1.19 MiB under at-answer, 1.75 MiB under after-grace |
| Real service, wrong-scope 403 | 524288 | 524288 | 1.63 MiB under, 2.19 MiB under |
| Fake reads 4 MiB, then answers | 5308416 | 6946816 | 3.0 MiB over at-answer, 4.0 MiB over after-grace |
| Fake answers, then reads 4 MiB (new control) | 262144 | 8519680 | 1.88 MiB under at-answer, 5.4 MiB over after-grace |
Other: full read 67108864 after grace (over both); hold 262144 and 720896; reset 0 and 1441792 (1.3 MiB under after-grace). The answer-then-read-4-MiB case is caught only by the after-grace check.
Margin choice: kept the 2 MiB margin (baseline plus 2 MiB is 2.69 MiB; under any 4 MiB reader; about 1.2 to 2.2 MiB above the real service); a smaller margin would shrink the real-service headroom (about 1.2 MiB at answer time).

## Risks
The 200 ms delay before the new fake reads assumes the client samples `atAnswer` in that time (it was 262144 against 2228224 here). The baseline is one sample per JVM and moves with machine buffering; the real-service margin of about 1.2 MiB is one run, not repeated. Linux unverified (macOS, Java 21 only).
Blocked-on: none. Manual action: none.
