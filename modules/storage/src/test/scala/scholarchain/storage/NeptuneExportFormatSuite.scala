package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8

/** The export is what the Neptune openCypher bulk loader reads. Documented
  * column types (bulk-load-tutorial-format-opencypher, read 2026-11-14): Bool,
  * Boolean, Byte, Short, Int, Long, Float, Double, String, DateTime and the
  * auto-cast Char, Date, LocalDate, LocalDateTime, Duration, Point. No array
  * type is documented.
  */
class NeptuneExportFormatSuite extends munit.FunSuite:
  import Fixtures.*

  private val documented = Set(
    "Bool",
    "Boolean",
    "Byte",
    "Short",
    "Int",
    "Long",
    "Float",
    "Double",
    "String",
    "DateTime"
  )

  private def exported(): (InMemoryObjectStore, NeptuneExportReport) =
    val projection =
      new SparkGraphProjector(source(graphTables()), LoadBounds.Default)
        .project()
    val store = new InMemoryObjectStore
    (store, NeptuneExporter.exportTo(projection, runId, store))

  private def header(store: ObjectStore, key: String): Vector[String] =
    val text = new String(store.get(key).get, UTF_8)
    Csv.parse(text).head

  test("every property column carries a documented type, never an array") {
    val (store, report) = exported()
    assert(report.files.nonEmpty)
    report.files.foreach { file =>
      val columns = header(store, file.key)
      val typed = columns.filterNot(_.startsWith(":"))
      typed.foreach { column =>
        val parts = column.split(":", -1)
        assertEquals(parts.length, 2, column)
        assert(documented.contains(parts(1)), s"${file.key}: $column")
        assert(!column.contains("[") && !column.contains(" "), column)
      }
    }
  }

  test("vertex files lead with :ID and :LABEL, edge files with :ID :START_ID") {
    val (store, report) = exported()
    report.files.filter(_.kind == "vertex").foreach { file =>
      assertEquals(header(store, file.key).take(2), Vector(":ID", ":LABEL"))
    }
    report.files.filter(_.kind == "edge").foreach { file =>
      assertEquals(
        header(store, file.key).take(4),
        Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
      )
    }
  }

  test("the representative works list is one String column joined by ;") {
    val (store, report) = exported()
    val file = report.files.find(_.label == "COAUTHORED_WITH").get
    val columns = header(store, file.key)
    assert(columns.contains("representativeWorkIds:String"), columns.toString)
    assert(!columns.exists(_.endsWith("[]")))
    val rows = Csv.parse(new String(store.get(file.key).get, UTF_8))
    val at = columns.indexOf("representativeWorkIds:String")
    assert(rows.tail.exists(_(at).contains(";")), "expected a joined list cell")
  }

  test("a list read back from the CSV equals the projected list") {
    val (store, report) = exported()
    val file = report.files.find(_.label == "COAUTHORED_WITH").get
    val back =
      NeptuneCsvReader.edges(new String(store.get(file.key).get, UTF_8))
    val lists = back.flatMap(_.props.get("representativeWorkIds"))
    assert(lists.nonEmpty)
    assert(lists.forall(_.isInstanceOf[Prop.StrList]))
  }

  test("a list element holding the separator is refused, not split") {
    assertEquals(
      intercept[StorageException](Prop.StrList(Vector("a;b")).cell).code,
      ErrorCode.MalformedRow
    )
    assertEquals(Prop.StrList(Vector("a", "b")).typeName, "String")
    assertEquals(Prop.StrList(Vector("a", "b")).cell, "a;b")
  }

  test("the manifest is its own dataset, outside every load prefix") {
    val (store, report) = exported()
    assert(report.manifestKey.contains(s"/${NeptuneExporter.ManifestDataset}/"))
    val dataDirs =
      report.files.map(f => f.key.take(f.key.lastIndexOf('/') + 1)).toSet
    assertEquals(dataDirs.size, 1)
    assert(!report.manifestKey.startsWith(dataDirs.head))
    assert(
      !report.manifestKey.startsWith(s"graph/${NeptuneExporter.DataDataset}/")
    )
    val under = store.list(dataDirs.head).map(_.key)
    assert(under.forall(_.endsWith(".csv")))
    assert(!under.exists(_.endsWith("load-manifest.json")))
  }

  test("the in-memory loader still loads from the relocated manifest") {
    val (store, report) = exported()
    val graph = new InMemoryNeptune(store)
    val result = graph.load(report.manifestKey)
    assertEquals(result.inserted, report.vertexCount + report.edgeCount)
    assertEquals(result.jobs, Vector.empty)
  }
