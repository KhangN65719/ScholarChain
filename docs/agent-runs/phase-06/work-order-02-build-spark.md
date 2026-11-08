# Work order 02: enable local Spark in the build

Issued by: orchestrator, 2026-11-07. Owner: `build-engineer`. Context: `docs/agent-runs/phase-06/prompt.md` (Part B), `preflight.md`. Local only; no AWS, no commit.

## Why
`sparkAnalytics` (`modules/spark`) has no source yet. Before `spark-engineer` writes any, the build must let a local Spark session run in tests and via `runMain`. Known facts (orchestrator-read from `build.sbt`, `project/DependencyVersions.scala`):
- `sparkAnalytics` depends on `core` only and declares `"org.apache.spark" % "spark-sql_2.13" % DependencyVersions.spark % Provided`. Ledger: Scala 3.3.7, Spark 4.0.5, Java 21 at runtime, `-release:17`.
- Provided dependencies are not on the `run` classpath, so a `sparkAnalytics/runMain` would fail with NoClassDefFoundError.
- Spark 4 on Java 21 normally needs `--add-opens` JVM flags for tests and runs; sbt tests run in-process by default.
- The root project aggregates `sparkAnalytics` but its `dependsOn` has only `core`, `hadoop` and `entityResolution` (all `test->compile`). Independent tests under `tests/**` therefore cannot reference Spark code.

## Required
1. Make `sparkAnalytics / Compile / run` work locally: put Provided dependencies on the run classpath, fork the run JVM, and pass the Java 21 `--add-opens` flags Spark needs. Bounded memory setting is acceptable if justified.
2. Make `sparkAnalytics / Test` able to start a local `SparkSession` (`local[*]` or `local[2]`): fork with the same JVM flags, working Spark logging, no UI port requirement. Do not change any other project's test behavior.
3. Give `tests/**` a way to test Spark code without a runtime edge: the same `test->compile` pattern the root already uses for `hadoop` and `entityResolution`, plus whatever Test-scoped Spark dependency and JVM flags the root test run needs. The `sparkAnalytics` module must stay out of the Compile, Runtime and packaged classpath of `services`, `rag` and `storage`. If the root test run needs `Test / fork := true` (a behavior change for every root test), prefer a narrower route (for example a separate forked test configuration or scoped javaOptions) and justify the choice. Existing root tests must keep their counts.
4. Keep every version in `project/DependencyVersions.scala`. Add no new version value. Do not add GraphX or any new artifact family without reporting why; `spark-engineer` is told to use DataFrame/Dataset-of-Row APIs (no Scala 3 encoder derivation).
5. Keep `packageJobs` and `stageJob` for `sparkAnalytics` working. Do not touch Hadoop, ER, core or services build settings except where item 3 requires a root-level change.
6. Prove nothing by assertion: the module has no source, so full proof of Spark-on-Scala-3 needs `spark-engineer`'s first test. State this limit in the report and list exactly what remains unproven (Scala 3.3.7 reading `spark-sql_2.13` signatures, Scala library version resolution to 2.13.x, Java 21 flags sufficiency, Spark local start). Report the resolved Scala 2.13 library situation as far as `sbt Test/compile` and `sbt check` reveal.

## Verification
- `sbt Test/compile` and `sbt check` yourself. Report exact pass and fail counts per module. Expected unchanged: root 240, entity-resolution 389, hadoop 207 (core 41 per Phase 5, to be read this time), all `Failed 0, Errors 0`.
- No other sbt invocation.

## Boundaries
Own only build files (`build.sbt`, `project/**` excluding ledger values, `.scalafmt.conf`, `.gitignore`, packaging). No application source, no `tests/**`, no `modules/**`, no `.claude/**`. Anything else: `BLOCKED-ON` with owner and evidence. Do not weaken or delete tests.

## Report
STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED (with a short diff description per change and the reason), COMMANDS RUN (exit codes), TEST RESULTS (exact counts), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Include the exact JVM flags chosen and the exact `runMain` form `spark-engineer` should expect.
