package scholarchain.storage

import scholarchain.core.StableIds

class NeptuneSuite extends munit.FunSuite:
  import Fixtures.*

  private val bounds = LoadBounds.Default

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def project(tables: Map[String, Vector[Row]]): GraphProjection =
    new SparkGraphProjector(source(tables), bounds).project()

  private def replace(
      table: String,
      f: Vector[Row] => Vector[Row]
  ): Map[String, Vector[Row]] =
    val tables = graphTables()
    tables.updated(table, f(tables(table)))

  private val legacyId = "coauthor:v1:" + ("a" * 64)

  test("vertex and edge IDs equal the Spark output IDs (core IDs)") {
    val tables = graphTables()
    // Base scope: the Extended default adds mention and snapshot vertices that
    // no Spark node table lists (see GraphScopeSuite).
    val projection =
      new SparkGraphProjector(source(tables), bounds, GraphScope.Base).project()
    val sparkVertexIds = Vector("nodes-researcher", "nodes-work", "nodes-venue")
      .flatMap(t => tables(t))
      .flatMap(row =>
        Vector("nodeId", "canonicalWorkId", "venueId").flatMap(row.get(_))
      )
      .map(_.str)
      .toSet
    assertEquals(projection.vertices.map(_.id).toSet, sparkVertexIds)
    val sparkEdgeIds =
      tables("edges-coauthored").map(r => r("edgeId").str).toSet
    val coauthored = projection.edges.filter(_.label == "COAUTHORED_WITH")
    assertEquals(coauthored.map(_.id).toSet, sparkEdgeIds)
    assertEquals(
      coauthored.map(_.id).toSet,
      Set(edgeId(r1, r2), edgeId(m1, r1))
    )
    assert(edgeId(m1, r1).startsWith("mention-coauthor:v1:"))
    assert(edgeId(r1, r2).startsWith("coauthor:v1:"))
    coauthored.foreach { e =>
      val window = scholarchain.core.YearWindow(2000, 2009)
      assertEquals(
        e.id,
        StableIds.coauthorEdgeFor(e.from, e.to, window, policy)
      )
    }
  }

  test("mention nodes are AuthorMention vertices, researchers Researcher") {
    val labels = project(graphTables()).vertices.map(v => v.id -> v.label).toMap
    assertEquals(labels(m1), "AuthorMention")
    assertEquals(labels(r1), "Researcher")
    assertEquals(labels(w1), "Work")
    assertEquals(labels(venue), "Venue")
  }

  test("coauthor edges carry count, years, window, evidence pointer") {
    val edge = project(graphTables()).edges.find(_.id == edgeId(r1, r2)).get
    assertEquals(edge.props("jointPublicationCount"), Prop.I64(2L))
    assertEquals(edge.props("firstCollaborationYear"), Prop.I32(2001))
    assertEquals(edge.props("lastCollaborationYear"), Prop.I32(2005))
    assertEquals(edge.props("window"), Prop.Str("2000-2009"))
    assertEquals(edge.props("windowStart"), Prop.I32(2000))
    assertEquals(edge.props("windowEnd"), Prop.I32(2009))
    assertEquals(
      edge.props("evidencePointer"),
      Prop.Str(s"edge-support:edgeId=${edge.id}")
    )
    assertEquals(edge.props("runId"), Prop.Str(runId))
    assertEquals(
      edge.props("transformVersion"),
      Prop.Str("spark-analytics/1.1.0")
    )
  }

  test("Spark-only columns carry as optional properties") {
    val projection = project(graphTables())
    val node = projection.vertices.find(_.id == m1).get
    assertEquals(node.props("nodeKind"), Prop.Str("unresolved-mention"))
    val edge = projection.edges.find(_.id == edgeId(m1, r1)).get
    assertEquals(edge.props("endpointAUnresolved"), Prop.Flag(true))
    assertEquals(edge.props("endpointBUnresolved"), Prop.Flag(false))
    val stripped = replace(
      "edges-coauthored",
      _.map(_ - "endpointAUnresolved" - "endpointBUnresolved")
    )
    val plain = project(stripped).edges.find(_.id == edgeId(r1, r2)).get
    assert(!plain.props.contains("endpointAUnresolved"))
  }

  test("a legacy coauthor:v1: ID on a mention endpoint is rejected, clearly") {
    val bad = replace(
      "edges-coauthored",
      rows =>
        rows.map { row =>
          if row("researcherA").str == m1 then
            row + ("edgeId" -> ujson.Str(legacyId))
          else row
        }
    )
    val error = intercept[StorageException](project(bad))
    assertEquals(error.code, ErrorCode.LegacyEdgeId)
    assert(error.getMessage.contains("Legacy"))
    assert(error.getMessage.contains("mention-coauthor:v1:"))
  }

  test("a legacy ID in edge-support is rejected as well") {
    val bad = replace(
      "edge-support",
      _.map(row =>
        if row("researcherA").str == m1 then
          row + ("edgeId" -> ujson.Str(legacyId))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.LegacyEdgeId)
  }

  test("an edge whose ID does not match its key is rejected") {
    val bad = replace(
      "edges-coauthored",
      _.map(_ + ("edgeId" -> ujson.Str(edgeId(r1, r2).replace("a", "b"))))
    )
    val c = code(project(bad))
    assert(c == ErrorCode.InvalidEdgeId || c == ErrorCode.DuplicateId)
  }

  test("an edge with a missing endpoint vertex is rejected") {
    val bad = replace(
      "nodes-researcher",
      _.filterNot(_("nodeId").str == r2)
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEndpoint)
  }

  test("AUTHORED to a work that is not a vertex is rejected") {
    val bad = replace(
      "nodes-work",
      _.filterNot(_("canonicalWorkId").str == w2)
    )
    // w2 also supports the R1-R2 edge, so evidence tracing fails first or the
    // authored endpoint does; both are endpoint or evidence errors.
    val c = code(project(bad))
    assert(c == ErrorCode.MissingEndpoint || c == ErrorCode.MissingEvidence)
  }

  test("a coauthor edge without supporting works is rejected") {
    val bad = replace(
      "edge-support",
      _.filterNot(_("edgeId").str == edgeId(m1, r1))
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("a joint count that edge-support does not back is rejected") {
    val bad = replace(
      "edge-support",
      _.filterNot(row =>
        row("edgeId").str == edgeId(r1, r2) && row("canonicalWorkId").str == w2
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("evidence years must match the edge years") {
    val bad = replace(
      "edge-support",
      _.map(row =>
        if row("canonicalWorkId").str == w2 then
          row + ("year" -> ujson.Num(2006))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("edge-support for an unknown edge is rejected") {
    val ghost = supportRow(edgeId(r2, m1), r2, m1, w3, 2007)
    val bad = replace("edge-support", _ :+ ghost)
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("a null character in any string is rejected") {
    val bad = replace(
      "nodes-work",
      _.map(row => row + ("title" -> ujson.Str("bad\u0000title")))
    )
    assertEquals(code(project(bad)), ErrorCode.NullCharacter)
  }

  test("conflicting duplicate vertices are rejected, identical ones merge") {
    val dup = graphTables()("nodes-work").head
    val same = replace("nodes-work", _ :+ dup)
    assertEquals(
      project(same).vertices.size,
      project(graphTables()).vertices.size
    )
    val differs = replace(
      "nodes-work",
      _ :+ (dup + ("title" -> ujson.Str("Other")))
    )
    assertEquals(code(project(differs)), ErrorCode.DuplicateId)
  }

  test("the row bound fails before output") {
    val tight = LoadBounds(1L, 10, 10L)
    assertEquals(
      code(new SparkGraphProjector(source(), tight).project()),
      ErrorCode.BoundExceeded
    )
  }

  test("CSV round trip keeps IDs, labels and typed properties") {
    val projection = project(graphTables())
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(projection, runId, store)
    assertEquals(report.vertexCount, projection.vertices.size)
    assertEquals(report.edgeCount, projection.edges.size)
    val back = report.files.flatMap { file =>
      val text = new String(store.get(file.key).get, "UTF-8")
      if file.kind == "vertex" then NeptuneCsvReader.vertices(text).map(Left(_))
      else NeptuneCsvReader.edges(text).map(Right(_))
    }
    assertEquals(
      back.collect { case Left(v) => v }.toSet,
      projection.vertices.toSet
    )
    assertEquals(
      back.collect { case Right(e) => e }.toSet,
      projection.edges.toSet
    )
  }

  test("quotes and commas survive; newlines are normalized and counted") {
    val projection = project(graphTables())
    val bo = projection.vertices.find(_.id == r2).get
    assertEquals(bo.props("displayName"), Prop.Str("Bo, \"B\" Chen"))
    val work = projection.vertices.find(_.id == w2).get
    assertEquals(work.props("title"), Prop.Str("Second paper"))
    assertEquals(projection.normalizedValues, 1)
  }

  test("export is byte-identical when repeated and loads idempotently") {
    val projection = project(graphTables())
    val store = new InMemoryObjectStore
    val first = NeptuneExporter.exportTo(projection, runId, store)
    val second = NeptuneExporter.exportTo(projection, runId, store)
    assertEquals(second.created, 0)
    assertEquals(second.unchanged, first.created)
    val graph = new InMemoryNeptune(store)
    val load1 = graph.load(first.manifestKey)
    val stateAfterOne = (graph.vertices, graph.edges)
    val load2 = graph.load(first.manifestKey)
    assertEquals((graph.vertices, graph.edges), stateAfterOne)
    assertEquals(
      load1.inserted,
      projection.vertices.size + projection.edges.size
    )
    assertEquals(load2.inserted, 0)
    assertEquals(load2.updated, 0)
    assertEquals(load2.unchanged, load1.inserted)
  }

  test("a tampered CSV fails the checksum in the bulk loader") {
    val projection = project(graphTables())
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(projection, runId, store)
    val manifest = ujson.read(store.get(report.manifestKey).get)
    manifest("files")(0)("sha256") = "0" * 64
    val tampered = new InMemoryObjectStore
    NeptuneExporter.exportTo(projection, runId, tampered)
    val other = ObjectKeys.versioned(
      S3Prefix.Graph,
      "neptune-bad",
      "v1",
      runId,
      "load-manifest.json"
    )
    tampered.putImmutable(other, ujson.write(manifest).getBytes("UTF-8"), "t")
    assertEquals(
      code(new InMemoryNeptune(tampered).load(other)),
      ErrorCode.ChecksumMismatch
    )
  }

  private def loaded(): InMemoryNeptune =
    val store = new InMemoryObjectStore
    val report = NeptuneExporter.exportTo(project(graphTables()), runId, store)
    val graph = new InMemoryNeptune(store)
    graph.load(report.manifestKey)
    graph

  test("runtime query port answers parameterized catalog queries") {
    val rows = loaded().run(CypherCatalog.coauthorsOf(r1, "2000-2009", 10))
    assertEquals(rows.size, 2)
    assertEquals(rows.head("coauthorId").str, r2)
    assertEquals(rows.head("jointPublicationCount").num, 2.0)
    assertEquals(
      rows.head("evidencePointer").str,
      s"edge-support:edgeId=${edgeId(r1, r2)}"
    )
    val evidence = loaded().run(CypherCatalog.edgeEvidence(edgeId(r1, r2), 1))
    assertEquals(evidence.size, 1)
  }

  test("a hostile value is bound as data and never reaches the query text") {
    val hostile = "x' OR 1=1 //\" DETACH DELETE n"
    val query = CypherCatalog.coauthorsOf(hostile, "2000-2009", 10)
    assert(!query.text.contains(hostile))
    assert(query.text.contains("$researcherId"))
    assertEquals(loaded().run(query), Vector.empty)
  }

  test("query text with literals, writes or unbound parameters is rejected") {
    def reject(text: String, params: Map[String, ujson.Value]) =
      assertEquals(
        code(CypherQuery("q", text, params)),
        ErrorCode.QueryRejected
      )
    val limit = Map("limit" -> ujson.Num(1))
    reject("MATCH (n) WHERE id(n) = 'abc' RETURN n LIMIT $limit", limit)
    reject("MATCH (n) DETACH DELETE n LIMIT $limit", limit)
    reject("MATCH (n) SET n.x = $limit RETURN n LIMIT $limit", limit)
    reject("CALL db.labels() LIMIT $limit", limit)
    reject("MATCH (n) WHERE id(n) = $id RETURN n LIMIT $limit", limit)
    reject("MATCH (n) RETURN n", Map.empty)
    reject("MATCH (n) RETURN n; MATCH (m) RETURN m LIMIT $limit", limit)
  }
