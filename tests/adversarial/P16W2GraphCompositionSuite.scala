package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.graph.GraphChecks
import scholarchain.storage.{
  GraphProjection,
  Hashing,
  JsonlExportSource,
  SparkGraphProjector,
  LoadBounds,
  Row
}

/** GraphChecks' composedEdges and composedVertices against what the real
  * projector builds from the same export directory. The expectation is not
  * derived from GraphChecks' own rule: the projector is the oracle.
  *
  * The class name carries no "Spark" on purpose: `SparkGraphProjector` reads a
  * `TableSource` and starts no Spark session, so the suite runs in the root
  * in-process group.
  */
class P16W2GraphCompositionSuite extends FunSuite:
  import Phase7Storage.*

  private def writeExport(w: World): Path =
    val dir = Files.createTempDirectory("p16w2-comp-")
    Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    val tables = (w.tables
      .updated("path-evidence", Vector.empty[Row]))
      .toVector
      .sortBy(_._1)
      .map { (name, rows) =>
        val bytes =
          rows
            .map(r => ujson.write(ujson.Obj.from(r)) + "\n")
            .mkString
            .getBytes(UTF_8)
        val d = Files.createDirectories(dir.resolve(name))
        Files.write(d.resolve("part-00000.jsonl"), bytes)
        name -> ujson.Obj(
          "rows" -> rows.size.toDouble,
          "files" -> ujson.Arr(
            ujson.Obj(
              "path" -> s"$name/part-00000.jsonl",
              "rows" -> rows.size.toDouble,
              "bytes" -> bytes.length.toDouble,
              "sha256" -> Hashing.sha256(bytes)
            )
          )
        )
      }
    Files.write(
      dir.resolve("export-manifest.json"),
      ujson
        .write(
          ujson.Obj(
            "manifestType" -> JsonlExportSource.ManifestType,
            "sourceSuccessSeen" -> true,
            "sourceRunId" -> runId,
            "transformVersion" -> "scholarchain.spark/1",
            "exportVersion" -> "1",
            "tables" -> ujson.Obj.from(tables)
          )
        )
        .getBytes(UTF_8)
    )
    dir

  private def projected(dir: Path): GraphProjection =
    new SparkGraphProjector(
      JsonlExportSource.open(dir),
      LoadBounds.Default
    ).project()

  test("composedEdges and composedVertices equal the projector's counts"):
    val dir = writeExport(world())
    val projection = projected(dir)
    val r = GraphChecks.run(dir, 1L, 5).reconciliation
    assertEquals(r("composedEdges"), projection.edges.size.toLong)
    assertEquals(r("composedVertices"), projection.vertices.size.toLong)
    // Literal expectation from the fixture, so a change of both sides together
    // still fails: edges 7 AUTHORED + 1 PUBLISHED_IN + 3 COAUTHORED_WITH + 5
    // RESOLVES_TO + 3 DERIVED_FROM; vertices 4 + 3 + 1 + 5 mentions + 1 snapshot.
    assertEquals(projection.edges.size, 19)
    assertEquals(projection.vertices.size, 14)

  test("the two derived edge types match the projector's labels"):
    val dir = writeExport(world())
    val projection = projected(dir)
    val r = GraphChecks.run(dir, 1L, 5).reconciliation
    def edgesOf(label: String): Long =
      projection.edges.count(_.label == label).toLong
    assertEquals(r("resolvesToEdges"), edgesOf("RESOLVES_TO"))
    assertEquals(r("derivedFromEdges"), edgesOf("DERIVED_FROM"))
    assert(edgesOf("RESOLVES_TO") > 0 && edgesOf("DERIVED_FROM") > 0)
    assertEquals(
      r("snapshotVertices"),
      projection.vertices.count(_.label == "DatasetSnapshot").toLong
    )

  test("a second snapshot adds one vertex in both"):
    val w = world()
    val other = provenance(snapshot = "snap-2")
    val works = w.rows("nodes-work")
    val moved = works.head.updated("provenance", provJson(other))
    val dir = writeExport(w.replace("nodes-work", moved +: works.tail))
    val projection = projected(dir)
    val r = GraphChecks.run(dir, 1L, 5).reconciliation
    assertEquals(r("snapshotVertices"), 2L)
    assertEquals(r("composedVertices"), projection.vertices.size.toLong)
    assertEquals(r("composedEdges"), projection.edges.size.toLong)

  test("an export with no resolved mention composes without RESOLVES_TO"):
    val w = world()
    val unresolved = w
      .rows("edges-authored")
      .map(row => row.updated("mentionId", row("researcherNodeId")))
    val dir = writeExport(w.replace("edges-authored", unresolved))
    val projection = projected(dir)
    val r = GraphChecks.run(dir, 1L, 5).reconciliation
    assertEquals(r("resolvesToEdges"), 0L)
    assertEquals(projection.edges.count(_.label == "RESOLVES_TO"), 0)
    assertEquals(r("composedEdges"), projection.edges.size.toLong)
    assertEquals(r("composedVertices"), projection.vertices.size.toLong)
