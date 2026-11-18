package scholarchain.evaluation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.graph.{GraphCheckReport, GraphChecks}

/** Hand-built exports, one corrupted invariant each. Rows carry only the fields
  * GraphChecks reads.
  */
class GraphChecksSuite extends FunSuite:
  private val run = "run:v1:fx"

  private def prov(runId: String = run, rec: String = "rec"): String =
    s""""provenance":{"runId":"$runId","sourceRecordId":"$rec"}"""

  private def researcher(id: String, runId: String = run): String =
    s"""{"nodeId":"$id","nodeKind":"researcher",${prov(runId)}}"""

  private def work(id: String): String =
    s"""{"canonicalWorkId":"$id","source":"dblp",${prov()}}"""

  private def authored(r: String, w: String): String =
    s"""{"researcherNodeId":"$r","canonicalWorkId":"$w","mentionId":"m-$r-$w","authorPosition":1,${prov()}}"""

  private def edge(id: String, a: String, b: String, joint: Int): String =
    s"""{"edgeId":"$id","researcherA":"$a","researcherB":"$b","window":"all","jointPublicationCount":$joint,${prov()}}"""

  private def support(id: String, w: String): String =
    s"""{"edgeId":"$id","canonicalWorkId":"$w","window":"all",${prov()}}"""

  private def hop(
      pair: String,
      idx: Int,
      from: String,
      to: String,
      id: String
  ): String =
    s"""{"pairKey":"$pair","status":"found","hopIndex":$idx,"fromId":"$from","toId":"$to","edgeId":"$id","jointPublicationCount":1,"window":"all",${prov()}}"""

  private val r1 = "researcher:v1:r1"
  private val r2 = "researcher:v1:r2"
  private val w1 = "work:v1:w1"
  private val e1 = "coauthor:v1:e"

  private def clean: Map[String, Vector[String]] = Map(
    "nodes-researcher" -> Vector(researcher(r1), researcher(r2)),
    "nodes-work" -> Vector(work(w1)),
    "edges-authored" -> Vector(authored(r1, w1), authored(r2, w1)),
    "edges-coauthored" -> Vector(edge(e1, r1, r2, 1)),
    "edge-support" -> Vector(support(e1, w1)),
    "path-evidence" -> Vector(hop("p", 1, r1, r2, e1))
  )

  private def writeExport(
      tables: Map[String, Vector[String]],
      manifest: Boolean = true
  ): Path =
    val dir = Files.createTempDirectory("gc-")
    tables.foreach { (name, rows) =>
      val d = Files.createDirectory(dir.resolve(name))
      Files.write(
        d.resolve("part-00000.jsonl"),
        rows.map(_ + "\n").mkString.getBytes(UTF_8)
      )
    }
    if manifest then
      Files.write(
        dir.resolve("export-manifest.json"),
        s"""{"sourceRunId":"$run","tables":{}}""".getBytes(UTF_8)
      )
    dir

  private def check(tables: Map[String, Vector[String]]): GraphCheckReport =
    GraphChecks.run(writeExport(tables), 1L, 10)

  test("a clean export has every counter at 0 and a complete trace"):
    val r = check(clean)
    assertEquals(r.danglingEdgeEndpoints, 0L)
    assertEquals(r.danglingWorkRefs, 0L)
    assertEquals(r.edgesWithoutSupport, 0L)
    assertEquals(r.jointCountMismatches, 0L)
    assertEquals(r.pathHopsNotInEdges, 0L)
    assertEquals(r.pathHopsChecked, 1L)
    assertEquals(r.provenanceRunIdMismatches, 0L)
    assertEquals(r.orphanResearchers, 0L)
    assertEquals(r.orphanWorks, 0L)
    assertEquals(r.trace.size, 1)
    assertEquals(r.traceIncomplete, 0L)
    assertEquals(r.trace.head.works.head.authored.size, 2)
    assertEquals(r.rowTotals("edge-support"), 1L)

  test("an edge with no support row is only edgesWithoutSupport"):
    val r = check(clean.updated("edge-support", Vector.empty))
    assertEquals(r.edgesWithoutSupport, 1L)
    assertEquals(r.jointCountMismatches, 0L)
    assert(r.trace.head.problems.contains("no-support-rows"))

  test("a joint count different from the support count is a mismatch"):
    val r =
      check(clean.updated("edges-coauthored", Vector(edge(e1, r1, r2, 3))))
    assertEquals(r.jointCountMismatches, 1L)
    assertEquals(r.edgesWithoutSupport, 0L)

  test("dangling endpoints and work references are counted"):
    val r = check(
      clean
        .updated(
          "edges-coauthored",
          Vector(edge(e1, r1, "researcher:v1:ghost", 1))
        )
        .updated(
          "edges-authored",
          clean("edges-authored") :+ authored(r1, "work:v1:ghost")
        )
    )
    assertEquals(r.danglingEdgeEndpoints, 1L)
    assertEquals(r.danglingWorkRefs, 1L)

  test("a hop is matched on its exact edge id, endpoints and window"):
    val missing = check(
      clean.updated(
        "path-evidence",
        Vector(hop("p", 1, r1, r2, "mention-coauthor:v1:e"))
      )
    )
    assertEquals(missing.pathHopsNotInEdges, 1L)
    assertEquals(missing.detail("path-hop:edge-missing"), 1L)
    val wrong = check(
      clean.updated(
        "path-evidence",
        Vector(hop("p", 1, r1, "researcher:v1:r9", e1))
      )
    )
    assertEquals(wrong.pathHopsNotInEdges, 1L)
    assertEquals(wrong.detail("path-hop:endpoints-differ"), 1L)

  test("the hop endpoints match in either order"):
    val r =
      check(clean.updated("path-evidence", Vector(hop("p", 1, r2, r1, e1))))
    assertEquals(r.pathHopsNotInEdges, 0L)

  test(
    "provenance runId is checked against the manifest, else the modal run id"
  ):
    val bad = clean.updated(
      "nodes-researcher",
      Vector(researcher(r1), researcher(r2, "run:v1:other"))
    )
    assertEquals(check(bad).provenanceRunIdMismatches, 1L)
    val noManifest = GraphChecks.run(writeExport(bad, manifest = false), 1L, 5)
    assertEquals(noManifest.provenanceRunIdMismatches, 1L)
    assertEquals(noManifest.expectedRunId, run)

  test("duplicates and orphans are counted per table"):
    val r = check(
      clean.updated(
        "nodes-work",
        Vector(work(w1), work(w1), work("work:v1:w2"))
      )
    )
    assertEquals(r.duplicateIds("nodes-work"), 1L)
    assertEquals(r.orphanWorks, 1L)
    assertEquals(r.duplicateIds("nodes-researcher"), 0L)

  test("a missing required table is named and the run still completes"):
    val r = check(clean.removed("path-evidence"))
    assert(r.missingTables.contains("path-evidence"))
    assertEquals(r.pathHopsChecked, 0L)

  test("a missing required table makes the run incomplete"):
    val r = check(clean.removed("path-evidence"))
    assert(r.incomplete)
    assertEquals(r.toJson("status").str, "INCOMPLETE")
    assertEquals(check(clean).toJson("status").str, "COMPLETE")
    assert(!check(clean).incomplete)

  test("the command line exits 3 with an INCOMPLETE line on a missing table"):
    val out = Files.createTempDirectory("gc-out-")
    val args = Vector(
      writeExport(clean.removed("edge-support")).toString,
      out.toString,
      "--run-id",
      "inc1",
      "--sample",
      "1"
    )
    val captured = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(captured)(GraphChecks.cli(args))
    assertEquals(code, GraphChecks.IncompleteExit)
    assertEquals(code, 3)
    val text = captured.toString("UTF-8")
    assert(text.contains("GRAPHCHECKS INCOMPLETE"), text)
    assert(text.contains("edge-support"), text)
    assert(!text.contains("GRAPHCHECKS RESULT"), text)
    // the evidence is still written and sealed
    assert(Files.isRegularFile(out.resolve("inc1").resolve("SHA256SUMS")))

  test("support rows to edges that do not exist are counted"):
    val orphan = support("coauthor:v1:ghost", w1)
    val r =
      check(clean.updated("edge-support", Vector(support(e1, w1), orphan)))
    assertEquals(r.supportRowsToMissingEdges, 1L)
    assertEquals(
      r.toJson("counters")("supportRowsToMissingEdges").num.toLong,
      1L
    )
    assertEquals(check(clean).supportRowsToMissingEdges, 0L)
    // the orphan row does not leak into the edge counters
    assertEquals(r.edgesWithoutSupport, 0L)
    assertEquals(r.jointCountMismatches, 0L)

  test("the dangling endpoint rate counts rows, not distinct edge ids"):
    // the same edge row twice, one endpoint missing in each: 2 dangling
    // endpoints over 2 edge rows x 2 endpoints + 2 authored rows = 6
    val ghost = edge(e1, r1, "researcher:v1:ghost", 1)
    val r = check(clean.updated("edges-coauthored", Vector(ghost, ghost)))
    assertEquals(r.danglingEdgeEndpoints, 2L)
    assertEquals(r.edgesTotal, 1L)
    val rate = r.toJson("rates")("danglingEndpointRate").num
    assertEqualsDouble(rate, 2.0 / 6.0, 1e-9)

  test("the sample is deterministic and bounded by the edge count"):
    val dir = writeExport(clean)
    assertEquals(GraphChecks.run(dir, 3L, 99), GraphChecks.run(dir, 3L, 99))
    assertEquals(GraphChecks.run(dir, 3L, 0).trace.size, 0)

  test("reconciliation reports the expected counts without judging them"):
    val r = GraphChecks.runWith(writeExport(clean), 1L, 1, Some(10L), Some(10L))
    assertEquals(r.reconciliation("expectedVertices"), 10L)
    assertEquals(r.reconciliation("vertexDifference"), 3L - 10L)

  test("the command line writes a sealed run directory once"):
    val out = Files.createTempDirectory("gc-out-")
    val args = Vector(
      writeExport(clean).toString,
      out.toString,
      "--run-id",
      "t1",
      "--sample",
      "2"
    )
    assertEquals(GraphChecks.cli(args), 0)
    val dir = out.resolve("t1")
    assert(Files.isRegularFile(dir.resolve("SHA256SUMS")))
    assert(Files.isRegularFile(dir.resolve("graph-check-report.json")))
    intercept[IllegalStateException](GraphChecks.cli(args))
