# Report 02: test-engineer (work order 02, flip the P-4 twins), as received (tables kept, prose lightly condensed)

STATUS: COMPLETE. Four twins flipped, six pins inverted. `sbt quick` after the change: exit 0, 0 failures.
Owner: test-engineer. Work order: docs/agent-runs/phase-12/work-order-02-test-engineer-flip.md.

## Step 1: red tests before any change
The first `sbt quick` output was truncated, so the summary lines were lost. The failures were recovered from the JUnit XML in target/test-reports/ (13:34:08): exactly 10 failures, in three suites, no regressions.

| File | Test | Kind | Failure |
|---|---|---|---|
| AdversarialXmlSuite | a record tag inside an XML comment is read as a live record | pin | expected 1 record, got 0 |
| AdversarialXmlSuite | a commented-out region contributes no record | twin | `.fail` expected failure but test passed |
| AdversarialXmlSuite | whether a commented-out record is ingested turns on one space | pin | expected 1 record, got 0 |
| AdversarialXmlSuite | a record tag inside a CDATA section is read as a live record | pin | expected 1 record, got 0 |
| AdversarialMissingFieldSuite | a zero-author work is counted as a single-author work | pin | SingleAuthorWorks expected 1, got 0 |
| AdversarialMissingFieldSuite | zero-author works do not increment SingleAuthorWorks | twin | `.fail` expected failure but test passed |
| AdversarialDuplicateIdSuite | one ORCID on two mentions of one work collapses to the first | pin | expected 1 ORCID claim, got 2 |
| AdversarialDuplicateIdSuite | pair consolidation is decided by arrival order when the tiebreak ties | pin | forward and reversed both retained the first run ID |
| AdversarialDuplicateIdSuite | pair consolidation is independent of replay arrival order | twin | `.fail` expected failure but test passed |
| AdversarialDuplicateIdSuite | shared ORCID on two authors is reported as a conflict | twin | `.fail` expected failure but test passed |

## Step 2: which test proves each fix
- Comment and CDATA regions yield no record: the XmlSuite twin and three pins.
- Zero-author works use `ZeroAuthorWorks`, not `SingleAuthorWorks`: the MissingFieldSuite twin and pin.
- A shared ORCID is a conflict, one claim per mention: the DuplicateIdSuite ORCID twin and pin.
- Consolidation independent of arrival order: the DuplicateIdSuite order twin and pin.

## Changes
Production behavior was read from `git diff` of IdentifierJoin.scala, TeamExpansion.scala and TaskSink.scala. hadoop-engineer's report was not available (D1a).

| Test (new name where renamed) | Before | After | Assertions |
|---|---|---|---|
| XmlSuite "a commented-out region contributes no record" | `.fail` | ordinary | unchanged, `.fail` removed |
| XmlSuite "a record tag inside an XML comment is not read as a record" | 1 record, parsed as ghost/1 | 0 records, and a live record after the comment is still read (live/1) | inverted, one assertion added |
| XmlSuite "a commented-out record is not ingested whatever the comment spacing" | spaced 1, tight 0 | both 0 | inverted |
| XmlSuite "a record tag inside a CDATA section is not read as a record" | 1 record | 0 records, and a live record after the section is still read | inverted, one assertion added |
| MissingFieldSuite "zero-author works do not increment SingleAuthorWorks" | `.fail` | ordinary | unchanged, `.fail` removed |
| MissingFieldSuite "a zero-author work is counted apart from a single-author work" | SingleAuthorWorks = 1 | SingleAuthorWorks = 0, ZeroAuthorWorks = 1; team, authorship, reject assertions kept | inverted, one assertion added |
| DuplicateIdSuite "shared ORCID on two authors is reported as a conflict" | `.fail` | ordinary | unchanged, `.fail` removed |
| DuplicateIdSuite "one ORCID on two mentions of one work keeps a claim per mention" | 1 claim, first mention | 2 claims whose entity IDs equal both mention IDs | inverted |
| DuplicateIdSuite "pair consolidation is independent of replay arrival order" | `.fail` | ordinary | unchanged, `.fail` removed |
| DuplicateIdSuite "pair consolidation keeps the lowest run ID whatever the arrival order" | forward != reversed, different run IDs | forward == reversed, both keep the lower runId | inverted |

Comment and text only: stale DEFECT and "delete the `.fail` tag" comments rewritten; tests/ADVERSARIAL_COVERAGE.md updated (top table, rows 1, 3 and 10, residual-gaps bullet) to record the four as fixed in Phase 12. Left unchanged: the mixed-script key defect in row 2 (carried, hadoop-engineer) and the other DEFECT pins in AdversarialXmlSuite.

## How each flipped test can fail
- XmlSuite: any record emitted from a comment or CDATA region, either comment style; the live-record-after assertions also fail if the skip region swallows the following record.
- MissingFieldSuite: a zero-author work incrementing SingleAuthorWorks; the pin also fails if ZeroAuthorWorks is not counted or the empty team, authorship or reject output changes.
- DuplicateIdSuite ORCID twin: ConflictRecords stays 0. ORCID pin: claims collapse to one or a mention loses its claim. Order twin and pin: retained record differs by arrival order; the pin also fails if it is not the lower run ID's record.

## Commands and results
| Run | Command | Result |
|---|---|---|
| Before | `sbt quick` | exit 1; 10 failures by JUnit XML |
| After | `sbt quick` | exit 0; [success] Total time: 82 s; Spark group Passed 62, Failed 0, Errors 0; root group Passed 966, Failed 0, Errors 0 |

`sbt check` not run, as ordered.

## Gaps and risks
- Before counts are partial: the first run's summary was truncated. The 10 failures are exact. "956 passed, 10 failed before" is inferred from 966 after, since no test was added or removed.
- `sbt quick` skips slow Spark suites. None were changed. They stay pending the human's `sbt clean check`.
- Added assertions (two live-record-after checks, the ZeroAuthorWorks check) go beyond the originals and passed.

Files changed: tests/adversarial/AdversarialXmlSuite.scala, AdversarialMissingFieldSuite.scala, AdversarialDuplicateIdSuite.scala, tests/ADVERSARIAL_COVERAGE.md.
