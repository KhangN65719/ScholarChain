# Local build and packaging

Use JDK 21 and the sbt launcher from the repository root. The Scala and Java compilers emit Java 17-compatible bytecode. The build reads no project-specific environment variables. The launcher uses the installed Java or `JAVA_HOME`, and the first run needs access to the public Maven repositories for pinned tooling and dependencies.

- `sbt fmt` formats the Scala sources and sbt build definitions.
- `sbt check` checks formatting, compiles, and runs the modules' tests and the root MUnit tests under `tests/contracts` and `tests/src/test/scala`. These tests run without AWS. The marker at `.claude/.last-test-run` updates after all stages pass.
- `sbt packageJobs` stages a job JAR and its runtime classpath under `modules/{hadoop,entity-resolution,spark}/target/job/`. The main JAR sits at the top level, with core and third-party runtime JARs under `lib/`. Supply those companion JARs to the launcher alongside the main JAR when job entry points are implemented. Hadoop supplies its client libraries. Spark supplies Spark SQL, its Hadoop client stack, and Scala 2.13. Spark code uses Java and DataFrame APIs across the Scala 3 to Scala 2.13 boundary. The Spark staging directory includes Scala 3 support and leaves Scala 2.13 to the Spark distribution.
- `sbt dockerLocal` builds `scholarchain-local:phase-2` using the installed Docker CLI and a running local Docker engine. Select a local Docker context before invoking it. Docker receives a staging directory with the Dockerfile and service runtime JARs. It may need to pull the digest-pinned Temurin image on the first build.

To smoke-test the image locally, run `docker run --rm --memory=256m scholarchain-local:phase-2`. The default command prints the Java version. The image uses UID and GID 10001 and sets the JVM's initial and maximum heap to 25% and 75% of its memory budget. Once there is a service entry point, pass its main class as the container command.
