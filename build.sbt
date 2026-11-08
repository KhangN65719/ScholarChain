import java.nio.file.{Files, StandardCopyOption}
import scala.sys.process.Process

ThisBuild / scalaVersion := DependencyVersions.scala3
ThisBuild / scalacOptions += "-release:17"
ThisBuild / javacOptions ++= Seq("--release", "17")
ThisBuild / libraryDependencies +=
  "org.scalameta" %% "munit" % DependencyVersions.munit % Test

lazy val markTestRun =
  taskKey[Unit]("Check the build and record a successful test run.")
lazy val allProjects = ScopeFilter(inAnyProject)
lazy val scalaSources =
  ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
lazy val buildDockerLocal =
  taskKey[Unit]("Build the local service runtime image.")
lazy val stageJob = taskKey[Unit]("Stage the job JAR and runtime dependencies.")

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
// collect fail fast instead of swallowing the machine. The UI is off so no run
// needs port 4040, and driver host and bind address are pinned to loopback so a
// local session does not depend on hostname resolution or a network.
lazy val sparkJvmOptions = Seq(
  "-Xmx2g",
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

def jobPackagingSettings(useProvidedScalaLibrary: Boolean = false) = Seq(
  stageJob := {
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
    sparkAnalytics % "test->compile"
  )
  .settings(
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
        (Compile / compile).all(allProjects),
        (Test / test).all(allProjects),
        Def.task {
          val marker =
            (baseDirectory.value / ".claude" / ".last-test-run").toPath
          val pending =
            Files.createTempFile(marker.getParent, ".test-run-", ".tmp")
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
      )
      .value
  )

lazy val core = project
  .in(file("modules/core"))
  .settings(
    libraryDependencies += "com.lihaoyi" %% "upickle" % DependencyVersions.upickle
  )
lazy val hadoop = project
  .in(file("modules/hadoop"))
  .dependsOn(core)
  .settings(jobPackagingSettings())
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
lazy val sparkAnalytics = project
  .in(file("modules/spark"))
  .dependsOn(core)
  .settings(jobPackagingSettings(useProvidedScalaLibrary = true))
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
lazy val storage = project.in(file("modules/storage")).dependsOn(core)
lazy val rag = project.in(file("modules/rag")).dependsOn(core, storage)
lazy val services =
  project
    .in(file("modules/services"))
    .dependsOn(core, storage, rag)
    .settings(
      libraryDependencies ++= Seq(
        "org.slf4j" % "slf4j-api" % DependencyVersions.slf4j,
        "org.slf4j" % "slf4j-simple" % DependencyVersions.slf4j % Runtime
      )
    )

addCommandAlias("fmt", ";root / scalafmtAll;root / scalafmtSbt")
addCommandAlias("check", "root / markTestRun")
addCommandAlias(
  "packageJobs",
  ";hadoop / stageJob;entityResolution / stageJob;sparkAnalytics / stageJob"
)
addCommandAlias("dockerLocal", "root / buildDockerLocal")
