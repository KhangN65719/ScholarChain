# Work order 06: code-reviewer (Opus), Phase 11 gate, round 1

Read-only. You have Read, Grep and Glob only. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Task

Perform the adversarial read-only review in docs/agent-runs/phase-11/prompt.md ("Use this work order for review", the 12-point order), against the Phase 11 gate:
- No critical or major verified finding remains.
- Every production behavior has a relevant test.
- All tests pass under their declared profiles.
- No reviewer wrote production code.
- Repair records identify owners and rerun evidence.

Verify every candidate finding against actual files before reporting. For each finding: severity (critical, major, minor), file and line, concrete failure input, expected impact, owning agent (per docs/agents.md).

## Scope and evidence

Phases through 10 are accepted (HEAD 6842c2e). The Phase 11 diff is uncommitted. Orchestrator-supplied `git diff --stat` (tracked): modules/rag main (Claims, Model, Retrieval, Synthesis, Verification, Workflow) and rag tests (ModelPortSuite, RepairRound1Suite, VerificationSuite, WorkflowSuite); modules/services main (QueryService, Resilience); tests/adversarial/Phase9ModelSuite, Phase9RepairSuite; tests/contracts/Phase10WireSuite. 15 files, 405 insertions, 135 deletions. No tracked constitutional file (CLAUDE.md, docs/agents.md, .claude/**, build.sbt) is modified.
New untracked files: modules/rag/src/test/.../Phase11ClaimIdSuite.scala, Phase11SemanticDegradeSuite.scala; modules/services/src/test/.../Phase11Suite.scala; tests/ADVERSARIAL_COVERAGE.md; tests/adversarial/Phase11{CarryOver,Config,Er,Fault,XmlTruncation}Suite.scala and Phase11Rig.scala; docs/agent-runs/phase-11/**; docs/agent-runs/phase-10/workflow-fix/live-check.md. Untracked and out of scope (no owner): .agents/, .codex/, AGENTS.md, datasets/slice/, readback-paths.py, .DS_Store files.

Read: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-11/{prompt.md, decisions.md, report-01..05, work orders 01 to 05}, phase-09 and phase-10 ratification.md and review files, tests/ADVERSARIAL_COVERAGE.md.

Test evidence (agent-run, not the human gate): rag-engineer `sbt quick` exit 0 (62 and 956 passed); test-engineer `sbt check` exit 0 at 12:57:44 AM, 1062 total passed, 0 failed, 0 errors (overall total not individually confirmed; about 15 tests of group totals unread). The human's `sbt clean check` has not been run.

## Specific things to check

1. The Phase 9 m5 and n1 and Phase 10 carry-overs (idempotency release after fatal Throwable, 4-digit year, Phase10WireSuite null to 0) are fixed in the owner's files and proven by a test that can fail.
2. Part A edits to Phase9ModelSuite and Phase9RepairSuite: confirm no assertion was weakened or removed (compare the git history by reading; the diff is large), the `added(r, n)` positional lookup cannot pass vacuously, and the schema pin is independent.
3. Whether the catch in `SemanticContextAgent` is too broad or too narrow, whether any other stage still lets a `TransientStorageException` escape as 503 where the course gate expects degradation (rag-engineer noted Neptune/metrics), and whether that matters for the gate.
4. The fatal-Throwable handling in QueryService and Resilience: lock or key leaks, double completion, thread-pool effects, and logging redaction.
5. The year parser: false positives and negatives (for example "in 1850", "papers 2015", "top 2015", "since 2015 and 2010").
6. The ADVERSARIAL_COVERAGE.md claims against the real tests: every row marked READ must be backed by an actual test; NAME rows are weak evidence. Name categories with no real test.
7. Tests that would pass if the behavior were broken in the new Phase11* suites (point 10 of the review order). The test-engineer supplied "test-can-fail" statements; check them.
8. Ownership: no production file edited by test-engineer, no tests/** edited by production owners, nothing under .claude/**, CLAUDE.md, docs/agents.md or build.sbt.
9. Course categories still thin: Neptune timeout has no typed storage error, comment/CDATA XML records read as live (carried from earlier phases), blocking-group memory unmeasured, config omissions for Spark, storage and rag. Say which are acceptable residual risks and which are major.

## Report format

Verdict first. Then findings ranked by severity, each with severity, file:line, concrete input, impact and owning agent. Then a per-gate-item pass or fail, then anything you could not verify and why (you cannot run tests or git).
