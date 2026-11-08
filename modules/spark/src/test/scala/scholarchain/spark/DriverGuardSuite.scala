package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

class DriverGuardSuite extends SparkSuite:
  test("collect above the row cap throws") {
    val data = session.range(10).toDF("n")
    val allowed = DriverGuard.rows(data, 10, "ten rows")
    assertEquals(allowed.size, 10)
    val error = intercept[DriverGuard.RowCapExceeded] {
      DriverGuard.rows(data, 9, "ten rows")
    }
    assert(error.getMessage.contains("ten rows"))
    assert(error.getMessage.contains("9"))
    intercept[IllegalArgumentException] {
      DriverGuard.rows(data, 0, "no rows allowed")
    }
  }

  private def mainSources: Vector[Path] =
    val root = Vector(
      Paths.get("src/main/scala"),
      Paths.get("modules/spark/src/main/scala")
    ).find(Files.isDirectory(_)).get
    val stream = Files.walk(root)
    try
      stream.iterator.asScala.toVector.filter(_.toString.endsWith(".scala"))
    finally stream.close()

  test("no unguarded collect in main sources") {
    val sources = mainSources
    assert(sources.nonEmpty)
    val forbidden = Vector(
      ".collect(",
      ".collectAsList(",
      ".toLocalIterator(",
      ".take(",
      ".takeAsList(",
      ".head(",
      ".first(",
      ".tail(",
      ".show(",
      ".toPandas("
    )
    val violations = for
      path <- sources
      text = Files.readString(path, UTF_8)
      token <- forbidden
      if text.contains(token) && path.getFileName.toString != "Support.scala"
    yield s"${path.getFileName}: $token"
    assertEquals(violations, Vector.empty[String])
    val support = sources.find(_.getFileName.toString == "Support.scala").get
    val guarded = Files.readString(support, UTF_8)
    assertEquals(guarded.split("\\.collect\\(").length - 1, 1)
    val outside = sources
      .filter(_.getFileName.toString != "Support.scala")
      .count(p =>
        Files.readString(p, UTF_8).contains("DriverGuard.rows(") ||
          Files.readString(p, UTF_8).contains("algo.rows(")
      )
    assert(
      outside > 0,
      "the other sources reach the driver only through the guard"
    )
  }
