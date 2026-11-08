package scholarchain.tests.adversarial

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scholarchain.spark.{OutputPolicy, PipelineArgs, Windows}
import scholarchain.tests.analytics.Harness

// Item 12: where output may go, strict argument parsing, and the refusals of
// the entry point. Nothing here starts a Spark session.
class SparkOutputPolicySuite extends FunSuite:
  private val repo: Path =
    OutputPolicy.repositoryRoot(Paths.get("")).getOrElse(fail("no repository"))

  private def tmp(name: String): Path =
    Files.createTempDirectory(Harness.root, name)

  private def refused(result: Either[String, Path], fragment: String): Unit =
    assert(result.isLeft, s"expected a refusal, got $result")
    assert(result.left.exists(_.contains(fragment)), result.toString)

  test("the repository root is found from the working directory") {
    assert(Files.exists(repo.resolve(".git")))
    assert(Files.isRegularFile(repo.resolve("build.sbt")))
  }

  test("output inside the repository is refused, however it is spelled") {
    val some = Some(repo)
    refused(OutputPolicy.check(repo.resolve("out"), some), "repository")
    refused(OutputPolicy.check(repo, some), "repository")
    refused(OutputPolicy.check(Paths.get("relative-out"), some), "repository")
    refused(OutputPolicy.check(Paths.get("tests/new/dir"), some), "repository")
    val dotted = repo.resolve("modules/spark/../../x/y")
    refused(OutputPolicy.check(dotted, some), "repository")
    refused(
      OutputPolicy.check(repo.resolve("docs/agent-runs"), some),
      "repository"
    )
  }

  test(
    "output under a target directory is refused even outside the repository"
  ) {
    val base = tmp("policy")
    refused(OutputPolicy.check(base.resolve("target/out"), None), "target")
    refused(OutputPolicy.check(base.resolve("a/target"), None), "target")
    refused(
      OutputPolicy.check(repo.resolve("modules/spark/target/x"), None),
      "target"
    )
  }

  test(
    "a sibling directory or a similar name is not the repository or target"
  ) {
    val base = tmp("policy-ok")
    val some = Some(repo)
    assert(OutputPolicy.check(base.resolve("out"), some).isRight)
    assert(OutputPolicy.check(base.resolve("targets/out"), some).isRight)
    assert(OutputPolicy.check(base.resolve("mytarget"), some).isRight)
    val sibling = repo.resolveSibling(repo.getFileName.toString + "-data")
    assert(OutputPolicy.check(sibling.resolve("out"), some).isRight)
    assert(OutputPolicy.check(base.resolve("out"), None).isRight)
  }

  test("a symbolic link into the repository does not get around the check") {
    val base = tmp("policy-link")
    val link = base.resolve("link")
    Files.createSymbolicLink(link, repo)
    refused(
      OutputPolicy.check(link.resolve("new-out"), Some(repo)),
      "repository"
    )
    val deep = base.resolve("deeper")
    Files.createSymbolicLink(deep, repo.resolve("modules"))
    refused(OutputPolicy.check(deep.resolve("a/b/c"), Some(repo)), "repository")
  }

  test("the filesystem root is refused") {
    refused(OutputPolicy.check(Paths.get("/"), None), "root")
  }

  // ---- the entry point refuses before it does any work ----

  private def args(out: String, more: String*): Vector[String] =
    Vector(
      "--er-run",
      "/no/such/er",
      "--hadoop-root",
      "/no/such/hadoop",
      "--out-root",
      out
    ) ++ more

  test("the entry point refuses a repository or target output, exit 2") {
    val inRepo = repo.resolve("never-created-output")
    val first = Harness.refuse(args(inRepo.toString))
    assertEquals(first.code, 2, first.text)
    assert(
      first.text.contains("Refusing to write inside the repository"),
      first.text
    )
    assert(!Files.exists(inRepo))
    val relative = Harness.refuse(args("never-created-relative"))
    assertEquals(relative.code, 2, relative.text)
    assert(!Files.exists(repo.resolve("never-created-relative")))
    val under = tmp("entry").resolve("target/out")
    val second = Harness.refuse(args(under.toString))
    assertEquals(second.code, 2, second.text)
    assert(second.text.contains("target"), second.text)
    assert(!Files.exists(under))
  }

  test("the entry point refuses an event log directory inside the repository") {
    val log = repo.resolve("never-created-logs")
    val out = tmp("entry-log").resolve("out")
    val run =
      Harness.refuse(args(out.toString, "--event-log-dir", log.toString))
    assertEquals(run.code, 2, run.text)
    assert(run.text.contains("repository"), run.text)
    assert(!Files.exists(log))
    assert(!Files.exists(out))
  }

  test("bad arguments print the usage and exit with code 2") {
    val run = Harness.refuse(Vector("--bogus"))
    assertEquals(run.code, 2, run.text)
    assert(run.text.contains("Usage: CollaborationPipeline"), run.text)
    assert(run.text.contains("SPARK-PIPELINE RESULT: FAILED"), run.text)
    assertEquals(Harness.refuse(Vector.empty).code, 2)
  }

  // ---- strict argument parsing ----

  private val base = Vector(
    "--er-run",
    "/er",
    "--hadoop-root",
    "/hadoop",
    "--out-root",
    "/out"
  )

  private def rejects(more: Vector[String], fragment: String): Unit =
    val result = PipelineArgs.parse(base ++ more)
    assert(result.isLeft, s"accepted $more")
    assert(result.left.exists(_.contains(fragment)), s"$more: $result")

  test("the defaults are the documented ones") {
    val parsed = PipelineArgs.parse(base).toOption.get
    assertEquals(parsed.sampleSize, 20)
    assertEquals(parsed.partitions, 8)
    assertEquals(parsed.hopLimit, 6)
    assertEquals(parsed.autoPairTop, 4)
    assertEquals(parsed.seed, 42L)
    assertEquals(parsed.maxIterations, 100)
    assertEquals(parsed.driverRowCap, 5000)
    assertEquals(parsed.maxIntegrityViolations, 1000L)
    assertEquals(parsed.master, "local[2]")
    assert(!parsed.overwrite)
    assertEquals(
      parsed.windows.map(_.label),
      Windows.defaultSpec.split(",").toVector
    )
  }

  test("required, unknown, repeated, valueless and stray arguments fail") {
    assert(PipelineArgs.parse(Vector.empty).isLeft)
    assert(PipelineArgs.parse(base.drop(2)).isLeft)
    rejects(Vector("--bogus", "1"), "Unknown option")
    rejects(Vector("stray"), "stray")
    rejects(Vector("--seed", "1", "--seed", "2"), "Duplicate option")
    rejects(Vector("--seed"), "needs a value")
    rejects(Vector("--seed", "--overwrite"), "needs a value")
    rejects(Vector("--overwrite", "true"), "stray")
    rejects(Vector("--overwrite", "--overwrite"), "Duplicate")
    assert(PipelineArgs.parse(Vector("--er-run=/er")).isLeft)
    rejects(Vector("--master", ""), "master")
  }

  test("numeric options are bounded and must be whole numbers") {
    val bad = Vector(
      "--sample-size" -> Vector("0", "abc", "1.5", "-1", "100001", ""),
      "--partitions" -> Vector("0", "10001", "x"),
      "--hop-limit" -> Vector("0", "65", "-3"),
      "--auto-pair-top" -> Vector("-1", "51"),
      "--seed" -> Vector("-1", "x"),
      "--max-iterations" -> Vector("0", "100001"),
      "--driver-row-cap" -> Vector("0", "10000001"),
      "--max-integrity-violations" -> Vector("-1", "many"),
      "--paper-summaries" -> Vector("-1", "100001"),
      "--representative-works" -> Vector("0", "1001")
    )
    bad.foreach { (option, values) =>
      values.foreach(v => rejects(Vector(option, v), option))
    }
    val good = Vector(
      "--sample-size" -> "1",
      "--partitions" -> "10000",
      "--hop-limit" -> "64",
      "--auto-pair-top" -> "0",
      "--seed" -> "0",
      "--max-integrity-violations" -> "0",
      "--paper-summaries" -> "0",
      "--driver-row-cap" -> "1"
    )
    good.foreach { (option, value) =>
      assert(PipelineArgs.parse(base ++ Vector(option, value)).isRight, option)
    }
  }

  test("windows must be ordered year ranges or all, with distinct labels") {
    Vector(
      "2005-2000",
      "abc",
      "2000",
      "2000-2004-2009",
      "",
      "2000-2004,2000-2004",
      "all,all",
      "2000-2004,,all",
      "-2000-2004",
      "2000-abc",
      "all-2000"
    ).foreach { spec =>
      assert(Windows.parse(spec).isLeft, s"accepted '$spec'")
      rejects(Vector("--windows", spec), "indow")
    }
    val ok = Windows.parse(" 2000-2004 , 2003-2006,all").toOption.get
    assertEquals(ok.map(_.label), Vector("2000-2004", "2003-2006", "all"))
    assertEquals(
      ok.map(w => (w.start, w.end)),
      Vector((2000, 2004), (2003, 2006), (1, 9999))
    )
    assert(Windows.parse("1900-1999,2000-2004,all").isRight)
  }
