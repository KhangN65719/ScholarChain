package scholarchain.tests.adversarial

import munit.FunSuite
import scala.util.Random
import scholarchain.core.*

/** Independent adversarial checks of the Phase 7 core coauthor ID rules for
  * unresolved-mention endpoints. Written from the work order and the domain
  * report, not from `MentionCoauthorSuite`: this suite builds Spark-shaped
  * teams through `StableIds` and attacks order, kind separation, malformed IDs
  * and provenance rather than replaying the module's cases.
  */
class Phase7MentionCoauthorSuite extends FunSuite:
  private val version = ContractSchema.currentVersion
  private val run = "run:v1:123e4567-e89b-42d3-a456-426614174000"
  private val policy = "scholarchain.spark.coauthor-policy/1"
  private val window = YearWindow(2019, 2023)

  private def provenance(
      snapshot: String = "snap-2026-11",
      record: String = "dblp/journals/x/1",
      transform: String = "scholarchain.spark.coauthor/1",
      runId: String = run
  ) = Provenance(
    version,
    SourceType.Dblp,
    snapshot,
    record,
    "https://example.invalid/r",
    transform,
    runId
  )

  private val works =
    (1 to 4).toVector.map(i => StableIds.work("dblp", s"rec/$i"))
  private val team = (1 to 6).toVector.map(StableIds.mention(works.head, _))
  private val researchers =
    Vector("A1", "A2", "A3").map(
      StableIds.researcherFromExternal("openalex", _)
    )

  private def edgeOf(
      x: String,
      y: String,
      w: YearWindow = window,
      p: String = policy,
      prov: Provenance = provenance()
  ): CoauthorEdge =
    val (a, b) = StableIds.orderedPair(x, y)
    CoauthorEdge(
      version,
      StableIds.coauthorEdgeFor(a, b, w, p),
      a,
      b,
      w,
      p,
      1L,
      2020,
      2020,
      Vector(works.head),
      prov
    )

  private def rejected(action: => Any): Unit =
    intercept[IllegalArgumentException](action)

  test("order independence holds for every pair of a Spark-shaped team"):
    val pairs = for
      i <- team.indices
      j <- team.indices if i < j
    yield (team(i), team(j))
    assertEquals(pairs.size, 15)
    val ids = pairs.map { case (a, b) =>
      val forward = StableIds.coauthorEdgeFor(a, b, window, policy)
      assertEquals(forward, StableIds.coauthorEdgeFor(b, a, window, policy))
      assertEquals(
        forward,
        StableIds.mentionCoauthorEdge(b, a, window, policy)
      )
      forward
    }
    assertEquals(ids.distinct.size, pairs.size)

  test("order independence holds for random mixed endpoint pairs"):
    val rng = new Random(7L)
    val pool = team ++ researchers ++ works.map(StableIds.mention(_, 1))
    (1 to 400).foreach { _ =>
      val a = pool(rng.nextInt(pool.size))
      val b = pool(rng.nextInt(pool.size))
      if a != b then
        val id = StableIds.coauthorEdgeFor(a, b, window, policy)
        assertEquals(id, StableIds.coauthorEdgeFor(b, a, window, policy))
        val bothResearchers =
          researchers.contains(a) && researchers.contains(b)
        assertEquals(id.startsWith("coauthor:v1:"), bothResearchers)
        assertEquals(id.startsWith("mention-coauthor:v1:"), !bothResearchers)
    }

  test("one edge per unordered pair: reversed endpoints cannot mint a second"):
    val e = edgeOf(team(0), team(1))
    val (lo, hi) = StableIds.orderedPair(team(1), team(0))
    assertEquals((e.researcherA, e.researcherB), (lo, hi))
    rejected(e.copy(researcherA = hi, researcherB = lo))
    rejected(
      e.copy(edgeId = StableIds.coauthorEdgeFor(hi, lo, window, policy))
        .copy(researcherA = hi, researcherB = lo)
    )

  test("mention endpoints sort before researcher endpoints"):
    val (a, b) = StableIds.orderedPair(researchers.head, team.head)
    assertEquals((a, b), (team.head, researchers.head))

  test("no collision across kinds, windows, policies and endpoint classes"):
    val endpoints = Vector(
      (team(0), team(1)),
      (team(0), researchers(0)),
      (team(1), researchers(0)),
      (team(0), StableIds.mention(works(1), 1))
    )
    val windows = Vector(window, YearWindow(2019, 2022), YearWindow(2020, 2023))
    val policies = Vector(policy, "p2", "p")
    val ids = for
      (x, y) <- endpoints
      w <- windows
      p <- policies
    yield StableIds.coauthorEdgeFor(x, y, w, p)
    val resolved = StableIds.coauthorEdgeFor(
      researchers(0),
      researchers(1),
      window,
      policy
    )
    assertEquals(ids.distinct.size, ids.size)
    assert(!ids.contains(resolved))
    // The hex payload of one kind never appears under the other kind.
    val payloads = (ids :+ resolved).map(_.split(":").last)
    assertEquals(payloads.distinct.size, payloads.size)

  test("length framing: delimiter-bearing policy versions do not collide"):
    val ids = Vector("a", "a ", "a|b", "a:b", "ab", "a\u0000b", "A").map(
      StableIds.coauthorEdgeFor(team(0), team(1), window, _)
    )
    assertEquals(ids.distinct.size, ids.size)

  test("blank policy versions are refused"):
    Vector("", " ", "\t\n").foreach { p =>
      rejected(StableIds.coauthorEdgeFor(team(0), team(1), window, p))
    }
    rejected(StableIds.coauthorEdgeFor(team(0), team(1), window, null))

  test("a mention-only edge ID can never be built for two researchers"):
    rejected(
      StableIds.mentionCoauthorEdge(
        researchers(0),
        researchers(1),
        window,
        policy
      )
    )
    rejected(
      StableIds.coauthorEdge(team(0), researchers(0), window, policy)
    )
    rejected(StableIds.coauthorEdge(team(0), team(1), window, policy))

  test("legacy coauthor:v1: IDs are refused on every mention endpoint mix"):
    val legacy = StableIds.coauthorEdge(
      researchers(0),
      researchers(1),
      window,
      policy
    )
    // A well-formed legacy-shaped ID over mention endpoints.
    Vector(
      (team(0), team(1)),
      (team(0), researchers(0)),
      (researchers(0), team(2))
    ).foreach { case (x, y) =>
      val (a, b) = StableIds.orderedPair(x, y)
      Vector(legacy, "coauthor:v1:" + "0" * 64).foreach { id =>
        val ex = intercept[IllegalArgumentException](
          CoauthorEdge(
            version,
            id,
            a,
            b,
            window,
            policy,
            1L,
            2020,
            2020,
            Vector(works.head),
            provenance()
          )
        )
        assert(ex.getMessage.contains("does not match its key"), ex.getMessage)
      }
    }
    // The converse: a mention-form ID on two researchers.
    val mentionForm = StableIds.mentionCoauthorEdge(
      team(0),
      team(1),
      window,
      policy
    )
    val (ra, rb) = StableIds.orderedPair(researchers(0), researchers(1))
    rejected(
      CoauthorEdge(
        version,
        mentionForm,
        ra,
        rb,
        window,
        policy,
        1L,
        2020,
        2020,
        Vector(works.head),
        provenance()
      )
    )

  test("legacy and spoofed IDs are refused on the wire"):
    val good = edgeOf(team(0), team(1))
    val legacy = "coauthor:v1:" + "ab" * 32
    Vector(legacy, "", "mention-coauthor:v1:zz", null: String).foreach { id =>
      val wire = ujson.read(ContractWire.encode(good))
      wire("edgeId") = if id == null then ujson.Null else ujson.Str(id)
      intercept[Exception](ContractWire.decode[CoauthorEdge](ujson.write(wire)))
    }

  private val hex = "ab" * 32

  test("malformed endpoint IDs are rejected by every ID entry point"):
    val bad = Vector(
      null,
      "",
      " ",
      "mention",
      "mention:v1",
      "mention:v1:",
      s"mention:v2:$hex",
      s"Mention:v1:$hex",
      s"mention:v1:${hex.toUpperCase}",
      s"mention:v1:${hex.dropRight(1)}",
      s"mention:v1:${hex}0",
      s"mention:v1:$hex\n",
      s" mention:v1:$hex",
      s"mention:v1:$hex ",
      s"mention:v1:$hex:extra",
      s"mention:v1:ns:$hex",
      s"mention:v1:${"g" * 64}",
      s"mention:v1:${"١" * 64}",
      s"mention::$hex",
      s"mention:v1:$hex\u0000",
      "mention:mention-1",
      s"coauthor:v1:$hex",
      s"edge:v1:$hex",
      run
    )
    bad.foreach { id =>
      assert(!StableIds.isCoauthorNode(id), s"accepted $id")
      rejected(StableIds.coauthorEdgeFor(id, team(0), window, policy))
      rejected(StableIds.coauthorEdgeFor(team(0), id, window, policy))
      rejected(StableIds.mentionCoauthorEdge(id, team(0), window, policy))
    }

  test("non-node kinds are not coauthor nodes"):
    val evidence = StableIds.evidence(
      SourceType.Dblp,
      "s",
      "r",
      EvidenceType.Publication,
      "l"
    )
    val chunk = StableIds.chunk(works.head, "s", "abstract", None, "t")
    Vector(works.head, evidence, chunk, run).foreach { id =>
      assert(!StableIds.isCoauthorNode(id), id)
      rejected(StableIds.coauthorEdgeFor(id, team(0), window, policy))
    }
    assert(StableIds.isCoauthorNode(team(0)))
    assert(StableIds.isCoauthorNode(researchers(0)))

  test("a researcher ID is never accepted where a mention is required"):
    rejected(StableIds.mention(researchers(0), 1))
    rejected(StableIds.mention(team(0), 1))
    rejected(StableIds.mention(works.head, 0))
    rejected(StableIds.mention(works.head, -1))

  test("year windows: boundaries bind the ID and bad windows never reach it"):
    val single = YearWindow(2020, 2020)
    assertNotEquals(
      StableIds.coauthorEdgeFor(team(0), team(1), single, policy),
      StableIds.coauthorEdgeFor(team(0), team(1), window, policy)
    )
    rejected(YearWindow(2021, 2020))
    val e = edgeOf(team(0), team(1), single)
    rejected(
      e.copy(firstCollaborationYear = 2019, lastCollaborationYear = 2020)
    )
    rejected(
      e.copy(firstCollaborationYear = 2020, lastCollaborationYear = 2021)
    )
    rejected(
      e.copy(firstCollaborationYear = 2020, lastCollaborationYear = 2019)
    )

  test("edge support invariants: count, works and years"):
    val e = edgeOf(team(0), team(1))
    rejected(e.copy(jointPublicationCount = 0L))
    rejected(e.copy(jointPublicationCount = -1L))
    rejected(e.copy(representativeWorkIds = Vector.empty))
    rejected(e.copy(representativeWorkIds = Vector(team(0))))
    rejected(e.copy(representativeWorkIds = Vector(works.head, "")))

  test("provenance survives the wire: runId, snapshotId, record, transform"):
    val prov = provenance(
      snapshot = "snap-ü-2026",
      record = "dblp/journals/日本語/42",
      transform = "tx/9"
    )
    Vector(
      edgeOf(team(0), team(1), prov = prov),
      edgeOf(team(0), researchers(0), prov = prov),
      edgeOf(researchers(0), researchers(1), prov = prov)
    ).foreach { e =>
      val back = ContractWire.decode[CoauthorEdge](ContractWire.encode(e))
      assertEquals(back, e)
      assertEquals(back.provenance.runId, run)
      assertEquals(back.provenance.snapshotId, "snap-ü-2026")
      assertEquals(back.provenance.sourceRecordId, "dblp/journals/日本語/42")
      assertEquals(back.provenance.transformVersion, "tx/9")
      val wire = ujson.read(ContractWire.encode(e))
      assertEquals(wire("provenance")("runId").str, run)
    }

  test("provenance cannot be blanked or forged"):
    rejected(provenance(snapshot = " "))
    rejected(provenance(record = ""))
    rejected(provenance(transform = "\t"))
    rejected(provenance(runId = "run:v1:not-a-uuid"))
    rejected(provenance(runId = "run:v1:" + hex))
    val good = edgeOf(team(0), team(1))
    val wire = ujson.read(ContractWire.encode(good))
    wire("provenance")("runId") = ujson.Str("")
    intercept[Exception](ContractWire.decode[CoauthorEdge](ujson.write(wire)))
    val wire2 = ujson.read(ContractWire.encode(good))
    wire2("schemaVersion") = ujson.Num(2)
    intercept[Exception](ContractWire.decode[CoauthorEdge](ujson.write(wire2)))
    val wire3 = ujson.read(ContractWire.encode(good))
    wire3.obj.remove("provenance")
    intercept[Exception](ContractWire.decode[CoauthorEdge](ujson.write(wire3)))

  test("a changed policy or window under the same endpoints is a new edge ID"):
    val a = edgeOf(team(0), team(1))
    val b = edgeOf(team(0), team(1), p = "policy-2")
    val c = edgeOf(team(0), team(1), w = YearWindow(2018, 2023))
    assertEquals(Set(a.edgeId, b.edgeId, c.edgeId).size, 3)
    rejected(a.copy(policyVersion = "policy-2"))
    rejected(a.copy(window = YearWindow(2018, 2023)))
