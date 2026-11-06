# Phase 02 revalidation review diff

Produced by the orchestrator with read-only Git for the `code-reviewer` dispatch. The reviewer has no Bash tool and receives this diff as evidence.

## Provenance

Commands run from the repository root:

```
git log --oneline -30
git diff --stat 32ebc6d..HEAD
git diff --stat 027c9b7..32ebc6d
git diff 027c9b7..32ebc6d -- build.sbt project/ .scalafmt.conf .gitignore Dockerfile packaging/ tests/check_test_marker.py
git diff --stat 32ebc6d..HEAD -- build.sbt project/ .scalafmt.conf .gitignore Dockerfile packaging/ tests/
```

- Accepted Phase 2 commit: `32ebc6d2ac21f1f351577b38ba27f9cfb355d259`
- Phase 1 baseline: `027c9b70512d09c4c3cde65d7bd1ccaa9f5d82f3`
- HEAD at dispatch: `f4453d6`, working tree clean.

The last command produced no output: no build-owned file changed between the accepted Phase 2 commit and HEAD. The current build files are therefore identical to the accepted Phase 2 build files, and the diff below is the full Phase 2 build introduction.

## Build-file changes between `027c9b7` and `32ebc6d`

```
 .gitignore                       |   5 +
 .scalafmt.conf                   |   2 +
 Dockerfile                       |   6 +
 build.sbt                        | 145 ++++++++++++++
 packaging/README.md              |  10 ++
 project/DependencyVersions.scala |  11 ++
 project/build.properties         |   1 +
 project/plugins.sbt              |   1 +
 project/project/build.sbt        |   2 +
 tests/check_test_marker.py       |  85 +++++++++
```

Empty skeleton roots added in the same commit (all `.gitkeep`, zero bytes): `.github/workflows/`, `datasets/{samples,schemas,scripts}/`, `docs/{dependencies,incidents,releases}/`, `experiments/`, `infra/terraform/{environments,modules}/`, `load-tests/`, `modules/{core,entity-resolution,hadoop,rag,services,spark,storage}/src/{main,test}/scala/`, `prompts/{runtime,schemas}/`, `reports/`, `scripts/`, `tests/{adversarial,contracts,end-to-end,integration,src/test/scala}/`.

