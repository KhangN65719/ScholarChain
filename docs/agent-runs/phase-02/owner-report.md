# Owner report

**COMPLETE — build-engineer.** Implemented the seven-module Scala 3 sbt build and empty main and test source roots. Hadoop, entity resolution, Spark, and storage depend on core. RAG depends on core and storage. Services depends on core, storage, and RAG.

Pinned Scala 3.3.7, sbt 1.11.7, sbt-scalafmt 2.5.6, and Scalafmt 3.10.0. The plugin and application build share `project/DependencyVersions.scala`. The sbt launcher and formatter read their pins from their native configuration files. Verification used Temurin 21.0.13 with an isolated tool cache. The build defines no project-specific environment variables.

- `sbt fmt Test/compile check` passed. `sbt projects` and `show scalaVersion` confirmed all project IDs and Scala versions.
- In a disposable copy, compile-only and failed formatting left the marker absent. Compile and test failures preserved the existing marker timestamp. Successful `check` runs created or refreshed the marker.
- The direct `.claude/hooks/verify-tests-ran.sh` check passed.

The scaffold contains no application or contract tests. Application dependencies, packaging aliases, and containers were deferred under the narrowed work order. The next gate is independent review.

## Full course candidate

**COMPLETE — build-engineer.** Added the full Phase 2 build, repository skeleton, pinned dependencies, job staging, and local Docker packaging. The seven modules compile as Scala 3 and target Java 17 bytecode. The local image uses digest-pinned Temurin 21.0.13, UID/GID 10001, and percentage-based JVM memory limits.

`SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false /tmp/scholarchain-phase2/sbt fmt Test/compile check packageJobs` exited 0 on the final build. Each job stage contained one primary JAR and its application dependencies. Hadoop and Spark provider libraries stayed out of the packages. Spark 4.0.5 supplies Scala 2.13.16, matching the standard library resolved by Scala 3.3.7. The orchestrator verified the Docker build, Java version, non-root UID, and effective heap limit.

A disposable fixture compiled Hadoop Java and Spark DataFrame API calls, then ran one passing MUnit uPickle round-trip from `tests/contracts`. Earlier marker probes verified that failed formatting, compilation, and tests leave the marker absent or unchanged, and that successful checks refresh it. The direct Stop-hook probe and `git diff --check` passed.

The build requires a JDK and sbt, plus a local Docker engine for `dockerLocal`. The first run needs access to public artifact repositories. Usage is recorded in [packaging/README.md](../../../packaging/README.md). The next gate is independent review and human ratification.
