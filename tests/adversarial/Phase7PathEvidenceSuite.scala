package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.{Provenance, StableIds}
import scholarchain.storage.*
import Phase7Storage.*

/** Independent checks of the Spark `path-evidence` reader: edge ID shape, node
  * kinds, supporting work IDs, hop membership, status rows and provenance.
  */
class Phase7PathEvidenceSuite extends FunSuite:
  private val r3 = StableIds.researcherFromExternal("openalex", "A3")
  private val eRR = StableIds.coauthorEdgeFor(r1, r2, window, policy)
  private val eMM = StableIds.coauthorEdgeFor(m1, m2, window, policy)
  private val eMR = StableIds.coauthorEdgeFor(m2, r1, window, policy)
  private val eR13 = StableIds.coauthorEdgeFor(r1, r3, window, policy)

  private def hop(
      from: String,
      to: String,
      edge: String,
      index: Long = 1,
      works: Seq[String] = Seq(w1),
      p: Provenance = provenance()
  ): Row =
    Map(
      "pairKey" -> s("pair-1"),
      "window" -> s(windowLabel),
      "sourceId" -> s(from),
      "targetId" -> s(to),
      "hopIndex" -> n(index),
      "fromId" -> s(from),
      "toId" -> s(to),
      "edgeId" -> s(edge),
      "jointPublicationCount" -> n(1),
      "supportingWorkIds" -> arr(works*),
      "provenance" -> provJson(p)
    )

  private def status(
      kind: String,
      reason: Option[String] = None,
      p: Provenance = provenance()
  ): Row =
    val base: Row = Map(
      "pairKey" -> s("pair-2"),
      "window" -> s(windowLabel),
      "sourceId" -> s(r1),
      "targetId" -> s(r2),
      "status" -> s(kind),
      "provenance" -> provJson(p)
    )
    reason.fold(base)(x => base + ("unreachableReason" -> s(x)))

  private def edgesTable: Vector[Row] =
    Vector(
      coauthorRow(r1, r2, 1, 2020, 2020, Seq(w1), provenance()),
      coauthorRow(m1, m2, 1, 2020, 2020, Seq(w1), provenance()),
      coauthorRow(m2, r1, 1, 2020, 2020, Seq(w1), provenance()),
      coauthorRow(r1, r3, 1, 2020, 2020, Seq(w1), provenance())
    )

  private def source(rows: Vector[Row], withEdges: Boolean = true) =
    val tables =
      if withEdges then
        Map("path-evidence" -> rows, "edges-coauthored" -> edgesTable)
      else Map("path-evidence" -> rows)
    new InMemoryTableSource(tables)

  private def read(rows: Vector[Row], withEdges: Boolean = true) =
    PathEvidence.read(source(rows, withEdges), Bounds)

  test("valid hop rows for resolved, mention and mixed pairs are read"):
    val rows = Vector(
      hop(r1, r2, eRR),
      hop(m1, m2, eMM, index = 2),
      hop(m2, r1, eMR, index = 3)
    )
    val read1 = read(rows)
    assertEquals(read1.size, 3)
    assertEquals(read1.map(_.edgeId.get), Vector(eRR, eMM, eMR))
    assert(eRR.startsWith("coauthor:v1:"))
    assert(eMM.startsWith("mention-coauthor:v1:"))
    assert(eMR.startsWith("mention-coauthor:v1:"))
    assertEquals(read1.map(_.hopIndex.get), Vector(1, 2, 3))
    assertEquals(read1.head.supportingWorkIds, Vector(w1))

  test("provenance is kept on hop and status rows"):
    val p =
      provenance(snapshot = "snap-p", record = "rec/p", transform = "tx/8")
    val got = read(Vector(hop(r1, r2, eRR, p = p), status("trivial", p = p)))
    got.foreach { r =>
      assertEquals(r.provenance, p)
      assertEquals(r.provenance.runId, runId)
      assertEquals(r.provenance.snapshotId, "snap-p")
      assertEquals(r.provenance.sourceRecordId, "rec/p")
      assertEquals(r.provenance.transformVersion, "tx/8")
    }

  test("a row without provenance is a MalformedRow"):
    val noProv = hop(r1, r2, eRR) - "provenance"
    assertEquals(code(read(Vector(noProv))), ErrorCode.MalformedRow)
    val nullProv = hop(r1, r2, eRR) + ("provenance" -> ujson.Null)
    assertEquals(code(read(Vector(nullProv))), ErrorCode.MalformedRow)

  test("a legacy coauthor:v1: ID on a mention hop is refused as legacy"):
    val legacy = "coauthor:v1:" + "ab" * 32
    Vector((m1, m2), (m2, r1), (r1, m1)).foreach { case (a, b) =>
      assertEquals(
        code(read(Vector(hop(a, b, legacy)), withEdges = false)),
        ErrorCode.LegacyEdgeId
      )
    }
    // the genuine pair-of-researchers legacy-shaped ID is fine when it exists
    assertEquals(read(Vector(hop(r1, r2, eRR))).size, 1)

  test("the edge ID must have the form that matches its endpoints"):
    // mention-coauthor form on two researchers
    val mentionForm = StableIds.mentionCoauthorEdge(m1, m2, window, policy)
    assertEquals(
      code(read(Vector(hop(r1, r2, mentionForm)), withEdges = false)),
      ErrorCode.InvalidEdgeId
    )
    // a foreign kind on any pair
    Vector(
      w1,
      StableIds.mention(w1, 1),
      "edge:v1:" + "ab" * 32,
      "authored:v1:" + "ab" * 32,
      "",
      "mention-coauthor:v1:"
    ).foreach { bad =>
      assertEquals(
        code(read(Vector(hop(m1, m2, bad)), withEdges = false)),
        ErrorCode.InvalidEdgeId,
        bad
      )
    }

  test("the edge ID must be the prefix plus exactly 64 lowercase hex digits"):
    val hex = "ab" * 32
    Vector(
      hex.dropRight(1),
      hex + "0",
      hex.toUpperCase,
      "g" * 64,
      hex + "\n",
      " " + hex,
      hex + ":x"
    ).foreach { tail =>
      assertEquals(
        code(
          read(Vector(hop(r1, r2, "coauthor:v1:" + tail)), withEdges = false)
        ),
        ErrorCode.InvalidEdgeId,
        tail
      )
      assertEquals(
        code(
          read(
            Vector(hop(m1, m2, "mention-coauthor:v1:" + tail)),
            withEdges = false
          )
        ),
        ErrorCode.InvalidEdgeId,
        tail
      )
    }
    // well formed, absent table: accepted (no membership check without it)
    assertEquals(
      read(Vector(hop(r1, r2, "coauthor:v1:" + hex)), withEdges = false).size,
      1
    )

  test("from and to must be core node IDs"):
    Vector(w1, "mention:mention-1", "researcher:v1:zz", "", venue).foreach {
      bad =>
        assertEquals(
          code(read(Vector(hop(bad, r2, eRR)), withEdges = false)),
          ErrorCode.MalformedRow,
          s"from=$bad"
        )
        assertEquals(
          code(read(Vector(hop(r1, bad, eRR)), withEdges = false)),
          ErrorCode.MalformedRow,
          s"to=$bad"
        )
    }

  test("source and target of the pair must be core node IDs"):
    val bad = hop(r1, r2, eRR).updated("sourceId", s(w1))
    assertEquals(code(read(Vector(bad))), ErrorCode.MalformedRow)
    val bad2 = hop(r1, r2, eRR).updated("targetId", s("nope"))
    assertEquals(code(read(Vector(bad2))), ErrorCode.MalformedRow)

  test("a hop row needs both endpoints"):
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR) - "fromId"))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR) - "toId"))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR) + ("toId" -> ujson.Null)))),
      ErrorCode.MalformedRow
    )

  test("supporting works must be work IDs and must exist"):
    Vector(
      Seq(r1),
      Seq(StableIds.mention(w1, 1)),
      Seq(w1, "not-a-work"),
      Seq(""),
      Seq("work:v1:")
    ).foreach { works =>
      assertEquals(
        code(read(Vector(hop(r1, r2, eRR, works = works)))),
        ErrorCode.MalformedRow,
        works.toString
      )
    }
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR, works = Seq.empty)))),
      ErrorCode.MissingEvidence
    )
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR) - "supportingWorkIds"))),
      ErrorCode.MissingEvidence
    )

  test("a hop needs its index"):
    assertEquals(
      code(read(Vector(hop(r1, r2, eRR) - "hopIndex"))),
      ErrorCode.MissingEvidence
    )

  test("hop edge membership is checked against edges-coauthored when given"):
    val unknown = StableIds.coauthorEdgeFor(r2, r3, window, policy)
    assertEquals(
      code(read(Vector(hop(r2, r3, unknown)))),
      ErrorCode.MissingEvidence
    )
    // the same row is fine when the table is not supplied
    assertEquals(read(Vector(hop(r2, r3, unknown)), withEdges = false).size, 1)
    // an edge of another window is not an edge of this table
    val otherWindow =
      StableIds.coauthorEdgeFor(
        r1,
        r2,
        scholarchain.core.YearWindow(2000, 2001),
        policy
      )
    assertEquals(
      code(read(Vector(hop(r1, r2, otherWindow)))),
      ErrorCode.MissingEvidence
    )

  test(
    "a hop whose endpoints are not the endpoints of its cited edge is refused"
  ):
    // eR13 is the edge between r1 and r3; the hop claims r1 to r2.
    val outcome =
      try Right(read(Vector(hop(r1, r2, eR13))))
      catch case e: StorageException => Left(e.code)
    assert(
      outcome.isLeft,
      s"accepted a hop that cites another pair's edge: $outcome"
    )

  test("status rows: known statuses pass, unknown ones do not"):
    val ok = Vector(
      status("trivial"),
      status("endpoint-not-in-window"),
      status("unreachable-within-bound", Some("different-component")),
      status("unreachable-within-bound", Some("beyond-hop-limit")),
      status("unreachable-within-bound")
    )
    assertEquals(read(ok).size, 5)
    assertEquals(code(read(Vector(status("mystery")))), ErrorCode.MalformedRow)
    assertEquals(code(read(Vector(status("")))), ErrorCode.MalformedRow)
    assertEquals(
      code(read(Vector(status("trivial") - "status"))),
      ErrorCode.MalformedRow
    )

  test("unreachableReason is only valid on unreachable-within-bound rows"):
    assertEquals(
      code(read(Vector(status("trivial", Some("different-component"))))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(read(Vector(status("unreachable-within-bound", Some("because"))))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(
        read(
          Vector(
            hop(r1, r2, eRR) + ("unreachableReason" -> s("beyond-hop-limit"))
          )
        )
      ),
      ErrorCode.MalformedRow
    )

  test("the row bound stops the read"):
    val rows = Vector.fill(5)(hop(r1, r2, eRR))
    assertEquals(
      code(PathEvidence.read(source(rows), LoadBounds(4, 10, 1000))),
      ErrorCode.BoundExceeded
    )

  test("one bad row fails the read; nothing is partly returned"):
    val rows = Vector(hop(r1, r2, eRR), hop(r1, r2, eRR, works = Seq(r1)))
    assertEquals(code(read(rows)), ErrorCode.MalformedRow)

  test("a missing path-evidence table fails, it is not an empty result"):
    val none = new InMemoryTableSource(Map("edges-coauthored" -> edgesTable))
    assertEquals(
      code(PathEvidence.read(none, Bounds)),
      ErrorCode.MalformedManifest
    )
