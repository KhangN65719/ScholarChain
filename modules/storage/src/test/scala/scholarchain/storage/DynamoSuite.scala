package scholarchain.storage

import scholarchain.core.{
  ContractSchema,
  EvidenceBundle,
  EvidenceItem,
  EvidenceType,
  Provenance,
  SourceType,
  StableIds
}

class DynamoSuite extends munit.FunSuite:
  import Fixtures.*

  private val names = TableNames("scholarchain-test")

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def table(spec: DynamoTableSpec) = new InMemoryDynamoTable(spec)

  private val prov = Provenance(
    ContractSchema.currentVersion,
    SourceType.Derived,
    "snapshot",
    "record",
    "scholarchain:test",
    "test/1",
    runId
  )

  private def evidence(n: Int, text: Option[String]): EvidenceItem =
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
      text,
      None,
      prov
    )

  private def bundle(id: String, items: Vector[EvidenceItem]): EvidenceBundle =
    EvidenceBundle(ContractSchema.currentVersion, id, items, None, prov)

  test("conditional put: second put fails with ConditionalCheckFailed") {
    val t = table(RunLedger.spec(names))
    val item =
      RunLedger.runItem(runId, "spark", "STARTED", "t/1", "2026-11-08", 1)
    t.putIfAbsent(item)
    assertEquals(code(t.putIfAbsent(item)), ErrorCode.ConditionalCheckFailed)
  }

  test("idempotent put: same content is Unchanged, different content fails") {
    val t = table(ArtifactRegistry.spec(names))
    def reg(sha: String) =
      ArtifactRegistry.register(t, "graph", runId, "a", "graph/k", sha, 3)
    assertEquals(reg("aa"), WriteOutcome.Created)
    assertEquals(reg("aa"), WriteOutcome.Unchanged)
    assertEquals(code(reg("bb")), ErrorCode.ConditionalCheckFailed)
  }

  test("versioned update: stale expected version is refused") {
    val t = table(RunLedger.spec(names))
    RunLedger.start(t, runId, "spark", "t/1", "2026-11-08")
    RunLedger.transition(t, runId, "STARTED", "LOADED")
    assertEquals(
      code(RunLedger.transition(t, runId, "STARTED", "LOADED")),
      ErrorCode.ConditionalCheckFailed
    )
    val current = t.get(s"RUN#$runId", "META").get
    assertEquals(current("status").str, "LOADED")
    assertEquals(current("version").num, 2.0)
    val stale = current + ("status" -> ujson.Str("FAILED"))
    assertEquals(
      code(t.putIfVersion(stale, 1L)),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      code(RunLedger.transition(t, "run:v1:none", "A", "B")),
      ErrorCode.ConditionalCheckFailed
    )
  }

  test("TTL only on disposable data") {
    val ledger = table(RunLedger.spec(names))
    val withTtl = RunLedger
      .runItem(runId, "spark", "STARTED", "t/1", "x", 1) + ("ttl" -> ujson.Num(
      1
    ))
    assertEquals(code(ledger.putIfAbsent(withTtl)), ErrorCode.TtlNotAllowed)
    val bundles = table(EvidenceBundleTable.spec(names))
    assertEquals(
      code(
        bundles.putIfAbsent(
          Map(
            "pk" -> ujson.Str("BUNDLE#b"),
            "sk" -> ujson.Str("v1"),
            "ttl" -> ujson.Num(1)
          )
        )
      ),
      ErrorCode.TtlNotAllowed
    )
    val sessions = table(QuerySession.spec(names))
    assertEquals(
      code(
        sessions.putIfAbsent(
          Map("pk" -> ujson.Str("SESSION#s"), "sk" -> ujson.Str("TURN#000001"))
        )
      ),
      ErrorCode.TtlRequired
    )
    Vector(
      EntityLinkCache.spec(names),
      QuerySession.spec(names),
      ResearcherMetricCache.spec(names)
    ).foreach(spec => assertEquals(spec.ttl, TtlPolicy.Required))
    Vector(
      RunLedger.spec(names),
      EvidenceBundleTable.spec(names),
      ArtifactRegistry.spec(names)
    ).foreach(spec => assertEquals(spec.ttl, TtlPolicy.Forbidden))
  }

  test("an item over 400 KB is refused with a pointer to S3") {
    val bundles = table(EvidenceBundleTable.spec(names))
    val huge = bundle("big", Vector(evidence(1, Some("x" * (410 * 1024)))))
    assertEquals(
      code(EvidenceBundleTable.store(bundles, huge, "q1", runId)),
      ErrorCode.ItemTooLarge
    )
    val small = bundle("small", Vector(evidence(1, Some("x" * 1000))))
    assertEquals(
      EvidenceBundleTable.store(bundles, small, "q1", runId),
      WriteOutcome.Created
    )
    assert(
      ItemRules.sizeBytes(bundles.get("BUNDLE#small", "v1").get) <
        Limits.DynamoDb.MaxItemBytes
    )
  }

  test("oversized keys and missing keys are refused") {
    val t = table(RunLedger.spec(names))
    assertEquals(
      code(
        t.putIfAbsent(
          Map("pk" -> ujson.Str("a" * 2049), "sk" -> ujson.Str("META"))
        )
      ),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(t.putIfAbsent(Map("pk" -> ujson.Str("a")))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(
        DynamoTableSpec(
          "ab",
          "pk",
          "sk",
          TtlPolicy.Forbidden,
          Vector.empty,
          Vector.empty
        )
      ),
      ErrorCode.InvalidItem
    )
  }

  test("evidence bundle round-trips through its table") {
    val bundles = table(EvidenceBundleTable.spec(names))
    val b =
      bundle("bundle-1", Vector(evidence(1, None), evidence(2, Some("t"))))
    EvidenceBundleTable.store(bundles, b, "q1", runId)
    assertEquals(EvidenceBundleTable.load(bundles, "bundle-1"), Some(b))
    assertEquals(EvidenceBundleTable.load(bundles, "other"), None)
  }

  test("every stated access pattern is served by the keys") {
    val ledger = table(RunLedger.spec(names))
    RunLedger.start(ledger, runId, "spark", "t/1", "2026-11-08")
    RunLedger.recordStage(ledger, runId, "metrics", "DONE")
    RunLedger.recordStage(ledger, runId, "paths", "DONE")
    assertEquals(
      ledger.get(s"RUN#$runId", "META").map(_("kind").str),
      Some("spark")
    )
    assertEquals(
      ledger.query(s"RUN#$runId", Some("STAGE#"), 10).map(_("stage").str),
      Vector("metrics", "paths")
    )
    assertEquals(
      ledger.queryIndex("status-index", "STATUS#STARTED", None, 10).size,
      1
    )

    val links = table(EntityLinkCache.spec(names))
    EntityLinkCache.put(links, "orcid:1", "pol/1", Some(r1), "ACCEPTED", 100)
    EntityLinkCache.put(links, "orcid:1", "pol/2", None, "UNRESOLVED", 100)
    assertEquals(links.get("LINK#orcid:1", "POLICY#pol/1").size, 1)
    assertEquals(links.query("LINK#orcid:1", Some("POLICY#"), 10).size, 2)

    val sessions = table(QuerySession.spec(names))
    Vector(2, 1, 3).foreach(n =>
      QuerySession.appendTurn(sessions, "s1", n, "{}", 100)
    )
    assertEquals(
      sessions.query("SESSION#s1", Some("TURN#"), 10).map(_("sk").str),
      Vector("TURN#000001", "TURN#000002", "TURN#000003")
    )
    assert(sessions.get("SESSION#s1", "TURN#000002").isDefined)

    val bundles = table(EvidenceBundleTable.spec(names))
    EvidenceBundleTable.store(bundles, bundle("b1", Vector.empty), "q1", runId)
    EvidenceBundleTable.store(bundles, bundle("b2", Vector.empty), "q1", runId)
    EvidenceBundleTable.store(bundles, bundle("b3", Vector.empty), "q2", runId)
    assert(bundles.get("BUNDLE#b1", "v1").isDefined)
    assertEquals(
      bundles.queryIndex("query-index", "QUERY#q1", None, 10).size,
      2
    )

    val registry = table(ArtifactRegistry.spec(names))
    ArtifactRegistry.register(registry, "graph", runId, "a", "graph/a", "s1", 1)
    ArtifactRegistry.register(
      registry,
      "curated",
      runId,
      "b",
      "curated/b",
      "s2",
      1
    )
    assert(
      registry
        .get("ARTIFACT#graph", s"RUN#${RunDirectory.segment(runId)}#a")
        .isDefined
    )
    assertEquals(
      registry.queryIndex("run-index", s"RUN#$runId", None, 10).size,
      2
    )

    val cache = table(ResearcherMetricCache.spec(names))
    val loadReport = loadMetrics(cache)
    assertEquals(loadReport, 2)
    assertEquals(cache.query(s"RESEARCHER#$r1", Some("WINDOW#"), 10).size, 2)
    val sk = ResearcherMetricCache.sortKey(window, runId)
    assert(cache.get(s"RESEARCHER#$r1", sk).isDefined)

    Vector(
      RunLedger.spec(names),
      EntityLinkCache.spec(names),
      QuerySession.spec(names),
      EvidenceBundleTable.spec(names),
      ArtifactRegistry.spec(names),
      ResearcherMetricCache.spec(names)
    ).foreach { spec =>
      assert(spec.patterns.nonEmpty, spec.name)
      spec.patterns.flatMap(_.index).foreach { index =>
        assert(spec.indexes.exists(_.name == index), s"${spec.name}: $index")
      }
    }
  }

  private def loadMetrics(cache: DynamoTable): Int =
    val rows = Vector(
      metricsRow(r1, "2000-2009", 2000, 2009),
      metricsRow(r1, "all", 1, 9999)
    )
    rows.count { row =>
      DynamoRunLoader.putMetrics(cache, row, runId, 100) == WriteOutcome.Created
    }

  test("run loader is idempotent and preserves Spark-only columns") {
    val store = new InMemoryObjectStore
    val dir = Fixtures.writeRunDir(
      java.nio.file.Files.createTempDirectory("dyn-"),
      true
    )
    val run = RunDirectory.open(dir)
    val report = S3RunLoader.load(run, store, "v1", LoadBounds.Default)
    val s3 = S3RunManifest.read(store, report.manifestKey)
    val source = Fixtures.source(
      Map(
        "researcher-metrics" -> Vector(
          metricsRow(r1, "2000-2009", 2000, 2009),
          metricsRow(m1, "2000-2009", 2000, 2009)
        )
      )
    )
    val ledger = table(RunLedger.spec(names))
    val registry = table(ArtifactRegistry.spec(names))
    val cache = table(ResearcherMetricCache.spec(names))
    def load() =
      DynamoRunLoader.load(
        run,
        s3,
        source,
        ledger,
        registry,
        cache,
        "2026-11-08T00:00:00Z",
        1000,
        LoadBounds.Default
      )
    val first = load()
    val second = load()
    assertEquals(first.ledgerCreated, 1)
    assertEquals(first.metricsCreated, 2)
    assertEquals(first.artifactsCreated, s3.objects.size)
    assertEquals(second.ledgerCreated, 0)
    assertEquals(second.metricsCreated, 0)
    assertEquals(second.artifactsCreated, 0)
    assertEquals(second.metricsUnchanged, 2)
    assertEquals(second.artifactsUnchanged, s3.objects.size)
    assertEquals(
      ledger.get(s"RUN#${run.runId}", "META").get("status").str,
      "LOADED"
    )
    val item = cache
      .get(s"RESEARCHER#$r1", ResearcherMetricCache.sortKey(window, runId))
      .get
    assertEquals(item("nodeKind").str, "canonical")
    assertEquals(item("unresolvedCoauthorOccurrences").num, 0.0)
    val mention = cache.query(s"RESEARCHER#$m1", None, 5)
    assertEquals(mention.size, 1)
  }

  test("a metrics row that breaks the core contract is rejected") {
    val bad =
      metricsRow(r1, "2000-2009", 2000, 2009) + ("componentSize" -> ujson.Num(
        0
      ))
    assertEquals(
      code(
        DynamoRunLoader
          .putMetrics(table(ResearcherMetricCache.spec(names)), bad, runId, 1)
      ),
      ErrorCode.MalformedRow
    )
  }
