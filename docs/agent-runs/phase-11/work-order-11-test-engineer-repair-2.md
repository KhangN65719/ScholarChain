# Work order 11: test-engineer, repair round 2 (N-1 table, N-3, N-2 best effort)

Owner: test-engineer (tests/**). Date: 2026-11-09. Inputs: review-round-2.md, report-08-service-engineer-repair-2.md. The service-engineer finished first. This is the last repair round.

## Tasks (tests/** only)

1. **N-1 table.** Add to tests/adversarial/Phase11WindowAgreementSuite.scala structured windows (1, 2009), (500, 1500), (999, 2009), (1, 9999), (1000, 2009) and (2000, 9999), each with a no-window question and with a question that already carries a matching or conflicting window. Same rule: 422 UNSUPPORTED_CONSTRAINT, or 200 with the echo equal to the planner-applied window read from `/v1/explain`. Add a table check that the suite can detect over-refusal for the cases that must apply: for the six windows with a no-window question, require 200 (not 422).
2. **N-3.** tests/ADVERSARIAL_COVERAGE.md: Phase11FaultSuite has 13 tests, not 12; the "always" guarantee for `request.window` is now supported by the zero-padding fix (N-1) and the extended agreement table; cite counts as they are in your final run.
3. **N-2 (best effort inside tests/).** tests/adversarial/Phase10Http.scala `LogTap` swaps System.out and System.err globally without a lock. Add a single process-wide lock (a shared object in tests/) that `LogTap` takes, so tests/ suites that tap the streams cannot nest. Do not edit the services module's private lock in Phase11RepairSuite (service-engineer territory). In the report, state whether the services module suite and root suites can overlap in one JVM (read build.sbt for `fork` and `Test / parallelExecution` settings and report what you find), and mark N-2 as residual if the module-side lock cannot be shared. Do not change any assertion in Phase10Http users.

## Constraints

Local only. Edit tests/** only. Do not edit production code, module tests, build.sbt, CLAUDE.md, docs/agents.md, .claude/**. Never weaken, skip or delete a test. Run bare `sbt quick` in the background, read its output file. Do not run `sbt check`. Never two sbt at once. 15 minutes of silence means stop and report a hang. Read-only git. No commit.

## Report

Standard contract, complete in the final message: per-item before and after, per-suite counts from the XML for the suites you changed, the final `sbt quick` totals and exit code, failing tests with owners, the N-2 finding on JVM overlap, and exact commands.
