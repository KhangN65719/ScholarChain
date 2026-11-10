# Work order 13: hadoop-engineer, repair round 1 (review finding L11) and the missing work order 01 report

Owner: hadoop-engineer. Scope: `modules/hadoop/**` only (module-owned tests included). No production change unless the new test exposes a real defect; if it does, report it first and fix only that. Do not touch tests/**. Local only, no commit. One sbt command at a time; test-engineer has finished sbt and infra-engineer does not run it.

## L11 (Low): the input format's look-back is untested
No test drives `DblpXmlRecordReader`'s look-back seek (DblpXmlInputFormat.scala around lines 85-90). Reverting it to `raw.seek(start)` would pass every test. Add a reader-level test in modules/hadoop with a small split where a commented-out region containing a record tag (and, separately, a CDATA section) straddles the split boundary. It must prove: the straddling region contributes no record from either split; a live record after it is read exactly once across the two splits; and reverting the look-back makes the test fail. Show that it fails by describing (or, if you can do it safely, running) the reverted variant; do not leave the revert in the source. Also cover the 1 MiB look-back limit: say what happens to a region longer than the look-back, and test the documented behavior.

## The missing report (decision D1a)
Your work order 01 report text never reached the orchestrator. In your FINAL MESSAGE (plain text, not a file) include it now, in contract format:
- the item-to-test list for the four P-4 defects;
- the zero-author counter decision (`ZeroAuthorWorks`: where it is defined, wired and documented);
- the pair-consolidation order rule, with its exact comparison keys;
- how the shared-ORCID conflict is emitted and what provenance it carries;
- every file you created or modified in work order 01.
Read your own changed files to write it. Do not guess; mark anything you cannot confirm from the files.

## Procedure
Run `sbt quick` once at the end. Report exact `Passed:` lines, `[success]` and exit status. Do not run `sbt check`.

## Report
Contract fields for this order, then the work order 01 report as above.
