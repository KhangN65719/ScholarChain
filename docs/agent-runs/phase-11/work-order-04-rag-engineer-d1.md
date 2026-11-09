# Work order 04: rag-engineer, D1 (throttled S3 Vectors query kills the answer)

Owner: rag-engineer (modules/rag/**). Date: 2026-11-09. Inputs: report-03-test-engineer-adversarial.md (section "Failing test"), tests/adversarial/Phase11FaultSuite.scala:213.

Orchestrator ruling: the fix belongs in the consumer, not storage. Semantic context is optional, deterministic evidence must not depend on it. The storage port's documented behavior (`TransientStorageException` for throttling) stays.

## Defect

`SemanticContextAgent.context` (modules/rag/.../Retrieval.scala ~257-262) catches only `StorageException`. A `TransientStorageException` (plain RuntimeException) escapes and the whole query fails with 503, though the question needs only deterministic metrics.

## Tasks

1. Before code, list in the report which test proves the fix.
2. Make semantic retrieval degrade on any storage read failure that is a normal runtime failure of the vector query (`StorageException` and `TransientStorageException`; do not catch fatal Throwables or InterruptedException). On failure the agent returns no semantic context and records a system-written known gap (a fixed string, with no exception message or class echoed into answer text). The deterministic claims stay verified. The audit records that semantic retrieval degraded, using system-written text.
3. If the question explicitly requires semantic explanation (a semantic-only operation), the answer must abstain or state the gap honestly, never invent context. Keep the existing behavior of the sibling `StorageException` case and make both paths identical.
4. Add module tests: a throttled vector query leaves a deterministic answer verified with the known gap in the bundle; a semantic-only question with a throttled vector query abstains or states the gap; an exception message containing a canary never appears in the response or audit.
5. Run `sbt quick`. Do not edit tests/**. If tests/adversarial/Phase11FaultSuite.scala:213 still fails after your fix for a test-side reason, report BLOCKED-ON test-engineer with the evidence.

## Constraints

Local only. Edit modules/rag/** only. No new dependency. Never two sbt at once; 15 minutes silent means stop and report a hang. Read-only git. No commit. No edits to CLAUDE.md, docs/agents.md, .claude/**, build.sbt, tests/**.

## Report

Standard contract, complete in the final message, with per-suite counts, the test mapping, whether Phase11FaultSuite now passes (read its XML), and exact commands with exit codes.
