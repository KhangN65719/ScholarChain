package scholarchain.tests.analytics

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.{Failure, Success, Try}
import scholarchain.spark.{ExportTables, JsonlExport}
import scholarchain.storage.{
  Csv,
  GraphProjection,
  InMemoryNeptune,
  InMemoryObjectStore,
  JsonlExportSource,
  LoadBounds,
  NeptuneCsvReader,
  NeptuneExportReport,
  NeptuneExporter,
  SparkGraphProjector,
  StorageException
}

// Work order 35, item 1: the real fixture pipeline (Runs.a) through
// JsonlExport, JsonlExportSource.open, SparkGraphProjector and
// NeptuneExporter. modules/spark cannot depend on storage, so this is the only
// place the whole chain runs on real pipeline output.
class SparkRoundTripSuite extends SparkBase:
  private val documentedTypes =
    Set(
      "Bool",
      "Byte",
      "Short",
      "Int",
      "Long",
      "Float",
      "Double",
      "String",
      "Date",
      "DateTime"
    )

  private def info(message: String): Unit = println(s"[roundtrip] $message")

  private lazy val exportDir: Path = Harness.root.resolve("a-roundtrip-export")

  private lazy val exportRun: (Int, String) =
    val run = Runs.a
    assertEquals(run.code, 0, run.text)
    val buffer = new ByteArrayOutputStream()
    val stream = new PrintStream(buffer, true, "UTF-8")
    val code = JsonlExport.run(
      Vector(run.runDir.toString, exportDir.toString),
      stream,
      Some(Harness.session)
    )
    stream.flush()
    (code, buffer.toString("UTF-8"))

  private def exported(): Path =
    val (code, text) = exportRun
    assertEquals(code, 0, text)
    exportDir

  private lazy val source: Try[JsonlExportSource] =
    Try(JsonlExportSource.open(exported(), LoadBounds.Default))

  private def projector(): SparkGraphProjector =
    new SparkGraphProjector(opened(), LoadBounds.Default)

  private def opened(): JsonlExportSource = source match
    case Success(s) => s
    case Failure(e) => fail(s"JsonlExportSource.open failed: ${describe(e)}")

  private def describe(e: Throwable): String = e match
    case s: StorageException => s"${s.code}: ${s.getMessage}"
    case other => s"${other.getClass.getName}: ${other.getMessage}"

  private lazy val projection: Try[GraphProjection] =
    Try(projector().project())

  private def projected(): GraphProjection = projection match
    case Success(p) => p
    case Failure(e) =>
      fail(
        s"SparkGraphProjector.project failed on the real export: ${describe(e)}"
      )

  private lazy val exportedGraph
      : Try[(InMemoryObjectStore, NeptuneExportReport)] =
    Try {
      val store = new InMemoryObjectStore
      val runId = opened().sourceRun.runId
      (store, NeptuneExporter.exportTo(projected(), runId, store))
    }

  private def graphExport(): (InMemoryObjectStore, NeptuneExportReport) =
    exportedGraph match
      case Success(r) => r
      case Failure(e) =>
        fail(s"NeptuneExporter.exportTo failed: ${describe(e)}")

  private val expectedExportTables: Vector[String] = Vector(
    "semantic-summaries",
    "nodes-work",
    "researcher-metrics",
    "nodes-researcher",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support",
    "path-evidence",
    "components",
    "component-membership",
    "communities",
    "community-membership",
    "community-cross-edges"
  ).sorted

  test(
    "JsonlExport writes the seventeen tables the storage projector and analytics readers use"
  ) {
    val dir = exported()
    val manifest = ujson.read(os(dir.resolve("export-manifest.json")))
    val names = manifest("tables").obj.keys.toVector.sorted
    assertEquals(names, ExportTables.all.map(_.name).sorted)
    assertEquals(names, expectedExportTables)
    assertEquals(names.size, 17)
    assert(java.nio.file.Files.isRegularFile(dir.resolve("_SUCCESS")))
    info("export tables: " + names.mkString(", "))
    info(
      "row counts: " + manifest("tables").obj
        .map((n, t) => s"$n=${t("rows").num.toLong}")
        .mkString(", ")
    )
  }

  test("the export opens through JsonlExportSource and holds every table") {
    val s = opened()
    assertEquals(s.tableNames, ExportTables.all.map(_.name).toSet)
    assertEquals(s.sourceRun.runId, Runs.a.runId)
  }

  test("the real export projects with no LegacyEdgeId or MissingEvidence") {
    projection match
      case Failure(e: StorageException) =>
        val code = e.code.toString
        assert(
          code != "LegacyEdgeId" && code != "MissingEvidence",
          s"projector raised $code: ${e.getMessage}"
        )
        fail(s"projection failed with ${describe(e)}")
      case Failure(e) => fail(s"projection failed with ${describe(e)}")
      case Success(p) =>
        assert(p.vertices.nonEmpty, "no vertices")
        assert(p.edges.nonEmpty, "no relationships")
        info(s"vertices=${p.vertices.size} edges=${p.edges.size}")
  }

  test("the projection carries the expected labels and relationship types") {
    val p = projected()
    val labels = p.vertices.map(_.label).toSet
    val types = p.edges.map(_.label).toSet
    info("labels: " + labels.toVector.sorted.mkString(", "))
    info("types: " + types.toVector.sorted.mkString(", "))
    Vector("Researcher", "Work", "Venue").foreach { l =>
      assert(labels.contains(l), s"label $l absent from $labels")
    }
    // Topic and Institution have a label only when the export holds rows.
    val manifest = ujson.read(os(exported().resolve("export-manifest.json")))
    Vector("nodes-topic" -> "Topic", "nodes-institution" -> "Institution")
      .foreach { (table, label) =>
        val rows = manifest("tables")(table)("rows").num.toLong
        info(s"$table rows=$rows label present=${labels.contains(label)}")
        if rows > 0 then assert(labels.contains(label), s"$label absent")
        else assert(!labels.contains(label), s"$label without rows")
      }
    Vector(
      "AUTHORED",
      "COAUTHORED_WITH",
      "PUBLISHED_IN",
      "RESOLVES_TO",
      "DERIVED_FROM"
    ).foreach { t =>
      assert(types.contains(t), s"relationship $t absent from $types")
    }
    assert(
      labels.subsetOf(
        Set(
          "Researcher",
          "AuthorMention",
          "Work",
          "Venue",
          "Topic",
          "Institution",
          "DatasetSnapshot"
        )
      ),
      labels.toString
    )
  }

  test("the projection is deterministic and every edge endpoint is a vertex") {
    val p = projected()
    assertEquals(projector().project(), p)
    val ids = p.vertices.map(_.id)
    assertEquals(ids.distinct.size, ids.size)
    val known = ids.toSet
    p.edges.foreach { e =>
      assert(known.contains(e.from), s"edge ${e.id} start ${e.from}")
      assert(known.contains(e.to), s"edge ${e.id} end ${e.to}")
    }
    assertEquals(p.edges.map(_.id).distinct.size, p.edges.size)
  }

  test("exportTo writes node and relationship files with documented headers") {
    val (store, report) = graphExport()
    assert(report.files.nonEmpty)
    assertEquals(report.vertexCount, projected().vertices.size)
    assertEquals(report.edgeCount, projected().edges.size)
    report.files.foreach { f =>
      val content = new String(store.get(f.key).get, UTF_8)
      val table = Csv.parse(content)
      val header = table.head
      assertEquals(table.size - 1, f.rows, f.key)
      if f.kind == "vertex" then
        assertEquals(header.take(2), Vector(":ID", ":LABEL"), f.key)
      else
        assertEquals(
          header.take(4),
          Vector(":ID", ":START_ID", ":END_ID", ":TYPE"),
          f.key
        )
      val skip = if f.kind == "vertex" then 2 else 4
      header.drop(skip).foreach { column =>
        assert(!column.startsWith(":"), s"${f.key}: system column $column")
        val parts = column.split(":", -1)
        assertEquals(parts.length, 2, s"${f.key}: header cell '$column'")
        assert(
          documentedTypes.contains(parts(1)),
          s"${f.key}: type '${parts(1)}' is not documented"
        )
        assert(!column.contains("[]"), s"${f.key}: array type in $column")
      }
      table.tail.foreach { row =>
        assertEquals(row.size, header.size, s"${f.key}: ragged row")
      }
    }
    val kinds = report.files.map(_.kind).toSet
    assertEquals(kinds, Set("vertex", "edge"))
  }

  test("the flattened list column is a String joined by a semicolon") {
    val (store, report) = graphExport()
    val file = report.files.find(_.label == "COAUTHORED_WITH").get
    val table = Csv.parse(new String(store.get(file.key).get, UTF_8))
    val index = table.head.indexOf("representativeWorkIds:String")
    assert(index >= 0, table.head.toString)
    val works = table.tail.map(_(index))
    assert(works.forall(_.nonEmpty), "empty representativeWorkIds cell")
    works.foreach { cell =>
      cell.split(NeptuneExporter.ListSeparator).foreach { id =>
        assert(id.startsWith("work:"), s"element '$id' of '$cell'")
      }
    }
  }

  test("the manifest is outside every load prefix and the prefix holds CSV") {
    val (store, report) = graphExport()
    val loadPrefix =
      report.files.map(_.key.split("/run=").head).distinct
    assertEquals(loadPrefix.size, 1, loadPrefix.toString)
    val runPrefix =
      report.files.head.key
        .substring(0, report.files.head.key.lastIndexOf('/') + 1)
    assert(report.files.forall(_.key.startsWith(runPrefix)))
    assert(!report.manifestKey.startsWith(runPrefix), report.manifestKey)
    assert(!report.manifestKey.startsWith("graph/neptune/"), report.manifestKey)
    assert(report.manifestKey.startsWith("graph/neptune-manifest/"))
    val underLoad = store.list("graph/neptune/").map(_.key)
    assertEquals(underLoad.toSet, report.files.map(_.key).toSet)
    assert(underLoad.forall(_.endsWith(".csv")), underLoad.toString)
    val manifest = ujson.read(store.get(report.manifestKey).get)
    assertEquals(manifest("format").str, "opencypher")
    assertEquals(manifest("files").arr.size, report.files.size)
  }

  test(
    "InMemoryNeptune loads the exported files and a second load is a no-op"
  ) {
    val (store, report) = graphExport()
    val neptune = new InMemoryNeptune(store)
    val first = neptune.load(report.manifestKey)
    assertEquals(first.inserted, report.vertexCount + report.edgeCount)
    assertEquals(first.updated, 0)
    val second = neptune.load(report.manifestKey)
    assertEquals(second.inserted, 0)
    assertEquals(second.unchanged, report.vertexCount + report.edgeCount)
    assertEquals(
      neptune.vertices.map(_.id),
      projected().vertices.map(_.id).sorted
    )
    assertEquals(
      neptune.edges.map(_.id),
      projected().edges.map(_.id).sorted
    )
  }

  test("exporting the same projection again creates nothing") {
    val (store, report) = graphExport()
    val again =
      NeptuneExporter.exportTo(projected(), report.runId, store)
    assertEquals(again.created, 0)
    assertEquals(again.files, report.files)
    assertEquals(again.manifestKey, report.manifestKey)
  }

  test("the CSV reader restores every row count it wrote") {
    val (store, report) = graphExport()
    report.files.foreach { f =>
      val content = new String(store.get(f.key).get, UTF_8)
      val n =
        if f.kind == "vertex" then NeptuneCsvReader.vertices(content).size
        else NeptuneCsvReader.edges(content).size
      assertEquals(n, f.rows, f.key)
    }
  }

  private def os(path: Path): String =
    java.nio.file.Files.readString(path, UTF_8)
