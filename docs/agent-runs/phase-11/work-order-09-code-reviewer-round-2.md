# Work order 09: code-reviewer (Opus), Phase 11 gate, round 2 (changes only)

Read-only (Read, Grep, Glob). Return APPROVE or REQUEST_CHANGES. Do not edit any file. This is a fresh reviewer: read docs/agent-runs/phase-11/review-round-1.md first; it is the list you are checking.

## Scope: re-review only the changed items, plus anything the repair newly touched

Repair round 1 (reports 06 and 07, work orders 07 and 08, decisions D6 to D9):
- M-1 (major): modules/services/.../QueryService.scala (`compose`, the planner-window mirror, `strayYears`) against modules/rag/.../Planner.scala:366-402 (`windowsOf`, `private[rag]`) and :293. Re-trace the round-1 inputs (a) "since 2015" with window (2015,2015), (b) "in 2005 and 2009" with window (2005,2009), (c) "in 1850" and "in 2015 publications" with window (2000,2009), and look for new disagreements between the copy and the planner (overlap and priority rules, researcher-ID stripping, "overall"/"all time", "of 1000"/"for 1000"). Confirm that `request.window` always equals the planner-applied window or the request is 422, and that the tests prove it: modules/services Phase11Suite.scala (the corrected expectation) and tests/adversarial/Phase11WindowAgreementSuite.scala (43 questions, reads the applied window from explain). Can that suite fail on drift?
- m-1: Http.scala `Threads.daemon` UncaughtExceptionHandler, and Phase11RepairSuite.scala (modules/services). Check that the handler cannot itself throw, hang or leak the message, and that the global System.err redirection in the tests is restored.
- m-8: the InterruptedException tests in Phase11RepairSuite.
- m-3, m-4, m-5, m-6, m-7, n-1: tests/adversarial/Phase9RepairSuite.scala, Phase11FaultSuite.scala, Phase11XmlTruncationSuite.scala, Phase11ErSuite.scala, Phase11ConfigSuite.scala, tests/ADVERSARIAL_COVERAGE.md. Confirm each item is fixed and that no test was weakened or deleted. m-2 is accepted as a residual (D8); do not re-raise it unless the change made it worse.
- Process note D9: the service-engineer started a second `sbt quick` while the first was finishing. Judge whether that could have corrupted the evidence (the final `sbt quick` runs afterwards exited 0).

## Evidence

Orchestrator `git diff --stat`: 16 tracked files, 504 insertions, 142 deletions; Http.scala (+13) and QueryService.scala (+163) are the new tracked changes since round 1. No tracked CLAUDE.md, docs/agents.md, .claude/** or build.sbt change. New untracked: Phase11RepairSuite.scala (services module tests), Phase11WindowAgreementSuite.scala, plus the round-1 list.
Test evidence (agent, not the human gate): service-engineer `sbt quick` exit 0 (services 115 passed); test-engineer `sbt quick` exit 0 (`Passed: Total 62` and `Total 960`, 0 failed). The last full `sbt check` (exit 0, 12:57:44 AM, report 05) predates the repair; the Spark suites were not rerun since and no Spark source changed. The human's `sbt clean check` is pending.

## Report

Verdict first. Any new finding: severity, file:line, concrete input, impact, owning agent. Then each round-1 item marked fixed, not fixed or partially fixed with the basis. State what you could not verify.
