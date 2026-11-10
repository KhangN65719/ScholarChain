# Work order 12: test-engineer, repair round 1 (review findings M10, L9, L10)

Owner: test-engineer. Scope: `tests/**` only. No production edits. Local only, no commit. Do not weaken or delete any test. One sbt command at a time. infra-engineer is editing infra/ and scripts/ at the same time but does not run sbt.

Read first: review-round-1.md (test-engineer section), report-03, tests/adversarial/Phase10Http.scala, Phase10RepairSuite.scala, Phase10EarlyCloseSuite.scala, AdversarialMissingFieldSuite.scala.

## M10 (Medium): the F1 proof misses a server that reads part of the body
`assertBodyNotConsumed` accepts up to declared/4 = 16 MiB written at answer time. A server that reads up to about 10 to 16 MiB first would pass. Fix:
- Add a negative control on a fake server that reads exactly 4 MiB (the real `oversizeDrain` size) and then answers 401: the check must FAIL. If that cannot be made to fail without breaking the real-service tests, tighten the threshold using values you MEASURE on the real service, and say what you measured.
- Record the measured `atAnswer` and `afterGrace` byte counts for the real-service 401 and 403 cases (print them in the test output and put them in your report).
- Keep the existing negative controls. Re-derive the threshold from the measurements with a stated margin, and keep the Linux-buffer reasoning as a documented upper bound, not as the threshold.

## L9
- Phase10EarlyCloseSuite: the replay-guard test that passes the needle `""` must assert a meaningful message fragment, and the partial-body case must check the specific reason, not only that the message is non-empty.
## L10
- Update the stale comment in AdversarialMissingFieldSuite.scala (the "no counter separates it" text) to say that `ZeroAuthorWorks` now separates it.

## Procedure
List which test proves each item first. Run `sbt quick` once at the end (never overlapping; report a hang after 15 silent minutes) and report exact counts, the exit status, and the measured values. Do not run `sbt check`. State how each new or tightened test can fail.

## Report
Contract fields, per finding: fixed or not, the proving test, how it can fail, measured values, counts and exit status.
