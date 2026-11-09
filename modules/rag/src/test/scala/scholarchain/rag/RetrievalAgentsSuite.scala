package scholarchain.rag

import scholarchain.core.{CollaborationMetric, QueryOperation}
import scholarchain.storage.{
  CypherQuery,
  ErrorCode,
  InMemoryPathEvidence,
  NonPathKind,
  PathLookup,
  StorageException,
  VectorIndexes
}

class RetrievalAgentsSuite extends munit.FunSuite:
  private val world = DemoWorld.build()
  private val ids = DemoWorld.ids
  private val early = DemoWorld.Early
  private val late = DemoWorld.Late

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val metricsAgent = new CollaborationMetricsAgent(
    world.cache,
    world.ports.ranking,
    DemoWorld.GraphRunId
  )

  test("metrics are the exact stored values with run ID and metric version") {
    metricsAgent.metrics(ids.boris, early) match
      case MetricLookup.Found(c) =>
        assertEquals(c.metrics.publicationCount, 4L)
        assertEquals(c.metrics.distinctCoauthorCount, 3L)
        assertEquals(c.metrics.totalCoauthorOccurrences, 5L)
        assertEquals(c.metrics.averageCoauthorsPerPaper, 1.25)
        assertEquals(c.metrics.maximumCoauthorsOnOnePaper, 2L)
        assertEquals(c.metrics.weightedDegree, 5.0)
        assertEquals(c.metrics.bridgeScore, Some(0.5))
        assertEquals(c.metrics.componentSize, 4L)
        assertEquals(c.runId, DemoWorld.GraphRunId)
        assertEquals(c.sparkTransformVersion, DemoWorld.SparkTransform)
      case other => fail(s"expected metrics, got $other")
  }

  test("a missing window is absent, never zero") {
    assert(
      metricsAgent.metrics(ids.boris, late).isInstanceOf[MetricLookup.Absent]
    )
    assert(
      metricsAgent.metrics(ids.weiA, early).isInstanceOf[MetricLookup.Absent]
    )
  }

  test("metrics are read from the pinned graph run only") {
    val other = new CollaborationMetricsAgent(
      world.cache,
      world.ports.ranking,
      "run:v1:55555555-5555-4555-8555-555555555555"
    )
    assert(other.metrics(ids.boris, early).isInstanceOf[MetricLookup.Absent])
  }

  test("ranking by one metric: ties share a rank and mentions are excluded") {
    val r =
      metricsAgent.rank(early, CollaborationMetric.DistinctCoauthorCount, 10)
    assertEquals(r.entries.head.cached.metrics.researcherId, ids.boris)
    assertEquals(r.entries.head.value, 3.0)
    assertEquals(r.mentionsExcluded, 1)
    assertEquals(
      r.entries.map(e => (e.rank, e.cached.metrics.researcherId)).take(3).toSet,
      Set((1, ids.boris), (2, ids.ada), (2, ids.fiona))
    )
    assert(!r.entries.exists(_.cached.metrics.researcherId == ids.ines))
    assertEquals(r.entries.last.rank, 4)
  }

  test("a cut inside a tie is reported") {
    val r =
      metricsAgent.rank(early, CollaborationMetric.DistinctCoauthorCount, 2)
    assertEquals(r.entries.size, 2)
    assert(r.tieAtCutoff)
  }

  test("a path length cannot be ranked from stored metrics") {
    assertEquals(
      code(metricsAgent.rank(early, CollaborationMetric.ShortestPathLength, 3)),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(metricsAgent.rank(early, CollaborationMetric.BridgeScore, 99)),
      ErrorCode.InvalidQuery
    )
  }

  private val finder =
    new PathFinderAgent(world.ports.neptune, world.ports.paths)

  test("direct collaborators come from the coauthors-of template") {
    val page = finder.directCollaborators(ids.boris, early)
    assertEquals(page.links.size, 3)
    assertEquals(page.links.head.coauthorId, ids.ada)
    assertEquals(page.links.head.jointPublicationCount, 3L)
    assertEquals(page.links.head.firstCollaborationYear, 2001)
    assertEquals(page.links.head.lastCollaborationYear, 2007)
    assert(page.links.head.evidencePointer.startsWith("edge-support:edgeId="))
    assert(!page.mayBeTruncated)
  }

  test("a full page may hide more coauthors and says so") {
    val narrow = new PathFinderAgent(
      world.ports.neptune,
      world.ports.paths,
      PathLimits(coauthorLimit = 2)
    )
    assert(narrow.directCollaborators(ids.boris, early).mayBeTruncated)
  }

  test("a direct edge is found, or its absence is reported as complete") {
    val (edge, _) = finder.directEdge(ids.ada, ids.boris, early)
    assertEquals(edge.map(_.jointPublicationCount), Some(3L))
    val (none, complete) = finder.directEdge(ids.ada, ids.chidi, early)
    assertEquals(none, None)
    assert(complete)
  }

  test("edge evidence gives the supporting works") {
    val link = finder.directEdge(ids.ada, ids.boris, early)._1.get
    assertEquals(
      finder.edgeWorks(link.edgeId).toSet,
      Set("w1", "w4", "w5").map(ids.works)
    )
  }

  test(
    "a shortest path has IDs, weights, works, years and provenance per hop"
  ) {
    finder.shortestPath(ids.ada, ids.chidi, early) match
      case PathLookup.Found(_, source, target, hops) =>
        assertEquals((source, target), (ids.ada, ids.chidi))
        assertEquals(
          hops.map(h => (h.fromId, h.toId)),
          Vector(
            (ids.ada, ids.boris),
            (ids.boris, ids.chidi)
          )
        )
        assertEquals(hops.map(_.jointPublicationCount), Vector(3L, 1L))
        assertEquals(
          hops.map(h => (h.firstCollaborationYear, h.lastCollaborationYear)),
          Vector((Some(2001), Some(2007)), (Some(2005), Some(2005)))
        )
        assert(hops.forall(_.supportingWorkIds.nonEmpty))
        assert(hops.forall(_.provenance.runId == DemoWorld.GraphRunId))
        assert(hops.forall(_.edgeId.startsWith("coauthor:v1:")))
      case other => fail(s"expected a path, got $other")
  }

  test("a path read in the other direction is flipped") {
    finder.shortestPath(ids.chidi, ids.ada, early) match
      case PathLookup.Found(_, _, _, hops) =>
        assertEquals(hops.head.fromId, ids.chidi)
        assertEquals(hops.last.toId, ids.ada)
      case other => fail(s"expected a path, got $other")
  }

  test(
    "honest non-path results: different component, not precomputed, window"
  ) {
    finder.shortestPath(ids.ada, ids.dana, early) match
      case p @ PathLookup.NonPath(_, _, _, _, _, kind, _) =>
        assertEquals(kind, NonPathKind.DifferentComponent)
        assert(p.noPathExists)
      case other => fail(s"expected a non-path, got $other")
    val unknown = finder.shortestPath(ids.chidi, ids.dana, early)
    assert(unknown.isInstanceOf[PathLookup.NotPrecomputed])
    assert(!unknown.noPathExists)
    finder.shortestPath(ids.boris, ids.dana, late) match
      case p @ PathLookup.NonPath(_, _, _, _, _, kind, _) =>
        assertEquals(kind, NonPathKind.EndpointNotInWindow)
        assert(!p.noPathExists)
      case other => fail(s"expected a non-path, got $other")
  }

  test("depth and result limits are enforced") {
    val shallow = new PathFinderAgent(
      world.ports.neptune,
      world.ports.paths,
      PathLimits(maxDepth = 1)
    )
    assertEquals(
      shallow.shortestPath(ids.ada, ids.chidi, early),
      PathLookup.ExceedsDepth(2, 1)
    )
    val few = new PathFinderAgent(
      world.ports.neptune,
      world.ports.paths,
      PathLimits(maxResults = 1)
    )
    assertEquals(
      few.shortestPath(ids.ada, ids.chidi, early),
      PathLookup.ExceedsResults(2, 1)
    )
  }

  test("a slow path read times out without sleeping") {
    val slow = new PathFinderAgent(
      world.ports.neptune,
      new InMemoryPathEvidence(DemoWorld.pathRows, _ => 5000L),
      PathLimits(timeoutMillis = 100)
    )
    assertEquals(
      slow.shortestPath(ids.ada, ids.chidi, early),
      PathLookup.TimedOut(100, 5000)
    )
  }

  test("limits outside the storage bounds are refused when built") {
    intercept[StorageException](PathLimits(maxDepth = 99))
    intercept[IllegalArgumentException](PathLimits(timeoutMillis = 0))
  }

  test("only the allowlisted templates reach the graph") {
    val gate = new AllowlistedNeptune(world.neptune)
    val foreign = CypherQuery(
      "all-nodes",
      "MATCH (n) WHERE id(n) = $id RETURN id(n) AS id LIMIT $limit",
      Map("id" -> ujson.Str("x"), "limit" -> ujson.Num(5))
    )
    assertEquals(code(gate.run(foreign)), ErrorCode.QueryRejected)
    assertEquals(
      AllowlistedNeptune.Templates,
      Set("coauthors-of", "edge-evidence")
    )
  }

  test(
    "user text cannot make a query: write and multi-statement text is rejected"
  ) {
    val bad = Vector(
      "MATCH (n) DETACH DELETE n LIMIT $limit",
      "MATCH (n) RETURN n; MATCH (m) RETURN m LIMIT $limit",
      "MATCH (n {name: 'x'}) RETURN n LIMIT $limit"
    )
    bad.foreach { text =>
      assertEquals(
        code(CypherQuery("probe", text, Map("limit" -> ujson.Num(1)))),
        ErrorCode.QueryRejected,
        text
      )
    }
  }

  private val analyst = new CommunityAnalystAgent(
    world.ports.components,
    world.ports.communities
  )

  test("community analysis keeps direct degree apart from bridge importance") {
    val boris = metricsAgent.metrics(ids.boris, early) match
      case MetricLookup.Found(c) => c
      case other                 => fail(s"expected metrics, got $other")
    val a = analyst.analyze(ids.boris, early, Some(boris))
    assertEquals(a.component.map(_.size), Some(4L))
    assertEquals(a.component.map(_.componentId), Some("component-1"))
    assertEquals(a.community.map(_.communityId), Some("community-1"))
    assertEquals(a.community.map(_.size), Some(3L))
    assertEquals(a.neighbors.map(_.neighborId), Vector("community-3"))
    assertEquals(a.neighbors.map(_.edgeCount), Vector(1L))
    assertEquals(a.distinctCoauthors, Some(3L))
    assertEquals(a.weightedDegree, Some(5.0))
    assertEquals(a.bridgeScore, Some(0.5))
    val ada = analyst.analyze(
      ids.ada,
      early,
      metricsAgent.metrics(ids.ada, early) match
        case MetricLookup.Found(c) => Some(c)
        case _                     => None
    )
    assertEquals(ada.bridgeScore, Some(0.0))
    assertEquals(ada.distinctCoauthors, Some(2L))
  }

  test("an unknown researcher has no component or community") {
    val a = analyst.analyze(ids.weiA, early, None)
    assertEquals(a.component, None)
    assertEquals(a.community, None)
    assertEquals(a.neighbors, Vector.empty)
  }

  private val semantic = new SemanticContextAgent(world.ports.semantic.get)

  test(
    "semantic context carries scores and pointers and says it is descriptive"
  ) {
    val (contexts, problems) = semantic.context(
      QueryOperation.ResearcherLookup,
      "Ada Quillfeather",
      Vector(ids.ada),
      None
    )
    assertEquals(problems, Vector.empty[String])
    val items = contexts.flatMap(_.items)
    assert(items.nonEmpty)
    assert(items.forall(_.subjectId == ids.ada))
    assert(items.forall(_.textRef.nonEmpty))
    assert(items.forall(_.distance.isFinite))
    assert(contexts.forall(!_.deterministicMetricsIncluded))
    assert(contexts.forall(_.metricsNotice.contains("not from this retrieval")))
  }

  test("each operation asks the index that fits it") {
    assertEquals(
      semantic.indexesFor(QueryOperation.ShortestPath),
      Vector(VectorIndexes.CollaborationPath)
    )
    assertEquals(
      semantic.indexesFor(QueryOperation.Community).head,
      VectorIndexes.CollaborationCommunity
    )
    assertEquals(
      semantic.indexesFor(QueryOperation.Topic),
      Vector(VectorIndexes.TopicInstitutionSummary)
    )
    assert(
      semantic
        .indexesFor(QueryOperation.EvidenceLookup)
        .contains(VectorIndexes.PaperAbstract)
    )
  }

  test("the empty topic index yields no context and no failure") {
    val (contexts, problems) = semantic.context(
      QueryOperation.Topic,
      "graph mining",
      Vector.empty,
      None
    )
    assertEquals(contexts, Vector.empty[SemanticContext])
    assertEquals(problems, Vector.empty[String])
  }
