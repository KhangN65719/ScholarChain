package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.graph.{GraphCheckReport, GraphChecks}

/** Adversarial fixtures for `GraphChecks`: tiny hand-built export directories,
  * one corrupted invariant each, in the layout of `JsonlExport` (one directory
  * per table holding `part-NNNNN.jsonl`, plus `export-manifest.json`).
  *
  * The clean world: researchers R1 R2 R3, works W1 W2, authored R1-W1, R2-W1,
  * R2-W2, R3-W2, coauthor edges E12 (support W1) and E23 (support W2), and one
  * path R1 -> R2 -> R3 whose hops are E12 and E23, plus an unreachable status
  * pair whose hop columns are null. Every row carries runId `run:v1:fixture`.
  *
  * Counter semantics are taken from plan.md group 2. Where the plan leaves a
  * count ambiguous (one corruption could be counted per row or per endpoint),
  * the corruption is built so both readings give 1.
  */
class P16GraphChecksSuite extends FunSuite:
  private val run = "run:v1:fixture"
  private val rid = (n: Int) => s"researcher:v1:orcid:R$n"
  private val wid = (n: Int) => s"work:v1:dblp:W$n"
  private val e12 = "coauthor:v1:e12"
  private val e23 = "coauthor:v1:e23"

  private def prov(table: String, runId: String = run): String =
    s"""{"schemaVersion":1,"source":"derived","snapshotId":"s","""
      + s""""sourceRecordId":"x","sourceUri":"scholarchain:spark-analytics/$table","""
      + s""""transformVersion":"spark-analytics/1.1.0","runId":"$runId"}"""

  private def researcher(n: Int, runId: String = run): String =
    s"""{"nodeId":"${rid(n)}","nodeKind":"researcher","idRule":"orcid","""
      + s""""orcid":"R$n","linkStatus":"linked","memberCount":1,"""
      + s""""displayName":"R$n","mentionCount":1,"""
      + s""""provenance":${prov("nodes-researcher", runId)}}"""

  private def work(n: Int, runId: String = run): String =
    s"""{"canonicalWorkId":"${wid(n)}","representativeWorkId":"${wid(n)}","""
      + s""""memberCount":1,"memberWorkIds":["${wid(n)}"],"title":"T$n","""
      + s""""year":2024,"venueId":null,"doi":null,"source":"dblp","""
      + s""""sourceRecordId":"W$n","provenance":${prov("nodes-work", runId)}}"""

  private def authored(r: Int, w: Int): String =
    s"""{"researcherNodeId":"${rid(r)}","nodeKind":"researcher","""
      + s""""canonicalWorkId":"${wid(w)}","workId":"${wid(w)}","""
      + s""""mentionId":"mention:v1:m$r$w","authorPosition":1,"year":2024,"""
      + s""""countedInMetrics":true,"provenance":${prov("edges-authored")}}"""

  private def coauthored(
      id: String,
      a: String,
      b: String,
      joint: Int,
      work: String
  ): String =
    s"""{"edgeId":"$id","windowStart":2020,"windowEnd":2029,"""
      + s""""researcherA":"$a","researcherB":"$b","""
      + s""""policyVersion":"scholarchain.spark.coauthor-policy/1","""
      + s""""jointPublicationCount":$joint,"firstCollaborationYear":2024,"""
      + s""""lastCollaborationYear":2024,"representativeWorkIds":["$work"],"""
      + s""""supportTable":"edge-support","endpointAUnresolved":false,"""
      + s""""endpointBUnresolved":false,"""
      + s""""provenance":${prov("edges-coauthored")},"window":"2020-2029"}"""

  private def support(id: String, a: String, b: String, work: String): String =
    s"""{"edgeId":"$id","windowStart":2020,"windowEnd":2029,"""
      + s""""researcherA":"$a","researcherB":"$b","canonicalWorkId":"$work","""
      + s""""year":2024,"provenance":${prov("edge-support")},"""
      + s""""window":"2020-2029"}"""

  private def hop(
      pair: String,
      idx: Int,
      from: String,
      to: String,
      edge: String
  ): String =
    s"""{"pairKey":"$pair","sourceId":"${rid(1)}","targetId":"${rid(3)}","""
      + s""""selection":"auto","status":"found","unreachableReason":null,"""
      + s""""hopLimit":6,"pathLength":2,"hopIndex":$idx,"fromId":"$from","""
      + s""""toId":"$to","edgeId":"$edge","jointPublicationCount":1,"""
      + s""""firstCollaborationYear":2024,"lastCollaborationYear":2024,"""
      + s""""supportingWorkIds":["${wid(1)}"],"windowStart":2020,"""
      + s""""windowEnd":2029,"provenance":${prov("path-evidence")},"""
      + s""""window":"2020-2029"}"""

  private val statusRow: String =
    s"""{"pairKey":"2020-2029|${rid(3)}|${rid(1)}","sourceId":"${rid(3)}","""
      + s""""targetId":"${rid(1)}","selection":"auto","status":"unreachable","""
      + s""""unreachableReason":"no-path","hopLimit":6,"pathLength":null,"""
      + s""""hopIndex":null,"fromId":null,"toId":null,"edgeId":null,"""
      + s""""jointPublicationCount":null,"firstCollaborationYear":null,"""
      + s""""lastCollaborationYear":null,"supportingWorkIds":null,"""
      + s""""windowStart":2020,"windowEnd":2029,"""
      + s""""provenance":${prov("path-evidence")},"window":"2020-2029"}"""

  /** The rows of every table of the clean world. */
  private def clean: Map[String, Vector[String]] = Map(
    "nodes-researcher" -> Vector(researcher(1), researcher(2), researcher(3)),
    "nodes-work" -> Vector(work(1), work(2)),
    "edges-authored" -> Vector(
      authored(1, 1),
      authored(2, 1),
      authored(2, 2),
      authored(3, 2)
    ),
    "edges-coauthored" -> Vector(
      coauthored(e12, rid(1), rid(2), 1, wid(1)),
      coauthored(e23, rid(2), rid(3), 1, wid(2))
    ),
    "edge-support" -> Vector(
      support(e12, rid(1), rid(2), wid(1)),
      support(e23, rid(2), rid(3), wid(2))
    ),
    "path-evidence" -> Vector(
      hop("p1", 1, rid(1), rid(2), e12),
      hop("p1", 2, rid(2), rid(3), e23),
      statusRow
    )
  )

  private val emptyTables = Vector(
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-published-in",
    "semantic-summaries",
    "researcher-metrics",
    "components",
    "component-membership",
    "communities",
    "community-membership",
    "community-cross-edges"
  )

  private def writeExport(tables: Map[String, Vector[String]]): Path =
    val dir = Files.createTempDirectory("p16-graphchecks-")
    (tables.keys.toVector ++ emptyTables.filterNot(tables.contains)).foreach:
      name =>
        val tableDir = Files.createDirectory(dir.resolve(name))
        val rows = tables.getOrElse(name, Vector.empty)
        Files.write(
          tableDir.resolve("part-00000.jsonl"),
          rows.map(_ + "\n").mkString.getBytes(UTF_8)
        )
    val manifest =
      s"""{"manifestType":"scholarchain.spark.jsonl-export-manifest/1","""
        + s""""exportVersion":"spark-jsonl-export/1.0.0","sourceRunId":"$run","""
        + s""""sourceSuccessSeen":true,"tables":{}}"""
    Files.write(dir.resolve("export-manifest.json"), manifest.getBytes(UTF_8))
    dir

  private def check(tables: Map[String, Vector[String]]): GraphCheckReport =
    GraphChecks.run(writeExport(tables), 16L, 50)

  private def counters(r: GraphCheckReport): Map[String, Long] = Map(
    "danglingEdgeEndpoints" -> r.danglingEdgeEndpoints,
    "edgesWithoutSupport" -> r.edgesWithoutSupport,
    "jointCountMismatches" -> r.jointCountMismatches,
    "pathHopsNotInEdges" -> r.pathHopsNotInEdges,
    "provenanceRunIdMismatches" -> r.provenanceRunIdMismatches,
    "orphanResearchers" -> r.orphanResearchers,
    "orphanWorks" -> r.orphanWorks,
    "duplicateIds" -> r.duplicateIds.values.sum
  )

  test("a clean export gives every counter 0, status rows included"):
    val r = check(clean)
    assertEquals(counters(r).values.toSet, Set(0L), s"${counters(r)}")

  test("the clean fixture holds no duplicate in any table"):
    assert(check(clean).duplicateIds.values.forall(_ == 0L))

  test("a dangling edge endpoint is counted"):
    // E23 now names researcher R9, who is in no nodes table
    val tables = clean.updated(
      "edges-coauthored",
      Vector(
        coauthored(e12, rid(1), rid(2), 1, wid(1)),
        coauthored(e23, rid(2), rid(9), 1, wid(2))
      )
    )
    val r = check(tables)
    assertEquals(r.danglingEdgeEndpoints, 1L, s"${counters(r)}")

  test("both endpoints of an edge missing count as two"):
    val tables = clean.updated(
      "edges-coauthored",
      Vector(
        coauthored(e12, rid(1), rid(2), 1, wid(1)),
        coauthored(e23, rid(8), rid(9), 1, wid(2))
      )
    )
    assertEquals(check(tables).danglingEdgeEndpoints, 2L)

  test("a duplicate researcher id is reported under duplicateIds"):
    val tables = clean.updated(
      "nodes-researcher",
      clean("nodes-researcher") :+ researcher(2)
    )
    val r = check(tables)
    assert(r.duplicateIds.values.sum >= 1L, s"${r.duplicateIds}")
    assertEquals(r.danglingEdgeEndpoints, 0L)
    assertEquals(r.orphanResearchers, 0L)

  test("a duplicate in one table does not leak into another table's count"):
    val tables = clean.updated("nodes-work", clean("nodes-work") :+ work(1))
    val r = check(tables)
    assert(r.duplicateIds.values.sum >= 1L, s"${r.duplicateIds}")
    val others = r.duplicateIds.filterNot((k, _) => k.contains("work"))
    assert(others.values.forall(_ == 0L), s"${r.duplicateIds}")

  test("a coauthor edge with no support row is counted"):
    // E23 loses its support row (its joint count then also disagrees with the
    // 0 support rows, which the mismatch counter may or may not include)
    val tables = clean.updated(
      "edge-support",
      Vector(support(e12, rid(1), rid(2), wid(1)))
    )
    val r = check(tables)
    assertEquals(r.edgesWithoutSupport, 1L, s"${counters(r)}")
    assertEquals(r.danglingEdgeEndpoints, 0L)

  test("a joint count different from the support count is counted"):
    // E12 claims 2 joint publications, one support row backs it
    val tables = clean.updated(
      "edges-coauthored",
      Vector(
        coauthored(e12, rid(1), rid(2), 2, wid(1)),
        coauthored(e23, rid(2), rid(3), 1, wid(2))
      )
    )
    val r = check(tables)
    assertEquals(r.jointCountMismatches, 1L, s"${counters(r)}")
    assertEquals(r.edgesWithoutSupport, 0L)

  test("more support rows than the joint count is also a mismatch"):
    val tables = clean.updated(
      "edge-support",
      clean("edge-support") :+ support(e12, rid(1), rid(2), wid(2))
    )
    val r = check(tables)
    assertEquals(r.jointCountMismatches, 1L, s"${counters(r)}")
    assertEquals(r.edgesWithoutSupport, 0L)

  test("a path hop whose edge is not in edges-coauthored is counted"):
    // hop 2 names an edge, and endpoints, that no coauthor row has
    val tables = clean.updated(
      "path-evidence",
      Vector(
        hop("p1", 1, rid(1), rid(2), e12),
        hop("p1", 2, "researcher:v1:ghost1", "researcher:v1:ghost2", "x:ghost"),
        statusRow
      )
    )
    val r = check(tables)
    assertEquals(r.pathHopsNotInEdges, 1L, s"${counters(r)}")
    assertEquals(r.edgesWithoutSupport, 0L)

  test("a status row with null hop columns is never a bad hop"):
    assertEquals(check(clean).pathHopsNotInEdges, 0L)

  test("a provenance runId different from the manifest's is counted"):
    val tables = clean.updated(
      "nodes-work",
      Vector(work(1), work(2, "run:v1:someone-else"))
    )
    val r = check(tables)
    assertEquals(r.provenanceRunIdMismatches, 1L, s"${counters(r)}")
    assertEquals(r.duplicateIds.values.sum, 0L)

  test("provenance mismatches are counted per row across tables"):
    val tables = clean
      .updated("nodes-work", Vector(work(1, "run:v1:x"), work(2)))
      .updated(
        "nodes-researcher",
        Vector(researcher(1), researcher(2, "run:v1:y"), researcher(3))
      )
    assertEquals(check(tables).provenanceRunIdMismatches, 2L)

  test("a researcher with no authored edge is an orphan"):
    val tables = clean.updated(
      "nodes-researcher",
      clean("nodes-researcher") :+ researcher(4)
    )
    val r = check(tables)
    assertEquals(r.orphanResearchers, 1L, s"${counters(r)}")
    assertEquals(r.orphanWorks, 0L)

  test("a work with no authored edge is an orphan"):
    val tables =
      clean.updated("nodes-work", clean("nodes-work") :+ work(3))
    val r = check(tables)
    assertEquals(r.orphanWorks, 1L, s"${counters(r)}")
    assertEquals(r.orphanResearchers, 0L)

  test("the same export, seed and size give an identical report"):
    val dir = writeExport(clean)
    assertEquals(GraphChecks.run(dir, 16L, 50), GraphChecks.run(dir, 16L, 50))

  test("a sample size above the edge count and a zero sample size both work"):
    val dir = writeExport(clean)
    assertEquals(counters(GraphChecks.run(dir, 1L, 5000)).values.toSet, Set(0L))
    assertEquals(counters(GraphChecks.run(dir, 1L, 0)).values.toSet, Set(0L))
