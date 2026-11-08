package scholarchain.storage

import scholarchain.core.{GraphEdgeType, StableIds}

/** RESOLVES_TO and DERIVED_FROM, the two README relationship types the Phase 6
  * outputs back, and the three they do not (AFFILIATED_WITH, HAS_TOPIC,
  * SUPPORTED_BY).
  */
class GraphScopeSuite extends munit.FunSuite:
  import Fixtures.*

  private val bounds = LoadBounds.Default
  private val edgePolicy = "scholarchain.storage.edge-policy/1"

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def project(
      tables: Map[String, Vector[Row]],
      scope: GraphScope = GraphScope.Extended
  ): GraphProjection =
    new SparkGraphProjector(source(tables), bounds, scope).project()

  private def replace(
      table: String,
      f: Vector[Row] => Vector[Row]
  ): Map[String, Vector[Row]] =
    val tables = graphTables()
    tables.updated(table, f(tables(table)))

  /** The row with one provenance field replaced. */
  private def patch(row: Row, field: String, value: String): Row =
    row.get("provenance") match
      case Some(p) =>
        row.updated(
          "provenance",
          ujson.Obj.from(
            p.obj.toVector :+ (field -> (ujson.Str(value): ujson.Value))
          )
        )
      case None => row

  private def evidence(record: String, locator: String): String =
    StableIds.evidence(
      scholarchain.core.SourceType.Derived,
      edgePolicy,
      record,
      scholarchain.core.EvidenceType.Publication,
      locator
    )

  private def coreId(
      edgeType: GraphEdgeType,
      from: String,
      to: String,
      record: String,
      locator: String
  ): String =
    StableIds.graphEdge(
      edgeType,
      from,
      to,
      edgePolicy,
      Vector(evidence(record, locator))
    )

  // The fixture's AUTHORED rows: four resolved mentions and the unresolved m1.
  private val resolved = Vector(
    (r1, w1, StableIds.mention(w1, 1)),
    (r2, w1, StableIds.mention(w1, 3)),
    (r1, w2, StableIds.mention(w2, 1)),
    (r2, w2, StableIds.mention(w2, 2))
  )

  test("the default scope is Extended; Base stays selectable") {
    val default = new SparkGraphProjector(source(), bounds).project()
    assertEquals(default, project(graphTables(), GraphScope.Extended))
    assertEquals(default.vertices.size, 7 + 4 + 1)
    assertEquals(default.edges.size, 8 + 4 + 3)
    val base =
      new SparkGraphProjector(source(), bounds, GraphScope.Base).project()
    assertEquals(
      base.edges.map(_.label).toSet,
      Set("AUTHORED", "COAUTHORED_WITH", "PUBLISHED_IN")
    )
    assert(!base.vertices.exists(_.label == "DatasetSnapshot"))
    assertEquals(base.vertices.size, 7)
    assertEquals(base.edges.size, 5 + 1 + 2)
  }

  test("RESOLVES_TO links each resolved mention to its canonical researcher") {
    val projection = project(graphTables())
    val edges = projection.edges.filter(_.label == "RESOLVES_TO")
    assertEquals(
      edges.map(e => (e.from, e.to)).toSet,
      resolved.map((who, _, mention) => (mention, who)).toSet
    )
    assertEquals(edges.size, 4)
    // the unresolved mention is its own node and resolves to nothing
    assert(!edges.exists(e => e.from == m1 || e.to == m1))
    val mentions =
      projection.vertices.filter(v => resolved.map(_._3).contains(v.id))
    assertEquals(mentions.size, 4)
    mentions.foreach { v =>
      assertEquals(v.label, "AuthorMention")
      assertEquals(v.props("resolution"), Prop.Str("resolved"))
      assertEquals(v.props("runId"), Prop.Str(runId))
      assertEquals(v.props("sourceRecordId"), Prop.Str(v.id))
      assert(v.props.contains("canonicalWorkId"))
      assert(v.props.contains("authorPosition"))
    }
    // the unresolved vertex is unchanged
    assertEquals(
      projection.vertices.find(_.id == m1).get.props("nodeKind"),
      Prop.Str("unresolved-mention")
    )
  }

  test(
    "RESOLVES_TO and DERIVED_FROM IDs are core graphEdge IDs, rerun-stable"
  ) {
    val projection = project(graphTables())
    val ids = projection.edges.filter(_.label == "RESOLVES_TO").map(_.id).toSet
    assertEquals(
      ids,
      resolved
        .map((who, _, mention) =>
          coreId(GraphEdgeType.ResolvesTo, mention, who, mention, who)
        )
        .toSet
    )
    val derived =
      projection.edges
        .filter(_.label == "DERIVED_FROM")
        .map(e => e.from -> e.id)
    assertEquals(
      derived.toSet,
      Set(w1, w2, w3)
        .map(w =>
          w -> coreId(
            GraphEdgeType.DerivedFrom,
            w,
            "hadoop-snapshot:s1",
            w,
            "hadoop-snapshot:s1"
          )
        )
    )
    (ids ++ derived.map(_._2)).foreach(id =>
      assert(id.matches("edge:v1:[0-9a-f]{64}"), id)
    )
    // another run of the same data keeps every edge ID and vertex ID
    val rerun = project(
      graphTables().map { case (table, rows) =>
        table -> rows.map(
          patch(_, "runId", "run:v1:22222222-2222-4222-8222-222222222222")
        )
      }
    )
    assertEquals(rerun.edges.map(_.id), projection.edges.map(_.id))
    assertEquals(rerun.vertices.map(_.id), projection.vertices.map(_.id))
    assertEquals(
      rerun.edges.map(_.props("runId")).distinct,
      Vector(Prop.Str("run:v1:22222222-2222-4222-8222-222222222222"))
    )
  }

  test(
    "DERIVED_FROM points each work at the DatasetSnapshot of its provenance"
  ) {
    val projection = project(graphTables())
    val snapshots = projection.vertices.filter(_.label == "DatasetSnapshot")
    assertEquals(snapshots.map(_.id), Vector("hadoop-snapshot:s1"))
    val snapshot = snapshots.head
    assertEquals(snapshot.props("snapshotId"), Prop.Str("hadoop-snapshot:s1"))
    assertEquals(snapshot.props("runId"), Prop.Str(runId))
    assertEquals(
      snapshot.props("transformVersion"),
      Prop.Str("spark-analytics/1.1.0")
    )
    val edges = projection.edges.filter(_.label == "DERIVED_FROM")
    assertEquals(edges.map(_.from).toSet, Set(w1, w2, w3))
    assertEquals(edges.map(_.to).toSet, Set("hadoop-snapshot:s1"))
    edges.foreach { e =>
      assertEquals(e.props("sourceRecordId"), Prop.Str(e.from))
      assertEquals(e.props("snapshotId"), Prop.Str("hadoop-snapshot:s1"))
    }
  }

  test("two snapshots give two context nodes, and each work its own") {
    val tables = replace(
      "nodes-work",
      _.map { row =>
        if row("canonicalWorkId").str == w3 then
          row + ("provenance" -> ujson.Obj.from(
            row("provenance").obj ++ Map(
              "snapshotId" -> ujson.Str("hadoop-snapshot:s2")
            )
          ))
        else row
      }
    )
    val projection = project(tables)
    assertEquals(
      projection.vertices.filter(_.label == "DatasetSnapshot").map(_.id),
      Vector("hadoop-snapshot:s1", "hadoop-snapshot:s2")
    )
    val derived = projection.edges
      .filter(_.label == "DERIVED_FROM")
      .map(e => e.from -> e.to)
      .toMap
    assertEquals(derived(w3), "hadoop-snapshot:s2")
    assertEquals(derived(w1), "hadoop-snapshot:s1")
  }

  test("one snapshot with two different runs is refused, not merged") {
    val tables = replace(
      "nodes-work",
      _.map { row =>
        if row("canonicalWorkId").str == w3 then
          row + ("provenance" -> ujson.Obj.from(
            row("provenance").obj ++ Map(
              "runId" -> ujson.Str(
                "run:v1:33333333-3333-4333-8333-333333333333"
              )
            )
          ))
        else row
      }
    )
    assertEquals(code(project(tables)), ErrorCode.DuplicateId)
  }

  test("a resolved mention that is not a core mention ID is refused") {
    val bad = replace(
      "edges-authored",
      _.map(row =>
        if row("mentionId").str == StableIds.mention(w1, 1) then
          row + ("mentionId" -> ujson.Str("m-not-core"))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)
    // the base scope never reads the mention as a vertex, so it is unchanged
    assertEquals(project(bad, GraphScope.Base).edges.size, 8)
  }

  test("a mention that differs from a mention researcher node is refused") {
    val bad = replace(
      "edges-authored",
      _.map(row =>
        if row("mentionId").str == StableIds.mention(w1, 1) then
          row + ("researcherNodeId" -> ujson.Str(m1))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)
  }

  test(
    "a resolved mention whose researcher is no vertex is a missing endpoint"
  ) {
    val bad = replace("nodes-researcher", _.filterNot(_("nodeId").str == r2))
    assertEquals(code(project(bad)), ErrorCode.MissingEndpoint)
  }

  test("a repeated mention row is one vertex, a conflicting one is refused") {
    val row = graphTables()("edges-authored").head
    val same = replace("edges-authored", _ :+ row)
    assertEquals(project(same).vertices, project(graphTables()).vertices)
    val differs = replace(
      "edges-authored",
      _ :+ (row + ("canonicalWorkId" -> ujson.Str(w3)))
    )
    assert(
      Set(ErrorCode.DuplicateId, ErrorCode.MissingEvidence)
        .contains(code(project(differs)))
    )
  }

  test("legacy IDs, evidence tracing and the bounds still hold when extended") {
    val legacy = "coauthor:v1:" + ("a" * 64)
    val bad = replace(
      "edges-coauthored",
      _.map(row =>
        if row("researcherA").str == m1 then
          row + ("edgeId" -> ujson.Str(legacy))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.LegacyEdgeId)
    val unsupported = replace(
      "edge-support",
      _.filterNot(_("edgeId").str == edgeId(m1, r1))
    )
    assertEquals(code(project(unsupported)), ErrorCode.MissingEvidence)
    assertEquals(
      code(
        new SparkGraphProjector(
          source(),
          LoadBounds(1L, 10, 10L),
          GraphScope.Extended
        ).project()
      ),
      ErrorCode.BoundExceeded
    )
    // the other base relationship types are untouched by the extension
    val base = project(graphTables(), GraphScope.Base)
    val extended = project(graphTables())
    Vector("AUTHORED", "COAUTHORED_WITH", "PUBLISHED_IN").foreach { label =>
      assertEquals(
        extended.edges.filter(_.label == label),
        base.edges.filter(_.label == label)
      )
    }
  }

  test("AFFILIATED_WITH, HAS_TOPIC and SUPPORTED_BY are never invented") {
    val tables = graphTables() ++ Map(
      "nodes-topic" -> Vector(
        Map(
          "topicId" -> ujson.Str("topic:ml"),
          "name" -> ujson.Str("Machine learning"),
          "provenance" -> provenance("nodes-topic", "topic:ml")
        )
      ),
      "nodes-institution" -> Vector(
        Map(
          "institutionId" -> ujson.Str("inst:mit"),
          "name" -> ujson.Str("MIT"),
          "provenance" -> provenance("nodes-institution", "inst:mit")
        )
      )
    )
    val projection = project(tables)
    assert(projection.vertices.exists(_.label == "Topic"))
    assert(projection.vertices.exists(_.label == "Institution"))
    val labels = projection.edges.map(_.label).toSet
    assertEquals(
      labels,
      Set(
        "AUTHORED",
        "COAUTHORED_WITH",
        "PUBLISHED_IN",
        "RESOLVES_TO",
        "DERIVED_FROM"
      )
    )
    assert(!labels.contains("AFFILIATED_WITH"))
    assert(!labels.contains("HAS_TOPIC"))
    assert(!labels.contains("SUPPORTED_BY"))
  }

  test("one mention resolved to two researchers is a conflicting resolution") {
    val row = graphTables()("edges-authored").head
    val mention = row("mentionId").str
    // same mention and work, claimed by r2 as well as r1
    val sameWork = replace(
      "edges-authored",
      _ :+ (row + ("researcherNodeId" -> ujson.Str(r2)))
    )
    val error = intercept[StorageException](project(sameWork))
    assertEquals(error.code, ErrorCode.DuplicateId)
    assert(error.getMessage.contains("Conflicting resolution"))
    assert(error.getMessage.contains(mention))
    // same mention claimed on another work
    val otherWork = replace(
      "edges-authored",
      _ :+ (row ++ Map(
        "researcherNodeId" -> ujson.Str(r2),
        "canonicalWorkId" -> ujson.Str(w2)
      ))
    )
    assert(
      Set(ErrorCode.DuplicateId, ErrorCode.MalformedRow)
        .contains(code(project(otherWork)))
    )
    // Base scope does not read mentions as vertices, so it is unaffected
    assertEquals(
      project(sameWork, GraphScope.Base).edges.count(_.label == "RESOLVES_TO"),
      0
    )
    // an identical repeat is still one edge, deterministically
    val repeated = replace("edges-authored", _ :+ row)
    assertEquals(project(repeated), project(graphTables()))
  }

  test("an extended export round-trips and reloads idempotently") {
    val projection = project(graphTables())
    val store = new InMemoryObjectStore
    val first = NeptuneExporter.exportTo(projection, runId, store)
    assertEquals(
      first.files.map(f => (f.kind, f.label)).toSet,
      Set(
        ("vertex", "Researcher"),
        ("vertex", "AuthorMention"),
        ("vertex", "Work"),
        ("vertex", "Venue"),
        ("vertex", "DatasetSnapshot"),
        ("edge", "AUTHORED"),
        ("edge", "COAUTHORED_WITH"),
        ("edge", "PUBLISHED_IN"),
        ("edge", "RESOLVES_TO"),
        ("edge", "DERIVED_FROM")
      )
    )
    val second = NeptuneExporter.exportTo(projection, runId, store)
    assertEquals(second.created, 0)
    val graph = new InMemoryNeptune(store)
    val load1 = graph.load(first.manifestKey)
    assertEquals(
      load1.inserted,
      projection.vertices.size + projection.edges.size
    )
    val state = (graph.vertices, graph.edges)
    val load2 = graph.load(first.manifestKey)
    assertEquals((graph.vertices, graph.edges), state)
    assertEquals((load2.inserted, load2.updated), (0, 0))
    assertEquals(graph.vertices.toSet, projection.vertices.toSet)
    assertEquals(graph.edges.toSet, projection.edges.toSet)
  }
