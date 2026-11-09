# Work order 03: test-engineer, adversarial pass for Phase 11

Owner: test-engineer (tests/**). Date: 2026-11-08. Inputs: docs/agent-runs/phase-11/prompt.md (course section and scope note), report-01-rag-engineer.md, report-02-service-engineer.md (read both), phase-09 and phase-10 ratification.md, existing suites under tests/.

## Part A: repair root tests that encode old behavior (rag m5 and n1)

Per report-01, 16 root tests fail because claim IDs are now system-assigned (`claim-N-kind-12hex`) and the synthesis schema moved to `synthesis-output/3` (hash 3ff03e974a310c6399465499474c70c1242bed7520e9db8f45b42129bbb89226):
- tests/adversarial/Phase9ModelSuite.scala lines 86, 117, 148, 210, 251, 281, 316, 390, 466, 538: find claims by position or kind, not by the model-written ID. Keep every assertion about verdict, change, reason and audit; only the lookup key changes. Also assert the system ID shape and that no model-written ID text leaks.
- tests/adversarial/Phase9RepairSuite.scala lines 90, 110, 178, 306 (lookup by ID) and 589, 616 (schema pin `/2` to `/3`). The hash must be an independently pinned literal, not read from the code under test: use the value above, which the rag-engineer took from a failing run, and add a test that recomputes sha256 of the schema text in the test to confirm the literal (so the pin is not circular).
- List every changed expectation before and after. Do not weaken or delete any test.

## Part B: carried defects in tests/

- tests/contracts/Phase10WireSuite.scala:56 has a frozen example that uses `null` where the wire contract should show 0. Fix the example to 0 (confirm in modules/services source that the real wire value is 0 and that the matcher still checks types). If production actually emits null, leave it failing and report BLOCKED-ON service-engineer.
- Add regression tests at the HTTP level for the Phase 10 carry-overs once report-02 shows how they were fixed: the idempotency key is released after a fatal Throwable (a replay does not get a permanent 504), and "top 1000 coauthors" with a structured window is accepted (not 422), while a true year conflict ("since 2015" with a window ending 2010) is still 422. Add the Phase 9 n1 case end to end: an abstention-only model output through the services returns an abstained answer with a reason. If a fix is BLOCKED or not delivered, write the test anyway and leave it failing, reported against the owner.

## Part C: required adversarial categories

1. Build a coverage matrix: each category in prompt.md "Required categories" mapped to existing test names and files (read the existing suites under tests/ and modules/*/src/test). Save it as tests/ADVERSARIAL_COVERAGE.md.
2. For each category with weak or missing coverage, add adversarial tests under tests/ (new files, named Phase11*). Candidates to check hardest: truncated DBLP XML mid-tag and mid-entity; confusable-character (homoglyph) author names must not silently merge into one researcher without an uncertainty record; conflicting DOI (same DOI, different titles) and duplicate DOI records; reordered author lists; very large blocking groups (bounded pair expansion, no unbounded memory); coauthor edges whose endpoints are unresolved or conflict (must not enter the graph as canonical edges); empty and disconnected graphs for every Spark measure; known-graph oracles for distinct degree, components, shortest path, bridge and k-core; large-team pair expansion n(n-1)/2; missing topic, institution, abstract and profile metadata; duplicate vector keys and stale model versions; prompt injection in title, abstract and full-text chunks; LLM malformed JSON and fabricated researcher, evidence and work IDs; unsupported claims; Neptune timeout; DynamoDB conditional-write conflict; S3 Vectors throttling and transient failure; replayed workflow and idempotency; API authentication and authorization, malformed bodies, timeouts; logging redaction (a secret, a bearer token and an API key placed in request, config and failure paths never appear in logs or responses); configuration omissions (each required key missing gives a clear typed error, no secret value in the message).
3. Use only existing production seams and local fakes. No AWS, no network, no new dependency.
4. A test that exposes a production defect stays failing. Never patch production code. Never weaken, skip or delete a test to make the run green. For each failing test report: test name, file and line, concrete input, observed versus expected, impact, and owner (per docs/agents.md).
5. Each new test must be able to fail: for each new suite, state in the report the production behavior whose removal would make it fail (a test that would pass even if the behavior were broken is a finding against you).

## Spark and slow suites

`sbt quick` skips `slowSuites` (the root Spark integration suites). Report any Spark suite you add or change as pending until `sbt check` runs.

## Constraints

Local only: no AWS, credentials, paid APIs, Terraform, Docker. Edit only tests/**. Do not edit production code, module tests, build.sbt, CLAUDE.md, docs/agents.md or .claude/**. If build.sbt needs a `slowSuites` entry, report BLOCKED-ON build-engineer. Never run two sbt commands at once; an sbt run silent for 15+ minutes is stopped and reported as a hang. Run `sbt quick` after edits, iterate on test-side failures only, then run `sbt quick` once more for the final counts. Do not run `sbt check` in this order (the orchestrator will order it). Read-only git only. No commit.

## Report

Standard contract, complete in your final message. Include: exact per-suite executed counts from the JUnit XML for every suite you added or changed, the total tests and failures for the whole run, the Part A before/after list, the coverage matrix path, a per-requirement coverage statement, each failing test with owner, the test-can-fail statements, residual risks, and exact commands with exit codes.
