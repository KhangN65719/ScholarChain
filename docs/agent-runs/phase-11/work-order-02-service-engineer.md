# Work order 02: service-engineer, Phase 10 carry-overs

Owner: service-engineer (modules/services/**). Date: 2026-11-08. Inputs: docs/agent-runs/phase-10/ratification.md (carried items), decisions.md, review-round-1.md and review-round-2.md, this order. The rag-engineer finished work order 01 before you start; read modules/rag source if you need the wire shape.

## Findings to repair

1. **Idempotency key not released after a fatal Throwable.** When the workflow or a handler dies with a fatal Throwable (an Error such as OutOfMemoryError or StackOverflowError, or any Throwable that escapes the normal error path), the in-flight idempotency record is never released or failed, so every replay of that key gets 504 until restart. Fix: always release or fail the reservation in a `finally`-style path, including for fatal Throwables, so a later replay with the same key and body either re-runs or replays a stored typed failure. Choose one behavior, record it as a decision with its reason, and make sure the fatal Throwable itself is not swallowed silently (log without secrets, rethrow or map to a typed 500 with trace ID as the current design allows). Do not store a partial answer.
2. **A standalone 4-digit number counts as a year.** "top 1000 coauthors" with a structured window gets 422 (conflicting window). Fix: only treat a 4-digit number as a year when the context says so (year-like range 1900 to 2100 and either a year cue such as since, in, from, before, after, until, between, during, and, or 'year', or a range pattern), and never a number adjacent to a count cue (top, first, most, at least, best, limit, N coauthors/papers/researchers). If the year parse actually lives in modules/rag (the planner), do not edit it: report `BLOCKED-ON rag-engineer` with file and line and stop that item. State where it lives.

## Tasks

1. Before writing code, list in the report which specific test proves each item.
2. Implement in modules/services (or report BLOCKED-ON).
3. Add module feature tests: fatal Throwable (use a fake workflow that throws an Error subclass that is safe to throw in a test, not a real OutOfMemoryError) then replay of the same key returns something other than a permanent 504 and the key is released; the same for a Throwable in an async job; "top 1000 coauthors since 2015", "top 1000 coauthors" with a structured window, "since 2015", "between 2010 and 2015", "in 1999", "1000 papers", "2015 coauthors" cases. Existing tests that encoded the old behavior may be corrected only where they asserted the defect, with before and after listed. Do not weaken or delete other tests.
4. Run `sbt quick`. Root tests that fail only because they encode old behavior are not yours to edit: report `BLOCKED-ON test-engineer` with file, line and expected behavior.

## Constraints

Local only. No AWS, no new dependency, no Docker. Edit only modules/services/**. Do not edit CLAUDE.md, docs/agents.md, .claude/**, build.sbt, tests/** or modules/rag/**. Never two sbt commands at once; an sbt run silent for 15+ minutes is stopped and reported as a hang. Read-only git only. No commit.

## Report

Standard contract, in full in your final message. Include the pre-code test mapping, per-suite counts, changed expectations, decisions taken, wire changes, and exact commands with exit codes.
