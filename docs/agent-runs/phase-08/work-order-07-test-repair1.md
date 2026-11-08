# Work order 07: test-engineer, repair round 1

Owner: test-engineer (tests/**). Source: `docs/agent-runs/phase-08/review.md` round 1 (B1 tests, L8). Same rules as work order 05: local only, no new dependency, no production edits, never weaken or delete a test; report production defects as `BLOCKED-ON <owner>`.

## Context
The storage-engineer repaired B1, L2, L4, L6, L9 in `modules/storage/**` (work order 06). Its final report was not delivered (its stop gate replaced its final message), so read the production sources directly and work from them plus work order 06 and review.md. Facts from the storage-engineer's last messages: its one `sbt quick` exited 1 only because of TWO tests in `tests/adversarial/Phase8IngestSuite.scala` (around lines 648 and 694) that still expect the old behaviour; all storage suites passed. The new refusal for a same-label rerun with changed input is a new `ErrorCode` (read `modules/storage/src/main/scala/scholarchain/storage/Support.scala`; expected name `ExecutionInputChanged`); a document error on the unchanged path now rejects that key instead of failing the whole batch.

## Required work
1. Read `EmbeddingWork.scala`, `EmbeddingIngest.scala`, `SemanticDocuments.scala`, `JsonlExportSource.scala` and `Support.scala` in `modules/storage` as repaired. Do NOT copy the owner's tests; derive assertions from the specification below.
2. Repair the two failing tests so they assert the new, specified behaviour (not the old one):
   - same `executionLabel`, changed row text, added row, removed row, changed model, changed template version: the rerun is REFUSED with the new error code before anything is skipped, embedded or written; stored vectors and S3 documents are byte-identical afterwards; an identical rerun still resumes. This replaces the `.fail`-marked test "the same execution label over a changed row is not silent" (make it a plain passing test) and adds the added-row and changed-model cases.
   - a document error on the unchanged path rejects only that key with a recorded reason; the rest of the batch is written.
   Rewrite these as faithful assertions of the specification; do not delete coverage. If one of the two failing tests turns out to assert something the new spec still requires and production now breaks it, report `BLOCKED-ON storage-engineer` with evidence instead of changing the test.
3. L2 independent tests: a part file that is a symlink to an outside file with a matching SHA is refused by `JsonlExportSource.open`; a part file listed under the wrong table directory is refused.
4. L4: a template version bump that renders identical text works without `ImmutableKeyConflict`; document keys stay deterministic and the `textRef` fits the 200-character limit.
5. L6: a changed facet (`communityId`, `source`, `year`) under an unchanged text hash and model is not rewritten and is counted in the repair's drift counter (read the production name); nothing is overwritten.
6. L8: stop changing the JVM default `Locale` in the root suites. Run the locale-dependent checks without `Locale.setDefault` (for example pass the locale explicitly where the API allows, or run the check in a forked thread with a thread-confined approach); if production reads `Locale.getDefault` implicitly and no other way exists, say so in your report. No test may leave global state changed.
7. Keep every other Phase 8 test green.

## Process
Test-to-item list FIRST in your report. Run `sbt quick`; never two sbt commands at once; no `sbt check`. Report exact counts and which module totals you could not read.

## Report
Role-file contract. Orchestrator archives it as `report-07-test-repair1.md`.
