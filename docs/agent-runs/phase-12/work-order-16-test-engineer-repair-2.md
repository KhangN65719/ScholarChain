# Work order 16: test-engineer, repair round 2 (review 2 finding N2)

Owner: test-engineer. Scope: `tests/**` only. No production edits. Do not weaken or delete any test. One sbt command at a time; infra-engineer is editing infra/ and scripts/ at the same time but does not run sbt. Local only, no commit.

Read first: review-round-2.md (N2), report-12, tests/adversarial/Phase10Http.scala (the `BodyBaseline` object, around :548-580 and :834-836, and `assertBodyNotConsumed`).

## N2 (Medium): the BodyBaseline calibration fake reads the body
The fake answers 401 and then runs `while in.read() >= 0 do ()`. Its docstrings and report 12 say it never reads. The baseline grew from 131072 bytes at answer time to 2555904 after the grace period (a byte-at-a-time reader), while the real service stayed flat. So the after-grace limit (baseline plus 2 MiB) depends on loop speed and can climb toward the 16 MiB cap, which would let "answer, then read up to about 4 MiB" pass the after-grace check.

Required:
1. Make the calibration fake hold the socket open without reading (like the existing `holdUnread` control), so the baseline is only the bytes the OS buffers take. Fix the docstrings so they match.
2. Add a negative control: a fake that answers 401 and THEN reads exactly 4 MiB. `assertBodyNotConsumed` must FAIL it, and the failure must come from the after-grace check (assert the at-answer value is within its limit and the after-grace value is over its limit, or otherwise show which check caught it).
3. Re-measure and report the new baseline, the real-service 401 and 403, the 4 MiB reader (before and after answering) with the limits and margins. Keep all existing controls. Keep the cap at declared/4 as an upper bound only. If the new baseline plus 2 MiB still leaves the "answer, then read 4 MiB" case under the limit, tighten the margin from the measurements, and say how you chose it.
4. Run the measurement on at least two `sbt quick` runs only if the first shows margins under 1 MiB; otherwise one run is enough (never overlapping). The output file target/phase10-body-measurements.txt may be reused.

## Procedure
List which test proves each item first. Run `sbt quick` and report the exact `Passed:` lines, `[success]`, exit status and the measured values. Do not run `sbt check`. State how each new or changed test can fail.

## Report
Contract fields, the proving test, how it can fail, measured values with margins, and counts.
