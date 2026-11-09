package scholarchain.storage

import scholarchain.core.{
  ContractSchema,
  EvidenceBundle,
  EvidenceItem,
  EvidenceType,
  Provenance,
  SourceType,
  StableIds,
  YearWindow
}

/** Phase 9 read-side ports: metric reader, path port, component and community
  * ports, researcher directory, coauthor context and audit write helper.
  */
class ReadPortsSuite extends munit.FunSuite:
  import Fixtures.*

  private val names = TableNames("scholarchain-test")
  private val bounds = LoadBounds.Default
  private val window2000 = YearWindow(2000, 2009)
  private val window2010 = YearWindow(2010, 2019)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val r3: String =
    StableIds.researcherFromExternal("orcid", "0000-0003")
  private val r4: String =
    StableIds.researcherFromExternal("orcid", "0000-0004")
  private val r5: String =
    StableIds.researcherFromExternal("orcid", "0000-0005")

  private def prov(table: String, record: String): Provenance =
    Rows.provenance(Map("provenance" -> provenance(table, record)))

  // ---- item 1: metric cache reader ------------------------------------

  private val otherRun = "run:v1:22222222-2222-4222-8222-222222222222"

  private def metricsIn(run: String, id: String, label: String, w: YearWindow) =
    val p = provenance("researcher-metrics", s"$id|$label")
    p("runId") = run
    metricsRow(id, label, w.startYear, w.endYear) + ("provenance" -> p)

  private def cache(): InMemoryDynamoTable =
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    DynamoRunLoader.putMetrics(
      t,
      metricsRow(r1, "2000-2009", 2000, 2009),
      runId,
      9L
    )
    DynamoRunLoader.putMetrics(
      t,
      metricsRow(r1, "2010-2019", 2010, 2019),
      runId,
      9L
    )
    DynamoRunLoader.putMetrics(
      t,
      metricsRow(r2, "2000-2009", 2000, 2009),
      runId,
      9L
    )
    t

  test("metric cache: present returns core metrics with run and transform") {
    val found = ResearcherMetricCache.get(cache(), r1, window2000, Some(runId))
    val hit = found.getOrElse(fail("expected metrics"))
    assertEquals(hit.metrics.researcherId, r1)
    assertEquals(hit.metrics.window, window2000)
    assertEquals(hit.metrics.publicationCount, 2L)
    assertEquals(hit.runId, runId)
    assertEquals(hit.sparkTransformVersion, "spark-analytics/1.1.0")
    assertEquals(
      hit.storageTransformVersion,
      Some(StorageProvenance.TransformVersion)
    )
    assertEquals(hit.metrics.provenance.runId, runId)
  }

  test("metric cache: absent researcher, window or run is None, no default") {
    val t = cache()
    assertEquals(ResearcherMetricCache.get(t, r3, window2000, None), None)
    assertEquals(
      ResearcherMetricCache.get(t, r1, YearWindow(1990, 1999), None),
      None
    )
    assertEquals(
      ResearcherMetricCache.get(t, r1, window2000, Some(otherRun)),
      None
    )
    assertEquals(ResearcherMetricCache.windows(t, r3, 10).items, Vector.empty)
  }

  test("metric cache: one run answers without a run ID, two need one") {
    val t = cache()
    assert(ResearcherMetricCache.get(t, r1, window2000, None).isDefined)
    DynamoRunLoader.putMetrics(
      t,
      metricsIn(otherRun, r1, "2000-2009", window2000),
      otherRun,
      9L
    )
    assertEquals(
      code(ResearcherMetricCache.get(t, r1, window2000, None)),
      ErrorCode.AmbiguousRun
    )
    val both = ResearcherMetricCache.find(t, r1, window2000, 10)
    assertEquals(both.items.map(_.runId).toSet, Set(runId, otherRun))
    assertEquals(
      ResearcherMetricCache
        .get(t, r1, window2000, Some(otherRun))
        .map(_.runId),
      Some(otherRun)
    )
  }

  test("metric cache: window listing is bounded and flags truncation") {
    val t = cache()
    val all = ResearcherMetricCache.windows(t, r1, 10)
    assertEquals(
      all.items.map(_.metrics.window),
      Vector(window2000, window2010)
    )
    assert(!all.truncated)
    val one = ResearcherMetricCache.windows(t, r1, 1)
    assertEquals(one.items.size, 1)
    assert(one.truncated)
    assertEquals(
      code(ResearcherMetricCache.windows(t, r1, 0)),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(
        ResearcherMetricCache
          .windows(t, r1, ReadLimits.MaxMetricRows + 1)
      ),
      ErrorCode.InvalidQuery
    )
  }

  test("metric cache: an item stored under another key is refused") {
    val source = cache()
    val moved = source
      .get(
        s"RESEARCHER#$r2",
        "WINDOW#2000-2009#RUN#" +
          RunDirectory.segment(runId)
      )
      .get
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    t.putIfAbsent(moved + ("pk" -> ujson.Str(s"RESEARCHER#$r1")))
    assertEquals(
      code(ResearcherMetricCache.get(t, r1, window2000, Some(runId))),
      ErrorCode.MalformedRow
    )
    val garbled = moved + ("metricsJson" -> ujson.Str("{not json"))
    val u = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    u.putIfAbsent(garbled)
    assertEquals(
      code(ResearcherMetricCache.get(u, r2, window2000, Some(runId))),
      ErrorCode.MalformedRow
    )
  }

  // ---- item 2: path port -----------------------------------------------

  private def hop(
      label: String,
      source: String,
      target: String,
      index: Int,
      from: String,
      to: String,
      length: Int
  ): Row =
    Map(
      "pairKey" -> ujson.Str(s"$label|$source|$target"),
      "window" -> ujson.Str(label),
      "sourceId" -> ujson.Str(source),
      "targetId" -> ujson.Str(target),
      "status" -> ujson.Str("found"),
      "unreachableReason" -> ujson.Null,
      "pathLength" -> ujson.Num(length),
      "hopLimit" -> ujson.Num(6),
      "hopIndex" -> ujson.Num(index),
      "fromId" -> ujson.Str(from),
      "toId" -> ujson.Str(to),
      "edgeId" -> ujson.Str(edgeId(from, to)),
      "jointPublicationCount" -> ujson.Num(2),
      "firstCollaborationYear" -> ujson.Num(2001),
      "lastCollaborationYear" -> ujson.Num(2005),
      "supportingWorkIds" -> ujson.Arr(ujson.Str(w1), ujson.Str(w2)),
      "provenance" -> provenance("path-evidence", s"$source|$target|$index")
    )

  private def status(
      label: String,
      source: String,
      target: String,
      state: String,
      reason: Option[String]
  ): Row =
    Map(
      "pairKey" -> ujson.Str(s"$label|$source|$target"),
      "window" -> ujson.Str(label),
      "sourceId" -> ujson.Str(source),
      "targetId" -> ujson.Str(target),
      "status" -> ujson.Str(state),
      "unreachableReason" -> reason.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "hopLimit" -> ujson.Num(6),
      "provenance" -> provenance("path-evidence", s"$source|$target")
    )

  private val label = "2000-2009"

  private def pathRows: Vector[Row] = Vector(
    hop(label, r1, r3, 1, r1, r2, 2),
    hop(label, r1, r3, 2, r2, r3, 2),
    status(label, r1, r1, "trivial", None),
    status(label, r1, r4, "endpoint-not-in-window", None),
    status(
      label,
      r1,
      r5,
      "unreachable-within-bound",
      Some("different-component")
    ),
    status(label, r2, r5, "unreachable-within-bound", Some("beyond-hop-limit")),
    status(label, r3, r5, "unreachable-within-bound", None)
  )

  private def paths(
      rows: Vector[Row] = pathRows,
      cost: PathQuery => Long = _ => 0L
  ): InMemoryPathEvidence =
    InMemoryPathEvidence.from(
      source(Map("path-evidence" -> rows)),
      bounds,
      cost
    )

  private def query(
      s: String,
      t: String,
      depth: Int = 6,
      results: Int = 10,
      timeout: Long = 1000L
  ) = PathQuery(label, s, t, depth, results, timeout)

  test("path: a found path returns ordered hops with weight, works, years") {
    paths().shortestPath(query(r1, r3)) match
      case PathLookup.Found(w, s, t, hops) =>
        assertEquals((w, s, t), (label, r1, r3))
        assertEquals(
          hops.map(h => (h.fromId, h.toId)),
          Vector((r1, r2), (r2, r3))
        )
        assertEquals(hops.head.edgeId, edgeId(r1, r2))
        assertEquals(hops.head.jointPublicationCount, 2L)
        assertEquals(hops.head.supportingWorkIds, Vector(w1, w2))
        assertEquals(hops.head.firstCollaborationYear, Some(2001))
        assertEquals(hops.head.lastCollaborationYear, Some(2005))
        assertEquals(hops.head.provenance.runId, runId)
      case other => fail(s"expected Found, got $other")
  }

  test("path: the reverse orientation returns flipped, reordered hops") {
    paths().shortestPath(query(r3, r1)) match
      case PathLookup.Found(_, s, t, hops) =>
        assertEquals((s, t), (r3, r1))
        assertEquals(
          hops.map(h => (h.fromId, h.toId)),
          Vector((r3, r2), (r2, r1))
        )
        assertEquals(hops.map(_.edgeId), Vector(edgeId(r2, r3), edgeId(r1, r2)))
      case other => fail(s"expected Found, got $other")
  }

  test(
    "path: each non-path status is typed, only a component split is no path"
  ) {
    val p = paths()
    def kind(s: String, t: String) = p.shortestPath(query(s, t)) match
      case n: PathLookup.NonPath => n
      case other                 => fail(s"expected NonPath, got $other")
    assertEquals(kind(r1, r1).kind, NonPathKind.Trivial)
    assertEquals(kind(r1, r4).kind, NonPathKind.EndpointNotInWindow)
    val split = kind(r1, r5)
    assertEquals(split.status, "unreachable-within-bound")
    assertEquals(split.unreachableReason, Some("different-component"))
    assertEquals(split.kind, NonPathKind.DifferentComponent)
    assert(split.provenance.runId == runId)
    val far = kind(r2, r5)
    assertEquals(far.unreachableReason, Some("beyond-hop-limit"))
    assertEquals(far.kind, NonPathKind.BeyondHopLimit)
    val bare = kind(r3, r5)
    assertEquals(bare.unreachableReason, None)
    assertEquals(bare.kind, NonPathKind.UnreachableUnexplained)
    val verdicts = Vector(r1 -> r1, r1 -> r4, r1 -> r5, r2 -> r5, r3 -> r5)
      .map((s, t) => p.shortestPath(query(s, t)).noPathExists)
    assertEquals(verdicts, Vector(false, false, true, false, false))
    assert(!p.shortestPath(query(r1, r3)).noPathExists)
  }

  test("path: an unselected pair is NotPrecomputed, not a missing path") {
    paths().shortestPath(query(r2, r4)) match
      case PathLookup.NotPrecomputed(w, s, t) =>
        assertEquals((w, s, t), (label, r2, r4))
      case other => fail(s"expected NotPrecomputed, got $other")
    assert(!paths().shortestPath(query(r2, r4)).noPathExists)
    val other = paths().shortestPath(PathQuery("all", r1, r3, 6, 10, 1000L))
    assert(other.isInstanceOf[PathLookup.NotPrecomputed])
  }

  test("path: depth and result bounds answer typed, nothing is truncated") {
    val p = paths()
    assertEquals(
      p.shortestPath(query(r1, r3, depth = 1)),
      PathLookup.ExceedsDepth(2, 1)
    )
    assertEquals(
      p.shortestPath(query(r1, r3, results = 1)),
      PathLookup.ExceedsResults(2, 1)
    )
    assert(p.shortestPath(query(r1, r3, depth = 2, results = 2)) match
      case PathLookup.Found(_, _, _, hops) => hops.size == 2
      case _                               => false)
  }

  test("path: the timeout hook answers TimedOut without sleeping") {
    val slow = paths(cost = q => if q.targetId == r3 then 5000L else 1L)
    assertEquals(
      slow.shortestPath(query(r1, r3, timeout = 100L)),
      PathLookup.TimedOut(100L, 5000L)
    )
    assert(
      slow
        .shortestPath(query(r1, r3, timeout = 5000L))
        .isInstanceOf[PathLookup.Found]
    )
    assert(!slow.shortestPath(query(r1, r3, timeout = 100L)).noPathExists)
  }

  test("path: query bounds and endpoints are validated") {
    assertEquals(code(query(r1, r3, depth = 0)), ErrorCode.InvalidQuery)
    assertEquals(
      code(query(r1, r3, depth = ReadLimits.MaxPathDepth + 1)),
      ErrorCode.InvalidQuery
    )
    assertEquals(code(query(r1, r3, results = 0)), ErrorCode.InvalidQuery)
    assertEquals(
      code(query(r1, r3, results = ReadLimits.MaxPathResults + 1)),
      ErrorCode.InvalidQuery
    )
    assertEquals(code(query(r1, r3, timeout = 0L)), ErrorCode.InvalidQuery)
    assertEquals(
      code(query(r1, r3, timeout = ReadLimits.MaxPathTimeoutMillis + 1)),
      ErrorCode.InvalidQuery
    )
    assertEquals(code(query("Cypher; DROP", r3)), ErrorCode.InvalidQuery)
  }

  test("path: malformed pair rows are refused") {
    val gap =
      Vector(hop(label, r1, r3, 1, r1, r2, 2), hop(label, r1, r3, 3, r2, r3, 2))
    assertEquals(code(paths(gap)), ErrorCode.MalformedRow)
    val broken = Vector(
      hop(label, r1, r3, 1, r1, r2, 2),
      hop(label, r1, r3, 2, r4, r3, 2)
    )
    assertEquals(code(paths(broken)), ErrorCode.MalformedRow)
    val wrongEnd = Vector(hop(label, r1, r3, 1, r1, r2, 1))
    assertEquals(code(paths(wrongEnd)), ErrorCode.MalformedRow)
    val wrongLength = Vector(
      hop(label, r1, r3, 1, r1, r2, 5),
      hop(label, r1, r3, 2, r2, r3, 5)
    )
    assertEquals(code(paths(wrongLength)), ErrorCode.MalformedRow)
    val mixed = pathRows :+ status(label, r1, r3, "trivial", None)
    assertEquals(code(paths(mixed)), ErrorCode.MalformedRow)
    val twice =
      pathRows :+ status(label, r1, r4, "endpoint-not-in-window", None)
    assertEquals(code(paths(twice)), ErrorCode.DuplicateId)
    val badKey =
      Vector(hop(label, r1, r3, 1, r1, r2, 1) + ("pairKey" -> ujson.Str("x")))
    assertEquals(code(paths(badKey)), ErrorCode.MalformedRow)
    val badStatus = Vector(status(label, r1, r4, "found", None))
    assertEquals(code(paths(badStatus)), ErrorCode.MalformedRow)
    val badReason =
      Vector(status(label, r1, r4, "trivial", Some("beyond-hop-limit")))
    assertEquals(code(paths(badReason)), ErrorCode.MalformedRow)
  }

  test("path: the answer does not depend on row order (rerun determinism)") {
    val forward = paths(pathRows)
    val backward = paths(pathRows.reverse)
    val pairs = Vector(r1 -> r3, r3 -> r1, r1 -> r1, r1 -> r5, r2 -> r4)
    pairs.foreach { (s, t) =>
      assertEquals(
        forward.shortestPath(query(s, t)),
        backward.shortestPath(query(s, t))
      )
      assertEquals(
        forward.shortestPath(query(s, t)),
        forward.shortestPath(query(s, t))
      )
    }
  }

  // ---- item 3: component and community ports ---------------------------

  private def comp(id: String, size: Int, largest: Boolean): Row = Map(
    "window" -> ujson.Str(label),
    "componentId" -> ujson.Str(id),
    "componentSize" -> ujson.Num(size),
    "isLargest" -> ujson.Bool(largest),
    "provenance" -> provenance("components", id)
  )

  private def member(
      group: String,
      groupCol: String,
      id: String,
      size: Int
  ): Row =
    Map(
      "window" -> ujson.Str(label),
      "researcherId" -> ujson.Str(id),
      groupCol -> ujson.Str(group),
      groupCol.stripSuffix("Id") + "Size" -> ujson.Num(size),
      "provenance" -> provenance("membership", id)
    )

  private val componentTables: Map[String, Vector[Row]] = Map(
    "components" -> Vector(comp(r1, 3, true), comp(r4, 1, false)),
    "component-membership" -> Vector(
      member(r1, "componentId", r1, 3),
      member(r1, "componentId", r2, 3),
      member(r1, "componentId", r3, 3),
      member(r4, "componentId", r4, 1)
    )
  )

  private def components(
      tables: Map[String, Vector[Row]] = componentTables
  ): InMemoryComponents = InMemoryComponents.from(source(tables), bounds)

  test("components: size, largest flag, provenance, researcher lookup") {
    val c = components()
    val info = c.component(label, r1).getOrElse(fail("component"))
    assertEquals((info.size, info.isLargest), (3L, true))
    assertEquals(info.provenance.runId, runId)
    assertEquals(c.component(label, r4).map(_.isLargest), Some(false))
    assertEquals(c.componentOf(label, r2).map(_.componentId), Some(r1))
    assertEquals(c.componentOf(label, r4).map(_.size), Some(1L))
  }

  test("components: absent window, component or researcher is None") {
    val c = components()
    assertEquals(c.component("all", r1), None)
    assertEquals(c.component(label, r5), None)
    assertEquals(c.componentOf(label, r5), None)
    assertEquals(c.members(label, r5, None, 10), None)
  }

  test("components: member pages are bounded, ordered and keyset-paged") {
    val c = components()
    val sorted = Vector(r1, r2, r3).sorted
    val first = c.members(label, r1, None, 2).get
    assertEquals(first.members, sorted.take(2))
    assertEquals((first.total, first.nextAfter), (3L, Some(sorted(1))))
    val second = c.members(label, r1, first.nextAfter, 2).get
    assertEquals(second.members, sorted.drop(2))
    assertEquals(second.nextAfter, None)
    assertEquals(code(c.members(label, r1, None, 0)), ErrorCode.InvalidQuery)
    assertEquals(
      code(c.members(label, r1, None, ReadLimits.MaxPage + 1)),
      ErrorCode.InvalidQuery
    )
  }

  test("components: malformed or inconsistent tables are refused") {
    val badSize = componentTables.updated(
      "components",
      Vector(comp(r1, 4, true), comp(r4, 1, false))
    )
    assertEquals(code(components(badSize)), ErrorCode.RowCountMismatch)
    val twice = componentTables.updated(
      "component-membership",
      componentTables("component-membership") :+ member(
        r4,
        "componentId",
        r2,
        1
      )
    )
    assertEquals(code(components(twice)), ErrorCode.DuplicateId)
    val orphan = componentTables.updated(
      "component-membership",
      componentTables("component-membership") :+ member(
        r5,
        "componentId",
        r5,
        1
      )
    )
    assertEquals(code(components(orphan)), ErrorCode.MissingEndpoint)
    val dupComp = componentTables.updated(
      "components",
      componentTables("components") :+ comp(r1, 3, true)
    )
    assertEquals(code(components(dupComp)), ErrorCode.DuplicateId)
    val notId = componentTables.updated(
      "component-membership",
      componentTables("component-membership").map(
        _ + ("researcherId" -> ujson.Str("zzz"))
      )
    )
    assertEquals(code(components(notId)), ErrorCode.MalformedRow)
    val tight = LoadBounds(1L, 10, 10L)
    assertEquals(
      code(InMemoryComponents.from(source(componentTables), tight)),
      ErrorCode.BoundExceeded
    )
  }

  private val ids = Vector(r1, r3, r4).sorted
  private val c0 = ids(0)
  private val c1 = ids(1)
  private val c2 = ids(2)

  private def community(
      id: String,
      size: Int,
      internal: Int,
      cross: Int
  ): Row = Map(
    "window" -> ujson.Str(label),
    "communityId" -> ujson.Str(id),
    "communitySize" -> ujson.Num(size),
    "internalEdges" -> ujson.Num(internal),
    "crossEdges" -> ujson.Num(cross),
    "internalWeight" -> ujson.Num(internal * 2),
    "crossWeight" -> ujson.Num(cross),
    "provenance" -> provenance("communities", id)
  )

  private def cross(a: String, b: String, edges: Int, weight: Int): Row = Map(
    "window" -> ujson.Str(label),
    "communityA" -> ujson.Str(a),
    "communityB" -> ujson.Str(b),
    "edgeCount" -> ujson.Num(edges),
    "totalWeight" -> ujson.Num(weight),
    "provenance" -> provenance("community-cross-edges", s"$a|$b")
  )

  private val communityTables: Map[String, Vector[Row]] = Map(
    "communities" -> Vector(
      community(c0, 2, 1, 3),
      community(c1, 1, 0, 2),
      community(c2, 1, 0, 1)
    ),
    "community-membership" -> Vector(
      member(c0, "communityId", c0, 2),
      member(c0, "communityId", r2, 2),
      member(c1, "communityId", c1, 1),
      member(c2, "communityId", c2, 1)
    ),
    "community-cross-edges" -> Vector(cross(c0, c1, 2, 3), cross(c0, c2, 1, 1))
  )

  private def communities(
      tables: Map[String, Vector[Row]] = communityTables
  ): InMemoryCommunities = InMemoryCommunities.from(source(tables), bounds)

  test("communities: size, edges, members, lookup by researcher") {
    val c = communities()
    val info = c.community(label, c0).getOrElse(fail("community"))
    assertEquals((info.size, info.internalEdges, info.crossEdges), (2L, 1L, 3L))
    assertEquals((info.internalWeight, info.crossWeight), (2L, 3L))
    assertEquals(info.provenance.runId, runId)
    assertEquals(c.communityOf(label, r2).map(_.communityId), Some(c0))
    assertEquals(
      c.members(label, c0, None, 10).map(_.members),
      Some(Vector(c0, r2).sorted)
    )
    assertEquals(c.crossCommunityEdgeCount(label, c0), Some(3L))
    assertEquals(c.crossCommunityEdgeCount(label, c2), Some(1L))
  }

  test("communities: neighbors are the most connected first and bounded") {
    val c = communities()
    val page = c.neighbors(label, c0, 10).get
    assertEquals(page.neighbors.map(_.neighborId), Vector(c1, c2))
    assertEquals(page.neighbors.map(_.edgeCount), Vector(2L, 1L))
    assertEquals(page.neighbors.head.totalWeight, 3L)
    assertEquals((page.total, page.truncated), (2, false))
    val tight = c.neighbors(label, c0, 1).get
    assertEquals(tight.neighbors.map(_.neighborId), Vector(c1))
    assertEquals((tight.total, tight.truncated), (2, true))
    assertEquals(
      c.neighbors(label, c1, 10).get.neighbors.map(_.neighborId),
      Vector(c0)
    )
    assertEquals(code(c.neighbors(label, c0, 0)), ErrorCode.InvalidQuery)
  }

  test("communities: absent window, community or researcher is None") {
    val c = communities()
    assertEquals(c.community("all", c0), None)
    assertEquals(c.community(label, r5), None)
    assertEquals(c.communityOf(label, r5), None)
    assertEquals(c.members(label, r5, None, 5), None)
    assertEquals(c.crossCommunityEdgeCount(label, r5), None)
    assertEquals(c.neighbors(label, r5, 5), None)
  }

  test("communities: member pages are bounded and keyset-paged") {
    val c = communities()
    val sorted = Vector(c0, r2).sorted
    val first = c.members(label, c0, None, 1).get
    assertEquals(
      (first.members, first.nextAfter),
      (sorted.take(1), sorted.headOption)
    )
    assertEquals(
      c.members(label, c0, first.nextAfter, 1).get.members,
      sorted.drop(1)
    )
    assertEquals(code(c.members(label, c0, None, 0)), ErrorCode.InvalidQuery)
  }

  test("communities: inconsistent tables are refused") {
    val sum = communityTables.updated(
      "communities",
      Vector(
        community(c0, 2, 1, 4),
        community(c1, 1, 0, 2),
        community(c2, 1, 0, 1)
      )
    )
    assertEquals(code(communities(sum)), ErrorCode.RowCountMismatch)
    val noLinks = communityTables.updated("community-cross-edges", Vector.empty)
    assertEquals(code(communities(noLinks)), ErrorCode.RowCountMismatch)
    val unordered = communityTables.updated(
      "community-cross-edges",
      Vector(cross(c1, c0, 2, 3), cross(c0, c2, 1, 1))
    )
    assertEquals(code(communities(unordered)), ErrorCode.MalformedRow)
    val unknown = communityTables.updated(
      "community-cross-edges",
      communityTables("community-cross-edges") :+ cross(c2, c2 + "z", 1, 1)
    )
    assertEquals(code(communities(unknown)), ErrorCode.MissingEndpoint)
    val twice = communityTables.updated(
      "community-cross-edges",
      communityTables("community-cross-edges") :+ cross(c0, c1, 2, 3)
    )
    assertEquals(code(communities(twice)), ErrorCode.DuplicateId)
    val size = communityTables.updated(
      "communities",
      Vector(
        community(c0, 3, 1, 3),
        community(c1, 1, 0, 2),
        community(c2, 1, 0, 1)
      )
    )
    assertEquals(code(communities(size)), ErrorCode.RowCountMismatch)
    val negative = communityTables.updated(
      "communities",
      Vector(
        community(c0, 2, 1, 3),
        community(c1, 1, 0, 2),
        community(c2, 1, 0, 1).updated("crossEdges", ujson.Num(-1))
      )
    )
    assertEquals(code(communities(negative)), ErrorCode.MalformedRow)
  }

  // ---- item 4: researcher directory ------------------------------------

  private def node(id: String, kind: String, name: String): Row =
    researcherNode(id, kind, name)

  private val directoryRows: Vector[Row] = Vector(
    node(r1, "canonical", "Ada Lovelace") + ("orcid" -> ujson.Str("0000-0001")),
    node(r2, "canonical", "Lovelace, Ada"),
    node(m1, "unresolved-mention", "Ada Lovelace") + ("mentionCount" -> ujson
      .Num(3)),
    node(r3, "canonical", "José García") + ("orcid" -> ujson.Str("0000-0009")),
    node(r4, "canonical", "Jose Garcia") + ("orcid" -> ujson.Str("0000-0009"))
      + ("linkStatus" -> ujson.Str("accepted")) + ("idRule" -> ujson.Str(
        "orcid"
      )),
    node(r5, "canonical", "Grace Hopper") + ("displayName" -> ujson.Null)
  )

  private def directory(rows: Vector[Row] = directoryRows) =
    new InMemoryResearcherDirectory(rows)

  test("directory: ambiguity returns every candidate, mentions marked") {
    val result = directory().candidates("ada lovelace", 10)
    assertEquals(result.total, 3)
    assertEquals(result.normalizedQuery, "ada lovelace")
    val byId = result.candidates.map(c => c.nodeId -> c).toMap
    assertEquals(byId.keySet, Set(r1, r2, m1))
    assertEquals(byId(r1).matchKind, NameMatch.Exact)
    assertEquals(byId(m1).matchKind, NameMatch.Exact)
    assertEquals(byId(r2).matchKind, NameMatch.TokenSet)
    assert(byId(m1).isMention && !byId(r1).isMention)
    assertEquals(byId(m1).nodeKind, "unresolved-mention")
    assertEquals(byId(m1).mentionCount, 3L)
    assertEquals(byId(r1).orcid, Some("0000-0001"))
    assertEquals(
      result.candidates.map(_.matchKind),
      Vector(NameMatch.Exact, NameMatch.Exact, NameMatch.TokenSet)
    )
    assert(!result.truncated)
  }

  test("directory: accents, case and punctuation fold; ORCID sharing shows") {
    val result = directory().candidates("JOSÉ  García!", 10)
    assertEquals(result.candidates.map(_.nodeId).toSet, Set(r3, r4))
    result.candidates.foreach(c => assertEquals(c.sharesOrcidWith, 1))
    val folded = result.candidates.find(_.nodeId == r4).get
    assertEquals(folded.linkStatus, Some("accepted"))
    assertEquals(folded.idRule, Some("orcid"))
    assertEquals(folded.provenance.runId, runId)
  }

  test("directory: an unknown name is empty and affiliation is unavailable") {
    val result = directory().candidates("Nobody Atall", 10)
    assertEquals(result.candidates, Vector.empty)
    assertEquals(result.total, 0)
    assert(!result.affiliationAvailable)
    assert(result.note.contains("affiliation is unavailable"))
    assertEquals(directory().byId(r5).map(_.nodeId), Some(r5))
    assertEquals(directory().byId("researcher:v1:none"), None)
    assertEquals(directory().unnamedCount, 1)
  }

  test("directory: the candidate list is bounded and flags truncation") {
    val result = directory().candidates("Ada Lovelace", 2)
    assertEquals(result.candidates.size, 2)
    assertEquals(result.total, 3)
    assert(result.truncated)
    assertEquals(
      code(directory().candidates("Ada", 0)),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(directory().candidates("Ada", ReadLimits.MaxCandidates + 1)),
      ErrorCode.InvalidQuery
    )
    assertEquals(code(directory().candidates("!!!", 5)), ErrorCode.InvalidQuery)
  }

  test("directory: malformed rows are refused") {
    assertEquals(
      code(directory(Vector(node("zzz", "canonical", "X")))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(directory(Vector(node(r1, "unresolved-mention", "X")))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(directory(Vector(node(m1, "canonical", "X")))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(
        directory(
          Vector(node(r1, "canonical", "X"), node(r1, "canonical", "Y"))
        )
      ),
      ErrorCode.DuplicateId
    )
    assertEquals(
      code(
        directory(
          Vector(node(r1, "canonical", "X") + ("memberCount" -> ujson.Num(-1)))
        )
      ),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(directory(Vector(node(r1, "canonical", "X") - "provenance"))),
      ErrorCode.MalformedRow
    )
  }

  test("directory: from a table source honors the row bound") {
    val tables = Map("nodes-researcher" -> directoryRows)
    val d = InMemoryResearcherDirectory.from(source(tables), bounds)
    assertEquals(d.candidates("Grace Hopper", 5).total, 0)
    assertEquals(
      code(
        InMemoryResearcherDirectory
          .from(source(tables), LoadBounds(1L, 10, 10L))
      ),
      ErrorCode.BoundExceeded
    )
  }

  test("coauthor context: coauthorsOf results typed, mentions included") {
    val projection = new SparkGraphProjector(source(), bounds).project()
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(projection, runId, store)
    val graph = new InMemoryNeptune(store)
    graph.load(report.manifestKey)
    val links = CoauthorContext.of(graph, r1, "2000-2009", 10)
    assertEquals(links.map(_.coauthorId), Vector(r2, m1))
    assertEquals(links.head.jointPublicationCount, 2L)
    assertEquals(
      (links.head.firstCollaborationYear, links.head.lastCollaborationYear),
      (2001, 2005)
    )
    assertEquals(links.head.edgeId, edgeId(r1, r2))
    assert(links.head.evidencePointer.startsWith("edge-support:"))
    assertEquals(CoauthorContext.of(graph, r1, "1999-1999", 10), Vector.empty)
    assertEquals(
      code(CoauthorContext.of(graph, r1, "2000-2009", 0)),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(CoauthorContext.of(graph, "not an id", "2000-2009", 5)),
      ErrorCode.InvalidQuery
    )
  }

  // ---- item 6: the catalog stays closed --------------------------------

  test("cypher catalog stays closed: no shortest-path template is answered") {
    val projection = new SparkGraphProjector(source(), bounds).project()
    val store = new InMemoryObjectStore
    val graph = new InMemoryNeptune(store)
    val report = NeptuneExporter.exportTo(projection, runId, store)
    graph.load(report.manifestKey)
    val shortest = CypherQuery(
      "shortest-path",
      "MATCH p = shortestPath((a)-[*]-(b)) WHERE id(a) = $a RETURN id(a) LIMIT $limit",
      Map("a" -> ujson.Str(r1), "limit" -> ujson.Num(1))
    )
    assertEquals(code(graph.run(shortest)), ErrorCode.QueryRejected)
    assertEquals(
      Set(CypherCatalog.CoauthorsOf, CypherCatalog.EdgeEvidence),
      Set("coauthors-of", "edge-evidence")
    )
  }

  // ---- item 5: audit write helper --------------------------------------

  private val auditProv = Provenance(
    ContractSchema.currentVersion,
    SourceType.Derived,
    "snapshot",
    "record",
    "scholarchain:test",
    "test/1",
    runId
  )

  private def evidence(n: Int): EvidenceItem =
    EvidenceItem(
      ContractSchema.currentVersion,
      StableIds.evidence(
        SourceType.Derived,
        "v1",
        s"rec-$n",
        EvidenceType.Metric,
        "x"
      ),
      EvidenceType.Metric,
      None,
      "scholarchain:test",
      None,
      None,
      1.0,
      None,
      None,
      None,
      auditProv
    )

  private def bundle(id: String): EvidenceBundle =
    EvidenceBundle(
      ContractSchema.currentVersion,
      id,
      Vector(evidence(1), evidence(2)),
      None,
      auditProv
    )

  private def tables() =
    (
      new InMemoryDynamoTable(QuerySession.spec(names)),
      new InMemoryDynamoTable(EvidenceBundleTable.spec(names))
    )

  private def audit(
      ordinal: Int = 1,
      payload: String = """{"answerHash":"abc"}""",
      b: Option[EvidenceBundle] = Some(bundle("bundle-1"))
  ) = AuditRecord("s1", ordinal, payload, b, "q1", runId, 4102444800L)

  test("audit: a turn and its bundle are stored and read back") {
    val (sessions, bundles) = tables()
    val outcome = AuditTrail.record(sessions, bundles, audit())
    assertEquals(
      outcome,
      AuditOutcome(WriteOutcome.Created, Some(WriteOutcome.Created))
    )
    val turn = AuditTrail.turn(sessions, "s1", 1).get
    assertEquals(
      (turn.ordinal, turn.payloadJson),
      (1, """{"answerHash":"abc"}""")
    )
    assertEquals(
      AuditTrail.bundle(bundles, "bundle-1"),
      Some(bundle("bundle-1"))
    )
    assertEquals(AuditTrail.turn(sessions, "s1", 2), None)
    assertEquals(AuditTrail.bundle(bundles, "nope"), None)
  }

  test("audit: repeating a record is Unchanged; a different payload fails") {
    val (sessions, bundles) = tables()
    AuditTrail.record(sessions, bundles, audit())
    assertEquals(
      AuditTrail.record(sessions, bundles, audit()),
      AuditOutcome(WriteOutcome.Unchanged, Some(WriteOutcome.Unchanged))
    )
    assertEquals(
      code(
        AuditTrail.record(sessions, bundles, audit(payload = """{"x":1}"""))
      ),
      ErrorCode.ConditionalCheckFailed
    )
  }

  test("audit: a turn without a bundle, turns read in order, bounded") {
    val (sessions, bundles) = tables()
    AuditTrail.record(sessions, bundles, audit(ordinal = 2, b = None))
    AuditTrail.record(sessions, bundles, audit(ordinal = 1))
    val turns = QuerySession.turns(sessions, "s1", 10)
    assertEquals(turns.map(_.ordinal), Vector(1, 2))
    assertEquals(
      QuerySession.turns(sessions, "s1", 1).map(_.ordinal),
      Vector(1)
    )
    assertEquals(
      code(QuerySession.turns(sessions, "s1", 0)),
      ErrorCode.InvalidQuery
    )
    assertEquals(QuerySession.turns(sessions, "other", 5), Vector.empty)
    assertEquals(
      AuditTrail.record(sessions, bundles, audit(ordinal = 3, b = None)).bundle,
      None
    )
  }

  test("audit: invalid payload, ordinal and run are refused before any write") {
    val (sessions, bundles) = tables()
    assertEquals(
      code(AuditTrail.record(sessions, bundles, audit(payload = "{not json"))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(AuditTrail.record(sessions, bundles, audit(ordinal = -1))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(AuditTrail.record(sessions, bundles, audit(ordinal = 1000000))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(AuditTrail.record(sessions, bundles, audit().copy(runId = "run-1"))),
      ErrorCode.InvalidItem
    )
    assertEquals(AuditTrail.bundle(bundles, "bundle-1"), None)
    assertEquals(QuerySession.turns(sessions, "s1", 10), Vector.empty)
  }
