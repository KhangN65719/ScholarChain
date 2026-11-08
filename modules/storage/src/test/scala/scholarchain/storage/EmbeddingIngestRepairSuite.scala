package scholarchain.storage

/** Phase 8 repair round 1: B1 (rerun under one label with changed input), L4
  * (template bump, document errors) and L6 (metadata drift).
  */
class EmbeddingIngestRepairSuite extends munit.FunSuite:
  import Fixtures.*

  private val source = SourceRun(runId, "spark-analytics/1.1.0")
  private val index = VectorIndexes.ResearcherProfile

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def profile(id: String, text: String): Row =
    summaryRow("researcher-profile", id, text)

  private val baseRows: Vector[Row] = Vector(
    profile("p1", "Ada works on graph neural networks and community detection"),
    profile("p2", "Bo studies protein folding and the molecular biology"),
    profile("p3", "Cy designs compilers and type systems for functional code"),
    summaryRow(
      "community",
      "c1",
      "A community of researchers on graph networks"
    ),
    summaryRow("work", "w1", "A paper on graph neural networks for citations")
  )

  private def tablesOf(rows: Vector[Row]): TableSource =
    new InMemoryTableSource(
      Map(
        "semantic-summaries" -> rows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  private class CountingBucket extends InMemoryVectorBucket:
    var calls = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      calls += 1
      super.putVectors(index, batch)

  /** An object store that claims other content under chosen keys. */
  private final class ConflictStore extends ObjectStore:
    val inner = new InMemoryObjectStore
    var conflicting = Set.empty[String]
    protected def write(
        key: String,
        bytes: Array[Byte],
        sourceRef: String
    ): Unit = inner.putImmutable(key, bytes, sourceRef)
    def head(key: String): Option[ObjectMeta] =
      inner.head(key).map { meta =>
        if conflicting.contains(key) then meta.copy(sha256 = "f" * 64) else meta
      }
    def get(key: String): Option[Array[Byte]] = inner.get(key)
    def list(prefix: String): Vector[ObjectMeta] = inner.list(prefix)

  private final class Env(
      val store: ObjectStore = new InMemoryObjectStore,
      val defaultRenderer: SummaryRenderer = PassThroughRenderer,
      val defaultEmbedder: Embedder = new HashEmbedder
  ):
    val bucket = new CountingBucket
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))

    def ingest(
        embedder: Embedder = defaultEmbedder,
        renderer: SummaryRenderer = defaultRenderer
    ) = new EmbeddingIngest(embedder, renderer, bucket, store, table)

    def run(
        rows: Vector[Row] = baseRows,
        label: String = "1",
        batchSize: Int = 2,
        engine: EmbeddingIngest = ingest()
    ): IngestReport =
      engine.run(
        tablesOf(rows),
        source,
        IngestConfig(
          "2026-11-08T00:00:00Z",
          batchSize,
          executionLabel = label
        )
      )

    def pk(label: String = "1") = s"RUN#embed:$runId:$label"

    def ledger(label: String = "1"): Vector[Item] =
      table.query(pk(label), None, 1000)

    /** Everything a refused run must leave alone. */
    def state(): (
        Vector[(String, String)],
        Vector[Vector[VectorRecord]],
        Int,
        Vector[Item]
    ) =
      (
        store.list("").map(o => (o.key, o.sha256)),
        VectorIndexes
          .all(384, "cosine")
          .map(spec =>
            bucket.getVectors(
              spec.name,
              baseRows
                .map(r => Rows.str(r, "summaryId"))
                .distinct
                .sorted
            )
          ),
        bucket.calls,
        ledger()
      )

  private def hashOf(text: String): String = Hashing.sha256(text)

  private def changedInputRefused(env: Env, attempt: => IngestReport): Unit =
    val before = env.state()
    val error = intercept[StorageException](attempt)
    assertEquals(error.code, ErrorCode.ExecutionInputChanged)
    assert(error.getMessage.contains(s"embed:$runId:1"), error.getMessage)
    assert(error.getMessage.contains("new executionLabel"), error.getMessage)
    assertEquals(env.state(), before)
    assertEquals(
      Attr.optS(env.table.get(env.pk(), "META").get, "status"),
      Some("COMPLETED")
    )

  test("B1: the run record and each batch record hold the input digests") {
    val env = new Env()
    val report = env.run()
    val meta = env.table.get(env.pk(), "META").get
    assertEquals(Attr.optS(meta, "manifestSha256"), Some(report.manifestSha256))
    val batches =
      env.ledger().filter(i => Attr.optS(i, "entityType").contains("BATCH"))
    assertEquals(batches.size, 4)
    assert(
      batches.forall(b => Attr.optS(b, "itemsDigest").exists(_.length == 64))
    )
    val manifest = EmbeddingWorkManifest.read(env.store, report.manifestKey)
    val expected = WorkBatches
      .of(manifest, VectorIndexes.all(384, "cosine").map(_.name), 2)
      .map(b => WorkBatches.digest(b.items))
    assertEquals(
      batches.map(b => Attr.optS(b, "itemsDigest").get).toSet,
      expected.toSet
    )
  }

  test("B1: changed text under the same label is refused and nothing changes") {
    val env = new Env()
    env.run()
    val edited =
      baseRows.updated(0, profile("p1", "Ada now studies something else"))
    changedInputRefused(env, env.run(edited))
  }

  test("B1: an added row under the same label is refused, not lost silently") {
    val env = new Env()
    env.run()
    val added = baseRows :+ profile("p4", "Di studies distributed databases")
    changedInputRefused(env, env.run(added))
    assertEquals(env.bucket.count(index), 3)
  }

  test("B1: a removed row under the same label is refused") {
    val env = new Env()
    env.run()
    changedInputRefused(env, env.run(baseRows.drop(1)))
  }

  test("B1: a changed model under the same label is refused") {
    val env = new Env()
    env.run()
    changedInputRefused(
      env,
      env.run(engine = env.ingest(embedder = new FixedEmbedder(384)))
    )
  }

  test("B1: a changed template version under the same label is refused") {
    val env = new Env()
    env.run()
    val renderer = new SummaryRenderer:
      def render(row: Row): RenderedDocument =
        RenderedDocument(Rows.str(row, "summaryText"), "spark-summary/2")
    changedInputRefused(env, env.run(engine = env.ingest(renderer = renderer)))
  }

  test("B1: a changed batch size under the same label is refused") {
    val env = new Env()
    env.run(batchSize = 2)
    changedInputRefused(env, env.run(batchSize = 3))
  }

  test("B1: a run record without a recorded digest is refused, not trusted") {
    val env = new Env()
    env.run()
    val meta = env.table.get(env.pk(), "META").get
    env.table.putIfVersion(
      (meta - "manifestSha256") + ("version" -> Attr.n(
        meta("version").num.toLong + 1
      )),
      meta("version").num.toLong
    )
    assertEquals(code(env.run()), ErrorCode.ExecutionInputChanged)
  }

  test(
    "B1: a batch record that differs from its recomputed digest is refused"
  ) {
    val env = new Env()
    env.run()
    val sk = s"STAGE#batch#$index#000000"
    val batch = env.table.get(env.pk(), sk).get
    val v = batch("version").num.toLong
    env.table.putIfVersion(
      batch + ("itemsDigest" -> Attr.s("0" * 64)) + ("version" -> Attr.n(
        v + 1
      )),
      v
    )
    val calls = env.bucket.calls
    assertEquals(code(env.run()), ErrorCode.ExecutionInputChanged)
    assertEquals(env.bucket.calls, calls)
  }

  test("B1: identical input under the same label still resumes") {
    val env = new Env()
    env.run()
    val calls = env.bucket.calls
    val objects = env.store.list("").map(o => (o.key, o.sha256))
    val again = env.run()
    assertEquals(again.written, 0)
    assertEquals(again.perIndex.map(_.skippedResumed).sum, 5)
    assertEquals(env.bucket.calls, calls)
    assertEquals(env.store.list("").map(o => (o.key, o.sha256)), objects)
    assertEquals(
      Attr.optS(env.table.get(env.pk(), "META").get, "status"),
      Some("COMPLETED")
    )
  }

  test("B1: a failed batch of identical input is redone on the same label") {
    var broken = true
    val bucket = new CountingBucket:
      override def putVectors(i: String, batch: Vector[VectorRecord]): Unit =
        if broken && calls == 1 then
          calls += 1
          throw new RuntimeException("boom")
        super.putVectors(i, batch)
    val store = new InMemoryObjectStore
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val ingest =
      new EmbeddingIngest(
        new HashEmbedder,
        PassThroughRenderer,
        bucket,
        store,
        table
      )
    def go() = ingest.run(
      tablesOf(baseRows),
      source,
      IngestConfig("2026-11-08T00:00:00Z", 2, executionLabel = "1")
    )
    assertEquals(go().failedBatches.size, 1)
    broken = false
    val again = go()
    assertEquals(again.failedBatches, Vector.empty)
    assertEquals(bucket.count(index), 3)
  }

  test(
    "L4: a template bump that renders identical text works and leaves the vector alone"
  ) {
    val env = new Env()
    env.run()
    val objects = env.store.list("").map(o => (o.key, o.sha256))
    val before = env.state()._2
    val calls = env.bucket.calls
    val renderer = new SummaryRenderer:
      def render(row: Row): RenderedDocument =
        RenderedDocument(Rows.str(row, "summaryText"), "spark-summary/2")
    val report = env.run(label = "2", engine = env.ingest(renderer = renderer))
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.written, 0)
    assertEquals(report.skipped, 5)
    assertEquals(report.metadataDrift, 5)
    assert(report.metadataDriftKeys.forall(_.contains("templateVersion")))
    assertEquals(env.bucket.calls, calls)
    assertEquals(env.state()._2, before)
    // No unreferenced document is added.
    assertEquals(
      env.store
        .list("")
        .map(o => (o.key, o.sha256))
        .toSet
        .diff(objects.toSet)
        .map(_._1)
        .filterNot(_.contains("embedding-work")),
      Set.empty
    )
  }

  test("L4: a new row under a bumped template gets a document at the new key") {
    val env = new Env()
    val renderer = new SummaryRenderer:
      def render(row: Row): RenderedDocument =
        RenderedDocument(Rows.str(row, "summaryText"), "spark-summary/2")
    val report = env.run(engine = env.ingest(renderer = renderer))
    assertEquals(report.written, 5)
    val record = env.bucket.getVectors(index, Vector("summary:v1:p1")).head
    val ref = record.metadata("textRef") match
      case MetaValue.Str(v) => v
      case other            => fail(s"textRef is $other")
    assertEquals(
      ref,
      SemanticDocuments.key(
        runId,
        "summary:v1:p1",
        hashOf(Rows.str(baseRows.head, "summaryText")),
        "spark-summary/2"
      )
    )
    assert(ref.length <= Limits.S3Vectors.MaxMetadataStringChars)
  }

  test("L4: a document error on the unchanged path rejects that key only") {
    val store = new ConflictStore
    val env = new Env(store = store)
    env.run()
    val p1 = baseRows.head
    store.conflicting = Set(
      SemanticDocuments.key(
        runId,
        "summary:v1:p1",
        hashOf(Rows.str(p1, "summaryText")),
        "spark-summary/1"
      )
    )
    val report = env.run(label = "2")
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector(("summary:v1:p1", ErrorCode.ImmutableKeyConflict))
    )
    assertEquals(report.skipped, 4)
    assertEquals(report.written, 0)
    val batchStatuses = env
      .ledger("2")
      .filter(i => Attr.optS(i, "entityType").contains("BATCH"))
      .map(i => Attr.optS(i, "status").get)
    assert(batchStatuses.contains("FINISHED_WITH_REJECTS"))
    assert(!batchStatuses.contains("FAILED"))
    assertEquals(
      Attr.optS(env.table.get(env.pk("2"), "META").get, "status"),
      Some("COMPLETED_WITH_REJECTS")
    )
  }

  test(
    "L6: changed facets on an unchanged text are counted as drift, not rewritten"
  ) {
    val env = new Env()
    env.run()
    val before = env.bucket.getVectors(index, Vector("summary:v1:p1"))
    val moved = baseRows.updated(
      0,
      baseRows.head.updated("subjectId", ujson.Str(r2))
    )
    val calls = env.bucket.calls
    val report = env.run(moved, label = "2")
    assertEquals(report.written, 0)
    assertEquals(report.skipped, 5)
    assertEquals(report.metadataDrift, 1)
    assertEquals(report.perIndex.map(_.metadataDrift).sum, 1)
    assertEquals(report.metadataDriftKeys.size, 1)
    assert(report.metadataDriftKeys.head.startsWith("summary:v1:p1["))
    assert(report.metadataDriftKeys.head.contains("subjectId"))
    assertEquals(env.bucket.getVectors(index, Vector("summary:v1:p1")), before)
    assertEquals(env.bucket.calls, calls)
    val summary = env.table.get(env.pk("2"), "STAGE#summary").get
    assertEquals(summary("metadataDrift").num.toInt, 1)
    assert(
      Attr
        .optS(summary, "metadataDriftKeys")
        .exists(_.contains("summary:v1:p1"))
    )
    val drifting = env
      .ledger("2")
      .filter(i => Attr.optS(i, "entityType").contains("BATCH"))
      .map(_.get("metadataDrift").map(_.num.toInt).getOrElse(0))
    assertEquals(drifting.sum, 1)
  }

  test("L6: identical input reports no drift; the key list is bounded") {
    val env = new Env()
    env.run()
    val same = env.run(label = "2")
    assertEquals(same.metadataDrift, 0)
    assertEquals(same.metadataDriftKeys, Vector.empty)
    assertEquals(
      env.table
        .get(env.pk("2"), "STAGE#summary")
        .get("metadataDrift")
        .num
        .toInt,
      0
    )

    val many = (1 to 25).toVector.map(i =>
      profile(f"q$i%02d", s"researcher $i studies topic number $i")
    )
    val other = new Env()
    other.run(many)
    val moved = many.map(_.updated("subjectId", ujson.Str(r2)))
    val report = other.run(moved, label = "2")
    assertEquals(report.metadataDrift, 25)
    assertEquals(report.metadataDriftKeys.size, EmbeddingIngest.MaxDriftKeys)
    assertEquals(report.written, 0)
  }
