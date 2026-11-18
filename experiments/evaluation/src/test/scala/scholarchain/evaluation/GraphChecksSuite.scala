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
    // clean: 2 researcher rows + 1 work row, 2 resolved mentions, no snapshot
    assertEquals(r.reconciliation("vertexTablesRowSum"), 3L)
    assertEquals(r.reconciliation("composedVertices"), 5L)
    assertEquals(r.reconciliation("vertexDifference"), 5L - 10L)
    assertEquals(r.reconciliation("vertexTablesRowSumMinusExpected"), 3L - 10L)

  private def venue(id: String): String =
    s"""{"venueId":"$id",${prov()}}"""

  private def workWithSnapshot(id: String, snapshot: String): String =
    s"""{"canonicalWorkId":"$id","source":"dblp","provenance":{"runId":"$run","sourceRecordId":"rec","snapshotId":"$snapshot"}}"""

  private def published(w: String, v: String): String =
    s"""{"fromId":"$w","toId":"$v",${prov()}}"""

  private def authoredWith(r: String, w: String, mention: String): String =
    s"""{"researcherNodeId":"$r","canonicalWorkId":"$w","mentionId":"$mention","authorPosition":1,${prov()}}"""

  /** 2 researchers, 2 works of one snapshot, 1 venue; 3 authored rows (2 with a
    * resolved mention, 1 whose mention is the researcher node), 2 published-in
    * rows, 1 coauthor edge.
    */
  private def projected: Map[String, Vector[String]] = Map(
    "nodes-researcher" -> Vector(researcher(r1), researcher(r2)),
    "nodes-work" -> Vector(
      workWithSnapshot(w1, "snap:1"),
      workWithSnapshot("work:v1:w2", "snap:1")
    ),
    "nodes-venue" -> Vector(venue("venue:v1:a")),
    "edges-authored" -> Vector(
      authoredWith(r1, w1, "mention:v1:m1"),
      authoredWith(r2, w1, "mention:v1:m2"),
      authoredWith(r1, "work:v1:w2", r1)
    ),
    "edges-published-in" -> Vector(
      published(w1, "venue:v1:a"),
      published("work:v1:w2", "venue:v1:a")
    ),
    "edges-coauthored" -> Vector(edge(e1, r1, r2, 1)),
    "edge-support" -> Vector(support(e1, w1)),
    "path-evidence" -> Vector(hop("p", 1, r1, r2, e1))
  )

  test("the composed counts add the projector's derived edges and vertices"):
    val r = GraphChecks.runWith(
      writeExport(projected),
      1L,
      1,
      Some(8L),
      Some(10L),
      Some("fixture")
    )
    val rec = r.reconciliation
    // edges: 3 + 2 + 1 table rows; RESOLVES_TO = the 2 rows whose mention is
    // not the researcher node; DERIVED_FROM = the 2 nodes-work rows
    assertEquals(rec("edgeTablesRowSum"), 6L)
    assertEquals(rec("resolvesToEdges"), 2L)
    assertEquals(rec("derivedFromEdges"), 2L)
    assertEquals(rec("composedEdges"), 10L)
    // vertices: 2 + 2 + 1 table rows, 2 resolved mentions, 1 snapshot
    assertEquals(rec("vertexTablesRowSum"), 5L)
    assertEquals(rec("resolvedMentionVertices"), 2L)
    assertEquals(rec("snapshotVertices"), 1L)
    assertEquals(rec("composedVertices"), 8L)
    // against figures that match: a zero difference, and the bare row sums
    // still differ, which is why the old comparison was never zero
    assertEquals(rec("edgeDifference"), 0L)
    assertEquals(rec("vertexDifference"), 0L)
    assertEquals(rec("edgeTablesRowSumMinusExpected"), 6L - 10L)
    assertEquals(rec("vertexTablesRowSumMinusExpected"), 5L - 8L)
    // edgesTotal counts the coauthor edges only
    assertEquals(r.edgesTotal, 1L)
    val json = r.toJson
    assertEquals(json("counters")("coauthorEdgesTotal").num.toLong, 1L)
    assertEquals(json("counters")("edgesTotal").num.toLong, 1L)
    val basis = json("reconciliationBasis").str
    assert(basis.contains("not the count of a Neptune load"), basis)
    assert(basis.contains("source: fixture"), basis)
    assert(basis.contains("coauthor edges only"), basis)
    assert(!basis.contains("Neptune load:"), basis)

  test("a wrong expected figure shows as a nonzero difference"):
    val r = GraphChecks.runWith(
      writeExport(projected),
      1L,
      1,
      Some(9L),
      Some(10L),
      Some("fixture")
    )
    assertEquals(r.reconciliation("vertexDifference"), -1L)
    assertEquals(r.reconciliation("edgeDifference"), 0L)

  test("no expected figure means no difference is reported"):
    val r = GraphChecks.run(writeExport(projected), 1L, 1)
    assert(!r.reconciliation.contains("edgeDifference"))
    assert(!r.reconciliation.contains("expectedEdges"))
    assertEquals(r.reconciliation("composedEdges"), 10L)
    assert(r.reconciliationBasis.contains("none given"))

  test("an expected figure needs its source and has no default"):
    val dir = writeExport(projected).toString
    val noSource = GraphChecks.parseArgs(
      Vector(dir, "/out", "--expect-edges", "10")
    )
    assert(noSource.left.exists(_.contains("--expect-source")), noSource)
    val ok = GraphChecks
      .parseArgs(
        Vector(dir, "/out", "--expect-edges", "10", "--expect-source", "log:20")
      )
      .toOption
      .get
    assertEquals(ok.expectedEdges, Some(10L))
    assertEquals(ok.expectedVertices, None)
    assertEquals(ok.expectedSource, Some("log:20"))
    val none = GraphChecks.parseArgs(Vector(dir, "/out")).toOption.get
    assertEquals(none.expectedEdges, None)
    assertEquals(none.expectedVertices, None)

  test("an unknown option is refused by name and nothing is written"):
    val dir = writeExport(projected).toString
    val parsed = GraphChecks.parseArgs(
      Vector(dir, "/out", "--expect-edge", "10", "--expect-source", "x")
    )
    assert(parsed.left.exists(_.contains("--expect-edge")), parsed)
    assert(!parsed.left.exists(_.contains("--expect-source")), parsed)
    val out = Files.createTempDirectory("gc-out-")
    val captured = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(captured)(
      GraphChecks.cli(
        Vector(
          dir,
          out.toString,
          "--run-id",
          "typo",
          "--expect-edge",
          "10",
          "--expect-source",
          "x"
        )
      )
    )
    assertEquals(code, 2)
    val text = captured.toString("UTF-8")
    assert(text.contains("Unknown option: --expect-edge"), text)
    assert(!text.contains("GRAPHCHECKS RESULT"), text)
    assert(!Files.exists(out.resolve("typo")))
    // every documented option is still accepted
    val ok = GraphChecks.parseArgs(
      Vector(
        dir,
        "/out",
        "--seed",
        "1",
        "--sample",
        "1",
        "--run-id",
        "r",
        "--expect-vertices",
        "1",
        "--expect-edges",
        "1",
        "--expect-source",
        "s"
      )
    )
    assert(ok.isRight, ok)

  test("the command line prints the reconciliation and records its source"):
    val out = Files.createTempDirectory("gc-out-")
    val args = Vector(
      writeExport(projected).toString,
      out.toString,
      "--run-id",
      "rec1",
      "--sample",
      "1",
      "--expect-edges",
      "10",
      "--expect-vertices",
      "8",
      "--expect-source",
      "rehearsal-log:20"
    )
    val captured = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(captured)(GraphChecks.cli(args))
    assertEquals(code, 0)
    val text = captured.toString("UTF-8")
    assert(text.contains("GRAPHCHECKS RECONCILIATION"), text)
    assert(text.contains("edgeDifference=0"), text)
    assert(text.contains("vertexDifference=0"), text)
    val info = ujson.read(Files.readString(out.resolve("rec1/run-info.json")))
    assertEquals(info("expectedSource").str, "rehearsal-log:20")
    assertEquals(info("expectedEdges").num.toLong, 10L)

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
