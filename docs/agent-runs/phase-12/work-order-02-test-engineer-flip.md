# Work order 02: test-engineer, flip the four P-4 `.fail` twins

Owner: test-engineer. Scope: `tests/**` only. No production edits. Local only, no commit. Runs after work order 01 and before work order 03. One sbt at a time.

## Context
hadoop-engineer fixed four production defects in modules/hadoop (work order 01). Per its closing note, the files changed are XmlRecordStream.scala, DblpXmlInputFormat.scala, TeamExpansion.scala, TaskSink.scala, IdentifierJoin.scala, LocalPipelineRunner.scala and IdentifierJoinJob.scala, plus a new module suite PhaseTwelveDefectFixSuite.scala (18 passed, 0 failed). Its last `sbt quick` failed on 10 tests under tests/** that are red by design: the 4 `.fail` twins and 6 pins that assert the old behavior. Its full report text was not received by the orchestrator, so find the 10 yourself (see Procedure).

The four twins (Phase 11 ratification, "Carried forward"):
1. tests/adversarial/AdversarialXmlSuite.scala: "a commented-out region contributes no record" (`.fail`)
2. tests/adversarial/AdversarialMissingFieldSuite.scala: "zero-author works do not increment SingleAuthorWorks" (`.fail`)
3. tests/adversarial/AdversarialDuplicateIdSuite.scala: "shared ORCID on two authors is reported as a conflict" (`.fail`)
4. tests/adversarial/AdversarialDuplicateIdSuite.scala: "pair consolidation is independent of replay arrival order" (`.fail`)

## Rules (decision D1)
- Flip each twin by removing only the `.fail` tag. Do not change a twin's assertions.
- Each pin asserts the old buggy behavior. Invert it to assert the corrected behavior, keeping its intent and coverage. Do not delete a pin, and do not loosen it. Likely pins: the comment and CDATA "read as a live record" tests and "whether a commented-out record is ingested turns on one space" in AdversarialXmlSuite, and the order-dependent `forward != reversed` pin in AdversarialDuplicateIdSuite. Find the rest from the sbt output.
- Update stale comments in the flipped tests (for example "delete the `.fail` tag and the paired pins") so they describe what the test now proves.
- Also check tests/ADVERSARIAL_COVERAGE.md and any other tests/** text that records these four as open defects, and update them.
- If a flipped twin still fails after the flip, the fix is incomplete. Do not weaken the test. Report `BLOCKED-ON hadoop-engineer` with the failing input and output.

## Procedure
1. Before changing anything, run `sbt quick` once (never two sbt at once; report a hang after 15 silent minutes). List every failing test by exact name and file. Confirm the failures are exactly the 4 twins and about 6 pins. Any other failure is a regression: report it, and do not mask it.
2. List which test proves each item, as in work order 01.
3. Make the flips and inversions. Run `sbt quick` once more. Expect 0 failures.
4. Do not run `sbt check`.

## Report
Contract fields. Include the red-test list from step 1, a table of every test changed (before behavior, after behavior, assertions unchanged or inverted), the exact before and after counts and the exit status. State how each flipped test can fail.
