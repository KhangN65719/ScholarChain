# Phase 6 validation record

Each item names who ran it. Observed output is quoted from the human's pasted terminal output; "agent-reported" means the orchestrator did not observe it.

## V1. ER batch run (human-run, 2026-11-07, 6:30 PM)
- Command (human, `!` prefix), exactly as supplied by entity-resolution-engineer in `er-batch-owner-report.md`: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false SBT_OPTS=-Xmx4g sbt "entityResolution/runMain scholarchain.er.ErBatch --run-root <subset-v6 snapshot dir> --out-root /Users/winnguyen/scholarchain-data/phase6-out/er" 2>&1 | tee .../logs/phase-06-er-batch.log | tail -n 40`, then `echo "sbt-exit=${pipestatus[1]}"` appended to the same log.
- Exit: `sbt-exit=0`, `[success] Total time: 25 s`, `ER-BATCH RESULT: SUCCESS`. Log: `/Users/winnguyen/scholarchain-data/logs/phase-06-er-batch.log`.
- Run ID `run:v1:2f4cd132-afcb-4bca-83c5-1a0ddf001ace`; snapshot `8e1be6626420`; policy `scholarchain.er.policy/1.4.0`; schemaVersion 1; batch version `entity-resolution-batch/1.0.0`; Hadoop input run `run:v1:d4722bc7-0d02-42da-bdb6-088ee508a143`; datasets `dblp:2026-11-05`, `openalex:2026-11-07`, `crossref:2026-11-07` (the manifest lists no orcid snapshot).
- Output: `/Users/winnguyen/scholarchain-data/phase6-out/er/run=run-v1-2f4cd132-afcb-4bca-83c5-1a0ddf001ace/snapshot=8e1be6626420/` (manifest read by the orchestrator).
- Input counts (manifest): candidates read 362,905; distinct pairs 311,249; duplicates dropped 51,656; works 4,118; researcher mentions 21,568 (author-mention rows 21,568, none missing from works); researcher rows 1,612; records indexed 25,686; duplicate ids 0; missing-record pairs 0; invalid candidates 0.
- Decisions (311,249): ACCEPTED 7,015 (researcher 6,254, work 761); REJECTED 255,181; UNRESOLVED 16,986; CONFLICT 32,067 (researcher 32,026, work 41). Paths: exact-id 35,399 (3,332 accepted, 32,067 conflict), deterministic-bibliographic 59,646 (24 accepted), probabilistic 216,204 (3,659 accepted, 16,986 unresolved), llm-adjudicated 0. LLM: eligible 11,678, offline stub abstained on all, network calls 0.
- Researcher clusters (17,446): merged 2,075 (5,774 members); external-id singletons 3,041; unlinked 11,857; integrity-failed 473 (896 members). Canonical researchers 5,116 (ORCID rule 4,599, of which 1,490 appear in the researchers export; identity rule 517). Work clusters (3,556): merged 356 (918 members), unlinked 3,200, failed 0.
- Integrity failures: 473 clusters, 489 records. By type: `authoritative-identifier-split-across-clusters` 456, `conflict-decision-inside-cluster` 16, `conflicting-authoritative-identifiers` 16, `same-work-record-two-mentions` 1.
- Resources: 24,762 ms elapsed; peak heap 1,191,944,704 bytes (sum of pools) against a 4 GiB max.
- Not checked: the orchestrator did not recompute any count from the part files; the cross-sums that do reconcile (cluster totals and member totals) were checked by arithmetic on the manifest.

### Observations for review (orchestrator, not verdicts)
- 456 of 473 failures (96%) are the failure type the agent added beyond the work order: one ORCID appearing in several clusters that no ACCEPTED pair joins. Those mentions get a null canonical ID, so 896 of 21,568 mentions (4.2%) are not linked even though an authoritative ORCID is present on them. The sample row for the first failure shows one ORCID spread over three clusters. Why the pairs were not accepted (not a candidate, rejected by a position rule, conflict, unresolved) has not been broken down. Spark treats a null canonical ID as the mention's own node, so no false merge reaches the graph, and the loss is conservative. To be examined by the independent reviewer and disclosed as a limitation; it is not repaired here.
- `exact-id|CONFLICT` is 32,067 (`conflict.id:orcid` 32,026): pairs whose ORCIDs differ. This is the Phase 5 known limitation (candidate generation pairs authors with their own coauthors), not new.
- Only exact-id and bibliographic-identifier agreement accept in practice; 11,857 mentions stay unlinked.

## V2. Agent-reported checks (not observed by the orchestrator)
- entity-resolution-engineer: `sbt Test/compile` exit 0; `sbt check` twice `[success]`; root 240, entity-resolution 389, hadoop 207, all `Failed 0, Errors 0` (core not read). Source: `er-batch-owner-report.md`.
- build-engineer: `sbt Test/compile` exit 0; `sbt check` attempt 1 exit 1 (formatting of `build.sbt`), attempt 2 exit 0; core 41, entity-resolution 389, hadoop 207, root 240, all `Failed 0, Errors 0`. Source: `build-engineer-report.md`.
- spark-engineer: `sbt check` four runs (3 failed on Spark analyzer, `Dataset.checkpoint` and empty-partition-schema errors, then exit 0, 276 s); sparkAnalytics 44/0/0, entity-resolution 389/0/0, hadoop 207/0/0; core and root totals not located. Source: `spark-owner-report.md`.

## V3. Local Spark run on the subset (human-run, 2026-11-07, finished 8:00 PM)
- Command: exactly the one in `spark-owner-report.md` (`sparkAnalytics/runMain scholarchain.spark.CollaborationPipeline --er-run <ER run> --hadoop-root <subset-v6 snapshot dir> --out-root /Users/winnguyen/scholarchain-data/phase6-out/spark --event-log-dir /Users/winnguyen/scholarchain-data/logs/spark-events`), piped through `tee` to `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run.log`, then `echo "sbt-exit=${pipestatus[1]}"` appended. The harness moved it to the background after 120 s; the completion notice reports exit 0 for the whole line.
- Observed (the orchestrator read the captured tail and `summary.txt`): `SPARK-PIPELINE RESULT: SUCCESS`, `sbt-exit=0`, `[success] Total time: 380 s`. The `[error]`-prefixed INFO lines after the result are Spark shutdown messages sent to stderr.
- Run `run:v1:996e776c-dc4d-4149-8fb4-0364531397f1`; output `/Users/winnguyen/scholarchain-data/phase6-out/spark/run=run-v1-996e776c-dc4d-4149-8fb4-0364531397f1/`; ER input run `run:v1:2f4cd132-...` snapshot `8e1be6626420`; Hadoop run `run:v1:d4722bc7-...`.
- Inputs (counts): researcher links 21,568; work links 4,118; canonical researchers (ER) 5,116; works 4,118; author-papers 21,568; pair candidates 58,367; teams 4,118.
- Canonical: works 3,556 (merged 356; 2 without a year); researcher nodes 17,569 (unresolved-mention nodes 12,453; unresolved mentions in duplicate records 300); authored edges 21,268; `authoredNotCounted` 2,609 (meaning not yet explained, see below).
- Pairs: 58,367 = mapped 50,752 + duplicate-record 7,615 (checked by arithmetic); self-pair 0; endpoint-missing 0; work-mismatch 0. Coauthored edges 98,661 across windows.
- Windows (seven): 1900-1999, 2000-2004, 2005-2009, 2010-2014, 2015-2019, 2020-2029, all. Metric rows 35,160 (17,490 nodes in `all`). Summaries 41,885. Integrity violations 0.
- Components, window `all`: 17,490 nodes, 49,251 edges, 3,035 components, largest 148 nodes (fraction 0.0085), 46 singletons. Per-window largest fraction falls from 0.136 (1900-1999) to 0.0085 to 0.0105 in later windows.
- Communities, `all`: 3,310 nodes in the count column, 61 as the second figure (columns unlabeled in `summary.txt`; to be read against `DESIGN.md`). Coreness `all`: max 24.
- Invariants, all 11 `pass` (integrityViolationsAboveCeiling, authoredEdgesWithoutResearcherNode, authoredEdgesWithoutWorkNode, edgesNotInCanonicalOrder, edgeEndpointsOutsideWindowNodes, edgesOutsideTheirWindow, nodesWithoutComponent, nodesWithoutCommunity, nodesWithoutCoreness, weightedDegreeNotEqualToCoauthorOccurrences, teamsAndAuthorPapersCoverDifferentWorks).
- Tables: 25 written (row counts in the log and `summary.txt`); checkpoints 206; stages 5,142; tasks 31,517.
- Not checked: no table was read back or recomputed by the orchestrator; the `summary.txt` columns for `metrics`, `components`, `communities` and `coreness` lines are unlabeled; the real-run outputs were not compared against an independent computation; the spark event-log directory and `stage-metrics.json` and `plans/` were not inspected.

## V4. test-engineer and repair R1 (see `repairs.md`)
- test-engineer (agent-reported; full report body not archived because its writes are limited to `tests/**` and its replies did not carry the body): 14 coverage items written as 13 files under `tests/integration/Spark*.scala` and `tests/adversarial/Spark*.scala`; its `sbt check` had 6 root failures (F1 x4, F2 x2), routed to spark-engineer in work order 05. The test-to-item list from that report is NOT on file.
- spark-engineer repair R1: agent-reported in `repairs.md`.

## V5. Human gate `sbt clean check` after R1 (human-run, 2026-11-07, finished 10:01 PM)
- Command exactly as given in the work order; log `/Users/winnguyen/scholarchain-data/logs/phase-06-clean-check.log`. No `sbt-exit` line was appended (the gate command has none); the harness reports exit 0 for the pipeline, and the observed tail shows `[success]`.
- Observed tail: `Passed: Total 45, Failed 0, Errors 0, Passed 45` (sparkAnalytics), `Passed: Total 373, Failed 0, Errors 0, Passed 373` (root), `[success] Total time: 795 s`. The `tail -n 40` window did not show core, entityResolution or hadoop totals; read the log if needed.
- Not observed: the real Spark run has not been re-run since R1.

## V6. Spark re-run after R1 (human-run, 2026-11-07, finished 10:10 PM)
- First attempt (10:03 PM) was my error: the pasted command still held the `<subset-v6 snapshot dir>` placeholder; the pipeline refused it with `SPARK-PIPELINE RESULT: FAILED Unknown option or stray argument snapshot`, exit 2 (`sbt-exit=1`), nothing written. It also shows strict argument parsing in the real binary.
- Second attempt, same command with the real Hadoop path from `phase-06-spark-run.log` plus `--overwrite`, log `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run-2.log`: `SPARK-PIPELINE RESULT: SUCCESS`, `sbt-exit=0`, `[success] Total time: 412 s`.
- Observed: the run ID is unchanged (`run:v1:996e776c-...`, same output directory, overwritten); all 25 table row counts and all `summary.txt` count, metrics, components, communities, coreness and invariant lines equal V3's (read by the orchestrator, compared by eye); stages 5,004 (was 5,142), tasks 31,242 (was 31,517); checkpoints 206. Consistent with the real inputs containing no duplicate keys.
- Not checked: `summary.txt` still has no header lines (headers exist only in `repairs.md`); the part-file renaming and the changed summary text were not inspected on disk; no table read back.

## V7. Human gate `sbt clean check` after R2 (human-run, 2026-11-07, finished 11:09 PM)
- Log `/Users/winnguyen/scholarchain-data/logs/phase-06-clean-check-2.log`. Observed tail: sparkAnalytics `Passed: Total 52, Failed 0, Errors 0`; root `Passed: Total 373, Failed 0, Errors 0`; `[success] Total time: 806 s`; harness exit 0. core, entityResolution and hadoop totals are outside the 40-line tail.
- Pending: real Spark re-run (new run ID expected), B3 evidence, limited re-review.

## V8. Spark re-run after R2 (human-run, 2026-11-07, finished 11:16 PM)
- Command as in V3 without `--overwrite`; log `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run-3.log`. Observed: `SPARK-PIPELINE RESULT: SUCCESS`, `sbt-exit=0`, `[success] Total time: 408 s`; 25 tables, 206 checkpoints, stages 5,043, tasks 31,457.
- New run ID `run:v1:3ca63bcd-122b-4f1f-854f-6e44fcd57419` (B2 effect: differs from V3/V6 `996e776c-...`), output `/Users/winnguyen/scholarchain-data/phase6-out/spark/run=run-v1-3ca63bcd-122b-4f1f-854f-6e44fcd57419/`. The V3/V6 directory remains as the earlier run.
- Pending: compare counts with V3, B3 evidence extraction, limited re-review.

## V9. B3 evidence from run 3ca63bcd (spark-engineer, read-only; agent-reported, read from `summary.txt`, `run-manifest.json`, `stage-metrics.json`)
- Communities: cap 100, no window hit it, `allWindowsConverged` true; passes to fixpoint 7, 9, 11, 15, 15, 19, 19 for 1900-1999, 2000-2004, 2005-2009, 2010-2014, 2015-2019, 2020-2029, all (20 passes in total); connected components 8 passes, converged.
- Shuffle: 465 jobs, 5,043 stages, 31,457 tasks, shuffle read 18,059,041,909 B, write 14,270,737,778 B; 8 shuffle partitions, adaptive execution off; partitions at write 8 (most tables), 16 nodes-researcher, 25 semantic-summaries, 40 integrity-violations. By label (stages, read, write): coreness 1,886, 6.62 GB, 5.51 GB; communities 691, 3.08 GB, 2.37 GB; path-length-estimates 318, 1.30 GB, 1.00 GB; connected-components 247, 1.01 GB, 0.85 GB; path-evidence 245, 0.84 GB, 0.63 GB; unlabelled 851, 2.43 GB, 1.80 GB.
- Duplicate-key counts all 0 for all seven datasets; `conflictingDuplicateInputKeys` 0 pass; all 12 invariants pass; `pairs.mapped` 50,752 = distinct 50,752 = `expectedFromTeams`; `pairs.duplicate-record` 7,615 = distinct.
- Equal to V3: nodes-researcher 17,569; edges-coauthored 98,661; semantic-summaries 41,885; `all` components 3,035, largest 148 of 17,490.
- Config: hopLimit 6, sampleSize 20, depthCap 64, autoPairTop 4; `path-evidence` 42 rows (fits 6 auto pairs per window, unconfirmed). Manifest `code.gitHead` befe9c4 (last commit), sources digest `c60760f5…` (not compared against the sources by me).
- NOT READABLE (Parquet only): `path-evidence` status counts and `unreachableReason` split; `path-length-estimates` values. B3's path-result requirement is therefore open.

## V10. Human gate `sbt clean check` after R3 (human-run, finished 2026-11-08 12:00 AM)
- Log `/Users/winnguyen/scholarchain-data/logs/phase-06-clean-check-3.log`. Observed tail: sparkAnalytics `Total 55, Failed 0, Errors 0`; root `Total 373, Failed 0, Errors 0`; `[success] Total time: 808 s`; harness exit 0. core, entityResolution and hadoop totals are outside the tail.
- The readback script `readback-paths.py` (human-run, 2026-11-07) printed `NEITHER pyarrow NOR duckdb is installed` (exit 3); fallback to R3 taken.
- Pending: real re-run (new run ID), path numbers from the new `summary.txt`, limited re-review.

## V11. Spark re-run after R3 (human-run, finished 2026-11-08 12:08 AM) and path results
- Command as in V8; log `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run-4.log`. Observed: `SPARK-PIPELINE RESULT: SUCCESS`, `sbt-exit=0`, `[success] Total time: 411 s`; 25 tables, 206 checkpoints, stages 5,055, tasks 31,517. New run ID `run:v1:312ea79b-637c-440b-85f7-5c0640c3609c`; output `/Users/winnguyen/scholarchain-data/phase6-out/spark/run=run-v1-312ea79b-637c-440b-85f7-5c0640c3609c/`. Final run of Phase 6; runs 996e776c and 3ca63bcd remain on disk as superseded.
- Read by the orchestrator from that run's `summary.txt`: column headers now present on metrics, components, communities, coreness; all 14 duplicate-key counts 0; `pairs.*.distinct` equal the row counts (50,752 mapped, 7,615 duplicate-record); 12 invariants pass; row counts, metrics, components (`all`: 17,490 nodes, 3,035 components, largest 148), communities and coreness equal V3.
- Path results (`path-status`, distinct pairs per window; 6 pairs per window selected automatically): found 6 in 1900-1999, 2000-2004, 2005-2009; 3 found + 3 unreachable (`different-component`) in 2010-2014, 2015-2019 and `all`; 0 found + 6 unreachable (`different-component`) in 2020-2029. No `beyond-hop-limit` and no `endpoint-not-in-window` among the automatic pairs.
- Path-length estimates (one row per window, for the largest component only; `mention:v1:` anchors, i.e. the largest component's lowest-ID node is an unresolved mention): size, sampled sources, pairs measured, mean path, effective diameter (90th percentile), max distance, unreached-due-to-cap: 1900-1999: 8, 8, 56, 1.0, 1, 1, 0; 2000-2004: 10, 10, 90, 1.0, 1, 1, 0; 2005-2009: 16, 16, 240, 1.417, 2, 2, 0; 2010-2014: 16, 16, 240, 1.617, 2, 2, 0; 2015-2019: 31, 20 sampled (allNodesSampled false), 600, 1.593, 2, 2, 0; 2020-2029: 122, 20, 2,420, 3.966, 6, 9, 0; `all`: 148, 20, 2,940, 4.155, 6, 9, 0. seed 42, depthCap 64.
- Observation (not a verdict): in the two widest windows half or all automatic pairs are in different components, so path evidence there mostly records that no path exists; estimates describe only the largest component (0.85% of nodes in `all`). Community convergence and shuffle totals are from V9 (run 3ca63bcd) and were not re-read for this run.

## V12. Human gate `sbt clean check` after R4 (human-run, finished 2026-11-08 12:53 AM)
- Log `/Users/winnguyen/scholarchain-data/logs/phase-06-clean-check-4.log`. Observed tail: sparkAnalytics `Total 61, Failed 0, Errors 0`; root `Total 373, Failed 0, Errors 0`; `[success] Total time: 1133 s`; harness exit 0. core, entityResolution and hadoop totals outside the tail.
- Pending: final real re-run (expect new run ID, `_work` removed, `inRunIdSeed` true), convergence and shuffle totals from it, limited re-review of R4.

## V13. Final Spark run after R4 (human-run, finished 2026-11-08 1:01 AM)
- Command as in V8; log `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run-5.log`. Observed by the orchestrator: `SPARK-PIPELINE RESULT: SUCCESS`, `sbt-exit=0`, `[success] Total time: 421 s`; run ID `run:v1:fc4bbd1a-f751-49a3-8eb3-a068e4a1a60a`; output `/Users/winnguyen/scholarchain-data/phase6-out/spark/run=run-v1-fc4bbd1a-f751-49a3-8eb3-a068e4a1a60a/`; the run directory listing (`ls`, orchestrator) shows `_SUCCESS`, `load-manifests`, `plans`, `run-manifest.json`, `run-metrics.json`, `stage-metrics.json`, `summary.txt`, `tables`: no `_work`, no `_checkpoints`. This is the final Phase 6 run; 996e776c, 3ca63bcd and 312ea79b remain on disk as superseded.
- Read by spark-engineer from the final run's `summary.txt`, `run-manifest.json`, `stage-metrics.json` (agent-reported; orchestrator did not open them): community convergence all 7 windows converged, passes 7, 9, 11, 15, 15, 19, 19 (20 in total, cap 100); connected components 8 passes, converged. 467 jobs, 5,055 stages, 31,517 tasks, shuffle read 18,059,862,263 B, write 14,271,558,132 B; 8 shuffle partitions, adaptive execution off, `local[2]`, seed 42, hopLimit 6, sampleSize 20; 206 checkpoints, `removedAfterRun` true. By label (stages, tasks, read B): coreness 1,886, 11,279, 6.62 GB; communities 691, 4,295, 3.08 GB; unlabelled 863, 5,547, 2.43 GB; path-length-estimates 318, 2,038, 1.30 GB; connected-components 247, 1,547, 1.01 GB; path-evidence 245, 1,528, 0.84 GB.
- Manifest `code`: `sparkMainSourcesSha256` `bd49cf6d75bed935e39960704c91181215a3aab53829c8e78ba559684d352101`; `inRunIdSeed` true; `runIdTracksCode` "yes: the sources digest is in the run-ID seed"; `gitHead` befe9c4 (last commit, not the working tree). Whether the digest matches the final sources was not recomputed.
- 13 invariants all pass, including `conflictingIdentityInputKeys` and `conflictingDuplicateInputKeys`; all duplicate-key counts 0, so neither the warning nor the error path ran on real data (covered only by `ConflictingKeysSuite`).
- Equal to run 312ea79b: nodes-researcher 17,569; edges-coauthored 98,661; summaries 41,885; `all` components 3,035 (largest 148); path-status lines identical; lines 44-137 of 312ea79b's `summary.txt` equal the new file apart from the added invariant line. Lines 1-39 of the old file were not compared.
- Not read: the run log (the human's exit status is observed from the pasted tail), manifest `outputs` and load-manifest entries, `plans/`, any Parquet table.

### Observations for review (orchestrator, not verdicts)
- The graph is fragmented: 55% of mentions are unresolved (ER) and each unresolved mention is its own node, so the largest component holds 0.85% of nodes in `all`. That limits path and bridge findings and is a direct consequence of conservative ER, not a Spark defect. It must be stated in the final record.
- `canonical.authoredNotCounted=2609` is not explained by the report (300 duplicate-record mentions explain only the 21,568 vs 21,268 difference). Ask the owner to define it.
- `researcher nodes 17,569` vs `unresolved nodes 12,453`: the remaining 5,116 equals the ER canonical researcher count, consistent but unchecked against the tables.
