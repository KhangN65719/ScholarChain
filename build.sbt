import java.nio.file.{Files, StandardCopyOption}
import sbt.internal.util.MessageOnlyException
import scala.sys.process.Process

ThisBuild / scalaVersion := DependencyVersions.scala3
ThisBuild / scalacOptions += "-release:17"
ThisBuild / javacOptions ++= Seq("--release", "17")
ThisBuild / libraryDependencies +=
  "org.scalameta" %% "munit" % DependencyVersions.munit % Test

lazy val markTestRun =
  taskKey[Unit]("Check the build and record a successful test run.")
// The one marker-writing step, shared by markTestRun (check) and quickRun
// (quick). Each runs it last, so the marker exists only after every earlier
// step passed. Because quickRun skips slowSuites, a marker from `quick` means
// "the quick set passed on these sources", not "every test passed"; only
// `check` covers every suite.
lazy val writeTestMarker = Def.task {
  val marker = (baseDirectory.value / ".claude" / ".last-test-run").toPath
  val pending = Files.createTempFile(marker.getParent, ".test-run-", ".tmp")
  try
    Files.move(
      pending,
      marker,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING
    )
  finally Files.deleteIfExists(pending)
  ()
}
lazy val allProjects = ScopeFilter(inAnyProject)
lazy val scalaSources =
  ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
lazy val buildDockerLocal =
  taskKey[Unit]("Build the local service runtime image.")
lazy val stageJob = taskKey[Unit]("Stage the job JAR and runtime dependencies.")
lazy val jobSdkGuard = taskKey[Unit](
  "Fail when a batch job module has an AWS SDK jar on its Runtime classpath."
)
// Job modules stay outside the runtime graph and must not ship the AWS SDK.
lazy val jobProjects = ScopeFilter(
  inProjects(hadoop, entityResolution, sparkAnalytics)
)
// The jar's resolved organization decides when sbt recorded it; the path
// segment is the fallback for an entry without module metadata.
def isAwsSdkJar(entry: Attributed[File]): Boolean =
  entry.get(moduleID.key).exists(_.organization == "software.amazon.awssdk") ||
    entry.data.getPath.contains("/software.amazon.awssdk/")
// Hadoop's own diagnostics go through SLF4J, and hadoop-client-runtime brings
// only slf4j-api 1.7.36 with no binding, so a test run logs nothing: job
// submission messages and task failure stacks are discarded on exactly the run
// that needs them. Both entries read the pinned ledger slf4j value, which
// resolves the api to 2.0.18 and evicts Hadoop's 1.7.36; 2.0.x is a drop-in
// replacement for a caller of the 1.7 api, which is all Hadoop is here.
lazy val slf4jTestBinding = Seq(
  "org.slf4j" % "slf4j-api" % DependencyVersions.slf4j % Test,
  "org.slf4j" % "slf4j-simple" % DependencyVersions.slf4j % Test
)

// Spark 4 on Java 21 needs the java.base packages opened to its unnamed module
// (the list is Spark's own JavaModuleOptions) plus two reflection switches.
// IgnoreUnrecognizedVMOptions keeps the same list valid on other JDKs. The
// heap is capped at 2g: local[2] over hand-checkable fixtures and bounded
// local runs fit well inside it, and a cap makes an accidental full-graph
// collect fail fast instead of swallowing the machine. ExitOnOutOfMemoryError
// completes that intent: without it an OutOfMemoryError can leave the forked
// JVM stuck while sbt waits, so the JVM exits at the first one and the run
// fails with a stack trace. The UI is off so no run needs port 4040, and driver
// host and bind address are pinned to loopback so a local session does not
// depend on hostname resolution or a network.
lazy val sparkJvmOptions = Seq(
  "-Xmx2g",
  "-XX:+ExitOnOutOfMemoryError",
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
  "-Djdk.reflect.useDirectMethodHandle=false",
  "-Dio.netty.tryReflectionSetAccessible=true",
  "-Dspark.ui.enabled=false",
  "-Dspark.driver.host=127.0.0.1",
  "-Dspark.driver.bindAddress=127.0.0.1"
)

// A root suite is a Spark suite when its class name contains "Spark" or it
// lives in a package segment named spark. Only those suites fork.
def isSparkSuite(name: String): Boolean =
  name.contains("Spark") || name.contains(".spark.")

