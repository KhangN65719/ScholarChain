package scholarchain.spark

import org.apache.spark.sql.Row
import scholarchain.core.{
  CoauthorEdge,
  ContractSchema,
  EvidenceType,
  GraphEdge,
  GraphEdgeType,
  ResearcherMetrics,
  SourceType,
  StableIds,
  YearWindow
}
import scholarchain.spark.Fixtures.{label, string, strings}

class CoauthorshipSuite extends SparkSuite:
  private val windowSpec = "1990-1999,2000-2004,2005-2009,all"
  private lazy val windows = Windows.parse(windowSpec).toOption.get
  private lazy val raw = Inputs.load(session, Fixtures.clean)
  private lazy val canon = Canonical.build(session, raw, Fixtures.context)
  private lazy val graph = build(5)

  private def build(representativeLimit: Int): CoauthorTables =
    Coauthorship.build(
      session,
      raw,
      canon,
      Windowing.frame(session, windows),
      Fixtures.context,
      representativeLimit
    )

  private def pairLabel(row: Row): (String, String) =
    val first = label(string(row, "researcherA"))
    val second = label(string(row, "researcherB"))
    if first < second then (first, second) else (second, first)

  private type EdgeFact = (String, String, String, Long, Int, Int)

  private def edgeFacts(tables: CoauthorTables): Set[EdgeFact] =
    rows(tables.edges).map { r =>
      val (a, b) = pairLabel(r)
      (
        string(r, "window"),
        a,
        b,
        r.getAs[Long]("jointPublicationCount"),
        r.getAs[Int]("firstCollaborationYear"),
        r.getAs[Int]("lastCollaborationYear")
      )
    }.toSet

  test(
    "pair weights count distinct canonical works (merged duplicate counted once)"
  ) {
    val expected: Set[EdgeFact] = Set(
      ("2000-2004", "A", "B", 2L, 2001, 2002),
      ("2000-2004", "A", "C", 1L, 2001, 2001),
      ("2000-2004", "B", "C", 1L, 2001, 2001),
      ("2000-2004", "B", "E", 1L, 2000, 2000),
      ("2000-2004", "A", "X", 1L, 2004, 2004),
      ("2005-2009", "A", "D", 1L, 2005, 2005),
      ("2005-2009", "A", "F", 1L, 2006, 2006),
      ("all", "A", "B", 2L, 2001, 2002),
      ("all", "A", "C", 1L, 2001, 2001),
      ("all", "B", "C", 1L, 2001, 2001),
      ("all", "B", "E", 1L, 2000, 2000),
      ("all", "A", "X", 1L, 2004, 2004),
      ("all", "A", "D", 1L, 2005, 2005),
      ("all", "A", "F", 1L, 2006, 2006)
    )
    assertEquals(edgeFacts(graph), expected)
    assertEquals(rows(graph.edges).size, 14)
  }

  test("a self pair is dropped and counted") {
    val counts = rows(graph.pairCounts)
      .map(r => string(r, "disposition") -> r.getAs[Long]("pairs"))
      .toMap
    assertEquals(counts("self-pair"), 1L)
    assertEquals(counts("duplicate-record"), 3L)
    assertEquals(counts("mapped"), 10L)
    assert(!counts.contains("endpoint-missing"))
    val selfEdges = rows(graph.edges).filter(r =>
      string(r, "researcherA") == string(r, "researcherB")
    )
    assertEquals(selfEdges.size, 0)
  }

  test("pair counts give candidate rows and distinct pairs") {
    val counts = rows(graph.pairCounts)
      .map(r =>
        string(r, "disposition") -> (
          r.getAs[Long]("pairs"),
          r.getAs[Long](
            "distinctPairs"
          )
        )
      )
      .toMap
    // W3 is A A D: the two A mentions each pair with D, so the pair A-D of W3
    // is two candidate rows and one distinct pair. Mapped rows: W1 3, W2 1,
    // W3 2, W4 1, W5 1, W6 1, W7 1 = 10; distinct pairs = 9.
    assertEquals(counts("mapped"), (10L, 9L))
    assertEquals(counts("self-pair"), (1L, 1L))
    assertEquals(counts("duplicate-record"), (3L, 3L))
    // Two rows of one pair of one work are one distinct pair.
    val repeated = frame(
      "workId STRING, a STRING, b STRING, disposition STRING, " +
        "mentionA STRING, mentionB STRING, canonicalWorkId STRING"
    )(
      Row("w", "x", "y", "mapped", "m1", "m2", "w"),
      Row("w", "x", "y", "mapped", "m1", "m2", "w"),
      Row("w", "x", "z", "mapped", "m1", "m3", "w")
    )
    val stats = rows(
      Sql.run(
        session,
        """SELECT disposition, count(*) AS pairs,
          |  count(DISTINCT struct(workId, a, b)) AS distinctPairs
          |FROM {d} GROUP BY disposition""".stripMargin,
        "d" -> repeated
      )
    ).head
    assertEquals(stats.getAs[Long]("pairs"), 3L)
    assertEquals(stats.getAs[Long]("distinctPairs"), 2L)
  }

  test("an edge ID is always the core ID, mention-coauthor for a mention") {
    val a = SyntheticRun.researcherIds("A")
    val b = SyntheticRun.researcherIds("B")
    val mention = SyntheticRun.mentionId("W3", 3)
    val window = YearWindow(2000, 2004)
    assertEquals(
      EdgeIds.edgeId(a, b, 2000, 2004),
      StableIds.coauthorEdge(a, b, window, EdgeIds.policyVersion)
    )
    assert(EdgeIds.edgeId(a, b, 2000, 2004).startsWith("coauthor:v1:"))
    val id = EdgeIds.edgeId(a, mention, 2000, 2004)
    assert(id.matches("mention-coauthor:v1:[0-9a-f]{64}"), id)
    assertEquals(
      id,
      StableIds.coauthorEdgeFor(a, mention, window, EdgeIds.policyVersion)
    )
    assertEquals(
      id,
      StableIds.mentionCoauthorEdge(mention, a, window, EdgeIds.policyVersion)
    )
    assertNotEquals(id, EdgeIds.edgeId(b, mention, 2000, 2004))
    assertNotEquals(id, EdgeIds.edgeId(a, mention, 2000, 2003))
    assertEquals(id, EdgeIds.edgeId(a, mention, 2000, 2004))
    // Two mentions give the mention form too.
    val other = SyntheticRun.mentionId("W6", 2)
    assert(
      EdgeIds
        .edgeId(mention, other, 2000, 2004)
        .startsWith("mention-coauthor:v1:")
    )
    // A malformed endpoint is a data error, never a reason for a local ID.
    intercept[IllegalArgumentException](
      EdgeIds.edgeId("not-a-researcher", b, 2000, 2004)
    )
    intercept[IllegalArgumentException](
      EdgeIds.edgeId("mention:not-valid", b, 2000, 2004)
    )
  }

  test("every emitted edge ID round-trips through the core contracts") {
    val edges = rows(graph.edges)
    assertEquals(edges.size, 14)
    var mentionEdges = 0
    edges.foreach { r =>
      val window =
        YearWindow(r.getAs[Int]("windowStart"), r.getAs[Int]("windowEnd"))
      val a = string(r, "researcherA")
      val b = string(r, "researcherB")
      val id = string(r, "edgeId")
      val unresolved = a.startsWith("mention:") || b.startsWith("mention:")
      if unresolved then mentionEdges += 1
      assert(
        id.startsWith(
          if unresolved then "mention-coauthor:v1:" else "coauthor:v1:"
        ),
        id
      )
      val core = CoauthorEdge(
        ContractSchema.currentVersion,
        id,
        a,
        b,
        window,
        string(r, "policyVersion"),
        r.getAs[Long]("jointPublicationCount"),
        r.getAs[Int]("firstCollaborationYear"),
        r.getAs[Int]("lastCollaborationYear"),
        strings(r, "representativeWorkIds"),
        Fixtures.context.validated("edges-coauthored")
      )
      assertEquals(core.edgeId, id)
      val evidence = StableIds.evidence(
        SourceType.Derived,
        "v1",
        id,
        EvidenceType.GraphPath,
        "edge"
      )
      val graphEdge = GraphEdge(
        ContractSchema.currentVersion,
        StableIds.graphEdge(
          GraphEdgeType.CoauthoredWith,
          a,
          b,
          core.policyVersion,
          Vector(evidence)
        ),
        a,
        GraphEdgeType.CoauthoredWith,
        b,
        Map.empty,
        core.policyVersion,
        Vector(evidence),
        Some(core),
        Fixtures.context.validated("edges-coauthored")
      )
      assertEquals(graphEdge.coauthorship.map(_.edgeId), Some(id))
    }
    assertEquals(mentionEdges, 4)
    assertEquals(edges.map(string(_, "edgeId")).toSet.size, edges.size)
  }

  test(
    "every metrics row, mention nodes included, is a core ResearcherMetrics"
  ) {
    val metrics = rows(graph.metrics)
    val mentionRows =
      metrics.filter(r => string(r, "researcherId").startsWith("mention:"))
    assert(mentionRows.nonEmpty)
    metrics.foreach { r =>
      val core = ResearcherMetrics(
        ContractSchema.currentVersion,
        string(r, "researcherId"),
        YearWindow(r.getAs[Int]("windowStart"), r.getAs[Int]("windowEnd")),
        r.getAs[Long]("publicationCount"),
        r.getAs[Long]("distinctCoauthorCount"),
        r.getAs[Long]("totalCoauthorOccurrences"),
        r.getAs[Double]("averageCoauthorsPerPaper"),
        r.getAs[Long]("maximumCoauthorsOnOnePaper"),
        strings(r, "maximumTeamWorkIds"),
        r.getAs[Double]("weightedDegree"),
        "component-placeholder",
        1L,
        None,
        None,
        None,
        Fixtures.context.validated("researcher-metrics")
      )
      assertEquals(core.researcherId, string(r, "researcherId"))
    }
  }

  test("pair order is canonical") {
    rows(graph.edges).foreach { r =>
      val a = string(r, "researcherA")
      val b = string(r, "researcherB")
      assertEquals((a, b), StableIds.orderedPair(a, b))
    }
    val ab = rows(graph.edges)
      .find(r =>
        string(r, "window") == "2000-2004" && pairLabel(r) == ("A", "B")
      )
      .get
    val window = YearWindow(
      ab.getAs[Int]("windowStart"),
      ab.getAs[Int]("windowEnd")
    )
    val id = StableIds.coauthorEdge(
      string(ab, "researcherA"),
      string(ab, "researcherB"),
      window,
      EdgeIds.policyVersion
    )
    assertEquals(string(ab, "edgeId"), id)
    val core = CoauthorEdge(
      ContractSchema.currentVersion,
      string(ab, "edgeId"),
      string(ab, "researcherA"),
      string(ab, "researcherB"),
      window,
      string(ab, "policyVersion"),
      ab.getAs[Long]("jointPublicationCount"),
      ab.getAs[Int]("firstCollaborationYear"),
      ab.getAs[Int]("lastCollaborationYear"),
      strings(ab, "representativeWorkIds"),
      Fixtures.context.validated("edges-coauthored")
    )
    assertEquals(core.jointPublicationCount, 2L)
  }

  test("an unresolved mention is preserved unmerged in pairs") {
    val dora = SyntheticRun.mentionId("W3", 3)
    val xavier = SyntheticRun.mentionId("W6", 2)
    val edges = rows(graph.edges).filter(r => string(r, "window") == "all")
    val endpoints = edges
      .flatMap(r => Vector(string(r, "researcherA"), string(r, "researcherB")))
      .toSet
    assert(endpoints.contains(dora))
    assert(endpoints.contains(xavier))
    assert(endpoints.contains(SyntheticRun.researcherIds("A")))
    val flagged = edges.filter(r =>
      r.getAs[Boolean]("endpointAUnresolved") ||
        r.getAs[Boolean]("endpointBUnresolved")
    )
    assertEquals(flagged.size, 2)
    val duplicate = SyntheticRun.mentionId("W2b", 3)
    assert(!endpoints.contains(duplicate))
  }

  test("window boundary years are inclusive") {
    val byWindow = edgeFacts(graph).groupBy(_._1).map((k, v) => k -> v.size)
    assertEquals(byWindow, Map("2000-2004" -> 5, "2005-2009" -> 2, "all" -> 7))
    val firstWindow = edgeFacts(graph).filter(_._1 == "2000-2004")
    assert(firstWindow.exists(f => f._2 == "B" && f._3 == "E" && f._5 == 2000))
    assert(firstWindow.exists(f => f._2 == "A" && f._3 == "X" && f._6 == 2004))
    assert(!firstWindow.exists(f => f._3 == "D"))
  }

  test("works without a year are excluded and counted") {
    val undated =
      rows(canon.works).filter(r => r.isNullAt(r.fieldIndex("year")))
    assertEquals(
      undated.map(string(_, "canonicalWorkId")),
      Vector(SyntheticRun.workId("W4"))
    )
    val allEdges = edgeFacts(graph).filter(_._1 == "all")
    assertEquals(
      allEdges.find(f => f._2 == "A" && f._3 == "B").map(_._4),
      Some(2L)
    )
    val mapped = rows(graph.pairCounts)
      .map(r => string(r, "disposition") -> r.getAs[Long]("pairs"))
      .toMap
    assertEquals(mapped("mapped"), 10L)
  }

  test("representative work IDs are bounded and deterministic") {
    def reps(tables: CoauthorTables): Vector[String] =
      rows(tables.edges)
        .find(r => string(r, "window") == "all" && pairLabel(r) == ("A", "B"))
        .map(strings(_, "representativeWorkIds"))
        .get
    assertEquals(
      reps(graph),
      Vector(SyntheticRun.workId("W1"), SyntheticRun.workId("W2"))
    )
    assertEquals(reps(build(1)), Vector(SyntheticRun.workId("W1")))
    assertEquals(reps(build(5)), reps(graph))
    val support = rows(graph.edgeSupport).filter(r =>
      string(r, "window") == "all" && pairLabel(r) == ("A", "B")
    )
    assertEquals(support.size, 2)
  }

  private def metricsOf(window: String): Map[String, Row] =
    rows(graph.metrics)
      .filter(string(_, "window") == window)
      .map(r => label(string(r, "researcherId")) -> r)
      .toMap

  test("P C T A M W are hand-checked") {
    val first = metricsOf("2000-2004")
    assertEquals(first.keySet, Set("A", "B", "C", "E", "X"))
    def check(
        row: Row,
        p: Long,
        c: Long,
        t: Long,
        a: Double,
        m: Long,
        w: Double
    ): Unit =
      assertEquals(row.getAs[Long]("publicationCount"), p)
      assertEquals(row.getAs[Long]("distinctCoauthorCount"), c)
      assertEquals(row.getAs[Long]("totalCoauthorOccurrences"), t)
      assertEqualsDouble(
        row.getAs[Double]("averageCoauthorsPerPaper"),
        a,
        1e-12
      )
      assertEquals(row.getAs[Long]("maximumCoauthorsOnOnePaper"), m)
      assertEqualsDouble(row.getAs[Double]("weightedDegree"), w, 1e-12)
    check(first("A"), 3, 3, 4, 4.0 / 3.0, 2, 4.0)
    check(first("B"), 3, 3, 4, 4.0 / 3.0, 2, 4.0)
    check(first("C"), 1, 2, 2, 2.0, 2, 2.0)
    check(first("E"), 1, 1, 1, 1.0, 1, 1.0)
    check(first("X"), 1, 1, 1, 1.0, 1, 1.0)
    assertEquals(
      strings(first("A"), "maximumTeamWorkIds"),
      Vector(SyntheticRun.workId("W1"))
    )
    assertEquals(
      strings(first("E"), "maximumTeamWorkIds"),
      Vector(SyntheticRun.workId("W5"))
    )
    val all = metricsOf("all")
    check(all("A"), 5, 5, 6, 1.2, 2, 6.0)
    check(all("B"), 3, 3, 4, 4.0 / 3.0, 2, 4.0)
    rows(graph.metrics).foreach { r =>
      assertEqualsDouble(
        r.getAs[Double]("weightedDegree"),
        r.getAs[Long]("totalCoauthorOccurrences").toDouble,
        1e-12
      )
    }
  }

  test("M_r keeps every tied work") {
    val second = metricsOf("2005-2009")
    assertEquals(second.keySet, Set("A", "D", "F"))
    val ada = second("A")
    assertEquals(ada.getAs[Long]("maximumCoauthorsOnOnePaper"), 1L)
    assertEquals(
      strings(ada, "maximumTeamWorkIds"),
      Vector(SyntheticRun.workId("W3"), SyntheticRun.workId("W7")).sorted
    )
    assertEquals(ada.getAs[Long]("publicationCount"), 2L)
    assertEquals(ada.getAs[Long]("totalCoauthorOccurrences"), 2L)
  }

  test("unresolved coauthor occurrences are visible columns") {
    val first = metricsOf("2000-2004")
    assertEquals(first("A").getAs[Long]("unresolvedCoauthorOccurrences"), 1L)
    assertEquals(first("A").getAs[Long]("distinctUnresolvedCoauthorCount"), 1L)
    assertEquals(first("B").getAs[Long]("unresolvedCoauthorOccurrences"), 0L)
    assertEquals(first("X").getAs[Long]("unresolvedCoauthorOccurrences"), 0L)
    val all = metricsOf("all")
    assertEquals(all("A").getAs[Long]("unresolvedCoauthorOccurrences"), 2L)
    assertEquals(all("A").getAs[Long]("distinctUnresolvedCoauthorCount"), 2L)
    assertEquals(all("D").getAs[Long]("unresolvedCoauthorOccurrences"), 0L)
  }
