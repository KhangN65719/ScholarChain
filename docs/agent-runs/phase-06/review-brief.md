# Phase 6 gate review brief (orchestrator, for `code-reviewer`)

Read-only. Return APPROVE or REQUEST CHANGES with exact file:line evidence, all findings batched in one round. You cannot run anything; ask for missing checks and mark them pending. Never approve on agent claims alone: label what you verified by reading.

## Scope
- Tracked change: `build.sbt` (`git diff --stat`: 79 insertions, 2 deletions). Read the file and judge the `sparkJvmOptions`, `isSparkSuite`, root `testGrouping`, root `dependsOn`, and sparkAnalytics run/fork changes.
- Untracked, all new (git shows no diff for them; read them directly):
  - ER batch: `modules/entity-resolution/src/main/scala/scholarchain/er/{ErBatch,Clusters}.scala`, `.../src/test/scala/scholarchain/er/ErBatchSuite.scala`
  - Spark main: `modules/spark/src/main/scala/scholarchain/spark/*.scala`, design note `modules/spark/DESIGN.md`
  - Spark module tests: `modules/spark/src/test/scala/scholarchain/spark/*.scala`
  - Independent tests: `tests/adversarial/Spark*.scala`, `tests/integration/Spark*.scala`
- Evidence: `docs/agent-runs/phase-06/` (prompt.md Part A = course Phase 6 section verbatim and authoritative, Part B = scope note; preflight.md; work orders 01-05; owner reports; repairs.md; validation.md V1-V6). Not committed; Khang accepts after APPROVE.

## Course criteria to check against `prompt.md` Part A
Canonical entities and authorships with unresolved mentions preserved; weighted undirected coauthorship by explicit time window; collaboration and team-size metrics with work evidence; components; bounded shortest paths; communities; a defined bridge measure; coreness; temporal comparisons; evidence-backed semantic summaries; partitioned Parquet; load manifests; no full-graph collect on the driver; stage DAG, partition and shuffle behavior, checkpointing, counts, integrity failures, algorithm parameters and limitations, reproducible inputs and outputs. The bounded EMR run is deferred by decision; the local Spark run is the gate.

## Constraints to check
Gold labels and candidate pairs never serve as accepted links; no AWS, EMR, Terraform, live operation or network; logs outside `target/`; no secrets; no weakened or deleted tests; ownership (modules/spark by spark-engineer, tests/** by test-engineer, build.sbt by build-engineer, ER by entity-resolution-engineer); evidence labeled by producer.

## Local-run evidence (observed by the orchestrator from human-pasted output)
- ER batch on subset-v6: exit 0 (V1). Spark run: exit 0, 25 tables, 11 invariants pass, 0 integrity violations (V3). Human `sbt clean check` after repair R1: exit 0, `[success]`, sparkAnalytics 45/0/0, root 373/0/0, 795 s (V5; core, entityResolution, hadoop totals are in the log only). Spark re-run after R1, same run ID, same counts, exit 0 (V6).

## Hand-checkable fixture results (from `spark-owner-report.md` and the `tests/integration/Spark*` suites; verify the derivations yourself from the fixtures, do not trust the labels)
Distinct coauthors and pair weights, components, communities, bridge values, shortest paths with per-hop work IDs, coreness (K4, triangle with pendant, path, multi-round peel), time-window boundaries. The `test-engineer` report body and its test-to-item list were never archived: check coverage of the 14 items in `work-order-04-test-engineer.md` by reading the test suites, and report any item you cannot find a test for.

## Known items to examine (orchestrator observations, not verdicts)
1. ER: 456 of 473 integrity failures are the added type "authoritative identifier split across clusters", so 896 mentions (4.2%) with ORCIDs get no canonical ID. Conservative. Is it adequately disclosed, and is the rule defensible?
2. Graph fragmentation: 55% of mentions unresolved; in window `all` the largest component is 148 of 17,490 nodes (0.85%), 3,035 components. Is this limit stated where path and bridge results are described?
3. `Canonical.distinctByKey` (R1): silent smallest-row choice on conflicting duplicates; `pairs.*` disposition counts still count a pair row given twice as two rows.
4. Checkpoint deviation: `Dataset.checkpoint` replaced by Parquet write and read-back (Spark 4.0.5 error on union). Acceptable as "checkpointing" per the course?
5. Core `CoauthorEdge` cannot hold an unresolved-mention endpoint, so Spark uses a Spark-local `coauthor:v1:` edge ID. Contract gap or acceptable?
6. Part-file renaming (`Export.renameParts`) was added for an intermittent 12-digit-token test failure whose cause is unconfirmed. Is the manifest account/ARN check itself too brittle, or is the repair sound?
7. `summary.txt` has unlabeled columns (labels only in `repairs.md`); SLF4J multiple-providers warning unresolved; heap `-Xmx2g` and broadcast tuning.
8. Root `testGrouping` and sparkAnalytics `Compile / run` use `fullClasspath`: does packaging still exclude Spark (`stageJob` uses the Runtime classpath)?
9. Any place a driver collect, `.collect()`, `toLocalIterator` or `take` of unbounded size occurs outside the guarded helper; any use of `gold`, ER `decisions` tree, `scholarchain.er.eval/review/sampling` from `modules/spark` main code.