```diff
diff --git a/.gitignore b/.gitignore
new file mode 100644
index 0000000..a88e62c
--- /dev/null
+++ b/.gitignore
@@ -0,0 +1,5 @@
+target/
+.bsp/
+.bloop/
+.metals/
+/.claude/.last-test-run
diff --git a/.scalafmt.conf b/.scalafmt.conf
new file mode 100644
index 0000000..4a486a7
--- /dev/null
+++ b/.scalafmt.conf
@@ -0,0 +1,2 @@
+version = "3.10.0"
+runner.dialect = scala3
diff --git a/Dockerfile b/Dockerfile
new file mode 100644
index 0000000..c0a86d8
--- /dev/null
+++ b/Dockerfile
@@ -0,0 +1,6 @@
+FROM eclipse-temurin:21.0.13_9-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
+WORKDIR /opt/scholarchain
+COPY lib/ lib/
+USER 10001:10001
+ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-cp", "/opt/scholarchain/lib/*"]
+CMD ["-version"]
diff --git a/build.sbt b/build.sbt
new file mode 100644
index 0000000..8991b93
--- /dev/null
+++ b/build.sbt
@@ -0,0 +1,145 @@
+import java.nio.file.{Files, StandardCopyOption}
+import scala.sys.process.Process
+
+ThisBuild / scalaVersion := DependencyVersions.scala3
+ThisBuild / scalacOptions += "-release:17"
+ThisBuild / javacOptions ++= Seq("--release", "17")
+ThisBuild / libraryDependencies +=
+  "org.scalameta" %% "munit" % DependencyVersions.munit % Test
+
+lazy val markTestRun =
+  taskKey[Unit]("Check the build and record a successful test run.")
+lazy val allProjects = ScopeFilter(inAnyProject)
+lazy val scalaSources =
+  ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
+lazy val buildDockerLocal =
+  taskKey[Unit]("Build the local service runtime image.")
+lazy val stageJob = taskKey[Unit]("Stage the job JAR and runtime dependencies.")
+
+def jobPackagingSettings(useProvidedScalaLibrary: Boolean = false) = Seq(
+  stageJob := {
+    val output = target.value / "job"
+    val jobJar = (Compile / packageBin).value
+    val runtimeJars = (Runtime / dependencyClasspathAsJars).value.files
+    val shippedJars = runtimeJars.filterNot { jar =>
+      jar == jobJar ||
+      (useProvidedScalaLibrary && jar.getName.startsWith("scala-library-"))
+    }
+    IO.delete(output)
+    IO.copyFile(jobJar, output / jobJar.getName)
+    shippedJars.foreach { jar =>
+      IO.copyFile(jar, output / "lib" / jar.getName)
+    }
+    streams.value.log.info(s"Staged job in ${output}")
+  }
+)
+
+lazy val root = project
+  .in(file("."))
+  .aggregate(
+    core,
+    hadoop,
+    entityResolution,
+    sparkAnalytics,
+    storage,
+    rag,
+    services
+  )
+  .dependsOn(core % "test->compile")
+  .settings(
+    name := "scholarchain",
+    publish / skip := true,
+    Test / scalaSource := baseDirectory.value / "tests" / "src" / "test" / "scala",
+    Test / unmanagedSourceDirectories += baseDirectory.value / "tests" / "contracts",
+    buildDockerLocal := {
+      val context = target.value / "docker-local"
+      val serviceJar = (services / Compile / packageBin).value
+      val runtimeJars =
+        (services / Runtime / dependencyClasspathAsJars).value.files
+      IO.delete(context)
+      IO.copyFile(baseDirectory.value / "Dockerfile", context / "Dockerfile")
+      (serviceJar +: runtimeJars.filterNot(_ == serviceJar)).foreach { jar =>
+        IO.copyFile(jar, context / "lib" / jar.getName)
+      }
+      val exit = Process(
+        Seq(
+          "docker",
+          "build",
+          "--network=none",
+          "--tag",
+          "scholarchain-local:phase-2",
+          "."
+        ),
+        context
+      ).!
+      if (exit != 0) sys.error(s"Docker build failed with exit code ${exit}")
+    },
+    markTestRun := Def
+      .sequential(
+        scalafmtCheck.all(scalaSources),
+        Compile / scalafmtSbtCheck,
+        (Compile / compile).all(allProjects),
+        (Test / test).all(allProjects),
+        Def.task {
+          val marker =
+            (baseDirectory.value / ".claude" / ".last-test-run").toPath
+          val pending =
+            Files.createTempFile(marker.getParent, ".test-run-", ".tmp")
+          try
+            Files.move(
+              pending,
+              marker,
+              StandardCopyOption.ATOMIC_MOVE,
+              StandardCopyOption.REPLACE_EXISTING
+            )
+          finally Files.deleteIfExists(pending)
+          ()
+        }
+      )
+      .value
+  )
+
+lazy val core = project
+  .in(file("modules/core"))
+  .settings(
+    libraryDependencies += "com.lihaoyi" %% "upickle" % DependencyVersions.upickle
+  )
+lazy val hadoop = project
+  .in(file("modules/hadoop"))
+  .dependsOn(core)
+  .settings(jobPackagingSettings())
+  .settings(
+    libraryDependencies += "org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Provided
+  )
+lazy val entityResolution =
+  project
+    .in(file("modules/entity-resolution"))
+    .dependsOn(core)
+    .settings(jobPackagingSettings())
+lazy val sparkAnalytics = project
+  .in(file("modules/spark"))
+  .dependsOn(core)
+  .settings(jobPackagingSettings(useProvidedScalaLibrary = true))
+  .settings(
+    libraryDependencies += "org.apache.spark" % "spark-sql_2.13" % DependencyVersions.spark % Provided
+  )
+lazy val storage = project.in(file("modules/storage")).dependsOn(core)
+lazy val rag = project.in(file("modules/rag")).dependsOn(core, storage)
+lazy val services =
+  project
+    .in(file("modules/services"))
+    .dependsOn(core, storage, rag)
+    .settings(
+      libraryDependencies ++= Seq(
+        "org.slf4j" % "slf4j-api" % DependencyVersions.slf4j,
+        "org.slf4j" % "slf4j-simple" % DependencyVersions.slf4j % Runtime
+      )
+    )
+
+addCommandAlias("fmt", ";root / scalafmtAll;root / scalafmtSbt")
+addCommandAlias("check", "root / markTestRun")
+addCommandAlias(
+  "packageJobs",
+  ";hadoop / stageJob;entityResolution / stageJob;sparkAnalytics / stageJob"
+)
+addCommandAlias("dockerLocal", "root / buildDockerLocal")
diff --git a/packaging/README.md b/packaging/README.md
new file mode 100644
index 0000000..2c65f42
--- /dev/null
+++ b/packaging/README.md
@@ -0,0 +1,10 @@
+# Local build and packaging
+
+Use JDK 21 and the sbt launcher from the repository root. The Scala and Java compilers emit Java 17-compatible bytecode. The build reads no project-specific environment variables. The launcher uses the installed Java or `JAVA_HOME`, and the first run needs access to the public Maven repositories for pinned tooling and dependencies.
+
+- `sbt fmt` formats the Scala sources and sbt build definitions.
+- `sbt check` checks formatting, compiles, and runs the modules' tests and the root MUnit tests under `tests/contracts` and `tests/src/test/scala`. These tests run without AWS. The marker at `.claude/.last-test-run` updates after all stages pass.
+- `sbt packageJobs` stages a job JAR and its runtime classpath under `modules/{hadoop,entity-resolution,spark}/target/job/`. The main JAR sits at the top level, with core and third-party runtime JARs under `lib/`. Supply those companion JARs to the launcher alongside the main JAR when job entry points are implemented. Hadoop supplies its client libraries. Spark supplies Spark SQL, its Hadoop client stack, and Scala 2.13. Spark code uses Java and DataFrame APIs across the Scala 3 to Scala 2.13 boundary. The Spark staging directory includes Scala 3 support and leaves Scala 2.13 to the Spark distribution.
+- `sbt dockerLocal` builds `scholarchain-local:phase-2` using the installed Docker CLI and a running local Docker engine. Select a local Docker context before invoking it. Docker receives a staging directory with the Dockerfile and service runtime JARs. It may need to pull the digest-pinned Temurin image on the first build.
+
+To smoke-test the image locally, run `docker run --rm --memory=256m scholarchain-local:phase-2`. The default command prints the Java version. The image uses UID and GID 10001 and sets the JVM's initial and maximum heap to 25% and 75% of its memory budget. Once there is a service entry point, pass its main class as the container command.
diff --git a/project/DependencyVersions.scala b/project/DependencyVersions.scala
new file mode 100644
index 0000000..e464ae6
--- /dev/null
+++ b/project/DependencyVersions.scala
@@ -0,0 +1,11 @@
+object DependencyVersions {
+  // The sbt launcher and the Scalafmt engine read their pins from
+  // project/build.properties and .scalafmt.conf, respectively.
+  val scala3 = "3.3.7"
+  val sbtScalafmt = "2.5.6"
+  val upickle = "4.4.3"
+  val munit = "1.1.1"
+  val slf4j = "2.0.18"
+  val hadoop = "3.5.0"
+  val spark = "4.0.5"
+}
diff --git a/project/build.properties b/project/build.properties
new file mode 100644
index 0000000..01a16ed
--- /dev/null
+++ b/project/build.properties
@@ -0,0 +1 @@
+sbt.version=1.11.7
diff --git a/project/plugins.sbt b/project/plugins.sbt
new file mode 100644
index 0000000..dac93cf
--- /dev/null
+++ b/project/plugins.sbt
@@ -0,0 +1 @@
+addSbtPlugin("org.scalameta" % "sbt-scalafmt" % DependencyVersions.sbtScalafmt)
diff --git a/project/project/build.sbt b/project/project/build.sbt
new file mode 100644
index 0000000..4747282
--- /dev/null
+++ b/project/project/build.sbt
@@ -0,0 +1,2 @@
+// Load the same ledger while evaluating project/plugins.sbt.
+Compile / unmanagedSources += baseDirectory.value.getParentFile / "DependencyVersions.scala"
diff --git a/tests/check_test_marker.py b/tests/check_test_marker.py
new file mode 100644
index 0000000..85c8b27
--- /dev/null
+++ b/tests/check_test_marker.py
@@ -0,0 +1,85 @@
+"""Exercise the sbt verification marker in a disposable project copy."""
+
+import argparse
+from pathlib import Path
+import shutil
+import subprocess
+import tempfile
+
+
+FAILURE = "deliberate verification marker probe failure"
+
+
+def run_check(sbt, project, *, expected_failure=None):
+    result = subprocess.run(
+        [sbt, "check"],
+        cwd=project,
+        stdout=subprocess.PIPE,
+        stderr=subprocess.STDOUT,
+        text=True,
+        errors="replace",
+    )
+    if expected_failure is None:
+        passed = result.returncode == 0
+    else:
+        passed = result.returncode > 0 and any(
+            "munit.FailException:" in line and expected_failure in line
+            for line in result.stdout.splitlines()
+        )
+    if not passed:
+        raise SystemExit(
+            f"Unexpected sbt check result (exit {result.returncode})\n{result.stdout}"
+        )
+
+
+def marker_state(marker):
+    return marker.stat().st_mtime_ns, marker.read_bytes()
+
+
+def verify_marker(sbt, source, project):
+    for name in ("build.sbt", ".scalafmt.conf"):
+        shutil.copy2(source / name, project / name)
+    for name in ("project", "modules", "tests/src", "tests/contracts"):
+        if (source / name).is_dir():
+            shutil.copytree(
+                source / name, project / name, ignore=shutil.ignore_patterns("target")
+            )
+
+    marker = project / ".claude" / ".last-test-run"
+    marker.parent.mkdir()
+    run_check(sbt, project)
+    if not marker.is_file():
+        raise SystemExit("Successful sbt check did not create the test marker")
+    original = marker_state(marker)
+    print("Successful sbt check created the test marker", flush=True)
+
+    suite = project / "modules/services/src/test/scala/VerificationMarkerProbeSuite.scala"
+    suite.parent.mkdir(parents=True, exist_ok=True)
+    suite.write_text(
+        "class VerificationMarkerProbeSuite extends munit.FunSuite {\n"
+        '  test("verification marker probe") {\n'
+        f'    fail("{FAILURE}")\n'
+        "  }\n"
+        "}\n",
+        encoding="utf-8",
+    )
+    run_check(sbt, project, expected_failure=FAILURE)
+    if not marker.is_file() or marker_state(marker) != original:
+        raise SystemExit("Failing sbt check changed the test marker")
+    print("Deliberately failing MUnit test failed sbt check and preserved the test marker")
+
+
+def main():
+    parser = argparse.ArgumentParser(description=__doc__)
+    parser.add_argument("--sbt", default="sbt", help="Path to the sbt executable")
+    args = parser.parse_args()
+    sbt = shutil.which(args.sbt)
+    if sbt is None:
+        parser.error(f"sbt executable not found: {args.sbt}")
+    source = Path(__file__).resolve().parents[1]
+    with tempfile.TemporaryDirectory(prefix="scholarchain-marker-") as directory:
+        verify_marker(str(Path(sbt).absolute()), source, Path(directory))
+
+
+if __name__ == "__main__":
+    main()
```
