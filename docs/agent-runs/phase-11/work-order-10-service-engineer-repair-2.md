# Work order 10: service-engineer, repair round 2 (N-1, N-4)

Owner: service-engineer (modules/services/**). Date: 2026-11-09. Input: review-round-2.md (N-1, N-4). This is the last repair round.

## Tasks

1. **N-1.** A structured window whose start year is below 1000 (or any year the planner would not read as a 4-digit year) must not be echoed while a different window is applied. Preferred fix: zero-pad the appended clause to four digits (`from 0001-2009`) if the planner then reads exactly (1, 2009); verify by reading Planner.scala:366-402 (do not edit modules/rag). If zero-padding cannot be proven to match the planner for every year from 1 to 9999 (for example the planner's year regex or its `strayYears` mirror treats "0001" differently), instead return 422 UNSUPPORTED_CONSTRAINT when startYear < 1000 or endYear < 1000, with a clear message. Record the choice and why. Also check `planWindows` and the "pass unchanged" branch for the same gap: a question that carries a window and a structured window below 1000. Cases that must hold: {1,2009}, {500,1500}, {999,2009}, {1,9999}, {1000,2009}, {2000,9999}: each is either applied exactly as echoed or 422.
2. Add module tests in Phase11Suite for those cases over `compose` and over HTTP (200 with the echo equal to the applied window, or 422).
3. **N-4.** `QueryRequests.years` is dead in production and kept for tests. Either remove it and move its remaining test uses to the supported function, or mark it test-only and correct the stale comment. Do not delete or weaken any test: if you remove the function, the tests that used it must still assert the same behavior through the production function.

## Constraints

Local only. No AWS, no new dependency. Edit modules/services/** only. Do not edit tests/**, modules/rag/**, CLAUDE.md, docs/agents.md, .claude/**, build.sbt. Run bare `sbt quick` in the background and read its output file. First confirm no other sbt is running. Never two sbt at once. 15 minutes of silence means stop and report a hang. Read-only git. No commit.

## Report

Standard contract, complete in the final message: the choice for N-1 with proof, pre-code test mapping, changed expectations, per-suite counts, exact commands and exit codes.
