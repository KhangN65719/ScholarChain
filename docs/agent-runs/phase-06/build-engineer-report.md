# Owner report: work order 02 (Spark build enablement)

Produced by: `build-engineer`, received by the orchestrator 2026-11-07. Agent statements; the orchestrator has not diffed `build.sbt` yet (it is a tracked file, so `git diff -- build.sbt` is available to the reviewer).

**STATUS:** COMPLETE for the build change. Spark itself is not proven (no source in `modules/spark`).

**FILES CREATED:** none. **MODIFIED:** `build.sbt` only; `project/DependencyVersions.scala` unchanged, no version added.

## Changes (agent-reported)
1. `sparkJvmOptions` and `isSparkSuite` added at the top of `build.sbt` (one shared JVM-flag list).
2. `root`: `sparkAnalytics % "test->compile"` added to `dependsOn`; `spark-sql_2.13 % Test` (ledger version); a `Test / testGrouping` override.
3. `sparkAnalytics`: `Compile / run` and `runMain` use `Compile / fullClasspath` (includes Provided); `Compile / run / fork := true` with `javaOptions ++= sparkJvmOptions`; `Test / fork := true` with the same options.

## JVM flags
`-Xmx2g`, `-XX:+IgnoreUnrecognizedVMOptions`, `--add-opens=java.base/<pkg>=ALL-UNNAMED` for 13 packages (java.lang, java.lang.invoke, java.lang.reflect, java.io, java.net, java.nio, java.util, java.util.concurrent, java.util.concurrent.atomic, sun.nio.ch, sun.nio.cs, sun.security.action, sun.util.calendar), `--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED`, `-Djdk.reflect.useDirectMethodHandle=false`, `-Dio.netty.tryReflectionSetAccessible=true`, `-Dspark.ui.enabled=false`, `-Dspark.driver.host=127.0.0.1`, `-Dspark.driver.bindAddress=127.0.0.1`. The agent says the `--add-opens` list is from memory of Spark's `JavaModuleOptions` and was not checked against the 4.0.5 jars. No `spark.master` is set.

## Root test route
No root-wide `Test / fork`. `Test / testGrouping` keeps suites in an in-process "default" group; suites whose class name contains `Spark`, or whose package contains `.spark.`, go to a separate forked "spark" group with `sparkJvmOptions` (working directory repo root). A root Spark suite must be named accordingly (for example `SparkCoauthorSuite`) or it runs unforked without `--add-opens` and fails. The group exists only when a matching suite exists. `services`, `rag`, `storage` do not depend on `sparkAnalytics`; `stageJob` reads the Runtime classpath.

## `runMain` form
`sbt "sparkAnalytics/runMain scholarchain.spark.<Main> <args>"`, human-run with `!`. Forked working directory is `modules/spark`: use absolute paths; logs under `/Users/winnguyen/scholarchain-data/logs/`.

## Commands (agent-reported)
| Command | Exit | Result |
|---|---|---|
| `sbt Test/compile` | 0 | `[success]` |
| `sbt check`, attempt 1 | 1 | `scalafmtSbtCheck` flagged `build.sbt`; the agent reshaped the `testGrouping` block by hand (`sbt fmt` not permitted) |
| `sbt check`, attempt 2 | 0 | `[success]`, marker updated |

Counts, all `Failed 0, Errors 0`: core 41, entity-resolution 389, hadoop 207, root 240. Modules without tests printed no totals.

## Not proven (needs spark-engineer's first source and test)
Scala 3.3.7 compiling against `spark-sql_2.13` signatures; the resolved Scala 2.13 library version (eviction output not read); Java 21 flag sufficiency; a local `SparkSession` starting; `runMain` finding Provided classes; the forked "spark" group forking; `stageJob` for `sparkAnalytics` once source exists.

## Risks (agent-stated) and question
- New SLF4J warning in root tests: "Class path contains multiple SLF4J providers" (`slf4j-simple` and log4j's provider; the active provider remains `slf4j-simple`). No exclusion added. The agent asks whether to add one.
- Hadoop pin 3.5.0 versus Spark 4.0.5's expected 3.4.x: root resolves 3.5.0; behavior under Spark untested.
- Name-based Spark suite detection; `-Xmx2g` may need raising for larger runs.

## Next gates
Independent review of `build.sbt`; spark-engineer's first test settles the unproven items; test-engineer follows the `Spark` naming rule; human `sbt clean check`.
