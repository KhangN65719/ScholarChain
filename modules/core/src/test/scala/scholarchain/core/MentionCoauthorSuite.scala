package scholarchain.core

import munit.FunSuite

class MentionCoauthorSuite extends FunSuite:
  private val version = ContractSchema.currentVersion
  private val run = "run:v1:550e8400-e29b-41d4-a716-446655440000"
  private val provenance = Provenance(
    version,
    SourceType.Dblp,
    "fixture-v1",
    "fixture/work",
    "https://example.invalid/work",
    "scholarchain.spark.coauthor-policy/1",
    run
  )
  private val workId = StableIds.work("dblp", "fixture/work")
  private val otherWorkId = StableIds.work("dblp", "other/work")
  private val m1 = StableIds.mention(workId, 1)
  private val m2 = StableIds.mention(workId, 2)
  private val m3 = StableIds.mention(otherWorkId, 1)
  private val r1 = StableIds.researcherFromExternal("openalex", "A1")
  private val r2 = StableIds.researcherFromExternal("openalex", "A2")
  private val window = YearWindow(2020, 2024)
  private val policy = "policy-v1"

  private def edge(a: String, b: String): CoauthorEdge =
    val (l, r) = StableIds.orderedPair(a, b)
    CoauthorEdge(
      version,
      StableIds.coauthorEdgeFor(l, r, window, policy),
      l,
      r,
      window,
      policy,
      1,
      2024,
      2024,
      Vector(workId),
      provenance
    )

  private def rejects(expected: String)(action: => Any): Unit =
    val text = Rejection.textOf(action)
    assert(text.contains(expected), s"Expected '$expected' but got: '$text'")

  test("mention edge ID is deterministic and has a fixed shape"):
    val id = StableIds.mentionCoauthorEdge(m1, m2, window, policy)
    assertEquals(id, StableIds.mentionCoauthorEdge(m1, m2, window, policy))
    assert(id.matches("mention-coauthor:v1:[0-9a-f]{64}"), id)
    assertEquals(id, edge(m1, m2).edgeId)

  test("mention edge ID is independent of endpoint order"):
    assertEquals(
      StableIds.mentionCoauthorEdge(m1, m2, window, policy),
      StableIds.mentionCoauthorEdge(m2, m1, window, policy)
    )
    assertEquals(
      StableIds.coauthorEdgeFor(m1, r1, window, policy),
      StableIds.coauthorEdgeFor(r1, m1, window, policy)
    )

  test("mention edge ID binds window, policy and both endpoints"):
    val base = StableIds.mentionCoauthorEdge(m1, m2, window, policy)
    assertNotEquals(
      base,
      StableIds.mentionCoauthorEdge(m1, m2, YearWindow(2019, 2024), policy)
    )
    assertNotEquals(
      base,
      StableIds.mentionCoauthorEdge(m1, m2, YearWindow(2020, 2023), policy)
    )
    assertNotEquals(
      base,
      StableIds.mentionCoauthorEdge(m1, m2, window, "policy-v2")
    )
    assertNotEquals(base, StableIds.mentionCoauthorEdge(m1, m3, window, policy))
    assertNotEquals(base, StableIds.mentionCoauthorEdge(m2, m3, window, policy))

  test("resolved and unresolved edges are separated by kind and dispatch"):
    val resolved = StableIds.coauthorEdgeFor(r1, r2, window, policy)
    assertEquals(resolved, StableIds.coauthorEdge(r1, r2, window, policy))
    assert(resolved.startsWith("coauthor:v1:"))
    val mixed = StableIds.coauthorEdgeFor(m1, r1, window, policy)
    val unresolved = StableIds.coauthorEdgeFor(m1, m2, window, policy)
    assert(mixed.startsWith("mention-coauthor:v1:"))
    assert(unresolved.startsWith("mention-coauthor:v1:"))
    assertEquals(Set(resolved, mixed, unresolved).size, 3)
    // The kinds are not interchangeable through the strict entry points.
    rejects("Malformed researcher identifier")(
      StableIds.coauthorEdge(m1, r1, window, policy)
    )
    rejects("requires an unresolved mention endpoint")(
      StableIds.mentionCoauthorEdge(r1, r2, window, policy)
    )

  test("collision resistance: same payload under another kind never matches"):
    val ids = for
      (x, y) <- Vector((m1, m2), (m1, m3), (m2, m3), (m1, r1), (m2, r2))
      w <- Vector(window, YearWindow(2020, 2020))
      p <- Vector(policy, "other")
    yield StableIds.coauthorEdgeFor(x, y, w, p)
    assertEquals(ids.distinct.size, ids.size)
    val resolved = StableIds.coauthorEdge(r1, r2, window, policy)
    assert(!ids.contains(resolved))
    // Same four inputs hashed under the resolved kind differ from the mention kind.
    assertNotEquals(
      StableIds.coauthorEdgeFor(m1, r1, window, policy).split(":").last,
      StableIds.coauthorEdgeFor(r1, r2, window, policy).split(":").last
    )

  test("coauthor edges with mention endpoints round-trip over the wire"):
    Vector(edge(m1, m2), edge(m1, r1), edge(r1, r2)).foreach { e =>
      assertEquals(ContractWire.decode[CoauthorEdge](ContractWire.encode(e)), e)
    }
    val wire = ujson.read(ContractWire.encode(edge(m1, m2)))
    assertEquals(wire("schemaVersion").num, 1.0)
    assertEquals(
      wire("edgeId").str,
      StableIds.mentionCoauthorEdge(m1, m2, window, policy)
    )
    assertEquals(
      wire("provenance")("transformVersion").str,
      provenance.transformVersion
    )
    assertEquals(wire("provenance")("runId").str, run)
    assertEquals(wire("provenance")("sourceRecordId").str, "fixture/work")

  test("graph edge and metrics accept mention nodes and keep the edge key"):
    val unresolved = edge(m1, m2)
    val evidence = StableIds.evidence(
      SourceType.Dblp,
      "fixture-v1",
      "fixture/work",
      EvidenceType.Publication,
      "row"
    )
    val kind = GraphEdgeType.CoauthoredWith
    val graphEdge = GraphEdge(
      version,
      StableIds.graphEdge(
        kind,
        unresolved.researcherA,
        unresolved.researcherB,
        policy,
        Vector(evidence)
      ),
      unresolved.researcherA,
      kind,
      unresolved.researcherB,
      Map.empty,
      policy,
      Vector(evidence),
      Some(unresolved),
      provenance
    )
    assertEquals(
      ContractWire.decode[GraphEdge](ContractWire.encode(graphEdge)),
      graphEdge
    )
    val metrics = ResearcherMetrics(
      version,
      m1,
      window,
      1L,
      1L,
      1L,
      1.0,
      1L,
      Vector(workId),
      1.0,
      "component-1",
      2L,
      None,
      None,
      None,
      provenance
    )
    assertEquals(
      ContractWire.decode[ResearcherMetrics](ContractWire.encode(metrics)),
      metrics
    )
    assert(StableIds.isCoauthorNode(m1) && StableIds.isCoauthorNode(r1))
    assert(!StableIds.isCoauthorNode(workId))

  test("malformed or misordered endpoints are rejected"):
    val good = edge(m1, m2)
    val (lo, hi) = StableIds.orderedPair(m1, m2)
    rejects("Undirected endpoints")(
      good.copy(researcherA = hi, researcherB = lo)
    )
    rejects("Undirected endpoints")(
      good.copy(researcherA = lo, researcherB = lo)
    )
    rejects("Malformed researcher identifier")(good.copy(researcherB = workId))
    rejects("Malformed researcher identifier")(
      good.copy(researcherA = "mention:v1:abc")
    )
    rejects("Malformed researcher identifier")(
      good.copy(researcherA = "mention:mention-1")
    )
    rejects("Malformed researcher identifier")(good.copy(researcherA = null))
    rejects("Coauthor edge ID does not match its key")(
      good.copy(edgeId = StableIds.coauthorEdge(r1, r2, window, policy))
    )
    rejects("Coauthor edge ID does not match its key")(
      good.copy(edgeId = StableIds.mentionCoauthorEdge(m1, m3, window, policy))
    )
    rejects("Malformed researcher identifier")(
      StableIds.mentionCoauthorEdge(m1, workId, window, policy)
    )
    rejects("Malformed researcher identifier")(
      StableIds.coauthorEdgeFor("mention:v1:zz", m1, window, policy)
    )
    rejects("distinct endpoints")(
      StableIds.mentionCoauthorEdge(m1, m1, window, policy)
    )
    val wire = ujson.read(ContractWire.encode(good))
    wire("researcherA") = ujson.Str(workId)
    intercept[Exception](ContractWire.decode[CoauthorEdge](ujson.write(wire)))
    val resolvedWire = ujson.read(ContractWire.encode(edge(r1, r2)))
    resolvedWire("edgeId") = ujson.Str(good.edgeId)
    intercept[Exception](
      ContractWire.decode[CoauthorEdge](ujson.write(resolvedWire))
    )