// Suites the `quick` alias skips, by fully qualified class name. `check` still
// runs every one of them. Timings come from the human's clean check on
// 2026-11-08 (phase-06-clean-check-4.log); a suite is listed only when its
// measured time is dominated by starting a local Spark session or a Hadoop job.
lazy val slowSuites: Set[String] = Set(
  // Root Spark integration suites. Each one drives the real pipeline over the
  // hand-built worlds in tests/integration (Runs.a, a3, c1, c2); a single run
  // takes about a minute under local[2], and the suites share those runs.
  "scholarchain.tests.analytics.SparkCoauthorshipSuite",
  "scholarchain.tests.analytics.SparkDegenerateInputsSuite",
  "scholarchain.tests.analytics.SparkGraphMeasuresSuite",
  "scholarchain.tests.analytics.SparkOutputsSuite",
  "scholarchain.tests.analytics.SparkPathEvidenceSuite",
  "scholarchain.tests.analytics.SparkReproducibilitySuite",
  // Runs the pipeline and the export in its first test: about 168 s.
  "scholarchain.tests.analytics.SparkRoundTripSuite",
  "scholarchain.tests.analytics.SparkSummariesSuite",
  "scholarchain.tests.analytics.SparkTemporalWindowsSuite",
  // modules/spark suites that run the whole pipeline: 79 to 88 s per test.
  "scholarchain.spark.CollaborationPipelineSuite",
  "scholarchain.spark.ConflictingKeysSuite",
  // modules/hadoop suites that execute real MapReduce jobs on LocalJobRunner:
  // several seconds per job, 10 s for the seven-stage driver run.
  "scholarchain.hadoop.MapReduceEndToEndSuite",
  "scholarchain.hadoop.PipelineDriverOutputsSuite",
  "scholarchain.hadoop.PipelineDriverUnknownDatasetSuite"
)

// quick runs only the suites not named in slowSuites. It goes through testOnly,
// so each project keeps its own grouping and fork settings (the root Spark
// group, modules/spark Test / fork). It is a separate task, so Test / test and
// check are untouched.
lazy val quickTest =
  taskKey[Unit]("Run every test suite except the named slow suites.")
lazy val quickRun =
  taskKey[Unit]("Check formatting, compile and run the quick tests.")
// Every project must include quickSettings: one that omits it is skipped by
// quick's quickTest.all(allProjects) while check still runs its tests.
lazy val quickSettings = Seq(
  quickTest := Def.taskDyn {
    val names =
      (Test / definedTests).value.map(_.name).filterNot(slowSuites).sorted
    if (names.isEmpty) Def.task(())
    else (Test / testOnly).toTask(names.mkString(" ", " ", ""))
  }.value
)

