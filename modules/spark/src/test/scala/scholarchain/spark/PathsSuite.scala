package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}

class PathsSuite extends SparkSuite:
  private val line = Graph.build(
    session,
    Vector(
      (
        "w",
        Vector("a", "b", "c", "d", "e", "f", "g"),
        Vector(
          ("a", "b", 1L),
          ("b", "c", 1L),
          ("c", "d", 1L),
          ("d", "e", 1L),
          ("f", "g", 1L)
        )
      ),
      (
        "d",
        Vector("s", "x", "y", "t"),
        Vector(("s", "x", 1L), ("s", "y", 1L), ("x", "t", 1L), ("y", "t", 1L))
      )
    )
  )

  private def pairs(selected: (String, String, String)*): DataFrame =
    frame("window STRING, source STRING, target STRING, selection STRING")(
      selected.map((w, s, t) => Row(w, s, t, "test"))*
    )

  /** pairKey to its evidence rows, ordered by hop. */
  private def evidence(
      selected: Vector[(String, String, String)],
      hopLimit: Int
  ): Map[String, Vector[Row]] =
    val context = algo()
    val result = PathSearch.evidence(
      context,
      pairs(selected*),
      line.nodes,
      line.adj,
      line.edges,
      Components.compute(context, line.nodes, line.adj).frame,
      hopLimit
    )
    rows(result)
      .groupBy(_.getAs[String]("pairKey"))
      .map((key, group) =>
        key -> group.sortBy(r =>
          Option(r.getAs[Integer]("hopIndex")).map(_.intValue).getOrElse(0)
        )
      )

  private def hops(rowsOfPair: Vector[Row]): Vector[(String, String)] =
    rowsOfPair.map(r => (r.getAs[String]("fromId"), r.getAs[String]("toId")))

  test("shortest path with per-hop works") {
    val found = evidence(
      Vector(("w", "a", "e"), ("w", "b", "d")),
      4
    )
    val long = found("w|a|e")
    assertEquals(
      hops(long),
      Vector(("a", "b"), ("b", "c"), ("c", "d"), ("d", "e"))
    )
    assertEquals(long.map(_.getAs[String]("status")).distinct, Vector("found"))
    assertEquals(
      long.map(_.getAs[Integer]("pathLength").intValue).distinct,
      Vector(4)
    )
    assertEquals(
      long.map(
        _.getSeq[String](long.head.fieldIndex("supportingWorkIds")).toVector
      ),
      Vector(
        Vector("work-ab"),
        Vector("work-bc"),
        Vector("work-cd"),
        Vector("work-de")
      )
    )
    assertEquals(
      long.map(_.getAs[Long]("jointPublicationCount")).distinct,
      Vector(1L)
    )
    assertEquals(hops(found("w|b|d")), Vector(("b", "c"), ("c", "d")))
  }

  test("unreachable pair is recorded") {
    val found = evidence(
      Vector(("w", "a", "g"), ("w", "a", "a"), ("w", "a", "zz")),
      4
    )
    val unreachable = found("w|a|g")
    assertEquals(unreachable.size, 1)
    assertEquals(
      unreachable.head.getAs[String]("status"),
      "unreachable-within-bound"
    )
    assert(unreachable.head.isNullAt(unreachable.head.fieldIndex("pathLength")))
    assert(unreachable.head.isNullAt(unreachable.head.fieldIndex("hopIndex")))
    assertEquals(found("w|a|a").head.getAs[String]("status"), "trivial")
    assertEquals(found("w|a|a").head.getAs[Integer]("pathLength").intValue, 0)
    assertEquals(
      found("w|a|zz").head.getAs[String]("status"),
      "endpoint-not-in-window"
    )
  }

  test("hop bound is respected") {
    val found = evidence(Vector(("w", "a", "e"), ("w", "b", "d")), 3)
    assertEquals(
      found("w|a|e").map(_.getAs[String]("status")),
      Vector("unreachable-within-bound")
    )
    assertEquals(found("w|a|e").head.getAs[Integer]("hopLimit").intValue, 3)
    assertEquals(
      found("w|b|d").map(_.getAs[String]("status")).distinct,
      Vector("found")
    )
  }

  test("unreachable pairs say whether they are apart or beyond the hop limit") {
    def reason(rowsOfPair: Vector[Row]): Option[String] =
      Option(rowsOfPair.head.getAs[String]("unreachableReason"))
    val found = evidence(
      Vector(
        ("w", "a", "g"),
        ("w", "a", "e"),
        ("w", "a", "d"),
        ("w", "a", "a"),
        ("w", "a", "zz")
      ),
      3
    )
    // a and g lie in different components of the window graph.
    assertEquals(reason(found("w|a|g")), Some("different-component"))
    // a and e share a component but are 4 hops apart with a limit of 3.
    assertEquals(
      found("w|a|e").head.getAs[String]("status"),
      "unreachable-within-bound"
    )
    assertEquals(reason(found("w|a|e")), Some("beyond-hop-limit"))
    // Found, trivial and out-of-window pairs have no reason.
    assertEquals(found("w|a|d").head.getAs[String]("status"), "found")
    assertEquals(reason(found("w|a|d")), None)
    assertEquals(reason(found("w|a|a")), None)
    assertEquals(reason(found("w|a|zz")), None)
  }

  test("path status counts split unreachable pairs by reason, per window") {
    val context = algo()
    val selected = Vector(
      ("w", "a", "e"),
      ("w", "a", "d"),
      ("w", "b", "d"),
      ("w", "a", "g"),
      ("w", "a", "a"),
      ("w", "a", "zz"),
      ("d", "s", "t")
    )
    val evidenceFrame = PathSearch.evidence(
      context,
      pairs(selected*),
      line.nodes,
      line.adj,
      line.edges,
      Components.compute(context, line.nodes, line.adj).frame,
      3
    )
    val counts = PathReport.statusCounts(context, evidenceFrame)
    assertEquals(
      counts.columns.toVector,
      Vector("window", "status", "unreachableReason", "pairs")
    )
    // Hop limit 3. Window w: a-e is 4 hops (beyond-hop-limit), a-d 3 and b-d 2
    // (found; counted once each although a-d has 3 evidence rows), a-g in
    // different components, a-a trivial, a-zz endpoint outside the window.
    // Window d: s-t is 2 hops (found).
    assertEquals(
      rows(counts).map(PathReport.line),
      Vector(
        "[d, found, null, 1]",
        "[w, endpoint-not-in-window, null, 1]",
        "[w, found, null, 2]",
        "[w, trivial, null, 1]",
        "[w, unreachable-within-bound, beyond-hop-limit, 1]",
        "[w, unreachable-within-bound, different-component, 1]"
      )
    )
    val json = PathReport.json(counts.columns, rows(counts))
    assertEquals(json.arr.size, 6)
    assertEquals(
      json.arr(5).obj.map((k, v) => k -> v.toString).toMap,
      Map(
        "window" -> "\"w\"",
        "status" -> "\"unreachable-within-bound\"",
        "unreachableReason" -> "\"different-component\"",
        "pairs" -> "1"
      )
    )
    assertEquals(json.arr(0)("unreachableReason"), ujson.Null)
  }

  test("path length estimate lines carry every column and the window bounds") {
    val graph = Graph.build(
      session,
      Vector(
        (
          "w",
          Vector("a", "b", "c", "d", "e"),
          Vector(("a", "b", 1L), ("b", "c", 1L), ("c", "d", 1L), ("d", "e", 1L))
        )
      )
    )
    val context = algo()
    val labels = Components.compute(context, graph.nodes, graph.adj).frame
    val (_, components, _) = Components.tables(context, labels, graph.edges)
    val estimates =
      PathSearch.estimates(context, labels, components, graph.adj, 10, 64)
    val windows = frame("window STRING, windowStart INT, windowEnd INT")(
      Row("w", 1, 9999)
    )
    val report = PathReport.lengthEstimates(context, estimates, windows)
    assertEquals(
      report.columns.toVector,
      estimates.columns.toVector ++ Vector("windowStart", "windowEnd")
    )
    // 5 nodes on a path: 20 ordered pairs, 8 at distance 1, 6 at 2, 4 at 3 and
    // 2 at 4; mean (8 + 12 + 12 + 8) / 20 = 2.0; 90 percent of 20 is 18, first
    // reached by the cumulative 8, 14, 18, so the effective diameter is 3.
    assertEquals(
      rows(report).map(PathReport.line),
      Vector("[w, a, 5, 5, 10, true, 20, 2.0, 3, 4, 0, 42, 64, 1, 9999]")
    )
  }

  test("deterministic tie-break") {
    val first = evidence(Vector(("d", "s", "t")), 4)
    assertEquals(hops(first("d|s|t")), Vector(("s", "x"), ("x", "t")))
    val second = evidence(Vector(("d", "s", "t")), 4)
    assertEquals(hops(second("d|s|t")), hops(first("d|s|t")))
  }

  test("sampled path length and effective diameter on a path graph") {
    val graph = Graph.build(
      session,
      Vector(
        (
          "w",
          Vector("a", "b", "c", "d", "e"),
          Vector(("a", "b", 1L), ("b", "c", 1L), ("c", "d", 1L), ("d", "e", 1L))
        )
      )
    )
    val context = algo()
    val labels = Components.compute(context, graph.nodes, graph.adj).frame
    val (_, components, _) = Components.tables(context, labels, graph.edges)
    val exact = rows(
      PathSearch.estimates(context, labels, components, graph.adj, 10, 64)
    )
    assertEquals(exact.size, 1)
    val row = exact.head
    assertEquals(row.getAs[Long]("sampledSources"), 5L)
    assertEquals(row.getAs[Boolean]("allNodesSampled"), true)
    assertEquals(row.getAs[Long]("pairsMeasured"), 20L)
    assertEqualsDouble(row.getAs[Double]("averagePathLength"), 2.0, 1e-12)
    assertEquals(row.getAs[Integer]("effectiveDiameter90").intValue, 3)
    assertEquals(row.getAs[Integer]("maxObservedDistance").intValue, 4)
    assertEquals(row.getAs[Long]("unreachedPairsDueToDepthCap"), 0L)
    val sampled = rows(
      PathSearch.estimates(context, labels, components, graph.adj, 2, 64)
    ).head
    assertEquals(sampled.getAs[Long]("sampledSources"), 2L)
    assertEquals(sampled.getAs[Boolean]("allNodesSampled"), false)
    assertEquals(sampled.getAs[Long]("pairsMeasured"), 8L)
    val again = rows(
      PathSearch.estimates(context, labels, components, graph.adj, 2, 64)
    ).head
    assertEquals(
      again.getAs[Double]("averagePathLength"),
      sampled.getAs[Double]("averagePathLength")
    )
  }
