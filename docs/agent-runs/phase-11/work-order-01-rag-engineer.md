# Work order 01: rag-engineer, Phase 9 carry-overs m5 and n1

Owner: rag-engineer (modules/rag/**, prompts/**). Date: 2026-11-08. Inputs: docs/agent-runs/phase-09/review.md (round 2 follow-ups), phase-09 ratification.md, this order.

## Findings to repair

- **m5** `Model.scala:276`, `Verification.scala:743,757-763,61,70`: `claimId` is model-written free text (up to 40 chars) copied into Verified `AnswerClaim.claimId`, provenance and audit. Fix: claim IDs are assigned by the system, deterministically (for example from the run's query ID or answer position plus claim kind and a content hash), never copied from model output. A model-supplied `claimId` is ignored or dropped from the schema (your choice, record it; if the synthesis schema or prompt changes, bump the schema and prompt versions and re-pin the literal hashes in ModelPortSuite and Phase9RepairSuite as those suites do). IDs must be unique within an answer, stable across identical replays, and independent of any model text.
- **n1** `Verification.scala:741-754,802,671,782`: if the only kept claim is an abstention claim, the answer is not marked abstained and has no `abstentionReason`. Fix: when no non-abstention claim survives verification, the final answer is marked abstained with a system-written `abstentionReason`. Also stop repeating unsupported numbers parsed from model text in the weakened claim's `uncertainty` and in the audit; use system-written wording that does not echo model-supplied numbers or text.

## Tasks

1. Before writing code, list in the report which specific test proves each item (CLAUDE.md rule).
2. Implement both fixes in modules/rag only.
3. Add module feature tests: a model that supplies hostile or colliding claim IDs (same ID twice, injection text, empty, 10 000 chars) yields system IDs only; identical replays give identical IDs; an abstention-only synthesis output gives `abstained = true` with a reason; an unsupported number in model text does not appear in uncertainty or audit.
4. Existing tests that encode the old behavior may be corrected only where they asserted the defect itself (a model-written claim ID being preserved, an abstention-only answer not abstained). List every changed expectation with before and after. Do not weaken or delete any other test.
5. Run `sbt quick`. Fix module-side failures. Root tests under tests/ that fail only because they encode the old behavior: do not edit them; report them `BLOCKED-ON test-engineer` with file, line and the new expected behavior.

## Constraints

Local only. No AWS, no new dependency, no Docker. Edit only modules/rag/**. Do not edit CLAUDE.md, docs/agents.md, .claude/**, build.sbt or tests/**. Never run two sbt commands at once; if an sbt run is silent for 15+ minutes, stop it and report a hang. Read-only git only. No commit.

## Report

Standard contract, written in full (the Phase 9 repair report once failed to arrive: put the complete report in your final message). Include per-suite executed counts, the pre-code test mapping, changed expectations, new prompt or schema versions and hashes if any, wire changes visible to the services module, and exact commands with exit codes.
