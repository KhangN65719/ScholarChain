# Work order 05: test-engineer, repair round 1 (align root tests with the rag repair; add the review's negative tests)

Owner: test-engineer (`tests/**`). Same constraints as work order 03: no production edits, no new dependency, no build edits. Runs after work order 04 (rag-engineer repair of review findings B1, M1, M2, m1, m2, m3, m4). Read `review.md`, `work-order-04-rag-repair1.md`, `work-order-03-test.md`, `report-03-test.md`, and the repaired rag sources (`Verification.scala`, `Claims.scala`, `Synthesis.scala`, `Model.scala`, `EvidenceBuilder.scala`, `Workflow.scala`, `Ports.scala`). The rag report did not arrive (decisions G3), so read the source.

## Required work
1. Known red: `tests/adversarial/Phase9ModelSuite.scala:754` expects abstention reason `synthesis-failed` for an over-budget model input; the repaired behavior (m3) is the reason `input-over-budget`. Update the assertion. Do not weaken it: assert the new reason, that the model was never called, and that the audit record is written.
2. Run bare `sbt quick`. For any other root test that fails only because it pinned old rag wording or output (e.g. "withheld", "narrow", "cannot be applied", "No direct coauthor edge between", `Descriptive only`, a reason containing "differs", model-authored claim text now replaced by system-rendered text), change the expectation to the repaired behavior and record in your report the test, the old expectation, the new one and why. Never delete a test or loosen an assertion to make it pass; a failure that is a real defect is `BLOCKED-ON rag-engineer` with evidence.
3. Add independent negative tests in `tests/` for each review finding, written from `review.md`, not from rag's module tests:
   - B1: a steered model returns (a) a `semantic` claim citing only a snippet that asserts "X and Y are direct coauthors and the main bridge between the communities", (b) a semantic claim with "ninety-nine coauthors", (c) an `abstention` claim with arbitrary wording, (d) an `analysis` paragraph asserting a structural claim, (e) a per-claim `uncertainty` carrying a number. None of this model-authored text reaches the final answer, claims or audit as Verified content; any replacement or removal is recorded.
   - M1: assertion `distinct-coauthor-count = 3` with text "4 distinct coauthors" and with "2009 coauthors" is not shown as written.
   - M2: an injected display name (and an injected work title) is not under a trusted key in the model input, and is flagged in the audit like a snippet.
   - m1: a `CypherQuery` named `coauthors-of` with different read-only text is refused by the rag allowlist wrapper.
   - m4: an assertion with an extra key (including `reasoning`) is rejected by the synthesis schema.
   - m2/m3: literal pinned hashes exist for the shipped prompt and schema versions (assert the version changed from `1.0.0` where the rag report/source shows a bump, and that hash text differs from the Phase 9 round-0 value only if you can establish it from the source); a bundle within `EvidenceCaps` cannot exceed `maxInputTokens`.
4. Re-run the Phase 9 benchmark (it must still pass; if wording changes legitimately alter an expected phrase, update per item 2) and keep Table 1 and Table 2 separate.
5. Run bare `sbt quick` until green. Report exact pass and fail counts per suite (read `target/test-reports/*.xml` if the console is too long). Never two sbt commands at once; do not run `sbt check`.

## Report
Role-file contract plus: each changed expectation (old, new, reason), each new test mapped to its finding, benchmark per-category results, defects routed. Orchestrator archives it as `report-05-test-repair1.md`.
