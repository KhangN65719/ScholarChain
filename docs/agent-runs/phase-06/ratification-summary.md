# Phase 6 summary for ratification (orchestrator, 2026-11-08)

Status: gate evidence complete; independent review APPROVE (round 3). Not committed. Khang accepts and commits.

## What was built (by owner)
- entity-resolution-engineer: `ErBatch`, `Clusters` (accepted-link artifacts from ACCEPTED decisions only; canonical researcher IDs; integrity failures) and `ErBatchSuite`. Human-run on subset-v6: exit 0 (V1).
- build-engineer: `build.sbt` Spark enablement (`sparkJvmOptions`, forked Spark suite group, `fullClasspath` run, root test edge). Spark stays out of `stageJob` packaging.
- spark-engineer: `modules/spark` pipeline (canonical entities and authorships, windowed weighted coauthorship, metrics with work evidence, components, communities, bridge measures, coreness, bounded paths with evidence, sampled path length, temporal comparison, evidence-backed summaries, partitioned Parquet, load manifests planned-not-uploaded, run manifest, stage metrics, plans, checkpoints) plus module tests and `DESIGN.md`.
- test-engineer: independent suites `tests/integration/Spark*.scala`, `tests/adversarial/Spark*.scala` covering the 14 items of work order 04. Its own report body was not archived; the reviewer mapped all 14 items to named tests.
- Repairs R1-R4 (`repairs.md`), reviews in `review.md`, evidence in `validation.md` V1-V13.

## Gate evidence (observed by the orchestrator from human-pasted output)
- ER run exit 0 (V1). Final human `sbt clean check`: `[success]` 1133 s; reviewer read the log: core 41, entityResolution 389, hadoop 207, sparkAnalytics 61, root 373, 0 failures (V12, review round 3).
- Final bounded local Spark run on the subset: run `run:v1:fc4bbd1a-f751-49a3-8eb3-a068e4a1a60a`, exit 0, 421 s, 25 tables, 13 invariants pass, 0 integrity violations (V13). Earlier runs 996e776c, 3ca63bcd and 312ea79b remain on disk, superseded. Community convergence all windows, shuffle totals: agent-reported from the run files.
- Path results (run 312ea79b, identical in the final run): found 6, 6, 6, 3, 3, 0, 3 of 6 pairs per window (1900-1999 through `all`); the rest `different-component`.

## Deferred
- The README's bounded EMR run: deferred by decision (local Spark only; no AWS, EMR, Terraform or live operations in Phase 6). Load manifests are local artifacts, `planned-not-uploaded`.

## Known limitations to ratify
1. Conservative ER: 55% of mentions are unresolved nodes; largest component 148 of 17,490 nodes (0.85%) in `all`, 3,035 components. Path and bridge findings describe a fragmented graph; stated in the code limitation texts, manifest and `DESIGN.md`.
2. ER ORCID-split rule: 456 of 473 integrity failures; 896 mentions (4.2%) with ORCIDs get no canonical ID. The breakdown of why those pairs were not accepted is not done; whether ORCID equality alone may ever merge is an open question for Khang. ER run ID has no code digest.
3. B5: unresolved nodes (about 71%) use a Spark-local `coauthor:v1:` edge ID and metrics rows with no core form. Accepted as a Phase 6 limitation; a domain-engineer core contract change is a Phase 7 prerequisite before any graph load.
4. B2: outside a git checkout the sources digest is `unavailable` and the run ID does not change with code; the digest covers only `modules/spark/src/main/scala`. Before any non-local or packaged run: embed the digest at build time (build-engineer) or fail on `unavailable` (spark-engineer).
5. Checkpointing is Parquet write and read-back, not `Dataset.checkpoint` (Spark 4.0.5 error reported by the owner, unverified); a cluster run needs a shared filesystem.
6. `authorPapers` key includes `authorPosition`: a mention listed twice at different positions is neither duplicate nor conflict. Conflicting-key handling: error for identity inputs, warning for Hadoop inputs; neither path has run on real data (covered by `ConflictingKeysSuite` only).
7. Build: SLF4J multiple-providers warning, root Hadoop 3.5.0 vs module 3.4.1, `--add-opens` list not checked against the 4.0.5 jars, suite detection by name, `-Xmx2g`; `sparkAnalytics/stageJob` never run.
8. Test-engineer's report body not archived; `SparkSummariesSuite.scala:146-147` stale comment; `ConflictingKeysSuite` link-remap test does not pin the sole failing invariant; `DESIGN.md:49` says conflict detail is in `summary.txt` (manifest only); `finally` block latent exception and `getOrCreate()` caveat; sources digest computed twice; `CollaborationPipelineSuite.scala:380` passes only in a git checkout.
9. The real-run tables were not read back or recomputed independently (no Parquet reader installed); the real-run correctness evidence is the invariants, the counts reconciliation and the hand-checked fixtures.

## Phase 7 prerequisites
Core contract for unresolved-mention nodes and edges; loaders refuse a run directory without `_SUCCESS`; the new columns `semantic-summaries.nodeKind` and `path-evidence.unreachableReason`; run-ID digest rule (item 4).

## Commit hygiene
Exclude `datasets/slice/` (retired Phase 5 slice, human ruling, same as Phase 5) and `docs/.DS_Store`, `docs/agent-runs/.DS_Store`. `docs/agent-runs/phase-06/readback-paths.py` is an unused evidence file (the readback failed for lack of a Parquet library); keep or drop at your discretion. Untracked Phase 6 source and test files must be added explicitly.