def jobPackagingSettings(useProvidedScalaLibrary: Boolean = false) = Seq(
  jobSdkGuard := {
    val offending = (Runtime / dependencyClasspathAsJars).value
      .filter(isAwsSdkJar)
      .map(_.data.getName)
    if (offending.nonEmpty)
      throw new MessageOnlyException(
        s"${name.value} must not ship the AWS SDK on its Runtime classpath; " +
          s"found ${offending.size} jar(s): ${offending.sorted.mkString(", ")}"
      )
  },
  stageJob := {
    jobSdkGuard.value
    val output = target.value / "job"
    val jobJar = (Compile / packageBin).value
    val runtimeJars = (Runtime / dependencyClasspathAsJars).value.files
    val shippedJars = runtimeJars.filterNot { jar =>
      jar == jobJar ||
      (useProvidedScalaLibrary && jar.getName.startsWith("scala-library-"))
    }
    IO.delete(output)
    IO.copyFile(jobJar, output / jobJar.getName)
    shippedJars.foreach { jar =>
      IO.copyFile(jar, output / "lib" / jar.getName)
    }
    streams.value.log.info(s"Staged job in ${output}")
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    hadoop,
    entityResolution,
    sparkAnalytics,
    storage,
    rag,
    services
  )
  // Test-scoped only, in both edges. Aggregation is not a classpath edge, so
  // an independent test under tests/ can reference only what is named here.
  // hadoop is a batch job module: a compile or runtime edge would pull it into
  // the runtime graph, which the module boundaries forbid, so the edge maps
  // test->compile and appears in no Compile, Runtime or packaged classpath.
  // entityResolution is a batch job module and gets the same treatment, so
  // independent tests under tests/ can reach it without a runtime edge.
  .dependsOn(
    core % "test->compile",
    hadoop % "test->compile",
    entityResolution % "test->compile",
    // sparkAnalytics is a batch job module, so it gets the same Test-only edge.
    sparkAnalytics % "test->compile",
    // storage is already a runtime module; this Test-only edge adds no runtime
    // edge and lets independent tests under tests/ reference its contracts.
    storage % "test->compile",
    // rag is a runtime module; this Test-only edge adds no Compile, Runtime or
    // packaged-classpath edge and lets independent tests under tests/ reference
    // its contracts. No cycle: rag depends on core and storage, and nothing
    // depends on root.
    rag % "test->compile",
    // services is a runtime module; this Test-only edge adds no Compile,
    // Runtime or packaged-classpath edge and lets the black-box tests under
    // tests/ start the service runtime. No cycle: services depends on core,
    // storage and rag, and nothing depends on root.
    services % "test->compile"
  )
  .settings(quickSettings)
  .settings(
    quickRun := Def
      .sequential(
        scalafmtCheck.all(scalaSources),
        Compile / scalafmtSbtCheck,
        jobSdkGuard.all(jobProjects),
        (Compile / compile).all(allProjects),
        quickTest.all(allProjects),
        writeTestMarker
      )
      .value,
    // Provided is not carried across a project edge, so Spark is declared here
    // at Test for tests under tests/ that reach modules/spark code.
    libraryDependencies += "org.apache.spark" % "spark-sql_2.13" % DependencyVersions.spark % Test,
    // Root tests run in-process, and --add-opens cannot be applied to a running
    // JVM. Forking the whole root run would change every root test, so only the
    // Spark suites go to a forked group with the Spark flags; every other suite
    // stays in the in-process default group.
    Test / testGrouping := {
      val all = (Test / definedTests).value
      val spark = all.filter(t => isSparkSuite(t.name))
      val other = all.filterNot(t => isSparkSuite(t.name))
      val forkOptions = ForkOptions()
        .withWorkingDirectory(Some(baseDirectory.value))
        .withRunJVMOptions(sparkJvmOptions.toVector)
      val sparkPolicy = Tests.SubProcess(forkOptions)
      val sparkGroups =
        if (spark.isEmpty) Nil
        else Seq(new Tests.Group("spark", spark, sparkPolicy))
      Seq(new Tests.Group("default", other, Tests.InProcess)) ++ sparkGroups
    },
    name := "scholarchain",
    publish / skip := true,
    Test / scalaSource := baseDirectory.value / "tests" / "src" / "test" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / "tests" / "contracts",
    // The reserved independent-test roots. Without these a .scala file placed
    // in them is on no source path, so it is neither compiled nor run while
    // sbt check still passes and writes the marker.
    Test / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "tests" / "adversarial",
      baseDirectory.value / "tests" / "integration",
      baseDirectory.value / "tests" / "end-to-end"
    ),
    // sbt does not carry a Provided dependency across a project edge, so the
    // Hadoop API that modules/hadoop keeps Provided is declared here at Test
    // for tests under tests/ that reach the module's Hadoop-typed code.
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Test,
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-runtime" % DependencyVersions.hadoop % Test,
    libraryDependencies ++= slf4jTestBinding,
    buildDockerLocal := {
      val context = target.value / "docker-local"
      val serviceJar = (services / Compile / packageBin).value
      val runtimeJars =
        (services / Runtime / dependencyClasspathAsJars).value.files
      IO.delete(context)
      IO.copyFile(baseDirectory.value / "Dockerfile", context / "Dockerfile")
      (serviceJar +: runtimeJars.filterNot(_ == serviceJar)).foreach { jar =>
        IO.copyFile(jar, context / "lib" / jar.getName)
      }
      val exit = Process(
        Seq(
          "docker",
          "build",
          "--network=none",
          "--tag",
          "scholarchain-local:phase-2",
          "."
        ),
        context
      ).!
      if (exit != 0) sys.error(s"Docker build failed with exit code ${exit}")
    },
    markTestRun := Def
      .sequential(
        scalafmtCheck.all(scalaSources),
        Compile / scalafmtSbtCheck,
        jobSdkGuard.all(jobProjects),
        (Compile / compile).all(allProjects),
        (Test / test).all(allProjects),
        writeTestMarker
      )
      .value
  )

