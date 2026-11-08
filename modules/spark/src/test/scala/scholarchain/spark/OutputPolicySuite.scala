package scholarchain.spark

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Paths}
import scholarchain.core.YearWindow

class OutputPolicySuite extends munit.FunSuite:
  private val repository =
    OutputPolicy.repositoryRoot(Paths.get("").toAbsolutePath)

  private val required = Vector(
    "--er-run",
    "/data/er/snapshot=abc",
    "--hadoop-root",
    "/data/hadoop/snapshot=abc",
    "--out-root",
    "/data/out"
  )

  test("repository and target output paths are refused") {
    assert(repository.isDefined, "the test runs inside the repository")
    val root = repository.get
    val inside =
      OutputPolicy.check(root.resolve("modules/spark/out"), repository)
    assert(inside.left.exists(_.contains("repository")), inside.toString)
    val top = OutputPolicy.check(root.resolve("run-output"), repository)
    assert(top.isLeft)
    val buildDir =
      OutputPolicy.check(Paths.get("/tmp/x/target/out"), repository)
    assert(buildDir.left.exists(_.contains("target")), buildDir.toString)
    val outside =
      Files.createTempDirectory("scholarchain-policy-").resolve("run")
    assertEquals(
      OutputPolicy.check(outside, repository),
      Right(outside.toAbsolutePath.normalize)
    )
    val out = new ByteArrayOutputStream()
    val code = CollaborationPipeline.run(
      Vector(
        "--er-run",
        "/data/er/snapshot=abc",
        "--hadoop-root",
        "/data/hadoop/snapshot=abc",
        "--out-root",
        root.resolve("modules/spark/target/out").toString
      ),
      new PrintStream(out),
      None
    )
    assertEquals(code, 2)
    assert(out.toString.contains("SPARK-PIPELINE RESULT: FAILED"))
  }

  test("a missing input artifact fails before any work") {
    val empty = Files.createTempDirectory("scholarchain-empty-")
    val out = new ByteArrayOutputStream()
    val code = CollaborationPipeline.run(
      Vector(
        "--er-run",
        empty.resolve("er").toString,
        "--hadoop-root",
        empty.resolve("hadoop").toString,
        "--out-root",
        empty.resolve("out").toString
      ),
      new PrintStream(out),
      None
    )
    assertEquals(code, 2)
    assert(out.toString.contains("Missing input artifact"), out.toString)
    assert(!Files.exists(empty.resolve("out")))
  }

  test("strict argument parsing") {
    val ok = PipelineArgs.parse(required :+ "--overwrite")
    assert(ok.isRight, ok.toString)
    assertEquals(ok.toOption.get.windows.length, 7)
    assertEquals(ok.toOption.get.overwrite, true)
    assertEquals(ok.toOption.get.master, "local[2]")
    def error(extra: Vector[String]): String =
      PipelineArgs.parse(required ++ extra).left.getOrElse("accepted")
    assert(error(Vector("--bogus", "1")).contains("Unknown option"))
    assert(error(Vector("--partitions")).contains("needs a value"))
    assert(error(Vector("--partitions", "abc")).contains("--partitions"))
    assert(error(Vector("--partitions", "0")).contains("--partitions"))
    assert(error(Vector("--windows", "2005-2000")).contains("2005-2000"))
    assert(
      error(Vector("--windows", "2000-2004,2000-2004")).contains("distinct")
    )
    assert(error(Vector("--seed", "1", "--seed", "2")).contains("Duplicate"))
    assert(error(Vector("stray")).contains("stray"))
    assert(PipelineArgs.parse(required.dropRight(2)).isLeft)
    assert(PipelineArgs.parse(Vector.empty).isLeft)
  }

  test("windows are explicit, configurable and checked") {
    val parsed = Windows.parse("2000-2004,2005-2009,all").toOption.get
    assertEquals(parsed.map(_.label), Vector("2000-2004", "2005-2009", "all"))
    assertEquals(parsed.last.years, YearWindow(1, 9999))
    assertEquals(parsed.head.years, YearWindow(2000, 2004))
    assert(Windows.parse("").isLeft)
    assert(Windows.parse("2000-").isLeft)
    assert(Windows.parse("2000-2004-2005").isLeft)
  }
