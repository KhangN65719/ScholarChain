package scholarchain.tests.adversarial

import java.nio.file.Files
import munit.FunSuite
import scholarchain.tests.analytics.Harness
import scholarchain.spark.DriverGuard

// Item 10: no full driver collect. The scan reads the method references of the
// compiled classes, so a Scala collection's collect or head is not confused
// with a Spark one, and then checks the guard at run time.
class SparkDriverCollectScanSuite extends FunSuite:
  import SparkClassFiles.*

  // Spark methods that bring rows or values to the driver.
  private val pulling = Set(
    "collect",
    "collectAsList",
    "toLocalIterator",
    "take",
    "takeAsList",
    "head",
    "first",
    "tail",
    "takeOrdered",
    "top",
    "show",
    "toPandas",
    "reduce",
    "treeReduce",
    "countByValue",
    "countByKey",
    "collectAsMap"
  )

  // Datasets, DataFrames and RDDs of Spark: the types that hold the rows.
  private val holder = "org/apache/spark/.*(Dataset|RDD|DataFrame).*"

  private def pullingRefs(bytes: Array[Byte]): Vector[String] =
    pool(bytes).methods
      .filter((owner, _) => owner.matches(holder))
      .filter((_, name) => pulling.contains(name))
      .map((owner, name) => s"$owner.$name")

  private lazy val perClass: Vector[(String, Vector[String])] =
    classFiles.map(f =>
      f.getFileName.toString -> pullingRefs(Files.readAllBytes(f))
    )

  test("the scan sees the one guarded collect (positive control)") {
    val guard = perClass.filter(_._1 == "DriverGuard$.class")
    assertEquals(guard.size, 1)
    assert(
      guard.head._2.exists(_.endsWith(".collect")),
      s"DriverGuard calls no Spark collect: ${guard.head._2}"
    )
  }

  test("a scala collection collect or head is not flagged (negative control)") {
    // Canonical and Coauthorship use Vector.collect and Vector.head widely.
    val names = classFiles.map(_.getFileName.toString)
    assert(names.contains("CollaborationPipeline$.class"))
    val pipeline = perClass.filter(_._1 == "CollaborationPipeline$.class")
    assertEquals(pipeline.head._2, Vector.empty[String])
  }

  test("only DriverGuard pulls rows to the driver, and only with collect") {
    val offenders =
      perClass.filter(_._2.nonEmpty).filter(_._1 != "DriverGuard$.class")
    assertEquals(offenders, Vector.empty, offenders.mkString("\n"))
    val guard = perClass.filter(_._1 == "DriverGuard$.class").head._2.distinct
    assertEquals(guard.map(_.split('.').last).toSet, Set("collect"))
  }

  test("the guard also limits the frame before it collects") {
    val bytes = Files.readAllBytes(
      classesRoot.resolve("scholarchain/spark/DriverGuard$.class")
    )
    val methods = pool(bytes).methods.map(_._2)
    assert(methods.contains("limit"), methods.toString)
    assert(methods.contains("collect"), methods.toString)
  }

  test("main sources call Dataset collect only in the guard") {
    val pattern =
      "\\.(collect\\s*\\(\\s*\\)|collectAsList|toLocalIterator|takeAsList|takeOrdered|toPandas|show\\s*\\()".r
    val found = scalaSources.flatMap { file =>
      val code = withoutComments(Files.readString(file))
      pattern.findAllIn(code).map(m => s"${file.getFileName}: $m").toVector
    }
    assertEquals(found.size, 1, found.mkString("\n"))
    assert(found.head.startsWith("Support.scala"), found.head)
  }

  test("the guard rejects a result above its cap instead of truncating it") {
    val frame = Harness.session.range(0, 10).toDF()
    assertEquals(DriverGuard.rows(frame, 10, "ten rows").size, 10)
    assertEquals(DriverGuard.rows(frame, 11, "ten rows").size, 10)
    val error = intercept[DriverGuard.RowCapExceeded] {
      DriverGuard.rows(frame, 9, "ten rows")
    }
    assert(
      error.getMessage.contains("Refusing to collect ten rows"),
      error.getMessage
    )
    assert(error.getMessage.contains("over 9 rows"), error.getMessage)
    intercept[IllegalArgumentException](DriverGuard.rows(frame, 0, "none"))
    intercept[IllegalArgumentException](DriverGuard.rows(frame, -1, "none"))
  }

  test("a large table never reaches the driver in full") {
    // A million rows against a cap of 100: the guard asks Spark for 101 rows
    // and refuses, so the driver heap (2g) never holds the table.
    val big = Harness.session.range(0, 1000000).toDF()
    val error = intercept[DriverGuard.RowCapExceeded] {
      DriverGuard.rows(big, 100, "a million rows")
    }
    assert(error.getMessage.contains("over 100 rows"), error.getMessage)
    assertEquals(DriverGuard.count(big), 1000000L)
  }
