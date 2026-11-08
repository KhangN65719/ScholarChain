package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}

class GraphAlgorithmsSuite extends SparkSuite:
  private def labelMap(data: DataFrame): Map[(String, String), String] =
    rows(data)
      .map(r =>
        (r.getAs[String]("window"), r.getAs[String]("nodeId")) ->
          r.getAs[String]("label")
      )
      .toMap

  private def byWindow(data: DataFrame): Map[String, Row] =
    rows(data).map(r => r.getAs[String]("window") -> r).toMap

  private def clique(
      names: Vector[String],
      weight: Long
  ): Vector[(String, String, Long)] =
    for
      i <- names.indices.toVector
      j <- (i + 1) until names.size
    yield (names(i), names(j), weight)

  private val a = Vector("a1", "a2", "a3", "a4")
  private val b = Vector("b1", "b2", "b3", "b4")
  private def twoCliquesWithBridge(bridgeWeight: Long): Graph =
    Graph.build(
      session,
      Vector(
        (
          "w",
          a ++ b,
          clique(a, 1L) ++ clique(b, 1L) :+ (("a4", "b1", bridgeWeight))
        )
      )
    )

  test("components include singletons and per-window summaries") {
    val graph = Graph.build(
      session,
      Vector(
        (
          "w",
          Vector("a", "b", "c", "d", "e", "f"),
          Vector(("a", "b", 1L), ("b", "c", 1L), ("d", "e", 1L))
        ),
        ("v", Vector("x", "y", "z"), Vector(("x", "y", 1L)))
      )
    )
    val context = algo()
    val result = Components.compute(context, graph.nodes, graph.adj)
    assert(result.converged)
    assertEquals(
      labelMap(result.frame),
      Map(
        ("w", "a") -> "a",
        ("w", "b") -> "a",
        ("w", "c") -> "a",
        ("w", "d") -> "d",
        ("w", "e") -> "d",
        ("w", "f") -> "f",
        ("v", "x") -> "x",
        ("v", "y") -> "x",
        ("v", "z") -> "z"
      )
    )
    val (_, components, summary) =
      Components.tables(context, result.frame, graph.edges)
    val sizes = rows(components)
      .map(r =>
        (r.getAs[String]("window"), r.getAs[String]("componentId")) ->
          r.getAs[Long]("componentSize")
      )
      .toMap
    assertEquals(
      sizes,
      Map(
        ("w", "a") -> 3L,
        ("w", "d") -> 2L,
        ("w", "f") -> 1L,
        ("v", "x") -> 2L,
        ("v", "z") -> 1L
      )
    )
    val summaries = byWindow(summary)
    val w = summaries("w")
    assertEquals(w.getAs[Long]("nodeCount"), 6L)
    assertEquals(w.getAs[Long]("edgeCount"), 3L)
    assertEquals(w.getAs[Long]("componentCount"), 3L)
    assertEquals(w.getAs[Long]("largestComponentSize"), 3L)
    assertEqualsDouble(w.getAs[Double]("largestComponentFraction"), 0.5, 1e-12)
    assertEquals(w.getAs[Long]("singletonCount"), 1L)
    val v = summaries("v")
    assertEquals(v.getAs[Long]("nodeCount"), 3L)
    assertEquals(v.getAs[Long]("componentCount"), 2L)
    assertEqualsDouble(
      v.getAs[Double]("largestComponentFraction"),
      2.0 / 3.0,
      1e-12
    )
    assertEquals(v.getAs[Long]("singletonCount"), 1L)
  }

  test("coreness on triangle with pendant, clique and path") {
    val graph = Graph.build(
      session,
      Vector(
        (
          "t",
          Vector("a", "b", "c", "d", "z"),
          Vector(("a", "b", 1L), ("b", "c", 1L), ("a", "c", 1L), ("c", "d", 1L))
        ),
        (
          "k",
          Vector("k1", "k2", "k3", "k4"),
          clique(Vector("k1", "k2", "k3", "k4"), 1L)
        ),
        (
          "p",
          Vector("p1", "p2", "p3", "p4"),
          Vector(("p1", "p2", 1L), ("p2", "p3", 1L), ("p3", "p4", 1L))
        )
      )
    )
    val result = Coreness.compute(algo(), graph.nodes, graph.adj)
    val coreness = rows(result)
      .map(r =>
        (r.getAs[String]("window"), r.getAs[String]("nodeId")) ->
          r.getAs[Int]("coreness")
      )
      .toMap
    assertEquals(
      coreness,
      Map(
        ("t", "a") -> 2,
        ("t", "b") -> 2,
        ("t", "c") -> 2,
        ("t", "d") -> 1,
        ("t", "z") -> 0,
        ("k", "k1") -> 3,
        ("k", "k2") -> 3,
        ("k", "k3") -> 3,
        ("k", "k4") -> 3,
        ("p", "p1") -> 1,
        ("p", "p2") -> 1,
        ("p", "p3") -> 1,
        ("p", "p4") -> 1
      )
    )
  }

  test("label propagation separates two cliques joined by a bridge") {
    val graph = twoCliquesWithBridge(1L)
    val context = algo()
    val result = Communities.compute(context, graph.nodes, graph.adj)
    assert(result.converged, "the run reaches a fixpoint")
    assertEquals(result.perWindow("w")._2, true)
    val labels = labelMap(result.labels).map((k, v) => k._2 -> v)
    assertEquals(a.map(labels).distinct.size, 1)
    assertEquals(b.map(labels).distinct.size, 1)
    assertNotEquals(
      labels("a1"),
      labels("b1"),
      "the cliques are two communities"
    )
    val (communities, cross, _) =
      Communities.tables(context, result.labels, graph.edges)
    val table = rows(communities)
    assertEquals(table.size, 2)
    table.foreach { r =>
      assertEquals(r.getAs[Long]("communitySize"), 4L)
      assertEquals(r.getAs[Long]("internalEdges"), 6L)
      assertEquals(r.getAs[Long]("crossEdges"), 1L)
      assertEquals(r.getAs[Long]("internalWeight"), 6L)
      assertEquals(r.getAs[Long]("crossWeight"), 1L)
    }
    val crossRows = rows(cross)
    assertEquals(crossRows.size, 1)
    assertEquals(crossRows.head.getAs[Long]("edgeCount"), 1L)
    assertEquals(crossRows.head.getAs[Long]("totalWeight"), 1L)
  }

  test("a two-node component converges") {
    val graph = Graph.build(
      session,
      Vector(("w", Vector("x", "y"), Vector(("x", "y", 1L))))
    )
    val result = Communities.compute(algo(), graph.nodes, graph.adj)
    assert(result.converged)
    val labels = labelMap(result.labels)
    assertEquals(labels(("w", "x")), labels(("w", "y")))
  }

  test("iteration cap is recorded") {
    val graph = twoCliquesWithBridge(1L)
    val result = Communities.compute(algo(1), graph.nodes, graph.adj)
    assertEquals(result.iterations, 1)
    assertEquals(result.converged, false)
    assertEquals(result.perWindow("w")._2, false)
    assertEquals(DriverGuard.count(result.labels), 8L)
  }

  test("bridge measures on two cliques plus a bridge") {
    val graph = twoCliquesWithBridge(2L)
    val communities = frame("window STRING, nodeId STRING, label STRING")(
      (a.map(n => Row("w", n, "A")) ++ b.map(n => Row("w", n, "B")))*
    )
    val measures = rows(Bridge.measures(algo(), graph.adj, communities))
      .map(r => r.getAs[String]("researcherId") -> r)
      .toMap
    val a4 = measures("a4")
    assertEquals(a4.getAs[String]("communityId"), "A")
    assertEquals(a4.getAs[Long]("degree"), 4L)
    assertEquals(a4.getAs[Long]("strength"), 5L)
    assertEquals(a4.getAs[Long]("crossCommunityDegree"), 1L)
    assertEquals(a4.getAs[Long]("crossCommunityStrength"), 2L)
    assertEquals(a4.getAs[Long]("distinctCommunitiesConnected"), 1L)
    assertEquals(a4.getAs[Long]("distinctNeighborCommunities"), 2L)
    assertEqualsDouble(
      a4.getAs[Double]("participationCoefficient"),
      0.48,
      1e-12
    )
    assertEqualsDouble(a4.getAs[Double]("bridgeScore"), 0.48, 1e-12)
    val b1 = measures("b1")
    assertEquals(b1.getAs[Long]("crossCommunityDegree"), 1L)
    assertEqualsDouble(
      b1.getAs[Double]("participationCoefficient"),
      0.48,
      1e-12
    )
    val a1 = measures("a1")
    assertEquals(a1.getAs[Long]("degree"), 3L)
    assertEquals(a1.getAs[Long]("crossCommunityDegree"), 0L)
    assertEquals(a1.getAs[Long]("distinctCommunitiesConnected"), 0L)
    assertEquals(a1.getAs[Long]("distinctNeighborCommunities"), 1L)
    assertEqualsDouble(a1.getAs[Double]("participationCoefficient"), 0.0, 1e-12)
  }

  test("bridge definitions and limitations are stated") {
    val names = Bridge.definitions.map(_._1).toSet
    assertEquals(
      names,
      Set(
        "crossCommunityDegree",
        "crossCommunityStrength",
        "distinctCommunitiesConnected",
        "distinctNeighborCommunities",
        "participationCoefficient",
        "bridgeScore"
      )
    )
    assert(Bridge.definitions.forall(_._2.nonEmpty))
    assert(Bridge.limitations.contains("community method"))
    assert(Bridge.limitations.contains("betweenness"))
  }
