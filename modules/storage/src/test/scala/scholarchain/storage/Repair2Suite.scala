package scholarchain.storage

import java.nio.file.Files

class Repair2Suite extends munit.FunSuite:
  import Fixtures.*

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def project(tables: Map[String, Vector[Row]]): GraphProjection =
    new SparkGraphProjector(source(tables), LoadBounds.Default).project()

  private def replace(
      table: String,
      f: Vector[Row] => Vector[Row]
  ): Map[String, Vector[Row]] =
    val tables = graphTables()
    tables.updated(table, f(tables(table)))

  private val names = TableNames("t")

  test("B1: the consistent fixture (with its AUTHORED rows) projects") {
    assert(project(graphTables()).edges.nonEmpty)
  }

  test("B1: a support row naming other endpoints than its edge is refused") {
    val bad = replace(
      "edge-support",
      _.map(row =>
        if row("canonicalWorkId").str == w2 then
          row + ("researcherB" ->
            ujson.Str(
              scholarchain.core.StableIds
                .researcherFromExternal("orcid", "0000-9999")
            ))
        else row
      )
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("B1: a support row for another window than its edge is refused") {
    val bad = replace(
      "edge-support",
      _.map(_ + ("window" -> ujson.Str("2010-2019")))
    )
    assertEquals(code(project(bad)), ErrorCode.MissingEvidence)
  }

  test("B1: an endpoint that did not author a cited work is refused") {
    val noR2 = replace(
      "edges-authored",
      _.filterNot(r =>
        r("researcherNodeId").str == r2 && r("canonicalWorkId").str == w2
      )
    )
    assertEquals(code(project(noR2)), ErrorCode.MissingEvidence)
  }

  test("B1: the check covers a mention endpoint") {
    val noMention = replace(
      "edges-authored",
      _.filterNot(_("researcherNodeId").str == m1)
    )
    assertEquals(code(project(noMention)), ErrorCode.MissingEvidence)
  }

  test("B1: an AUTHORED row not counted in metrics cannot support a pair") {
    val uncounted = replace(
      "edges-authored",
      _.map(r =>
        if r("researcherNodeId").str == m1 then
          r + ("countedInMetrics" -> ujson.Bool(false))
        else r
      )
    )
    assertEquals(code(project(uncounted)), ErrorCode.MissingEvidence)
  }

  test("window text must equal the years") {
    val bad = replace(
      "edges-coauthored",
      _.map(_ + ("window" -> ujson.Str("1990-1999")))
    )
    assertEquals(code(project(bad)), ErrorCode.InvalidEdgeId)
  }

  test("IDs are never rewritten: a tab or newline in an ID is refused") {
    val bad = replace(
      "nodes-venue",
      _.map(_ + ("venueId" -> ujson.Str("venue:a\tb")))
    )
    assertEquals(code(project(bad)), ErrorCode.MalformedRow)
    val nl = replace(
      "edges-authored",
      _.map(_ + ("mentionId" -> ujson.Str("m\nx")))
    )
    assertEquals(code(project(nl)), ErrorCode.MalformedRow)
  }

  private def sparkOf(item: Item) = item.get("sparkTransformVersion")

  test("B2: Dynamo items record storage and Spark provenance, kept distinct") {
    val store = new InMemoryObjectStore
    val dir = Fixtures.writeRunDir(Files.createTempDirectory("r2-"), true)
    val run = RunDirectory.open(dir)
    val report = S3RunLoader.load(run, store, "v1", LoadBounds.Default)
    val s3 = S3RunManifest.read(store, report.manifestKey)
    val ledger = new InMemoryDynamoTable(RunLedger.spec(names))
    val registry = new InMemoryDynamoTable(ArtifactRegistry.spec(names))
    val cache = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val rows = Vector(metricsRow(r1, "2000-2009", 2000, 2009))
    DynamoRunLoader.load(
      run,
      s3,
      source(Map("researcher-metrics" -> rows)),
      ledger,
      registry,
      cache,
      "2026-11-08T00:00:00Z",
      100,
      LoadBounds.Default
    )
    val storage = ujson.Str("storage/1.0.0")
    val spark = ujson.Str("spark-analytics/1.1.0")
    val run1 = ledger.get(s"RUN#${run.runId}", "META").get
    assertEquals(run1("kind").str, "spark-analytics")
    assertEquals(run1("transformVersion"), spark)
    assertEquals(run1("storageTransformVersion"), storage)
    assertEquals(run1("sourceRef").str, s"spark-run:${run.runId}")
    val art = registry.queryIndex("run-index", s"RUN#${run.runId}", None, 50)
    assert(art.nonEmpty)
    art.foreach { item =>
      assertEquals(item("storageTransformVersion"), storage)
      assertEquals(sparkOf(item), Some(spark))
      assert(item("sourceRef").str.startsWith("spark-run:"))
    }
    val metric = cache.query(s"RESEARCHER#$r1", None, 5).head
    assertEquals(metric("storageTransformVersion"), storage)
    assertEquals(sparkOf(metric), Some(spark))
    assertEquals(metric("sourceRef").str, s"spark-run:${run.runId}")
  }

  test("B2: entity link cache records its source and the storage version") {
    val links = new InMemoryDynamoTable(EntityLinkCache.spec(names))
    EntityLinkCache.put(links, "orcid:1", "pol/1", Some(r1), "ACCEPTED", 100)
    val item = links.get("LINK#orcid:1", "POLICY#pol/1").get
    assertEquals(item("storageTransformVersion").str, "storage/1.0.0")
    assertEquals(item("sourceRef").str, "entity-link:orcid:1")
    assertEquals(item("linkPolicyVersion").str, "pol/1")
  }

  test("B2: vector metadata carries storage and Spark versions within limits") {
    val row = summaryRow("researcher-profile", "pv", longText)
    val made = SemanticVectorLoader.record(row, new FixedEmbedder(4), "base")
    assertEquals(
      made.metadata("storageTransformVersion"),
      MetaValue.Str("storage/1.0.0")
    )
    assertEquals(
      made.metadata("sparkTransformVersion"),
      MetaValue.Str("spark-analytics/1.1.0")
    )
    VectorMetadata.validate(made.metadata)
    assert(made.metadata.size <= Limits.S3Vectors.MaxMetadataKeys)
    assert(
      VectorMetadata.sizeBytes(made.metadata) <=
        Limits.S3Vectors.MaxFilterableMetadataBytes
    )
  }

  test("rejected-vector log: a different reject list gets its own key") {
    val store = new InMemoryObjectStore
    def rej(key: String) =
      Vector(RejectedVector("i", key, ErrorCode.MetadataInvalid, "why"))
    val a = RejectedVectors.persist(store, runId, "i", rej("a")).get
    val b = RejectedVectors.persist(store, runId, "i", rej("b")).get
    assertNotEquals(a, b)
    assertEquals(RejectedVectors.persist(store, runId, "i", rej("a")).get, a)
  }

  private def hop(
      edge: String,
      from: String,
      to: String,
      works: Vector[String] = Vector(w1)
  ): Row =
    Map(
      "pairKey" -> ujson.Str("p"),
      "window" -> ujson.Str("2000-2009"),
      "sourceId" -> ujson.Str(r1),
      "targetId" -> ujson.Str(r2),
      "status" -> ujson.Null,
      "unreachableReason" -> ujson.Null,
      "hopIndex" -> ujson.Num(1),
      "fromId" -> ujson.Str(from),
      "toId" -> ujson.Str(to),
      "edgeId" -> ujson.Str(edge),
      "supportingWorkIds" -> ujson.Arr.from(works.map(ujson.Str(_))),
      "provenance" -> provenance("path-evidence", "p")
    )

  private def readPaths(
      rows: Vector[Row],
      extra: Map[String, Vector[Row]] = Map.empty
  ) =
    PathEvidence.read(
      source(Map("path-evidence" -> rows) ++ extra),
      LoadBounds.Default
    )

  test(
    "path-evidence: edge shape, node kinds, work IDs, membership, provenance"
  ) {
    val good = hop(edgeId(r1, r2), r1, r2)
    assertEquals(readPaths(Vector(good)).head.provenance.runId, runId)
    assertEquals(
      code(readPaths(Vector(hop("coauthor:v1:abc", r1, r2)))),
      ErrorCode.InvalidEdgeId
    )
    assertEquals(
      code(readPaths(Vector(hop(edgeId(r1, r2), "junk", r2)))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(readPaths(Vector(hop(edgeId(r1, r2), r1, r2, Vector("x"))))),
      ErrorCode.MalformedRow
    )
    val coauthors = Map(
      "edges-coauthored" -> Vector(
        coauthorRow(m1, r1, 1, 2001, 2001, Vector(w1))
      )
    )
    assertEquals(
      code(readPaths(Vector(good), coauthors)),
      ErrorCode.MissingEvidence
    )
    val withEdge = Map("edges-coauthored" -> graphTables()("edges-coauthored"))
    assertEquals(readPaths(Vector(good), withEdge).size, 1)
  }

  test("D10: AUTHORED and PUBLISHED_IN IDs are core graphEdge IDs") {
    val projection = project(graphTables())
    val authored = projection.edges.filter(_.label == "AUTHORED")
    val published = projection.edges.filter(_.label == "PUBLISHED_IN")
    assert(authored.nonEmpty && published.nonEmpty)
    (authored ++ published).foreach { e =>
      assert(e.id.matches("edge:v1:[0-9a-f]{64}"), e.id)
      assertEquals(
        e.props("idDerivation"),
        Prop.Str("core:StableIds.graphEdge")
      )
    }
    assertEquals((authored ++ published).map(_.id).distinct.size, 6)
    val again = project(graphTables())
    assertEquals(again.edges.map(_.id), projection.edges.map(_.id))
    val other = graphTables().view
      .mapValues(_.map { row =>
        row.get("provenance") match
          case Some(p) =>
            val q = ujson.read(ujson.write(p))
            q("snapshotId") = "hadoop-snapshot:other"
            row + ("provenance" -> q)
          case None => row
      })
      .toMap
    // DERIVED_FROM points at the snapshot, so its ID follows the snapshot by
    // design; every other edge ID must not.
    def stable(p: GraphProjection) =
      p.edges.filter(_.label != "DERIVED_FROM").map(_.id)
    assertEquals(stable(project(other)), stable(projection))
    assertEquals(
      project(other).edges.count(_.label == "DERIVED_FROM"),
      projection.edges.count(_.label == "DERIVED_FROM")
    )
  }

  test("path-evidence: hop endpoints must be those of the cited edge") {
    val r3 = scholarchain.core.StableIds
      .researcherFromExternal("orcid", "0000-7777")
    val table = Map("edges-coauthored" -> graphTables()("edges-coauthored"))
    val edge = edgeId(r1, r2)
    assertEquals(readPaths(Vector(hop(edge, r2, r1)), table).size, 1)
    assertEquals(
      code(readPaths(Vector(hop(edge, r1, r3)), table)),
      ErrorCode.InvalidEdgeId
    )
    assertEquals(
      code(readPaths(Vector(hop(edge, r3, r2)), table)),
      ErrorCode.InvalidEdgeId
    )
  }

  test("small checks: Rows.int, Cypher limit, dot segments, size estimate") {
    val row: Row =
      Map("a" -> ujson.Num(1.5), "b" -> ujson.Num(3e10), "c" -> ujson.Num(7))
    assertEquals(code(Rows.int(row, "a")), ErrorCode.MalformedRow)
    assertEquals(code(Rows.int(row, "b")), ErrorCode.MalformedRow)
    assertEquals(Rows.int(row, "c"), 7)
    Vector(0, -1, 1001, 1000000).foreach { n =>
      assertEquals(
        code(CypherCatalog.coauthorsOf(r1, "w", n)),
        ErrorCode.QueryRejected
      )
    }
    CypherCatalog.coauthorsOf(r1, "w", CypherQuery.MaxLimit)
    assertEquals(code(ObjectKeys.requireValid("a/./b")), ErrorCode.InvalidKey)
    val plain = ItemRules.sizeBytes(Map("a" -> ujson.Obj("k" -> "v")))
    assertEquals(plain, 1L + 3 + 1 + 1 + 1)
    assertEquals(LoadBounds.Default.maxBytes, 1024L * 1024 * 1024)
  }
