package scholarchain.evaluation

import java.nio.file.Files
import munit.FunSuite
import scholarchain.evaluation.benchmark.*

class BenchmarkCliSuite extends FunSuite:
  private def run(args: Vector[String]): (Int, String) =
    val buf = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(buf)(BenchmarkGen.cli(args))
    (code, buf.toString)

  private def untouched(): String =
    Files.createTempDirectory("cli-nothing-").toString

  test(
    "a flag with no value prints BENCHMARK ERROR, exits 2 and writes nothing"
  ):
    val out = untouched()
    val (code, text) = run(Vector("/no/such/export", out, "--seed"))
    assertEquals(code, 2)
    assertEquals(
      text.trim,
      "BENCHMARK ERROR: option --seed needs a value; nothing written"
    )
    assert(!text.contains("Usage"), text)
    val listing = Files.list(java.nio.file.Paths.get(out))
    try assertEquals(listing.count(), 0L)
    finally listing.close()

  test("a flag followed by another flag has no value either"):
    val (code, text) = run(
      Vector("/no/such/export", untouched(), "--seed", "--version", "v1")
    )
    assertEquals(code, 2)
    assert(
      text.contains(
        "BENCHMARK ERROR: option --seed needs a value; nothing written"
      ),
      text
    )

  test("a seed that is not a whole number is refused, not defaulted"):
    val out = untouched()
    val (code, text) = run(
      Vector(
        "/no/such/export",
        out,
        "--seed",
        "20261117x",
        "--no-vector-export",
        "yes"
      )
    )
    assertEquals(code, 2)
    assertEquals(
      text.trim,
      "BENCHMARK ERROR: --seed needs a whole number, got '20261117x'; nothing written"
    )
    val listing = Files.list(java.nio.file.Paths.get(out))
    try assertEquals(listing.count(), 0L)
    finally listing.close()
    assertEquals(
      BenchmarkGen.optionError(
        Map("--seed" -> "7", "--no-vector-export" -> "yes")
      ),
      None
    )

  test("a stray argument is refused with the same line"):
    val (code, text) = run(Vector("/no/such/export", untouched(), "stray"))
    assertEquals(code, 2)
    assert(
      text.contains(
        "BENCHMARK ERROR: unexpected argument stray; nothing written"
      ),
      text
    )

  test("a valueless no-vector flag is named"):
    val (code, text) =
      run(Vector("/no/such/export", untouched(), "--no-vector-export"))
    assertEquals(code, 2)
    assert(text.contains("option --no-vector-export needs a value"), text)

  test("option pairs are read as before"):
    assertEquals(
      BenchmarkGen.parseOptions(
        Vector("--seed", "7", "--no-vector-export", "yes")
      ),
      Right(Map("--seed" -> "7", "--no-vector-export" -> "yes"))
    )
    assertEquals(BenchmarkGen.parseOptions(Vector.empty), Right(Map.empty))

  test("too few arguments still print the usage and exit 2"):
    val (code, text) = run(Vector("only-one"))
    assertEquals(code, 2)
    assert(text.startsWith("Usage: BenchmarkGen"), text)

  test("the Scala 3 library version comes from the scala3-library jar name"):
    assertEquals(
      ScalaVersions.fromJarName("scala3-library_3-3.3.7.jar"),
      Some("3.3.7")
    )
    assertEquals(ScalaVersions.fromJarName("scala-library-2.13.16.jar"), None)

  test("the running Scala 3 library version is found, never the 2.13 one"):
    val v = ScalaVersions.scala3Library
    assert(v.exists(_.startsWith("3.")), s"found $v")

  test("a new manifest records the Scala 3 version and the 2.13 library apart"):
    val dir = Files.createTempDirectory("manifest-")
    BenchmarkGen.writeOutputs(
      BenchResult(Vector.empty, Vector.empty, Vector.empty),
      BenchConfig(),
      "run:v1:fx",
      None,
      VectorCoverage.noneByFlag,
      Vector.empty,
      dir
    )
    val m =
      ujson.read(Files.readString(dir.resolve("BENCHMARK-MANIFEST-v1.json")))
    assert(m("scala3LibraryVersion").str.startsWith("3."), m.toString)
    assert(m("scala2StandardLibraryVersion").str.startsWith("2.13"))
    assert(!m.obj.contains("scalaVersion"))
