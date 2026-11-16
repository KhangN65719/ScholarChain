package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicLong

/** The local rehearsal of the graph phase (work order 107) and the exporter
  * changes made for it. The data is the small hand-built export of
  * `ExportFixtures`; nothing here touches AWS.
  */
class GraphRehearsalSuite extends munit.FunSuite:
  import Fixtures.*

  private final case class Outcome(code: Int, lines: Vector[String]):
    def last: String = lines.last
    def withPrefix(prefix: String): Vector[String] =
      lines.filter(_.startsWith(prefix))

  private def rehearse(
      exportDir: Path,
      outDir: Path,
      project: TableSource => GraphProjection = GraphRehearsal.defaultProject,
      exporter: GraphRehearsal.Exporter = NeptuneExporter.exportTo
  ): Outcome =
    val lines = Vector.newBuilder[String]
    val code = GraphRehearsal.run(
      Array(exportDir.toString, outDir.toString),
      line => lines += line,
      project,
      exporter,
      sampleEveryMillis = 5L
    )
    Outcome(code, lines.result())

  private def outDir(): Path = Files.createTempDirectory("rehearsal-out-")

  private def exported(
      tables: Map[String, Vector[Row]] = graphTables()
  ): Path = ExportFixtures.write(ExportFixtures.newDir(), tables)

  private def phases(outcome: Outcome): Vector[String] =
    outcome
      .withPrefix("REHEARSAL_PHASE")
      .map(_.split(" ").toVector.slice(1, 3).mkString(" "))

  private val tablesWithoutSupport: Map[String, Vector[Row]] =
    val tables = graphTables()
    tables.updated(
      "edge-support",
      tables("edge-support").filterNot(_("edgeId").str == edgeId(m1, r1))
    )

  test("success: the markers, the phases in order and the files written") {
    val out = outDir()
    val outcome = rehearse(exported(), out)
    assertEquals(outcome.code, GraphRehearsal.ExitSuccess)
    assertEquals(outcome.last, "REHEARSAL_RESULT=SUCCESS")
    assertEquals(
      phases(outcome),
      Vector(
        "name=open state=start",
        "name=open state=done",
        "name=project state=start",
        "name=project state=done",
        "name=export state=start",
        "name=export state=done"
      )
    )
    val tables = outcome.withPrefix("REHEARSAL_INPUT table=")
    assertEquals(tables.size, graphTables().size)
    assert(
      tables.exists(
        _.startsWith(
          "REHEARSAL_INPUT table=edges-authored files=3 rows=5 bytes="
        )
      ),
      tables.mkString("\n")
    )
    assert(outcome.lines.exists(_.startsWith("REHEARSAL_INPUT total tables=9")))
    assert(
      outcome.lines.exists(
        _.matches("REHEARSAL_LIVE_HEAP_MB after=project value=[0-9]+")
      )
    )
    assert(
      outcome.lines.exists(
        _.matches("REHEARSAL_PEAK_HEAP_MB=[0-9]+ max_heap_mb=[0-9]+")
      )
    )
    // The result is the last line and appears once.
    assertEquals(outcome.withPrefix("REHEARSAL_RESULT=").size, 1)
    val expected =
      new SparkGraphProjector(source(), LoadBounds.Default).project()
    assert(
      outcome.lines.exists(
        _.startsWith(
          s"REHEARSAL_OUTPUT vertices=${expected.vertices.size} " +
            s"edges=${expected.edges.size} "
        )
      )
    )
    val reference = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(expected, runId, reference)
    val written = new LocalFsObjectStore(out)
    (report.files.map(_.key) :+ report.manifestKey).foreach { key =>
      assertEquals(
        written.get(key).map(_.toVector),
        reference.get(key).map(_.toVector),
        key
      )
    }
    assertEquals(
      outcome.withPrefix("REHEARSAL_OUTPUT_FILE").size,
      report.files.size + 1
    )
  }

  test(
    "an invariant failure is FAILED with its full message, nothing written"
  ) {
    val out = outDir()
    val direct = intercept[StorageException](
      new SparkGraphProjector(source(tablesWithoutSupport), LoadBounds.Default)
        .project()
    )
    val outcome = rehearse(exported(tablesWithoutSupport), out)
    assertEquals(outcome.code, GraphRehearsal.ExitFailed)
    assertEquals(
      outcome.last,
      s"REHEARSAL_RESULT=FAILED reason=${direct.getMessage}"
    )
    assert(direct.getMessage.startsWith("MissingEvidence: "))
    assertEquals(
      phases(outcome),
      Vector(
        "name=open state=start",
        "name=open state=done",
        "name=project state=start"
      )
    )
    assertEquals(Files.list(out).count, 0L)
    assert(outcome.lines.exists(_.startsWith("REHEARSAL_PEAK_HEAP_MB=")))
  }

  test("an export that does not open is FAILED, before any input line") {
    val dir = exported()
    Files.delete(dir.resolve("_SUCCESS"))
    val outcome = rehearse(dir, outDir())
    assertEquals(outcome.code, GraphRehearsal.ExitFailed)
    assert(
      outcome.last.startsWith(
        "REHEARSAL_RESULT=FAILED reason=MissingSuccessMarker: "
      ),
      outcome.last
    )
    assertEquals(phases(outcome), Vector("name=open state=start"))
    assertEquals(outcome.withPrefix("REHEARSAL_INPUT").size, 0)
  }

  test("an OutOfMemoryError in the project phase is FAILED reason=OOM") {
    val outcome = rehearse(
      exported(),
      outDir(),
      project = _ => throw new OutOfMemoryError("Java heap space")
    )
    assertEquals(outcome.code, GraphRehearsal.ExitOom)
    assert(
      outcome.last.matches(
        "REHEARSAL_RESULT=FAILED reason=OOM maxHeapMB=[0-9]+ phase=project"
      ),
      outcome.last
    )
    assert(outcome.lines.exists(_.startsWith("REHEARSAL_PEAK_HEAP_MB=")))
  }

  test("an OutOfMemoryError in the export phase names that phase") {
    val outcome = rehearse(
      exported(),
      outDir(),
      exporter = (_, _, _) => throw new OutOfMemoryError("Java heap space")
    )
    assertEquals(outcome.code, GraphRehearsal.ExitOom)
    assert(
      outcome.last.matches(
        "REHEARSAL_RESULT=FAILED reason=OOM maxHeapMB=[0-9]+ phase=export"
      ),
      outcome.last
    )
  }

  test("any other error is FAILED with its class and message") {
    val outcome = rehearse(
      exported(),
      outDir(),
      project = _ => throw new IllegalStateException("boom")
    )
    assertEquals(outcome.code, GraphRehearsal.ExitFailed)
    assertEquals(
      outcome.last,
      "REHEARSAL_RESULT=FAILED reason=IllegalStateException: boom"
    )
  }

  test("a wrong argument count is a usage error, an inside output is refused") {
    val lines = Vector.newBuilder[String]
    assertEquals(
      GraphRehearsal.run(Array("only-one"), line => lines += line),
      GraphRehearsal.ExitUsage
    )
    assertEquals(
      lines.result(),
      Vector(s"REHEARSAL_RESULT=FAILED reason=${GraphRehearsal.Usage}")
    )
    val dir = exported()
    val outcome = rehearse(dir, dir.resolve("out"))
    assertEquals(outcome.code, GraphRehearsal.ExitFailed)
    assert(outcome.last.contains("must not be inside the export directory"))
  }

  test("the heap sampler keeps the largest value it has seen") {
    val now = new AtomicLong(7L)
    val sampler = new GraphRehearsal.HeapSampler(() => now.get, 5L)
    try
      assertEquals(sampler.peakBytes, 7L)
      now.set(3L)
      assertEquals(sampler.peakBytes, 7L)
      now.set(11L)
      assertEquals(sampler.peakBytes, 11L)
    finally sampler.close()
  }

  // The first exporter wrote each file by joining every cell list into one
  // String and encoding it. This is that rendering, rebuilt from the public
  // pieces, as the reference for the bytes the exporter writes now.
  private def reference(projection: GraphProjection): Map[String, String] =
    def file(
        name: String,
        header: Vector[String],
        rows: Vector[(Vector[String], Map[String, Prop])]
    ): (String, String) =
      val props = rows.map(_._2)
      val names = props.flatMap(_.keys).distinct.sorted
      val typed = names.map { n =>
        s"$n:${props.flatMap(_.get(n)).map(_.typeName).distinct.head}"
      }
      val lines = rows.map { case (lead, p) =>
        lead ++ names.map(n => p.get(n).map(_.cell).getOrElse(""))
      }
      val all = (header ++ typed) +: lines
      name -> all.map(Csv.line).mkString("", "\n", "\n")
    val vertexFiles =
      projection.vertices.groupBy(_.label).toVector.map { case (label, group) =>
        file(
          s"vertices-$label.csv",
          Vector(":ID", ":LABEL"),
          group.map(v => (Vector(v.id, v.label), v.props))
        )
      }
    val edgeFiles =
      projection.edges.groupBy(_.label).toVector.map { case (label, group) =>
        file(
          s"edges-$label.csv",
          Vector(":ID", ":START_ID", ":END_ID", ":TYPE"),
          group.map(e => (Vector(e.id, e.from, e.to, e.label), e.props))
        )
      }
    (vertexFiles ++ edgeFiles).toMap

  test("the exporter writes the bytes of the reference rendering") {
    val tables = graphTables()
    val unicode = tables.updated(
      "nodes-work",
      tables("nodes-work").map(row =>
        if row("canonicalWorkId").str == w1 then
          row + ("title" -> ujson.Str("Étude, \"graphes\" – 数据 😀"))
        else row
      )
    )
    val projection =
      new SparkGraphProjector(source(unicode), LoadBounds.Default).project()
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(projection, runId, store)
    val expected = reference(projection)
    assertEquals(report.files.size, expected.size)
    report.files.foreach { file =>
      val name = file.key.split("/").last
      assertEquals(
        new String(store.get(file.key).get, UTF_8),
        expected(name),
        name
      )
      assertEquals(
        store.get(file.key).get.toVector,
        expected(name).getBytes(UTF_8).toVector
      )
    }
    assert(expected("edges-COAUTHORED_WITH.csv").contains(s"$w1;$w2"), "list")
    assert(expected("vertices-Work.csv").contains("数据 😀"), "non-ASCII")
  }

  private val bad = Vector(
    GraphVertex("a1", "A", Map("n" -> Prop.I32(1))),
    GraphVertex("b1", "B", Map("x" -> Prop.Str("s"))),
    GraphVertex("b2", "B", Map("x" -> Prop.I32(2)))
  )

  private def refused(
      vertices: Vector[GraphVertex]
  ): (String, InMemoryObjectStore) =
    val store = new InMemoryObjectStore
    val error = intercept[StorageException](
      NeptuneExporter.exportTo(
        GraphProjection(vertices, Vector.empty, 0),
        runId,
        store
      )
    )
    (error.getMessage, store)

  test("mixed types in one column are refused before any file is written") {
    val (message, store) = refused(bad)
    assertEquals(
      message,
      "MalformedRow: Property x has mixed types: String,Int"
    )
    assertEquals(store.list(""), Vector.empty)
  }

  test("a list outside the list columns, or holding ';', is refused first") {
    val (outside, store1) = refused(
      Vector(
        GraphVertex("a1", "A", Map("n" -> Prop.I32(1))),
        GraphVertex("b1", "B", Map("tags" -> Prop.StrList(Vector("x"))))
      )
    )
    assertEquals(
      outside,
      "MalformedRow: Property tags holds a list but is not a flattened list column"
    )
    assertEquals(store1.list(""), Vector.empty)
    val (separator, store2) = refused(
      Vector(
        GraphVertex("a1", "A", Map("n" -> Prop.I32(1))),
        GraphVertex(
          "b1",
          "B",
          Map("representativeWorkIds" -> Prop.StrList(Vector("w;1")))
        )
      )
    )
    assert(
      separator.startsWith("MalformedRow: A list element contains"),
      separator
    )
    assertEquals(store2.list(""), Vector.empty)
  }
