# Phase 05 gate: sbt clean check (human run)

Run by the human architect in a plain terminal, 2026-11-07, after the stub files in `er/labeling/` were removed:

`SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check 2>&1 | tail -n 40`

Visible in the last 40 lines (tail only; earlier output not captured here):
- tests module: `Passed: Total 190, Failed 0, Errors 0, Passed 190`
- hadoop module: `Passed: Total 170, Failed 0, Errors 0, Passed 170`
- `[success] Total time: 17 s, completed Nov 7, 2026, 12:11:31 PM`

Not visible in the tail: core and entity-resolution counts (earlier agent runs reported core 41, entity-resolution 130). The `WARN` lines (JobTracker metrics, JobResourceUploader) are Hadoop LocalJobRunner warnings from the end-to-end suite, not failures.

Caveats: the run was `sbt clean check` with server flags rather than the bare `sbt check`, so it does not itself write the `.claude/.last-test-run` marker via the agent-allowed path; whether the marker was refreshed is unconfirmed. Fixed at this point in time: the sources changed after this run (none known) would make it stale.

## Second run (after the hadoop driver fix), human, 2026-11-07 12:21 PM
Same command (`SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check 2>&1 | tail -n 40`). Visible: `scholarchain.hadoop.PipelineDriverOutputsSuite` 7 tests passed; hadoop `Passed: Total 177, Failed 0, Errors 0`; `[success] Total time: 28 s`. The lines `ScholarChain stage source-normalization/openalex finished without writing expected output works` and `ScholarChain pipeline failed at stage source-normalization/openalex` come from the deliberate failure test ("a dataset that should produce works but writes none fails the run"), which passed. Other modules' counts are not in the 40-line tail (agent run reported 41, 130, 190). `sbt clean` removed `target/` again; no logs were kept there.

## Third run (after the snapshot-pinning change), human, 2026-11-07 12:43 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 188, Failed 0, Errors 0` (177 before plus the 11 `PipelineSnapshotSuite` tests); `[success] Total time: 27 s`. The "finished without writing expected output works" / "pipeline failed at stage source-normalization/openalex" lines are the deliberate failure test's output. The tail does not show the other modules' counts (agent runs: 41, 151, 190). `target/` was cleaned again.

## Fourth run (after the gold validator and evaluation tooling), human, 2026-11-07 1:30 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 188, Failed 0, Errors 0`; `[success] Total time: 28 s`. The "finished without writing expected output" lines are the deliberate failure test's output. The other modules' totals (agent run: core 41, tests 190, entity-resolution 201) are not in the tail, so the entity-resolution result, the module that changed, is not directly visible here; `[success]` means the whole check passed.

## Fifth run (after the blind-pass tooling and removal of TmpAffiliationAudit.scala), human, 2026-11-07 2:10 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 188, Failed 0, Errors 0`; `[success] Total time: 28 s`. The "finished without writing expected output" lines are the deliberate failure test's output. As in earlier runs the entity-resolution, core and tests counts are not in the tail; `[success]` means the whole check passed.

## Sixth run (after the pass-3 tooling), human, 2026-11-07 4:16 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 188, Failed 0, Errors 0`; `[success] Total time: 29 s`. The "finished without writing expected output" lines are the deliberate failure test's output. The entity-resolution, core and tests totals (agent run: 190, 298, 188 including 56 new pass-3 tests) are not in the tail; `[success]` means the whole check passed.

## Seventh run (final, after the gold archive and manifest edits; no Scala source changed since the sixth run), human, 2026-11-07 4:34 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 188, Failed 0, Errors 0`; `[success] Total time: 28 s`. The "finished without writing expected output" lines are the deliberate failure test's output. Other modules' totals are not in the tail (agent runs: core 41, tests 190, entity-resolution 298 including pass-3 tests). This is the recorded gate run for review.

## Eighth run (after repair round 1: same-DOI position rule, policy 1.2.0, Crossref affiliation drop, stronger leakage and network scans), human, 2026-11-07 4:59 PM
Same command. Visible in the 40-line tail: hadoop `Passed: Total 203, Failed 0, Errors 0` (188 before, plus 15 from this round); `[success] Total time: 29 s`. The "finished without writing expected output" lines are the deliberate failure test's output. The other modules' totals are not in the tail (agent runs: entity-resolution 325, core 41, tests/ about 216); `[success]` means the whole check passed. This run is the recorded gate for the repair round.

## Ninth run (repeat after the dataset register/validate steps; no Scala source changed since the eighth run), human, 2026-11-07 5:02 PM
Same command. Visible: hadoop `Passed: Total 203, Failed 0, Errors 0`; `[success] Total time: 29 s`. Identical outcome to the eighth run.

## Tenth run (after repair round 2: arXiv key, position CONFLICT, policy 1.3.0, stronger module LeakageSuite, unknown-dataset guard), human, 2026-11-07 5:23 PM
Command: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/sbt-check-full.txt | tail -n 40`. Visible in the tail: hadoop `Passed: Total 207, Failed 0, Errors 0` (203 before, plus the new unknown-dataset suite); `[success] Total time: 29 s`. The "finished without writing expected output" lines are the deliberate failure test's output. The full output is archived as `gold-support/logs/sbt-check-full.txt`. The orchestrator has no tool able to search it, but the re-reviewer read it: four summary lines, 41 (line 107), 231 (line 1033), 355 (line 1285) and 207 (line 1423), then `[success]` (line 1424), and the suites inside each block match the agent's attribution (core 41, tests 231, entity-resolution 355, hadoop 207). Agent run before it: core 41, tests 231, entity-resolution 355, hadoop 207. This is the recorded gate for repair round 2.

## Eleventh run (after the last repair round: same-record ORCID CONFLICT, policy 1.4.0, cleanup), human, 2026-11-07 5:56 PM
Command: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/sbt-check-full-r3.txt | tail -n 40`. Visible in the tail: hadoop `Passed: Total 207, Failed 0, Errors 0`; `[success] Total time: 29 s`. The "finished without writing expected output" lines are the deliberate failure test's output. The full output is archived as `gold-support/logs/sbt-check-full-r3.txt`; the human's `grep -n 'Passed: Total'` on the saved log printed four summary lines: line 369 `Total 41`, line 758 `Total 240`, line 1307 `Total 366`, line 1445 `Total 207`, all `Failed 0, Errors 0`. Attribution by size (inferred, sbt prints no project name): core 41, tests/ 240 (231 plus the 9 tests of `AdversarialErSameRecordSuite`), entity-resolution 366 (355 plus the 11 tests of `SameRecordIdAgreementSuite`), hadoop 207. This is the recorded gate for the last repair round; it was `sbt clean check` with server flags, so whether it refreshed `.claude/.last-test-run` is unconfirmed (a bare `!sbt check` follows).

## Twelfth run (final: `sbt check`, no `clean`), human, 2026-11-07 6:01 PM
Command: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt check 2>&1 | tail -n 40`. Visible: hadoop `Passed: Total 207, Failed 0, Errors 0`; `[success] Total time: 17 s`. The "finished without writing expected output" lines are the deliberate failure test's output. This run came after all repair rounds, the archive and the evidence edits (no Scala source changed after the eleventh run). Whether it refreshed `.claude/.last-test-run` could not be checked by the orchestrator (the guard refuses `ls` on that constitutional path); the human can confirm. The other modules' totals for the same sources are in the eleventh run: core 41, tests/ 240, entity-resolution 366, hadoop 207.

Phase 4 pipeline on the subset (first entry above, historical): not run at that time; output expected at `target/p5-logs/v2-pipeline.txt`.
