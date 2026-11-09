# Phase 11 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-09.
- Basis: morning-summary.md, decisions.md (through D17) and the review rounds (code-reviewer, Opus): REQUEST_CHANGES, REQUEST_CHANGES, then APPROVE. There was also a human-approved third repair round for a test-side race, and its independent review raised F1 to F6 (P-10).
- Workflow: the build ran unattended overnight under an explicit human prompt.

## Phase gate
- The first human `sbt clean check` FAILED: 1067 of 1068 passed. Phase10RepairSuite's "m1: 5 MiB body" test hit a test-side race. The service answers 401 and closes the connection before reading the body, so the client got a broken pipe.
- test-engineer fixed the race under tests/ only. A refusal now passes as either the typed 401/413 or an early close followed by a replay that is refused. Phase10EarlyCloseSuite (4 tests) forces the race with a fake server.
- The second human `sbt clean check` passed, with pipefail on: Passed: Total 81 and 1072, 0 failed, [success] in 1157 s, and sbt exit 0. Log: phase-11-clean-check-2.log.
- One green run does not prove the race is gone. It shows the fix broke nothing.
- Step 0: the formatter-hook live check passed in a fresh session (phase-10/workflow-fix/live-check.md).

## Delivered
- Carried defects resolved:
  - Phase 9 m5: claim IDs are now system-assigned.
  - Phase 9 n1: an abstention-only answer is marked abstained.
  - Phase 10: the idempotency key is released after a fatal Throwable.
  - Phase 10: "top 1000 coauthors" with a window now returns 200.
  - Phase10WireSuite: the example uses 0 instead of null.
- New defects found and fixed:
  - A throttled S3 Vectors query now degrades with a stated gap instead of failing the answer.
  - A query window is applied as echoed, or the request gets 422.
  - Fatal errors log the class name only, never the message.
- New adversarial suites (config, ER, faults, window agreement, XML truncation, carry-over) and the coverage matrix tests/ADVERSARIAL_COVERAGE.md.

## Carried forward
- REQUIRED before the Phase 12 scale run, owned by hadoop-engineer (P-4), four `.fail` defects:
  - Comment and CDATA XML records are read as live (AdversarialXmlSuite.scala:69).
  - Zero-author works increment SingleAuthorWorks (AdversarialMissingFieldSuite.scala:211).
  - A shared ORCID is not reported as a conflict (AdversarialDuplicateIdSuite.scala:290).
  - Pair consolidation depends on arrival order, which breaks determinism (AdversarialDuplicateIdSuite.scala:271).
- To Phase 12:
  - P-10, F1 to F6 on the round-3 test repair. F1 and F2 come first: a direct proof that the body was not processed, and typed-error checks on the replay.
  - P-1: async idempotency key release after a fatal error. The current behavior is the conservative one.
  - P-3: a public rag window reader, and degrading on transient Neptune or metric failures instead of returning a typed 503.
  - P-6: the bounded AWS integration tests.
- Accepted as noted:
  - P-5 test-reliability residuals (N-2, R-1, R-2).
  - Unmeasured: blocking-group memory beyond about 300 members, config omissions for Spark, storage and rag, and no typed storage error for a Neptune timeout.
- P-7 is out of date: prompts/** has been owned by rag-engineer since 6842c2e.
- Process slip (D9): an overlapping `sbt quick` in repair round 1. The human clean build supersedes it.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