lazy val core = project
  .in(file("modules/core"))
  .settings(quickSettings)
  .settings(
    libraryDependencies += "com.lihaoyi" %% "upickle" % DependencyVersions.upickle
  )
lazy val hadoop = project
  .in(file("modules/hadoop"))
  .dependsOn(core)
  .settings(jobPackagingSettings())
  .settings(quickSettings)
  .settings(
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Provided,
    // A cluster supplies the shaded runtime, so production keeps it off the
    // classpath. Tests run outside a cluster and need it: Configuration parses
    // XML on construction and reaches for the shaded Woodstox parser.
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-runtime" % DependencyVersions.hadoop % Test,
    // The runtime jar answers Configuration's construction only. Executing a
    // job needs LocalJobRunner plumbing, job submission, split assignment, a
    // task JVM, a shuffle and an OutputCommitter; the minicluster artifact is
    // Hadoop's published test-side counterpart to api + runtime and carries
    // them. Test scope, so it reaches no packaged job artifact.
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-minicluster" % DependencyVersions.hadoop % Test,
    libraryDependencies ++= slf4jTestBinding
  )
lazy val entityResolution =
  project
    .in(file("modules/entity-resolution"))
    .dependsOn(core)
    .settings(jobPackagingSettings())
    .settings(quickSettings)
lazy val sparkAnalytics = project
  .in(file("modules/spark"))
  .dependsOn(core)
  .settings(jobPackagingSettings(useProvidedScalaLibrary = true))
  .settings(quickSettings)
  .settings(
    libraryDependencies += "org.apache.spark" % "spark-sql_2.13" % DependencyVersions.spark % Provided,
    // run and runMain use the Runtime classpath, which omits Provided, so a
    // local run would fail with NoClassDefFoundError. Compile's full classpath
    // includes Provided. Neither this nor the packaging below changes: stageJob
    // reads the Runtime classpath, so Spark is still not shipped.
    Compile / run := Defaults
      .runTask(
        Compile / fullClasspath,
        Compile / run / mainClass,
        Compile / run / runner
      )
      .evaluated,
    Compile / runMain := Defaults
      .runMainTask(Compile / fullClasspath, Compile / run / runner)
      .evaluated,
    Compile / run / fork := true,
    Compile / run / javaOptions ++= sparkJvmOptions,
    Test / fork := true,
    Test / javaOptions ++= sparkJvmOptions
  )
lazy val storage = project
  .in(file("modules/storage"))
  .dependsOn(core)
  .settings(quickSettings)
  .settings(
    // The HTTP client is the JDK-based url-connection-client; the Apache (4 and
    // 5) and Netty clients are excluded from every SDK module so none is chosen by
    // classpath discovery.
    libraryDependencies ++= Seq(
      "s3",
      "s3vectors",
      "bedrockruntime",
      "neptunedata",
      "dynamodb",
      "url-connection-client"
    ).map { artifact =>
      ("software.amazon.awssdk" % artifact % DependencyVersions.awsSdk)
        .exclude("software.amazon.awssdk", "apache-client")
        .exclude("software.amazon.awssdk", "netty-nio-client")
        .exclude("software.amazon.awssdk", "apache5-client")
    }
  )
lazy val rag = project
  .in(file("modules/rag"))
  .dependsOn(core, storage)
  .settings(quickSettings)
lazy val services =
  project
    .in(file("modules/services"))
    .dependsOn(core, storage, rag)
    .settings(quickSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.slf4j" % "slf4j-api" % DependencyVersions.slf4j,
        "org.slf4j" % "slf4j-simple" % DependencyVersions.slf4j % Runtime
      )
    )

addCommandAlias("fmt", ";root / scalafmtAll;root / scalafmtSbt")
addCommandAlias("check", "root / markTestRun")
addCommandAlias("quick", "root / quickRun")
addCommandAlias(
  "packageJobs",
  ";hadoop / stageJob;entityResolution / stageJob;sparkAnalytics / stageJob"
)
addCommandAlias("dockerLocal", "root / buildDockerLocal")
