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
  .dependsOn(core % "test->compile")
  .settings(
    name := "scholarchain",
    publish / skip := true,
    Test / scalaSource := baseDirectory.value / "tests" / "src" / "test" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / "tests" / "contracts",
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
    libraryDependencies += "org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Provided
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
    libraryDependencies += "org.apache.spark" % "spark-sql_2.13" % DependencyVersions.spark % Provided
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
