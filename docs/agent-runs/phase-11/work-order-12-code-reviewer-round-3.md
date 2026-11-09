# Work order 12: code-reviewer (Opus), Phase 11 gate, final re-review of repair round 2 (changes only)

Read-only (Read, Grep, Glob). Return APPROVE or REQUEST_CHANGES. Do not edit any file. Fresh reviewer: read docs/agent-runs/phase-11/review-round-2.md first (the list you are checking). The repair budget of 2 rounds is used: any finding you return is recorded as open for the human and is not repaired in this run.

## Scope: only these changes

- N-1 and N-4 (service-engineer, report-08): modules/services/.../QueryService.scala `compose` (zero-padded clause), the pass-unchanged branch for windows below 1000, the `years` comment; Phase11Suite.scala new N-1 tests. Re-trace against Planner.scala:366-402 and :87: windows {1,2009}, {500,1500}, {999,2009}, {1,9999}, {1000,2009}, {2000,9999}, questions "in 0500-1500", "in 0999", "since 0500". Confirm `request.window` always equals the planner-applied window or the request is 422, now including sub-1000 windows. Check the maximum question length check and the 4-digit regex (`\b` boundaries) for the padded clause appended to a question that ends in a digit or punctuation.
- N-1 tests, N-2 and N-3 (test-engineer, report-09): tests/adversarial/Phase11WindowAgreementSuite.scala (`edgeTable`, the over-refusal test), tests/adversarial/Phase10Http.scala (`StreamSwapLock` and `LogTap`: deadlock, leak, a tap never closed, nested taps on one thread — a second `LogTap` created while the first is open on the same thread would block for 120 s; check whether any suite does that), tests/ADVERSARIAL_COVERAGE.md counts and claims.
- N-2 residual: the services-side System.err redirect cannot share the lock. Judge whether it can make any assertion pass vacuously or flake, and whether it is an acceptable residual for the gate.

## Evidence

Service-engineer `sbt quick` exit 0 at 1:28:27 AM (services 118 passed). Test-engineer `sbt quick` exit 0 at 1:31:49 AM, total 962 passed, 0 failed. The last `sbt check` (exit 0, 12:57:44 AM) predates repair rounds 1 and 2; the Spark slow suites have not been rerun and no Spark source changed. The human's `sbt clean check` is pending. No tracked CLAUDE.md, docs/agents.md, .claude/** or build.sbt change (orchestrator `git diff --stat`).

## Report

Verdict first. For each of N-1, N-2, N-3, N-4: fixed, partially fixed or not fixed, with basis. Any new finding with severity, file:line, concrete input, impact and owner. State what you could not verify.
