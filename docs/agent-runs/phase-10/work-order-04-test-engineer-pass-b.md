# Work order 04: test-engineer, pass B (execution)

Owner: test-engineer. Writes only tests/**. Date: 2026-11-08.

## Order

Use the test-engineer agent to execute and verify the Phase 10 black-box suites against the finished services module.

## Context

The build edge is in (report 03). `sbt quick` passed at 7:21:40 PM with the Phase 10 root suites green. Counts are incomplete: Phase10WireSuite and Phase10GraphSuite were not counted, and your pass A (77) and collateral review (82) disagree.

## Tasks

1. Run `sbt check` once (never two sbt commands at once). Tee to /Users/winnguyen/scholarchain-data/logs/ if the guard permits it, otherwise use the output you see. Report exact executed counts per Phase 10 suite and per group, and reconcile the 77 versus 82 discrepancy against what actually ran.
2. Verify the tests can fail. For each course-required test and each of the five gate items in spec.md, confirm by reading the service source that the assertion would fail if the behavior were broken (no vacuous assertions, no `assert(true)`, no catch-all that swallows failures, fixtures that actually exercise the path). Report any vacuous test. Fix a vacuous test in tests/** only.
3. If a test fails because of a production defect, leave it failing, do not edit modules/**, and report `BLOCKED-ON service-engineer` with the failing input and expected behavior.
4. Report what the 79 module tests and the 77 to 82 black-box tests together do and do not cover against the course gate, as residual risk.

## Constraints

Local only. No AWS, Docker, or network beyond loopback. Do not weaken or delete any test. Do not edit modules/**, build.sbt, CLAUDE.md, docs/agents.md or .claude/**. No commit. Read-only git only.

## Report

Standard contract, plus per-suite executed counts, the reconciliation, the vacuity review result, and the exact command.
