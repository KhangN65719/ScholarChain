# Phase 11 morning summary (2026-11-09)

**GATE MET (2026-11-09, second clean check, log phase-11-clean-check-2.log):** `Passed: Total 81` (Spark/Hadoop) and `Passed: Total 1072, Failed 0, Errors 0`, `[success] Total time: 1157 s`, `sbt exit: 0` (pipefail, so sbt's own status). 1072 is the first run's 1068 plus the 4 new Phase10EarlyCloseSuite tests. Still open as recorded, not gate blockers: review-round-4 findings F1 to F6 (P-10), and P-1, P-3 to P-9. The m1 race was flaky by nature, so one green run shows the fix did not break anything, not that the race is gone.

*Superseded, kept for history:* the first clean check FAILED (1152 s). `Failed: Total 1068, Failed 1, Errors 0, Passed 1067`; the Spark/Hadoop group passed 81 of 81. The one failure is `scholarchain.tests.adversarial.Phase10RepairSuite`, test "m1: a 5 MiB body from an unauthenticated client library is refused" (`java.io.IOException: fixed content-length: 187, bytes received: 0`, caused by `Broken pipe`, at Phase10RepairSuite.scala:287 via Phase10Http.scala:311). The "exit code 0" the background task showed was `tail`'s, not sbt's. This is the same test that flaked once in an agent `sbt quick` (see Process notes). Likely a test-side race: the server answers 4xx and closes before the client finishes writing 5 MiB. Not confirmed, and not classified by an owner. Owner: test-engineer (tests/). Not repaired: the 2-round repair budget was used. See decisions.md D13 and P-8.

**Round 3 (human-approved, D14/D15, report-10):** classified as a test-side race, no product defect. test-engineer changed the test to accept a typed refusal or an early close, with a raw-socket replay, log-marker and healthz proof, and fixed the same race in the 3 MiB 413 test; new Phase10EarlyCloseSuite (4). Agent `sbt quick`: exit 0, root group 966 passed, 0 failed. The gate is still open until your `sbt clean check` is green. Limits: the "body not processed" proof is indirect (P-9), and the round 3 change is not independently reviewed.

**Review of round 3 (review-round-4.md): REQUEST_CHANGES**, six findings, none repaired (D16, P-10). Main gaps: the "body not processed" proof is indirect (F1), the replay skips the typed-error checks (F2), and the 3 MiB 413 fallback does not fit the server (F3). No other large-body test has the same race. The second clean check (phase-11-clean-check-2.log) decides the gate.

Review verdict: APPROVE (fresh Opus code-reviewer, round 3), after round 1 REQUEST_CHANGES (1 major) and round 2 REQUEST_CHANGES (1 new major), with two batched repair rounds. The review predates the clean-check failure. Nothing is committed. Do not commit Phase 11 until the gate is green.

## Step 0: formatter hook, live
PASS on all three file classes (module main, module test, tests/). Each deliberate error was reformatted in the Edit call, and `git diff --stat` stayed empty. Evidence: docs/agent-runs/phase-10/workflow-fix/live-check.md. Limit: single-line spacing and one indentation error, one file at a time.

## What was built
- **Carried defects, all resolved by their owners:**
  - Phase 9 m5: claim IDs are system-assigned (`claim-N-kind-12hex`); the synthesis schema moved to `synthesis-output/3`. (rag-engineer)
  - Phase 9 n1: an abstention-only answer is now marked abstained (`claims-unsupported`), and unsupported numbers are no longer echoed. (rag-engineer)
  - Phase 10 idempotency key: released after a fatal Throwable; an async job that dies fatally is Failed with 409 JOB_FAILED on `/result`. (service-engineer)
  - Phase 10 4-digit-number-as-year: "top 1000 coauthors" with a structured window is 200. (service-engineer)
  - Phase10WireSuite.scala:56: null changed to 0. (test-engineer)
- **Defects found by the adversarial pass and review:**
  - D1: a throttled S3 Vectors query killed the whole answer. Now it degrades with a system-written gap. (rag-engineer)
  - M-1 and N-1: a structured window could be echoed while the planner applied a different one. Now the window is applied exactly as echoed, or the request is 422 (zero-padded clause for years below 1000). (service-engineer)
  - m-1: a fatal Error's message could reach stderr. Worker threads now log the class name only. (service-engineer)
- **Adversarial tests (test-engineer, tests/):** Phase11CarryOverSuite, Phase11FaultSuite, Phase11XmlTruncationSuite, Phase11ErSuite, Phase11ConfigSuite, Phase11WindowAgreementSuite, a Phase11Rig, plus tests/ADVERSARIAL_COVERAGE.md mapping all 21 required categories to tests. Also repaired Phase9ModelSuite and Phase9RepairSuite for system claim IDs. A `StreamSwapLock` was added to LogTap.
- **Module tests:** rag Phase11ClaimIdSuite (8), Phase11SemanticDegradeSuite (7); services Phase11Suite and Phase11RepairSuite.

## Test counts (agent evidence, not the gate)
- Full `sbt check`: exit 0 at 12:57:44 AM, 1071 s. `Passed: Total 1062, Failed 0, Errors 0`. The five group lines I could read sum to 1047, so the total of 1062 is unconfirmed. This run predates both repair rounds.
- Latest `sbt quick` (test-engineer, 1:31:49 AM, exit 0): `Passed: Total 962, Failed 0, Errors 0`. Spark group: 62, 0 failed. Services module: 118 passed.
- The Spark slow suites (tests/integration) have not run since the repairs. No Spark source changed.
- New suite counts from the XML: Phase11CarryOverSuite 9, Phase11FaultSuite 13, Phase11XmlTruncationSuite 7, Phase11ErSuite 6, Phase11ConfigSuite 9, Phase11WindowAgreementSuite 5, Phase9ModelSuite 27, Phase9RepairSuite 25, Phase10WireSuite 10.

## Review
- Round 1 (REQUEST_CHANGES): M-1 plus m-1 to m-8, n-1. Repair round 1 by service-engineer and test-engineer.
- Round 2 (REQUEST_CHANGES): N-1 major, N-2 to N-4. Repair round 2 by service-engineer and test-engineer.
- Round 3 (APPROVE): N-1, N-3 and N-4 fixed; N-2 partly fixed. Open: R-1, R-2 (below).
- Files: review-round-1.md, review-round-2.md, review-round-3.md. Reports 01 to 09 and work orders 00a to 12 are in docs/agent-runs/phase-11/.
- Ownership: the reviewer found no violation. No reviewer wrote code. No change to CLAUDE.md, docs/agents.md, .claude/** or build.sbt.

## PENDING-HUMAN (full text in decisions.md)
- P-1: should async idempotency keys be released when a job dies fatally? Current behavior (key kept, replay returns the Failed job) is the conservative one.
- P-2: superseded; "in 1850" with a window is now 422.
- P-3: whether rag should expose a public window reader (services keep a copy of the planner's, so drift is possible, guarded by the agreement test); whether `TransientStorageException` from Neptune or metric reads should degrade instead of a typed 503. Both deferred to Phase 12.
- P-4: owner and repair of four carried `.fail` defects: comment and CDATA XML records read as live (AdversarialXmlSuite.scala:69), zero-author works increment SingleAuthorWorks (AdversarialMissingFieldSuite.scala:211), a shared ORCID not reported as a conflict (AdversarialDuplicateIdSuite.scala:290), pair consolidation depends on arrival order (AdversarialDuplicateIdSuite.scala:271). The test-engineer assumed hadoop-engineer; unconfirmed.
- P-5: open residuals: N-2 (System.err swap by a services suite can overlap a root LogTap, low probability), R-1 (the 120 s LogTap lock timeout could error a queued P10 suite on a slow machine), R-2 (matching-window edge rows not forced to 200).
- P-6: bounded AWS integration tests were not run (no approved profile, local only).
- P-7: nothing needs factory-engineer. `prompts/` still has no owner (Phase 9 P1).
- Unmeasured: blocking-group memory beyond about 300 members; config omissions for Spark, storage and rag have no independent test; a Neptune timeout has no typed storage error (typed 503, nothing leaks).

## Process notes
- One rule slip: in repair round 1 the service-engineer started a second `sbt quick` while the first was finishing (D9). The final run was clean. The reviewer judged it unlikely to have corrupted the evidence. Your clean build settles that.
- The main session could not run `find`, `grep` or pipes (the hook refused), so it used `ls`, Read and agents.
- One earlier `sbt quick` run showed a possible timing flake in Phase10RepairSuite "m1: a 5 MiB body..." (broken pipe). It passed in the full check and later runs.

## Commands for you (run with the `!` prefix, one per line, `sbt clean check` last)
The first clean check failed (see top). After test-engineer's fix, rerun it without a pipe so the exit status is sbt's, or use `set -o pipefail`.
```
! git status --short
! git diff --stat
! sbt clean check
```

Excluded from the commit as before: AGENTS.md, .agents/, .codex/, datasets/slice/, docs/agent-runs/phase-06/readback-paths.py and the .DS_Store files. Include the untracked Phase11* test files, docs/agent-runs/phase-11/ and docs/agent-runs/phase-10/workflow-fix/live-check.md in the Phase 11 commit.
