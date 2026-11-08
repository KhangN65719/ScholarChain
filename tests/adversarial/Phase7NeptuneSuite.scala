package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.core.{StableIds, YearWindow}
import scholarchain.storage.*
import Phase7Storage.*

/** Independent adversarial checks of the Neptune projection, export, bulk-load
  * fake and read-only query port.
  */
class Phase7NeptuneSuite extends FunSuite:

  private def exported(
      w: World,
      run: String = runId,
      store: ObjectStore = new InMemoryObjectStore
  ): (ObjectStore, NeptuneExportReport) =
    (store, NeptuneExporter.exportTo(project(w), run, store))

  private def text(store: ObjectStore, key: String): String =
    new String(store.get(key).get, UTF_8)

  private def edgeIdsIn(store: ObjectStore, r: NeptuneExportReport) =
    r.files
      .filter(_.kind == "edge")
      .flatMap(f => NeptuneCsvReader.edges(text(store, f.key)))

  private def vertexIdsIn(store: ObjectStore, r: NeptuneExportReport) =
    r.files
      .filter(_.kind == "vertex")
      .flatMap(f => NeptuneCsvReader.vertices(text(store, f.key)))

  // Fixture arithmetic. The Base graph is 8 vertices (2 unresolved mentions,
  // 2 researchers, 3 works, 1 venue) and 11 edges (7 AUTHORED, 1 PUBLISHED_IN,
  // 3 COAUTHORED_WITH). The Extended default adds one AuthorMention vertex and
  // one RESOLVES_TO edge per resolved mention (the five AUTHORED rows whose
  // mention differs from the researcher), one DatasetSnapshot vertex per
  // distinct snapshot (one) and one DERIVED_FROM edge per work (three).
  private val baseNodes = Set(m1, m2, r1, r2, w1, w2, w3, venue)
  private val resolvedMentions = Set(
    StableIds.mention(w2, 1),
    StableIds.mention(w2, 2),
    StableIds.mention(w3, 1),
    StableIds.mention(w3, 2),
    StableIds.mention(w1, 3)
  )
  private val snapshotNode = "snap-1"
  private val baseVertexCount = 8
  private val baseEdgeCount = 11
  private val extendedVertexCount = 14
  private val extendedEdgeCount = 19
  private val extendedInserted = 33
  private val baseInserted = 19

  private def isEdgeV1(id: String): Boolean = id.matches("edge:v1:[0-9a-f]{64}")

  // 1. ID agreement ----------------------------------------------------------

  test("fixture arithmetic: the pinned Extended counts follow from the rows"):
    val w = world()
    val authored = w.rows("edges-authored")
    val resolved = authored.filter(r => r("mentionId") != r("researcherNodeId"))
    assertEquals(resolved.map(_("mentionId").str).toSet, resolvedMentions)
    assertEquals(resolved.size, 5)
    val snapshots = w
      .rows("nodes-work")
      .map(_("provenance")("snapshotId").str)
      .toSet
    assertEquals(snapshots, Set(snapshotNode))
    assertEquals(
      baseVertexCount,
      w.rows("nodes-researcher").size + w.rows("nodes-work").size +
        w.rows("nodes-venue").size
    )
    assertEquals(
      baseEdgeCount,
      authored.size + w.rows("edges-published-in").size +
        w.rows("edges-coauthored").size
    )
    assertEquals(
      extendedVertexCount,
      baseVertexCount + resolvedMentions.size + snapshots.size
    )
    assertEquals(
      extendedEdgeCount,
      baseEdgeCount + resolved.size + w.rows("nodes-work").size
    )
    assertEquals(extendedInserted, extendedVertexCount + extendedEdgeCount)
    assertEquals(baseInserted, baseVertexCount + baseEdgeCount)

  test("IDs built through core appear unchanged in the projection and CSV"):
    val w = world()
    val projection = project(w)
    val expectedNodes = baseNodes ++ resolvedMentions + snapshotNode
    assertEquals(projection.vertices.map(_.id).toSet, expectedNodes)
    val coauthorIds = Set(
      StableIds.coauthorEdgeFor(m1, m2, window, policy),
      StableIds.coauthorEdgeFor(r1, r2, window, policy),
      StableIds.coauthorEdgeFor(r1, m2, window, policy)
    )
    assertEquals(
      projection.edges.filter(_.label == "COAUTHORED_WITH").map(_.id).toSet,
      coauthorIds
    )
    val (store, report) = exported(w)
    assertEquals(vertexIdsIn(store, report).map(_.id).toSet, expectedNodes)
    assert(coauthorIds.subsetOf(edgeIdsIn(store, report).map(_.id).toSet))
    assertEquals(report.vertexCount, extendedVertexCount)
    // 7 AUTHORED + 1 PUBLISHED_IN + 3 COAUTHORED_WITH + 5 RESOLVES_TO
    // + 3 DERIVED_FROM
    assertEquals(report.edgeCount, extendedEdgeCount)
    assertEquals(projection.vertices.size, extendedVertexCount)
    assertEquals(projection.edges.size, extendedEdgeCount)

  test("labels follow the ID kind: mention nodes are AuthorMention"):
    val byId = project(world()).vertices.map(v => v.id -> v.label).toMap
    assertEquals(byId(m1), "AuthorMention")
    assertEquals(byId(m2), "AuthorMention")
    assertEquals(byId(r1), "Researcher")
    assertEquals(byId(w1), "Work")

  test("a non-core researcher node ID is refused"):
    val bad = world().replace(
      "nodes-researcher",
      Vector(researcherRow("mention:mention-1", "x", provenance()))
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)
    val bad2 = world().replace(
      "nodes-researcher",
      Vector(researcherRow(w1, "a work is not a researcher", provenance()))
    )
    assertEquals(code(project(bad2)), ErrorCode.MalformedRow)

  test("query results report the same edge IDs the export wrote"):
    val (store, report) = exported(world())
    val neptune = new InMemoryNeptune(store)
    neptune.load(report.manifestKey)
    val rows = neptune.run(CypherCatalog.coauthorsOf(r1, windowLabel, 10))
    val ids = rows.map(_("edgeId").str).toSet
    assertEquals(
      ids,
      Set(
        StableIds.coauthorEdgeFor(r1, r2, window, policy),
        StableIds.coauthorEdgeFor(r1, m2, window, policy)
      )
    )
    val evidence = neptune.run(CypherCatalog.edgeEvidence(ids.head, 5))
    assertEquals(evidence.size, 1)
    assert(evidence.head("evidencePointer").str.contains(ids.head))

  // 2. Legacy and ordering ---------------------------------------------------

  private val legacy = "coauthor:v1:" + "ab" * 32

  private def withEdgeId(w: World, edgeIndex: Int, id: String): World =
    val edges = w.rows("edges-coauthored")
    w.replace(
      "edges-coauthored",
      edges.updated(edgeIndex, edges(edgeIndex).updated("edgeId", s(id)))
    )

  test("legacy coauthor:v1: ID on a mention endpoint is refused in edges"):
    // index 0 is mention-mention, 2 is mention-researcher
    Vector(0, 2).foreach { i =>
      assertEquals(
        code(project(withEdgeId(world(), i, legacy))),
        ErrorCode.LegacyEdgeId
      )
    }

  test("legacy ID is refused in edge-support, even if the edge row is valid"):
    val w = world()
    val support = w.rows("edge-support")
    val broken = support.updated(0, support(0).updated("edgeId", s(legacy)))
    assertEquals(
      code(project(w.replace("edge-support", broken))),
      ErrorCode.LegacyEdgeId
    )

  test("a researcher pair keeps its coauthor:v1: ID; it is not legacy"):
    val projection = project(world())
    val rr = projection.edges.find(
      _.id == StableIds.coauthorEdgeFor(r1, r2, window, policy)
    )
    assert(rr.exists(_.id.startsWith("coauthor:v1:")))

  test("a wrong-kind ID on a researcher pair is refused as an invalid edge ID"):
    val mentionForm = StableIds.mentionCoauthorEdge(m1, m2, window, policy)
    assertEquals(
      code(project(withEdgeId(world(), 1, mentionForm))),
      ErrorCode.InvalidEdgeId
    )

  test("reversed endpoints do not yield a second edge"):
    val w = world()
    val rr = w.rows("edges-coauthored")(1)
    val reversed =
      rr.updated("researcherA", rr("researcherB"))
        .updated("researcherB", rr("researcherA"))
    assertEquals(
      code(
        project(
          w.replace("edges-coauthored", w.rows("edges-coauthored") :+ reversed)
        )
      ),
      ErrorCode.InvalidEdgeId
    )
    // The same pair in either order has one ID.
    assertEquals(
      StableIds.coauthorEdgeFor(r1, r2, window, policy),
      StableIds.coauthorEdgeFor(r2, r1, window, policy)
    )

  test("the same edge row repeated is one edge, not two"):
    val w = world()
    val rows = w.rows("edges-coauthored")
    val doubled = w.replace("edges-coauthored", rows :+ rows.head)
    // Support rows would also repeat; the edge must still appear once.
    val outcome =
      try Right(project(doubled))
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(p) =>
        assertEquals(p.edges.map(_.id).distinct.size, p.edges.size)
        assertEquals(p.edges.count(_.label == "COAUTHORED_WITH"), 3)
      case Left(c) =>
        assert(
          Set(ErrorCode.DuplicateId, ErrorCode.MissingEvidence).contains(c),
          c.toString
        )

  // 3. Idempotent reload -----------------------------------------------------

  test("export twice: second pass creates nothing and bytes are identical"):
    val store = new InMemoryObjectStore
    val (_, first) = exported(world(), store = store)
    val before = store.list("").map(m => m.key -> m.sha256)
    val (_, second) = exported(world(), store = store)
    assertEquals(second.created, 0)
    assertEquals(second.unchanged, first.created)
    assertEquals(store.list("").map(m => m.key -> m.sha256), before)

  test("load twice and interleaved: counts stay stable, nothing duplicates"):
    val store = new InMemoryObjectStore
    val (_, a) = exported(world(), runId, store)
    val (_, b) = exported(world(), otherRun, store)
    val neptune = new InMemoryNeptune(store)
    val first = neptune.load(a.manifestKey)
    // 14 vertices + 19 edges under the Extended default
    assertEquals(first.inserted, extendedInserted)
    assertEquals(first.updated + first.unchanged, 0)
    val second = neptune.load(b.manifestKey)
    assertEquals(
      (second.inserted, second.updated, second.unchanged),
      (0, 0, extendedInserted)
    )
    val third = neptune.load(a.manifestKey)
    assertEquals(
      (third.inserted, third.updated, third.unchanged),
      (0, 0, extendedInserted)
    )
    assertEquals(neptune.vertices.size, extendedVertexCount)
    assertEquals(neptune.edges.size, extendedEdgeCount)

  test("a changed payload under the same run key is refused, store untouched"):
    val store = new InMemoryObjectStore
    val (_, first) = exported(world(), store = store)
    val before = store.list("").map(m => m.key -> m.sha256)
    val changed = world().replace(
      "nodes-venue",
      Vector(
        Map(
          "venueId" -> s(venue),
          "name" -> s("VLDB changed"),
          "provenance" -> provJson(provenance())
        )
      )
    )
    assertEquals(
      code(NeptuneExporter.exportTo(project(changed), runId, store)),
      ErrorCode.ImmutableKeyConflict
    )
    assertEquals(store.list("").map(m => m.key -> m.sha256), before)
    val neptune = new InMemoryNeptune(store)
    assertEquals(neptune.load(first.manifestKey).inserted, extendedInserted)

  test("a file swapped under a manifest fails its checksum"):
    val (storeA, a) = exported(world())
    val (storeB, b) = exported(
      world().replace(
        "nodes-venue",
        Vector(
          Map(
            "venueId" -> s(venue),
            "name" -> s("tampered"),
            "provenance" -> provJson(provenance())
          )
        )
      )
    )
    assertEquals(a.files.map(_.key), b.files.map(_.key))
    val mixed = new InMemoryObjectStore
    mixed.putImmutable(a.manifestKey, storeA.get(a.manifestKey).get, "t")
    a.files.foreach { f =>
      val src = if f.label == "Venue" then storeB else storeA
      mixed.putImmutable(f.key, src.get(f.key).get, "t")
    }
    assertEquals(
      code(new InMemoryNeptune(mixed).load(a.manifestKey)),
      ErrorCode.ChecksumMismatch
    )

  test("loading a manifest that is absent fails"):
    assertEquals(
      code(
        new InMemoryNeptune(new InMemoryObjectStore)
          .load("graph/neptune/v=v1/run=x/load-manifest.json")
      ),
      ErrorCode.MalformedManifest
    )

  // 5. CSV and Cypher injection ---------------------------------------------

  private def nameWorld(name: String): World =
    val w = world()
    w.replace(
      "nodes-researcher",
      w.rows("nodes-researcher")
        .updated(2, researcherRow(r1, name, provenance()))
    )

  test("comma, quote and CSV-formula characters round-trip as one cell"):
    val nasty = "Doe, \"Jane\" =1+1, 'x',\"\""
    val (store, report) = exported(nameWorld(nasty))
    val v = vertexIdsIn(store, report).find(_.id == r1).get
    assertEquals(v.props("displayName"), Prop.Str(nasty))
    assertEquals(vertexIdsIn(store, report).size, extendedVertexCount)

  test("newlines are replaced by a space, counted, and never split a row"):
    val (store, report) = exported(nameWorld("Line1\nLine2\r\nLine3\tTab"))
    val v = vertexIdsIn(store, report).find(_.id == r1).get
    assertEquals(v.props("displayName"), Prop.Str("Line1 Line2  Line3 Tab"))
    assert(report.normalizedValues >= 1)
    assertEquals(vertexIdsIn(store, report).size, extendedVertexCount)

  test("a row-forging cell cannot add a vertex"):
    val forged = "x\n" + r2 + ",Researcher,Forged"
    val (store, report) = exported(nameWorld(forged))
    val ids = vertexIdsIn(store, report).map(_.id)
    assertEquals(ids.size, extendedVertexCount)
    assertEquals(ids.count(_ == r2), 1)

  test("a null character is refused in every text column"):
    assertEquals(code(project(nameWorld("a\u0000b"))), ErrorCode.NullCharacter)
    val w = world()
    val venueNul = w.replace(
      "nodes-venue",
      Vector(
        Map(
          "venueId" -> s("v\u0000"),
          "name" -> s("n"),
          "provenance" -> provJson(provenance())
        )
      )
    )
    assertEquals(code(project(venueNul)), ErrorCode.NullCharacter)
    val snap = world(provenance(snapshot = "snap\u00001"))
    assertEquals(code(project(snap)), ErrorCode.NullCharacter)

  test("identifiers with commas or quotes in a venue ID survive the CSV"):
    val oddVenue = "ven,ue\"x"
    val w = world()
    val changed = w
      .replace(
        "nodes-venue",
        Vector(
          Map(
            "venueId" -> s(oddVenue),
            "name" -> s("n"),
            "provenance" -> provJson(provenance())
          )
        )
      )
      .replace(
        "edges-published-in",
        Vector(
          Map(
            "fromId" -> s(w1),
            "toId" -> s(oddVenue),
            "provenance" -> provJson(provenance())
          )
        )
      )
    val (store, report) = exported(changed)
    assert(vertexIdsIn(store, report).exists(_.id == oddVenue))
    assert(edgeIdsIn(store, report).exists(e => e.to == oddVenue))

  test("provenance text with CSV metacharacters survives intact"):
    val snap = "snap, \"quoted\" 2026"
    val (store, report) = exported(world(provenance(snapshot = snap)))
    val v = vertexIdsIn(store, report).find(_.id == w1).get
    assertEquals(v.props("snapshotId"), Prop.Str(snap))

  test("openCypher values travel as parameters, never in the text"):
    val hostile = "x' OR 1=1 //\"; MATCH (n) DETACH DELETE n; $limit"
    val a = CypherCatalog.coauthorsOf(hostile, "2019-2023", 5)
    val b = CypherCatalog.coauthorsOf("plain", "2019-2023", 5)
    assertEquals(a.text, b.text)
    assert(!a.text.contains("OR 1=1"))
    assertEquals(a.params("researcherId").str, hostile)
    val e = CypherCatalog.edgeEvidence(hostile, 3)
    assert(!e.text.contains("DETACH"))

  test("hostile parameter values find nothing and change nothing"):
    val (store, report) = exported(world())
    val neptune = new InMemoryNeptune(store)
    neptune.load(report.manifestKey)
    val before = (neptune.vertices, neptune.edges)
    val rows = neptune.run(
      CypherCatalog.coauthorsOf("' OR 1=1 //", windowLabel, 100)
    )
    assertEquals(rows, Vector.empty)
    assertEquals((neptune.vertices, neptune.edges), before)

  test("query text with literals, writes or extra statements is rejected"):
    def q(
        text: String,
        params: Map[String, ujson.Value] = Map("limit" -> ujson.Num(1))
    ) =
      CypherQuery("t", text, params)
    val bad = Vector(
      "MATCH (n) WHERE n.id = 'a' RETURN n LIMIT $limit",
      "MATCH (n) WHERE n.id = \"a\" RETURN n LIMIT $limit",
      "MATCH (`n`) RETURN n LIMIT $limit",
      "MATCH (n) RETURN n LIMIT $limit; MATCH (m) RETURN m",
      "MATCH (n) DETACH DELETE n LIMIT $limit",
      "match (n) set n.x = 1 return n limit $limit",
      "CREATE (n) RETURN n LIMIT $limit",
      "MERGE (n) RETURN n LIMIT $limit",
      "MATCH (n) REMOVE n.x RETURN n LIMIT $limit",
      "CALL db.labels() YIELD x RETURN x LIMIT $limit",
      "LOAD CSV FROM $limit AS row RETURN row",
      "MATCH (n) RETURN n LIMIT $limit // DROP",
      "MATCH (n) FOREACH (x IN [1] | SET n.a = x) RETURN n LIMIT $limit",
      "MATCH (n) RETURN n LIMIT $limit\u0000"
    )
    bad.foreach { t =>
      assertEquals(code(q(t)), ErrorCode.QueryRejected, t)
    }

  test("a query without a $limit binding, or with unbound params, is rejected"):
    assertEquals(
      code(CypherQuery("t", "MATCH (n) RETURN n", Map.empty)),
      ErrorCode.QueryRejected
    )
    assertEquals(
      code(
        CypherQuery(
          "t",
          "MATCH (n) WHERE id(n) = $a RETURN n LIMIT $limit",
          Map("limit" -> ujson.Num(1))
        )
      ),
      ErrorCode.QueryRejected
    )
    assertEquals(
      code(
        CypherQuery(
          "t",
          "MATCH (n) RETURN n LIMIT $limit",
          Map("limit" -> ujson.Num(1), "extra" -> ujson.Str("x"))
        )
      ),
      ErrorCode.QueryRejected
    )

  test("an unknown catalog query name is refused by the fake port"):
    val neptune = new InMemoryNeptune(new InMemoryObjectStore)
    val q = CypherQuery(
      "not-in-catalog",
      "MATCH (n) RETURN n LIMIT $limit",
      Map("limit" -> ujson.Num(1))
    )
    assertEquals(code(neptune.run(q)), ErrorCode.QueryRejected)

  // Evidence and endpoints ---------------------------------------------------

  test("a coauthor edge with no supporting works is refused"):
    val w = world()
    val rr = w.rows("edges-coauthored")(1)("edgeId")
    val without = w.rows("edge-support").filterNot(_("edgeId") == rr)
    assertEquals(
      code(project(w.replace("edge-support", without))),
      ErrorCode.MissingEvidence
    )
    assertEquals(
      code(project(w.replace("edge-support", Vector.empty))),
      ErrorCode.MissingEvidence
    )

  test("joint count must equal the distinct supporting works, both ways"):
    val w = world()
    val rows = w.rows("edges-coauthored")
    def withCount(count: Long, reps: Seq[String]) =
      val old = rows(1)
      val e = coauthorRow(r1, r2, count, 2020, 2021, reps, provenance())
      assertEquals(e("edgeId"), old("edgeId"))
      w.replace("edges-coauthored", rows.updated(1, e))
    assertEquals(
      code(project(withCount(3, Seq(w2, w3)))),
      ErrorCode.MissingEvidence
    )
    assertEquals(
      code(project(withCount(1, Seq(w2)))),
      ErrorCode.MissingEvidence
    )

  test("duplicate support rows for one work do not inflate the count"):
    val w = world()
    val support = w.rows("edge-support")
    val dup = support.filter(_("canonicalWorkId") == s(w2))
    val inflated = w.replace("edge-support", support ++ dup)
    // count is 2, distinct works 2: still valid.
    assertEquals(project(inflated).edges.size, extendedEdgeCount)
    // One work counted twice must not satisfy a count of 2.
    val rows = w.rows("edges-coauthored")
    val e = rows(1)
    val onlyW2 = support.filterNot(r => r("edgeId") == e("edgeId")) ++
      Vector(supportRow(e, w2, 2020), supportRow(e, w2, 2021))
    assertEquals(
      code(project(w.replace("edge-support", onlyW2))),
      ErrorCode.MissingEvidence
    )

  test("representative works must be among the supporting works"):
    val w = world()
    val rows = w.rows("edges-coauthored")
    val e = coauthorRow(r1, r2, 2, 2020, 2021, Seq(w1, w3), provenance())
    assertEquals(
      code(project(w.replace("edges-coauthored", rows.updated(1, e)))),
      ErrorCode.MissingEvidence
    )

  test("supporting work must be a Work vertex; endpoints must be vertices"):
    val w = world()
    val ghost = StableIds.work("dblp", "rec/ghost")
    val support = w.rows("edge-support")
    val swapped = support.map { r =>
      if r("canonicalWorkId") == s(w3) then
        r.updated("canonicalWorkId", s(ghost))
      else r
    }
    val e = coauthorRow(r1, r2, 2, 2020, 2021, Seq(w2, ghost), provenance())
    val w2x = w
      .replace("edge-support", swapped)
      .replace("edges-coauthored", w.rows("edges-coauthored").updated(1, e))
    assertEquals(code(project(w2x)), ErrorCode.MissingEndpoint)
    val noR2 = w.replace(
      "nodes-researcher",
      w.rows("nodes-researcher").filterNot(_("nodeId") == s(r2))
    )
    assertEquals(code(project(noR2)), ErrorCode.MissingEndpoint)
    val badAuthored = w.replace(
      "edges-authored",
      w.rows("edges-authored")
        .updated(
          0,
          w.rows("edges-authored")(0).updated("canonicalWorkId", s(ghost))
        )
    )
    assertEquals(code(project(badAuthored)), ErrorCode.MissingEndpoint)
    val badPublished = w.replace(
      "edges-published-in",
      Vector(w.rows("edges-published-in")(0).updated("toId", s("venue:none")))
    )
    assertEquals(code(project(badPublished)), ErrorCode.MissingEndpoint)

  test("edge years must match the years of its supporting works"):
    val w = world()
    val rows = w.rows("edges-coauthored")
    val e = coauthorRow(r1, r2, 2, 2020, 2022, Seq(w2, w3), provenance())
    assertEquals(
      code(project(w.replace("edges-coauthored", rows.updated(1, e)))),
      ErrorCode.MissingEvidence
    )

  test("two vertices with one ID but different content are refused"):
    val w = world()
    val dupe = w.rows("nodes-researcher") :+ researcherRow(
      r1,
      "Someone Else",
      provenance()
    )
    assertEquals(
      code(project(w.replace("nodes-researcher", dupe))),
      ErrorCode.DuplicateId
    )
    val same = w.rows("nodes-researcher") :+ w.rows("nodes-researcher")(2)
    assertEquals(
      project(w.replace("nodes-researcher", same)).vertices.size,
      extendedVertexCount
    )

  test("mixed property types in one column are refused at export"):
    val p = project(world())
    val broken = p.copy(vertices = p.vertices.map { v =>
      if v.id == w1 then v.copy(props = v.props.updated("title", Prop.I32(1)))
      else v
    })
    assertEquals(
      code(NeptuneExporter.exportTo(broken, runId, new InMemoryObjectStore)),
      ErrorCode.MalformedRow
    )

  // 7. Bounds ----------------------------------------------------------------

  test("a bound exceeded fails projection and a failed export writes nothing"):
    val tight = LoadBounds(maxRows = 2, maxFiles = 10, maxBytes = 1L << 20)
    assertEquals(code(project(world(), tight)), ErrorCode.BoundExceeded)
    val store = new InMemoryObjectStore
    code(NeptuneExporter.exportTo(project(world(), tight), runId, store))
    assertEquals(store.list(""), Vector.empty)

  test("a missing table fails, it is not treated as empty"):
    val w = world()
    val cut = World(w.tables - "edge-support")
    assertEquals(code(project(cut)), ErrorCode.MalformedManifest)

  test("malformed cells fail with MalformedRow, not an arbitrary exception"):
    val w = world()
    val bad = w.replace(
      "nodes-work",
      w.rows("nodes-work")
        .updated(0, w.rows("nodes-work")(0).updated("year", s("2020")))
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)

  test(
    "a row with no provenance is a MalformedRow, not a NullPointerException"
  ):
    val w = world()
    val noProv = w.replace(
      "nodes-venue",
      Vector(Map("venueId" -> s(venue), "name" -> s("n")))
    )
    assertEquals(code(project(noProv)), ErrorCode.MalformedRow)
    val nullProv = w.replace(
      "nodes-venue",
      Vector(
        Map(
          "venueId" -> s(venue),
          "name" -> s("n"),
          "provenance" -> ujson.Null
        )
      )
    )
    assertEquals(code(project(nullProv)), ErrorCode.MalformedRow)

  // 9. Provenance ------------------------------------------------------------

  test("runId, snapshotId, sourceRecordId and transformVersion reach the CSV"):
    val p = provenance(
      snapshot = "snap-ü-7",
      record = "dblp/日本語/9",
      transform = "tx/3"
    )
    val (store, report) = exported(world(p))
    // A DatasetSnapshot vertex stands for the snapshot, not for one record:
    // its source record is the snapshot itself. Everything else carries the
    // row's record.
    val vertices = vertexIdsIn(store, report)
    assert(vertices.exists(_.label == "DatasetSnapshot"))
    vertices.foreach { v =>
      assertEquals(v.props("runId"), Prop.Str(runId), v.id)
      assertEquals(v.props("snapshotId"), Prop.Str("snap-ü-7"), v.id)
      assertEquals(
        v.props("sourceRecordId"),
        Prop.Str(
          if v.label == "DatasetSnapshot" then "snap-ü-7" else "dblp/日本語/9"
        ),
        v.id
      )
      assertEquals(v.props("transformVersion"), Prop.Str("tx/3"), v.id)
    }
    edgeIdsIn(store, report).foreach { e =>
      assertEquals(e.props("runId"), Prop.Str(runId), e.id)
      assertEquals(e.props("snapshotId"), Prop.Str("snap-ü-7"), e.id)
      assertEquals(e.props("sourceRecordId"), Prop.Str("dblp/日本語/9"), e.id)
      assertEquals(e.props("transformVersion"), Prop.Str("tx/3"), e.id)
    }

  test("the load manifest carries the export run and transform provenance"):
    val (store, report) = exported(world())
    val manifest = ujson.read(store.get(report.manifestKey).get)
    assertEquals(manifest("runId").str, runId)
    assertEquals(
      manifest("transformVersion").str,
      StorageProvenance.TransformVersion
    )
    assertEquals(manifest("sourceRef").str, s"spark-run:$runId")
    manifest("files").arr.foreach { f =>
      assertEquals(f("sha256").str.length, 64)
      assertEquals(
        f("sha256").str,
        Hashing.sha256(store.get(f("key").str).get)
      )
    }

  test("a malformed provenance row is refused"):
    val w = world()
    val bad = w.replace(
      "nodes-venue",
      Vector(
        Map(
          "venueId" -> s(venue),
          "name" -> s("n"),
          "provenance" -> ujson.Obj("runId" -> "not-a-run")
        )
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)

  // Locally derived AUTHORED and PUBLISHED_IN IDs ---------------------------

  private def authoredIds(p: GraphProjection) =
    p.edges.filter(_.label == "AUTHORED").map(e => e.id)

  test("authored and published-in IDs are stable, well formed and distinct"):
    val a = project(world())
    val b = project(world())
    assertEquals(a.edges.map(_.id), b.edges.map(_.id))
    val authored = a.edges.filter(_.label == "AUTHORED")
    val published = a.edges.filter(_.label == "PUBLISHED_IN")
    authored.foreach(e => assert(e.id.matches("edge:v1:[0-9a-f]{64}"), e.id))
    published.foreach(e => assert(e.id.matches("edge:v1:[0-9a-f]{64}"), e.id))
    assert(
      (authored ++ published).forall(e =>
        !e.id.startsWith("authored:") && !e.id.startsWith("published-in:")
      )
    )
    assertEquals(authored.size, 7)
    assertEquals(a.edges.map(_.id).distinct.size, a.edges.size)

  // Expected IDs come from core only: the evidence is minted from the row's own
  // keys (the mention and work for AUTHORED, the two endpoints for
  // PUBLISHED_IN), never from provenance.
  private val edgePolicy = "scholarchain.storage.edge-policy/1"

  private def coreEvidence(record: String, locator: String): String =
    StableIds.evidence(
      scholarchain.core.SourceType.Derived,
      edgePolicy,
      record,
      scholarchain.core.EvidenceType.Publication,
      locator
    )

  private def coreAuthored(who: String, work: String, mention: String) =
    StableIds.graphEdge(
      scholarchain.core.GraphEdgeType.Authored,
      who,
      work,
      edgePolicy,
      Vector(coreEvidence(mention, work))
    )

  private def corePublished(work: String, to: String) =
    StableIds.graphEdge(
      scholarchain.core.GraphEdgeType.PublishedIn,
      work,
      to,
      edgePolicy,
      Vector(coreEvidence(work, to))
    )

  test("authored and published-in IDs are exactly the core graphEdge IDs"):
    val p = project(world())
    val authored = p.edges.filter(_.label == "AUTHORED")
    val expectedAuthored = world().rows("edges-authored").map { r =>
      coreAuthored(
        r("researcherNodeId").str,
        r("canonicalWorkId").str,
        r("mentionId").str
      )
    }
    assertEquals(authored.map(_.id).toSet, expectedAuthored.toSet)
    assertEquals(expectedAuthored.distinct.size, expectedAuthored.size)
    val published = p.edges.filter(_.label == "PUBLISHED_IN")
    assertEquals(published.map(_.id), Vector(corePublished(w1, venue)))
    (authored ++ published).foreach { e =>
      assertEquals(
        e.props("idDerivation"),
        Prop.Str("core:StableIds.graphEdge")
      )
    }

  test("core edge IDs for these rows never equal a coauthor ID or hash"):
    val p = project(world())
    val derived =
      p.edges.filter(e => e.label == "AUTHORED" || e.label == "PUBLISHED_IN")
    derived.foreach(e => assert(e.id.startsWith("edge:v1:"), e.id))
    val coauthor = p.edges.filter(_.label == "COAUTHORED_WITH")
    coauthor.foreach(e =>
      assert(
        e.id.startsWith("coauthor:v1:") ||
          e.id.startsWith("mention-coauthor:v1:"),
        e.id
      )
    )
    val coauthorIds = coauthor.map(_.id).toSet
    val coauthorHashes = coauthor.map(_.id.split(":").last).toSet
    derived.foreach { e =>
      assert(!coauthorIds.contains(e.id))
      assert(!coauthorHashes.contains(e.id.split(":").last))
    }

  test("authored and published-in differ for equal endpoints and evidence"):
    val same = "work:v1:x"
    val a = StableIds.graphEdge(
      scholarchain.core.GraphEdgeType.Authored,
      r1,
      w1,
      edgePolicy,
      Vector(coreEvidence(m1, w1))
    )
    val b = StableIds.graphEdge(
      scholarchain.core.GraphEdgeType.PublishedIn,
      r1,
      w1,
      edgePolicy,
      Vector(coreEvidence(m1, w1))
    )
    assertNotEquals(a, b)
    assert(same.nonEmpty)

  test("edge ID of a repeated projection and a changed run is unchanged"):
    val a = project(world()).edges.map(_.id)
    val b = project(world()).edges.map(_.id)
    assertEquals(a, b)
    // A changed run keeps every ID, DERIVED_FROM included, because that edge
    // is named by the work and the snapshot, not by the run.
    val rerun = project(world(provenance(run = otherRun))).edges.map(_.id)
    assertEquals(a, rerun)
    // A changed snapshot is a different snapshot node, so only the DERIVED_FROM
    // edges (which name it) change; every other edge keeps its ID.
    val other = project(world(provenance(run = otherRun, snapshot = "s2")))
    val keep = (e: GraphEdgeRow) => e.label != "DERIVED_FROM"
    assertEquals(
      other.edges.filter(keep).map(_.id),
      project(world()).edges.filter(keep).map(_.id)
    )
    val oldDerived =
      project(world()).edges.filter(_.label == "DERIVED_FROM").map(_.id).toSet
    val newDerived =
      other.edges.filter(_.label == "DERIVED_FROM").map(_.id).toSet
    assertEquals(newDerived.size, 3)
    assertEquals(oldDerived.intersect(newDerived), Set.empty[String])

  test("authored ID is sensitive to mention, researcher and work"):
    def idFor(researcher: String, work: String, mention: String): String =
      val w = world()
      val edge = Map(
        "researcherNodeId" -> s(researcher),
        "canonicalWorkId" -> s(work),
        "mentionId" -> s(mention),
        "provenance" -> provJson(provenance())
      )
      // No coauthor edges here, so the single AUTHORED row is all there is.
      // Base scope: a mention node as the researcher is a valid AUTHORED row
      // (an unresolved mention authored it) but not a valid resolution.
      val alone = w
        .replace("edges-coauthored", Vector.empty)
        .replace("edge-support", Vector.empty)
        .replace("edges-authored", Vector(edge))
      authoredIds(projectIn(alone, GraphScope.Base)).head
    val base = idFor(r1, w2, StableIds.mention(w2, 1))
    assertNotEquals(base, idFor(r1, w2, StableIds.mention(w2, 2)))
    assertNotEquals(base, idFor(r2, w2, StableIds.mention(w2, 1)))
    assertNotEquals(base, idFor(r1, w3, StableIds.mention(w2, 1)))
    assertNotEquals(base, idFor(m1, w2, StableIds.mention(w2, 1)))
    assertEquals(base, idFor(r1, w2, StableIds.mention(w2, 1)))

  test("authored ID does not change with provenance, so a rerun keeps it"):
    val a = authoredIds(project(world(provenance(run = runId))))
    val b = authoredIds(
      project(world(provenance(run = otherRun, snapshot = "snap-2")))
    )
    assertEquals(a, b)

  test(
    "published-in ID is field sensitive and differs from authored for equal fields"
  ):
    val w = world()
    val venue2 = "venue:dblp:sigmod"
    val two = w
      .replace(
        "nodes-venue",
        w.rows("nodes-venue") :+ Map(
          "venueId" -> s(venue2),
          "name" -> s("SIGMOD"),
          "provenance" -> provJson(provenance())
        )
      )
      .replace(
        "edges-published-in",
        Vector(
          Map(
            "fromId" -> s(w1),
            "toId" -> s(venue),
            "provenance" -> provJson(provenance())
          ),
          Map(
            "fromId" -> s(w1),
            "toId" -> s(venue2),
            "provenance" -> provJson(provenance())
          ),
          Map(
            "fromId" -> s(w2),
            "toId" -> s(venue),
            "provenance" -> provJson(provenance())
          )
        )
      )
    val ids = project(two).edges.filter(_.label == "PUBLISHED_IN").map(_.id)
    assertEquals(ids.distinct.size, 3)

  test("delimiter-ambiguous endpoint pairs never share an edge ID silently"):
    // from|to joined with "|" is ambiguous when an ID contains "|". Either the
    // IDs differ or the projection refuses; two edges must not merge.
    val w = world()
    val vA = "venue|x"
    val vB = "x"
    val workA = StableIds.work("dblp", "pipe/1")
    val p = provenance()
    def venueRow(id: String): Row =
      Map("venueId" -> s(id), "name" -> s(id), "provenance" -> provJson(p))
    val crafted = w
      .replace("nodes-venue", Vector(venueRow(vA), venueRow(vB)))
      .replace(
        "edges-published-in",
        Vector(
          Map("fromId" -> s(w1), "toId" -> s(vA), "provenance" -> provJson(p)),
          Map("fromId" -> s(w1), "toId" -> s(vB), "provenance" -> provJson(p))
        )
      )
    val outcome =
      try Right(project(crafted))
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(proj) =>
        assertEquals(proj.edges.count(_.label == "PUBLISHED_IN"), 2)
      case Left(c) => assertEquals(c, ErrorCode.DuplicateId)
    assert(workA.nonEmpty)

  // Edge-support must match its edge and its authors -------------------------

  private def refusedWith(w: World, c: ErrorCode, fragment: String = ""): Unit =
    val e = intercept[StorageException](project(w))
    assertEquals(e.code, c, e.getMessage)
    assert(e.getMessage.contains(fragment), e.getMessage)

  private def edgeIndex(w: World, a: String, b: String): Int =
    val (x, y) = StableIds.orderedPair(a, b)
    w.rows("edges-coauthored")
      .indexWhere(r => r("researcherA") == s(x) && r("researcherB") == s(y))

  private def supportOf(w: World, edge: Row): Vector[Int] =
    w.rows("edge-support")
      .indices
      .filter { i =>
        w.rows("edge-support")(i)("edgeId") == edge("edgeId")
      }
      .toVector

  private def updateSupport(
      w: World,
      indices: Vector[Int],
      f: Row => Row
  ): World =
    w.replace(
      "edge-support",
      w.rows("edge-support").zipWithIndex.map { case (r, i) =>
        if indices.contains(i) then f(r) else r
      }
    )

  test("a support row naming other endpoints than its edge is refused"):
    val w = world()
    val rr = w.rows("edges-coauthored")(edgeIndex(w, r1, r2))
    // endpoints swapped
    refusedWith(
      updateSupport(
        w,
        supportOf(w, rr).take(1),
        r =>
          r.updated("researcherA", r("researcherB"))
            .updated("researcherB", r("researcherA"))
      ),
      ErrorCode.MissingEvidence,
      "different endpoints"
    )
    // a different researcher entirely (no mention involved, so not legacy)
    val other = StableIds.researcherFromExternal("openalex", "A3")
    refusedWith(
      updateSupport(
        w,
        supportOf(w, rr).take(1),
        _.updated("researcherB", s(other))
      ),
      ErrorCode.MissingEvidence,
      "different endpoints"
    )
    // the same on the mention-mention edge
    val mm = w.rows("edges-coauthored")(edgeIndex(w, m1, m2))
    refusedWith(
      updateSupport(w, supportOf(w, mm), _.updated("researcherB", s(r1))),
      ErrorCode.MissingEvidence,
      "different endpoints"
    )

  test("a support row for another window than its edge is refused"):
    val w = world()
    val rr = w.rows("edges-coauthored")(edgeIndex(w, r1, r2))
    refusedWith(
      updateSupport(
        w,
        supportOf(w, rr).take(1),
        _.updated("window", s("2000-2001"))
      ),
      ErrorCode.MissingEvidence,
      "different window"
    )
    // the matching window text is accepted
    val same =
      updateSupport(w, supportOf(w, rr), _.updated("window", s(windowLabel)))
    assertEquals(project(same).edges.size, extendedEdgeCount)

  private def withoutAuthored(w: World, who: String, work: String): World =
    w.replace(
      "edges-authored",
      w.rows("edges-authored")
        .filterNot(r =>
          r("researcherNodeId") == s(who) && r("canonicalWorkId") == s(work)
        )
    )

  test("an endpoint that did not author a cited work is refused"):
    val w = world()
    // researcher endpoints, either side, either work
    Vector((r1, w2), (r2, w2), (r1, w3), (r2, w3)).foreach { case (who, work) =>
      refusedWith(
        withoutAuthored(w, who, work),
        ErrorCode.MissingEvidence,
        "not both endpoints authored"
      )
    }
    // mention endpoints are checked as well
    refusedWith(
      withoutAuthored(w, m1, w1),
      ErrorCode.MissingEvidence,
      "not both endpoints authored"
    )
    refusedWith(
      withoutAuthored(w, m2, w1),
      ErrorCode.MissingEvidence,
      "not both endpoints authored"
    )
    // the mixed edge (m2, r1) needs r1 on w1
    refusedWith(
      withoutAuthored(w, r1, w1),
      ErrorCode.MissingEvidence,
      "not both endpoints authored"
    )

  test("an author of a different work does not stand in for the cited work"):
    val w = world()
    // r2 authored w2 and w3 only; relabel its w3 row as w1.
    val swapped = w.replace(
      "edges-authored",
      w.rows("edges-authored").map { r =>
        if r("researcherNodeId") == s(r2) && r("canonicalWorkId") == s(w3) then
          r.updated("canonicalWorkId", s(w1))
        else r
      }
    )
    refusedWith(swapped, ErrorCode.MissingEvidence, "not both endpoints")

  test("an AUTHORED row not counted in metrics cannot support a pair"):
    val w = world()
    val uncounted = w.replace(
      "edges-authored",
      w.rows("edges-authored").map { r =>
        if r("researcherNodeId") == s(r2) && r("canonicalWorkId") == s(w2) then
          r.updated("countedInMetrics", ujson.Bool(false))
        else r
      }
    )
    refusedWith(uncounted, ErrorCode.MissingEvidence, "not both endpoints")
    // A second, counted row for the same author and work restores the support.
    val restored = uncounted.replace(
      "edges-authored",
      uncounted.rows("edges-authored") :+
        authoredRow(r2, w2, StableIds.mention(w2, 5), provenance())
    )
    // One more AUTHORED edge and, as its mention is resolved, one more
    // RESOLVES_TO edge.
    assertEquals(project(restored).edges.size, extendedEdgeCount + 2)
    assertEquals(
      projectIn(restored, GraphScope.Base).edges.size,
      baseEdgeCount + 1
    )
    // Only the flag matters: an absent flag counts, as Spark's own rows do.
    val noFlag = w.replace(
      "edges-authored",
      w.rows("edges-authored").map(_ - "countedInMetrics")
    )
    assertEquals(project(noFlag).edges.size, extendedEdgeCount)

  test("an AUTHORED row by someone else does not authorize the pair"):
    val w = world()
    val other = StableIds.researcherFromExternal("openalex", "A3")
    val replaced = w
      .replace(
        "nodes-researcher",
        w.rows("nodes-researcher") :+ researcherRow(
          other,
          "Three",
          provenance()
        )
      )
      .replace(
        "edges-authored",
        w.rows("edges-authored").map { r =>
          if r("researcherNodeId") == s(r2) && r("canonicalWorkId") == s(w2)
          then r.updated("researcherNodeId", s(other))
          else r
        }
      )
    refusedWith(replaced, ErrorCode.MissingEvidence, "not both endpoints")

  test("an edge window text that differs from its years is refused"):
    val w = world()
    val i = edgeIndex(w, r1, r2)
    Vector("2019-2022", "2020-2023", "2019-2023 ", "all", "", "2019_2023")
      .foreach { text =>
        val row = w.rows("edges-coauthored")(i).updated("window", s(text))
        refusedWith(
          w.replace(
            "edges-coauthored",
            w.rows("edges-coauthored").updated(i, row)
          ),
          ErrorCode.InvalidEdgeId,
          "window text"
        )
      }

  test("the all-years window is spelled all, and only all"):
    val w = world()
    val full = YearWindow(1, 9999)
    val rr =
      coauthorRow(r1, r2, 2, 2020, 2021, Seq(w2, w3), provenance(), win = full)
    val others = w
      .rows("edges-coauthored")
      .filterNot(r =>
        r("researcherA") == s(StableIds.orderedPair(r1, r2)._1) &&
          r("researcherB") == s(StableIds.orderedPair(r1, r2)._2)
      )
    val supports = w
      .rows("edge-support")
      .filterNot(r =>
        r("researcherA") == s(StableIds.orderedPair(r1, r2)._1) &&
          r("researcherB") == s(StableIds.orderedPair(r1, r2)._2)
      ) ++ Vector(supportRow(rr, w2, 2020), supportRow(rr, w3, 2021))
    def build(label: String) =
      w.replace("edges-coauthored", others :+ rr.updated("window", s(label)))
        .replace("edge-support", supports)
    refusedWith(build("1-9999"), ErrorCode.InvalidEdgeId, "window text")
    assertEquals(project(build("all")).edges.size, extendedEdgeCount)
    // support rows that carry the label must carry the same one
    val labelled = build("all").replace(
      "edge-support",
      supports.map { r =>
        if r("edgeId") == rr("edgeId") then r.updated("window", s("all"))
        else r
      }
    )
    assertEquals(project(labelled).edges.size, extendedEdgeCount)

  test("a tab, newline or NUL inside an ID is refused, never rewritten"):
    val p = provenance()
    def venueWith(id: String): World =
      val w = world()
      w.replace(
        "nodes-venue",
        Vector(
          Map("venueId" -> s(id), "name" -> s("n"), "provenance" -> provJson(p))
        )
      ).replace(
        "edges-published-in",
        Vector(
          Map("fromId" -> s(w1), "toId" -> s(id), "provenance" -> provJson(p))
        )
      )
    Vector("ven\tue", "ven\nue", "ven\rue").foreach { id =>
      refusedWith(
        venueWith(id),
        ErrorCode.MalformedRow,
        "IDs are not normalized"
      )
    }
    refusedWith(venueWith("ven\u0000ue"), ErrorCode.NullCharacter)
    // the same fields as descriptive text are still normalized
    val named = project(
      venueWith("ok").replace(
        "nodes-venue",
        Vector(
          Map(
            "venueId" -> s("ok"),
            "name" -> s("a\tb\nc"),
            "provenance" -> provJson(p)
          )
        )
      )
    )
    assertEquals(
      named.vertices.find(_.id == "ok").get.props("name"),
      Prop.Str("a b c")
    )
    assert(named.normalizedValues >= 1)
    // an ID in an edge column
    val w = world()
    val authored = w.rows("edges-authored")
    Vector("mentionId", "researcherNodeId", "canonicalWorkId").foreach { col =>
      val bad = authored.updated(
        0,
        authored(0).updated(col, s(authored(0)(col).str + "\t"))
      )
      val e = intercept[StorageException](
        project(w.replace("edges-authored", bad))
      )
      assert(
        Set(ErrorCode.MalformedRow, ErrorCode.NullCharacter).contains(e.code),
        s"$col: ${e.getMessage}"
      )
    }
    // an ID in a node column
    val researchers = w.rows("nodes-researcher")
    val nodeBad = researchers.updated(
      0,
      researchers(0).updated("nodeId", s(researchers(0)("nodeId").str + "\n"))
    )
    val e2 = intercept[StorageException](
      project(w.replace("nodes-researcher", nodeBad))
    )
    assert(e2.code == ErrorCode.MalformedRow, e2.getMessage)

  // D13 ruling 2: the Extended default and the explicit Base scope ------------

  private def resolvedRows(w: World): Vector[Row] =
    w.rows("edges-authored")
      .filter(r => r("mentionId") != r("researcherNodeId"))

  private def coreEdge(
      edgeType: scholarchain.core.GraphEdgeType,
      from: String,
      to: String
  ): String =
    StableIds.graphEdge(
      edgeType,
      from,
      to,
      edgePolicy,
      Vector(coreEvidence(from, to))
    )

  private val extendedOnlyLabels =
    Set("DatasetSnapshot", "RESOLVES_TO", "DERIVED_FROM")

  test("explicit Base scope still gives the old counts and nothing extra"):
    val base = projectIn(world(), GraphScope.Base)
    assertEquals(base.vertices.size, baseVertexCount)
    assertEquals(base.edges.size, baseEdgeCount)
    assertEquals(base.vertices.map(_.id).toSet, baseNodes)
    assertEquals(
      base.edges.map(_.label).toSet,
      Set("AUTHORED", "COAUTHORED_WITH", "PUBLISHED_IN")
    )
    assertEquals(base.edges.count(_.label == "AUTHORED"), 7)
    assertEquals(
      base.vertices.map(_.label).toSet,
      Set("AuthorMention", "Researcher", "Work", "Venue")
    )
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(base, runId, store)
    assertEquals((report.vertexCount, report.edgeCount), (8, 11))
    assert(!report.files.exists(f => extendedOnlyLabels.contains(f.label)))
    val neptune = new InMemoryNeptune(store)
    val loaded = neptune.load(report.manifestKey)
    assertEquals(
      (loaded.inserted, loaded.updated, loaded.unchanged),
      (baseInserted, 0, 0)
    )
    assertEquals((neptune.vertices.size, neptune.edges.size), (8, 11))

  test("the default is Extended and Extended only adds to Base"):
    val w = world()
    val base = projectIn(w, GraphScope.Base)
    val ext = projectIn(w, GraphScope.Extended)
    assertEquals(project(w), ext)
    assertEquals(
      new SparkGraphProjector(w.source, Bounds).project(),
      new SparkGraphProjector(w.source, Bounds, GraphScope.Extended).project()
    )
    // Nothing of Base changes or disappears.
    base.vertices.foreach(v => assert(ext.vertices.contains(v), v.id))
    base.edges.foreach(e => assert(ext.edges.contains(e), e.id))
    val addedVertices = ext.vertices.filterNot(base.vertices.contains)
    val addedEdges = ext.edges.filterNot(base.edges.contains)
    assertEquals(
      addedVertices.map(_.label).groupBy(identity).view.mapValues(_.size).toMap,
      Map("AuthorMention" -> 5, "DatasetSnapshot" -> 1)
    )
    assertEquals(
      addedEdges.map(_.label).groupBy(identity).view.mapValues(_.size).toMap,
      Map("RESOLVES_TO" -> 5, "DERIVED_FROM" -> 3)
    )

  test("a Base load followed by an Extended load adds only the new elements"):
    val store = new InMemoryObjectStore
    val base = NeptuneExporter.exportTo(
      projectIn(world(), GraphScope.Base),
      runId,
      store
    )
    val ext = NeptuneExporter.exportTo(
      projectIn(world(), GraphScope.Extended),
      otherRun,
      store
    )
    val neptune = new InMemoryNeptune(store)
    assertEquals(neptune.load(base.manifestKey).inserted, baseInserted)
    val second = neptune.load(ext.manifestKey)
    // 6 new vertices (5 mentions, 1 snapshot) and 8 new edges (5 + 3).
    assertEquals(second.inserted, 14)
    assertEquals(second.inserted + second.updated + second.unchanged, 33)
    assertEquals(
      (neptune.vertices.size, neptune.edges.size),
      (extendedVertexCount, extendedEdgeCount)
    )
    val again = neptune.load(ext.manifestKey)
    assertEquals(
      (again.inserted, again.updated, again.unchanged),
      (0, 0, extendedInserted)
    )

  test(
    "RESOLVES_TO: one core edge per resolved mention, mention to researcher"
  ):
    val w = world()
    val p = project(w)
    val resolves = p.edges.filter(_.label == "RESOLVES_TO")
    val expected = resolvedRows(w)
      .map(r => (r("mentionId").str, r("researcherNodeId").str))
      .toSet
    assertEquals(resolves.map(e => (e.from, e.to)).toSet, expected)
    assertEquals(resolves.size, 5)
    resolves.foreach { e =>
      assert(isEdgeV1(e.id), e.id)
      assertEquals(
        e.id,
        coreEdge(scholarchain.core.GraphEdgeType.ResolvesTo, e.from, e.to)
      )
      assertEquals(
        e.props("idDerivation"),
        Prop.Str("core:StableIds.graphEdge")
      )
      assert(e.from.startsWith("mention:"), e.from)
      assert(!e.to.startsWith("mention:"), e.to)
      assert(!e.id.startsWith("coauthor:") && !e.id.startsWith("mention-"))
    }
    // An unresolved mention is its own node and resolves to nothing.
    assert(resolves.forall(e => e.from != m1 && e.from != m2))
    assertEquals(p.edges.map(_.id).distinct.size, p.edges.size)
    // The edge ID differs from the AUTHORED edge of the same row.
    val authoredIds = p.edges.filter(_.label == "AUTHORED").map(_.id).toSet
    assertEquals(
      authoredIds.intersect(resolves.map(_.id).toSet),
      Set.empty[String]
    )

  test("a resolved mention becomes an AuthorMention vertex built from its row"):
    val w = world()
    val p = project(w)
    val byId = p.vertices.map(v => v.id -> v).toMap
    resolvedRows(w).foreach { r =>
      val v = byId(r("mentionId").str)
      assertEquals(v.label, "AuthorMention")
      assertEquals(v.props("resolution"), Prop.Str("resolved"))
      assertEquals(
        v.props("canonicalWorkId"),
        Prop.Str(r("canonicalWorkId").str)
      )
      assertEquals(v.props("runId"), Prop.Str(runId))
    }
    // The two unresolved mentions are the Spark nodes, untouched.
    Vector(m1, m2).foreach { id =>
      assertEquals(byId(id).label, "AuthorMention")
      assert(!byId(id).props.contains("resolution"), id)
    }

  test("RESOLVES_TO refuses a mention ID that is not a core mention ID"):
    val w = world()
    val authored = w.rows("edges-authored")
    Vector("mention:mention-1", w1, r2, "plain-text", "mention:").foreach {
      bad =>
        val row = authored(0).updated("mentionId", s(bad))
        val e = intercept[StorageException](
          project(w.replace("edges-authored", authored.updated(0, row)))
        )
        assertEquals(e.code, ErrorCode.MalformedRow, bad)
        assert(e.getMessage.contains("core mention"), e.getMessage)
    }

  test("RESOLVES_TO refuses a researcher node that is itself a mention"):
    val w = world()
    val authored = w.rows("edges-authored")
    // m1 is a real vertex, so only the resolution rule can refuse this row.
    val row = authored(0).updated("researcherNodeId", s(m1))
    refusedWith(
      w.replace("edges-authored", authored.updated(0, row)),
      ErrorCode.MalformedRow,
      "itself a mention"
    )

  test("RESOLVES_TO targets must be researcher vertices"):
    val w = world()
    val authored = w.rows("edges-authored")
    val ghost = StableIds.researcherFromExternal("openalex", "A404")
    val row = authored(0).updated("researcherNodeId", s(ghost))
    assertEquals(
      code(project(w.replace("edges-authored", authored.updated(0, row)))),
      ErrorCode.MissingEndpoint
    )
    // A work or venue is not a researcher either.
    Vector(w1, venue).foreach { notResearcher =>
      val bad = authored.updated(
        0,
        authored(0).updated("researcherNodeId", s(notResearcher))
      )
      assertEquals(
        code(project(w.replace("edges-authored", bad))),
        ErrorCode.MissingEndpoint,
        notResearcher
      )
    }

  test(
    "a resolved mention that Spark also lists with other content is refused"
  ):
    val w = world()
    val mention = StableIds.mention(w2, 1)
    val clash = w.replace(
      "nodes-researcher",
      w.rows("nodes-researcher") :+
        researcherRow(mention, "Listed By Spark", provenance())
    )
    assertEquals(code(project(clash)), ErrorCode.DuplicateId)

  test("a repeated AUTHORED row adds neither a vertex nor an edge"):
    val w = world()
    val authored = w.rows("edges-authored")
    val doubled = w.replace("edges-authored", authored ++ authored)
    val p = project(doubled)
    assertEquals(p.vertices.size, extendedVertexCount)
    assertEquals(p.edges.size, extendedEdgeCount)
    assertEquals(p, project(w))

  test("one mention is never resolved to two researchers"):
    val w = world()
    val authored = w.rows("edges-authored")
    // Same mention and work as row 0 (r1), claimed by r2 as well.
    val second = authored(0).updated("researcherNodeId", s(r2))
    val outcome =
      try Right(project(w.replace("edges-authored", authored :+ second)))
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(p) =>
        val perMention = p.edges
          .filter(_.label == "RESOLVES_TO")
          .groupBy(_.from)
          .view
          .mapValues(_.size)
          .toMap
        assert(perMention.values.forall(_ == 1), perMention.toString)
      case Left(c) =>
        assert(
          Set(ErrorCode.DuplicateId, ErrorCode.MalformedRow).contains(c),
          c.toString
        )

  test("DERIVED_FROM: every work points at its snapshot with a core edge ID"):
    val p = project(world())
    val derived = p.edges.filter(_.label == "DERIVED_FROM")
    assertEquals(
      derived.map(e => (e.from, e.to)).toSet,
      Set((w1, snapshotNode), (w2, snapshotNode), (w3, snapshotNode))
    )
    assertEquals(derived.size, 3)
    derived.foreach { e =>
      assert(isEdgeV1(e.id), e.id)
      assertEquals(
        e.id,
        coreEdge(scholarchain.core.GraphEdgeType.DerivedFrom, e.from, e.to)
      )
      assertEquals(
        e.props("idDerivation"),
        Prop.Str("core:StableIds.graphEdge")
      )
      assertEquals(e.props("snapshotId"), Prop.Str(snapshotNode))
      assertEquals(e.props("runId"), Prop.Str(runId))
    }
    val snapshots = p.vertices.filter(_.label == "DatasetSnapshot")
    assertEquals(snapshots.map(_.id), Vector(snapshotNode))
    assertEquals(snapshots.head.props("runId"), Prop.Str(runId))
    assertEquals(
      snapshots.head.props("transformVersion"),
      Prop.Str("scholarchain.spark/1")
    )
    // Endpoints exist.
    val ids = p.vertices.map(_.id).toSet
    derived.foreach(e => assert(ids(e.from) && ids(e.to), e.id))
    // A work has exactly one snapshot edge.
    assertEquals(derived.groupBy(_.from).values.map(_.size).toSet, Set(1))

  test("DERIVED_FROM: several snapshots give several vertices and own edges"):
    val w = world()
    val works = w.rows("nodes-work")
    val moved = works.updated(
      2,
      workRow(w3, 2021, "Work three", provenance(snapshot = "snap-2"))
    )
    val p = project(w.replace("nodes-work", moved))
    assertEquals(
      p.vertices.filter(_.label == "DatasetSnapshot").map(_.id).toSet,
      Set("snap-1", "snap-2")
    )
    val target = p.edges
      .filter(_.label == "DERIVED_FROM")
      .map(e => e.from -> e.to)
      .toMap
    assertEquals(target, Map(w1 -> "snap-1", w2 -> "snap-1", w3 -> "snap-2"))
    assertEquals(p.vertices.size, extendedVertexCount + 1)
    assertEquals(p.edges.size, extendedEdgeCount)

  test("DERIVED_FROM: works that disagree on one snapshot's run are refused"):
    val w = world()
    val works = w.rows("nodes-work")
    val otherRunWork = works.updated(
      1,
      workRow(w2, 2020, "Work two", provenance(run = otherRun))
    )
    assertEquals(
      code(project(w.replace("nodes-work", otherRunWork))),
      ErrorCode.DuplicateId
    )
    val otherTransform = works.updated(
      1,
      workRow(
        w2,
        2020,
        "Work two",
        provenance(transform = "scholarchain.spark/2")
      )
    )
    assertEquals(
      code(project(w.replace("nodes-work", otherTransform))),
      ErrorCode.DuplicateId
    )
    // The same disagreement is harmless where no snapshot node exists.
    assertEquals(
      projectIn(
        w.replace("nodes-work", otherRunWork),
        GraphScope.Base
      ).vertices.size,
      baseVertexCount
    )
    // Different records of one snapshot are normal and do not conflict.
    val otherRecord = works.updated(
      1,
      workRow(w2, 2020, "Work two", provenance(record = "rec/other"))
    )
    val p = project(w.replace("nodes-work", otherRecord))
    assertEquals(p.vertices.count(_.label == "DatasetSnapshot"), 1)
    assertEquals(p.edges.size, extendedEdgeCount)

  test("DERIVED_FROM: distinct snapshot names are never merged into one node"):
    val w = world()
    val works = w.rows("nodes-work")
    // Both names print alike once a tab is read as a space.
    val tabbed = works
      .updated(0, workRow(w1, 2020, "Work one", provenance(snapshot = "a\tb")))
      .updated(1, workRow(w2, 2020, "Work two", provenance(snapshot = "a b")))
    val outcome =
      try Right(project(w.replace("nodes-work", tabbed)))
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(p) =>
        val nodes = p.vertices.filter(_.label == "DatasetSnapshot")
        nodes.foreach(v => assert(!v.id.exists(_.isControl), v.id))
        assertEquals(nodes.size, 2, "two different snapshots became one node")
      case Left(c) =>
        assert(
          Set(ErrorCode.MalformedRow, ErrorCode.DuplicateId).contains(c),
          c.toString
        )

  test("a NUL in a snapshot ID is refused, never loaded"):
    val w = world()
    val works = w.rows("nodes-work")
    val bad = works.updated(
      0,
      workRow(w1, 2020, "Work one", provenance(snapshot = "snap\u00001"))
    )
    assertEquals(
      code(project(w.replace("nodes-work", bad))),
      ErrorCode.NullCharacter
    )

  test("the Extended export writes one file per new label and reads back"):
    val (store, report) = exported(world())
    assertEquals(
      report.files.map(_.label).toSet,
      Set(
        "AuthorMention",
        "Researcher",
        "Work",
        "Venue",
        "DatasetSnapshot",
        "AUTHORED",
        "COAUTHORED_WITH",
        "PUBLISHED_IN",
        "RESOLVES_TO",
        "DERIVED_FROM"
      )
    )
    val edges = edgeIdsIn(store, report)
    val resolves = edges.filter(_.label == "RESOLVES_TO")
    val derived = edges.filter(_.label == "DERIVED_FROM")
    assertEquals(resolves.size, 5)
    assertEquals(derived.size, 3)
    assertEquals(
      resolves.map(e => (e.from, e.to)).toSet,
      resolvedRows(world())
        .map(r => (r("mentionId").str, r("researcherNodeId").str))
        .toSet
    )
    assertEquals(edges.map(_.id).distinct.size, extendedEdgeCount)
    (resolves ++ derived).foreach(e => assert(isEdgeV1(e.id), e.id))
    val vertices = vertexIdsIn(store, report)
    assertEquals(
      vertices.filter(_.label == "DatasetSnapshot").map(_.id),
      Vector(snapshotNode)
    )
    assertEquals(
      vertices.filter(_.label == "AuthorMention").map(_.id).toSet,
      resolvedMentions + m1 + m2
    )
    // The CSV round trip keeps the projection exactly.
    val p = project(world())
    assertEquals(vertices.sortBy(_.id), p.vertices.sortBy(_.id))
    assertEquals(edges.sortBy(_.id), p.edges.sortBy(_.id))

  test("the read-only queries are unchanged by the extra edges"):
    val (store, report) = exported(world())
    val neptune = new InMemoryNeptune(store)
    neptune.load(report.manifestKey)
    val coauthors = neptune.run(CypherCatalog.coauthorsOf(r1, windowLabel, 50))
    assertEquals(
      coauthors.map(_("edgeId").str).toSet,
      Set(
        StableIds.coauthorEdgeFor(r1, r2, window, policy),
        StableIds.coauthorEdgeFor(r1, m2, window, policy)
      )
    )
    val resolvesId =
      project(world()).edges.find(_.label == "RESOLVES_TO").get.id
    val evidence = neptune.run(CypherCatalog.edgeEvidence(resolvesId, 5))
    assertEquals(evidence.size, 1)
    assertEquals(evidence.head("evidencePointer"), ujson.Null)

  test("a rerun under another run ID keeps every vertex and edge ID"):
    val store = new InMemoryObjectStore
    val one = NeptuneExporter.exportTo(project(world()), runId, store)
    val rerun = project(world(provenance(run = otherRun)))
    val two = NeptuneExporter.exportTo(rerun, otherRun, store)
    assertEquals(
      project(world()).vertices.map(_.id),
      rerun.vertices.map(_.id)
    )
    assertEquals(project(world()).edges.map(_.id), rerun.edges.map(_.id))
    val neptune = new InMemoryNeptune(store)
    neptune.load(one.manifestKey)
    val loaded = neptune.load(two.manifestKey)
    assertEquals(loaded.inserted, 0)
    assertEquals(
      loaded.updated + loaded.unchanged,
      extendedInserted
    )
    assertEquals(
      (neptune.vertices.size, neptune.edges.size),
      (extendedVertexCount, extendedEdgeCount)
    )

  test("legacy coauthor:v1: on a mention endpoint is refused in either scope"):
    Vector(GraphScope.Base, GraphScope.Extended).foreach { scope =>
      Vector(0, 2).foreach { i =>
        assertEquals(
          code(projectIn(withEdgeId(world(), i, legacy), scope)),
          ErrorCode.LegacyEdgeId,
          s"$scope $i"
        )
      }
      val w = world()
      val support = w.rows("edge-support")
      assertEquals(
        code(
          projectIn(
            w.replace(
              "edge-support",
              support.updated(0, support(0).updated("edgeId", s(legacy)))
            ),
            scope
          )
        ),
        ErrorCode.LegacyEdgeId,
        scope.toString
      )
    }
