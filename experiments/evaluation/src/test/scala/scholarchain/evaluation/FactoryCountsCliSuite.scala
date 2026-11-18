package scholarchain.evaluation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.factory.*

class FactoryCountsCliSuite extends FunSuite:
  private def run(args: Vector[String]): (Int, String) =
    val buf = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(buf)(FactoryCounts.cli(args))
    (code, buf.toString)

  private def tmp(prefix: String): Path = Files.createTempDirectory(prefix)

  private def mapping(): Path =
    val p = Files.createTempFile("mapping-", ".tsv")
    Files.write(
      p,
      "file\t.*\t\\.md$\tevidence\tnone\t-\t-\n".getBytes(UTF_8)
    )
    p

  private def entries(dir: Path): Long =
    val s = Files.list(dir)
    try s.count()
    finally s.close()

  private def assertRefused(
      out: Path,
      args: Vector[String],
      expect: String
  ): Unit =
    val (code, text) = run(args)
    assertNotEquals(code, 0)
    assertEquals(code, 2)
    assertEquals(text.linesIterator.size, 1, text)
    assert(text.startsWith("FACTORYCOUNTS ERROR: "), text)
    assert(text.contains(expect), text)
    assert(text.contains("nothing written"), text)
    assert(!text.contains("Exception"), text)
    assertEquals(entries(out), 0L, "a refusal wrote something")

  test("a missing mapping file is refused with one line, not a stack trace"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    assertRefused(
      out,
      Vector(runs.toString, "/no/such/mapping.tsv", out.toString),
      "mapping file /no/such/mapping.tsv is not a file"
    )

  test("a missing agent-runs directory is refused"):
    val out = tmp("out-")
    assertRefused(
      out,
      Vector("/no/such/runs", mapping().toString, out.toString),
      "agent-runs directory /no/such/runs is not a directory"
    )

  test("a missing rewrites file is refused, not silently ignored"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    assertRefused(
      out,
      Vector(
        runs.toString,
        mapping().toString,
        out.toString,
        "--rewrites",
        "/no/such/rewrites.tsv"
      ),
      "rewrites file /no/such/rewrites.tsv is not a file"
    )

  test("a misspelt option is refused by name, not ignored"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    assertRefused(
      out,
      Vector(
        runs.toString,
        mapping().toString,
        out.toString,
        "--rewrite",
        "/some/file.tsv"
      ),
      "unknown option --rewrite"
    )
    assertRefused(
      out,
      Vector(
        runs.toString,
        mapping().toString,
        out.toString,
        "--phases",
        "1-1",
        "--seed",
        "3"
      ),
      "unknown option --seed"
    )

  test("a directory given as the mapping file is refused"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    assertRefused(
      out,
      Vector(runs.toString, tmp("map-dir-").toString, out.toString),
      "is not a file"
    )

  test("an unreadable mapping file is refused"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    val m = mapping()
    val readable = m.toFile.setReadable(false, false)
    try
      // A user that can read a file whatever its mode (root) cannot show this.
      assert(readable, "could not clear the read bit")
      if !Files.isReadable(m) then
        assertRefused(
          out,
          Vector(runs.toString, m.toString, out.toString),
          "is not readable"
        )
    finally m.toFile.setReadable(true, false)

  test("a mapping row that cannot be understood is refused in one line"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    val bad = Files.createTempFile("bad-mapping-", ".tsv")
    Files.write(bad, "nonsense\trow\n".getBytes(UTF_8))
    assertRefused(
      out,
      Vector(runs.toString, bad.toString, out.toString),
      "the inputs could not be read"
    )

  test("an existing run directory is refused before any work"):
    val runs = tmp("runs-")
    val out = tmp("out-")
    Files.createDirectory(out.resolve("taken"))
    val (code, text) = run(
      Vector(
        runs.toString,
        mapping().toString,
        out.toString,
        "--run-id",
        "taken"
      )
    )
    assertEquals(code, 2)
    assert(text.contains("already exists"), text)
    assert(text.contains("nothing written"), text)

  test("good inputs still run and write the sealed run directory"):
    val runs = tmp("runs-")
    val phase = Files.createDirectories(runs.resolve("phase-01"))
    Files.write(phase.resolve("notes.md"), "# notes\n".getBytes(UTF_8))
    val out = tmp("out-")
    val (code, text) = run(
      Vector(
        runs.toString,
        mapping().toString,
        out.toString,
        "--phases",
        "1-1",
        "--run-id",
        "ok-1"
      )
    )
    assertEquals(code, 0, text)
    assert(text.contains("FACTORYCOUNTS RESULT"), text)
    assert(Files.isRegularFile(out.resolve("ok-1").resolve("SHA256SUMS")))
