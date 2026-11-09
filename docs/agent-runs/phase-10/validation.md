# Phase 10 validation

Recorded by the orchestrator, 2026-11-08, from evidence it read. Ratification is the human's and is not recorded here.

## Phase gate

- The human ran `sbt clean check` with `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false`, tee'd to /Users/winnguyen/scholarchain-data/logs/phase-10-clean-check.log. Result: `[success] Total time: 1501 s (0:25:01.0), completed Nov 8, 2026, 10:01:35 PM`, exit 0.
- Totals visible in the last 60 lines: `Passed: Total 81, Failed 0, Errors 0` and `Passed: Total 1018, Failed 0, Errors 0`. Other groups (core, services 94 and the rest) are in the log file and were not in the lines read; `[success]` means every group passed.
- It ran after the last source change (build.sbt OOM flag, 9:25 PM `sbt quick`). Later edits were docs only.
- Slower than Phase 9 (1096 s). No OutOfMemoryError recurred. The earlier stalled agent-run `sbt check` was a Spark fork OOM with a stuck JVM (hang-record.md); cause not proven, mitigated by `-XX:+ExitOnOutOfMemoryError`, heap unchanged at 2g.

## Counts (agent-reported, JUnit XML)

Phase 10 black-box suites at the last agent run: ApiContract 24, Course 24, Graph 12, GraphBounds 4, Security 14, Repair 19, Wire 10, EndToEnd 5 (112). Services module 94 tests. Phase 10 root tests ran in the root default group.

## Reviews

Round 1 REQUEST CHANGES (0 blockers, M1, M2, minors, nits). One batched repair round. Round 2 APPROVE (review-round-2.md).

## Open and deferred

- Nits carried to Phase 11: idempotency key stays unreleased after a fatal Throwable; any standalone 4-digit number counts as a year; frozen example `sharesOrcidWith` null should be 0.
- Heap raise (`-Xmx2g`) not done: human decision.
- Deferred to Phase 12 or 14: Secrets Manager injection, container image and ECS or ALB health wiring (Dockerfile has no services entrypoint), SQS or Step Functions, DynamoDB-backed idempotency and jobs, real model, live Neptune templates, graph traversal relocation (D16), `/metrics` content beyond local text.
- Process gaps: the service-engineer's initial and repair reports reached the orchestrator only as summaries (reports 01b and 05); wire changes were recorded from source. Parallel writers triggered a formatter-collateral halt (resolved, report 02a).
- Excluded from the commit (D17): AGENTS.md, .agents/, .codex/, datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, .DS_Store files.
