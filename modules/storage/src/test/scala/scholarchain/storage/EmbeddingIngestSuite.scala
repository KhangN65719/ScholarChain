package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

class EmbeddingIngestSuite extends munit.FunSuite:
  import Fixtures.*

  private val source = SourceRun(runId, "spark-analytics/1.1.0")
  private val textGraph =
    "Ada works on graph neural networks and community detection in citation graphs"
  private val textProtein =
    "Bo studies protein folding and the molecular biology of enzymes"

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def withSubject(row: Row, subject: String): Row =
    row + ("subjectId" -> ujson.Str(subject))

  private val baseRows: Vector[Row] = Vector(
    summaryRow("researcher-profile", "p1", textGraph),
    withSubject(
      summaryRow("researcher-profile", "p2", textProtein),
      r2
    ),
    summaryRow(
      "researcher-profile",
      "p3",
      "Ada Chen graph embedding with neural network community detection"
    ),
    summaryRow(
      "community",
      "c1",
      "A community of researchers on graph neural networks"
    ),
    summaryRow(
      "work",
      "w1",
      "A paper on graph neural networks for citation analysis"
    ),
    summaryRow(
      "collaboration-path",
      "x1",
      "A collaboration path between Ada and Bo through shared graph papers"
    )
  )

  private def tablesOf(rows: Vector[Row]): TableSource =
    new InMemoryTableSource(
      Map(
        "semantic-summaries" -> rows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  private def workRow(id: String): Row =
    summaryRow("work", id, s"paper $id")
      .updated("subjectId", ujson.Str(w1))
      .updated("evidenceWorkIds", ujson.Arr(ujson.Str(w1)))

  /** Counts every PutVectors call and can be made to fail. */
  private class SpyBucket(
      failOnCall: Int => Option[RuntimeException] = _ => None
  ) extends InMemoryVectorBucket:
    val sizes = mutable.ArrayBuffer.empty[Int]
    var calls = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      calls += 1
      failOnCall(calls).foreach(e => throw e)
      sizes += batch.size
      super.putVectors(index, batch)

  private final class Env(
      val bucket: InMemoryVectorBucket = new SpyBucket(),
      val store: ObjectStore = new InMemoryObjectStore,
      embedder: Embedder = new HashEmbedder,
      renderer: SummaryRenderer = PassThroughRenderer,
      sleeper: Sleeper = NoSleep
  ):
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    private var tick = 0L
    private val clock = () =>
      tick += 5000000L
      tick
    val ingest =
      new EmbeddingIngest(
        embedder,
        renderer,
        bucket,
        store,
        table,
        sleeper,
        clock
      )

    def run(
        rows: Vector[Row] = baseRows,
        label: String = "1",
        batchSize: Int = 500,
        retry: RetryPolicy = RetryPolicy(3, 100, 1000)
    ): IngestReport =
      ingest.run(
        tablesOf(rows),
        source,
        IngestConfig(
          "2026-11-08T00:00:00Z",
          batchSize,
          retry,
          executionLabel = label
        )
      )

    def meta(index: String, key: String): Map[String, MetaValue] =
      bucket.getVectors(index, Vector(key)).head.metadata

  private def metaStr(md: Map[String, MetaValue], key: String): String =
    md(key) match
      case MetaValue.Str(v) => v
      case other            => fail(s"$key is $other")

  private def stages(env: Env, label: String = "1"): Vector[Item] =
    env.table.query(
      s"RUN#embed:$runId:$label",
      Some("STAGE#batch#"),
      1000
    )

  private def runStatus(env: Env, label: String = "1"): String =
    env.table
      .get(s"RUN#embed:$runId:$label", "META")
      .flatMap(Attr.optS(_, "status"))
      .getOrElse("none")

  test("the work manifest is content hashed, immutable and deterministic") {
    val a = new Env()
    val b = new Env()
    val ra = a.run()
    val rb = b.run()
    assertEquals(ra.manifestKey, rb.manifestKey)
    assertEquals(ra.manifestSha256, rb.manifestSha256)
    assert(
      ra.manifestKey.startsWith("semantic-context/embedding-work/v=v1/run=")
    )
    assert(ra.manifestKey.contains(ra.manifestSha256.take(16)))
    val manifest = EmbeddingWorkManifest.read(a.store, ra.manifestKey)
    assertEquals(manifest.items.size, 6)
    assertEquals(manifest.embeddingModel, "hash-embed-v1")
    assertEquals(manifest.dimension, 384)
    assertEquals(manifest.sourceRunId, runId)
    assertEquals(manifest.provenance.runId, runId)
    val order =
      VectorIndexes.all(384, "cosine").map(_.name).zipWithIndex.toMap
    assertEquals(
      manifest.items.map(i => (i.index, i.summaryId)),
      manifest.items
        .map(i => (i.index, i.summaryId))
        .sortBy((index, id) => (order(index), id))
    )
    val item = manifest.items.find(_.summaryId == "summary:v1:p1").get
    assertEquals(item.index, VectorIndexes.ResearcherProfile)
    assertEquals(item.documentType, "researcher-profile")
    assertEquals(item.textHash, Hashing.sha256(textGraph))
    assertEquals(item.templateVersion, "spark-summary/1")
    assertEquals(
      item.sourceRef,
      s"spark-run:$runId/semantic-summaries#summary:v1:p1"
    )
    // Immutable: storing it again is a no-op, a changed one under the key is refused.
    assert(!EmbeddingWorkManifest.put(a.store, manifest)._3.created)
    val changed = manifest.copy(items = manifest.items.drop(1))
    val tamperedKey = EmbeddingWorkManifest.key(changed, "0" * 64)
    a.store.putImmutable(
      tamperedKey,
      EmbeddingWorkManifest.encode(changed),
      "t"
    )
    assertEquals(
      code(EmbeddingWorkManifest.read(a.store, tamperedKey)),
      ErrorCode.ChecksumMismatch
    )
  }

  test(
    "batches are bounded, ordered by index and ordinal, and within the limit"
  ) {
    val env = new Env()
    val rows = (1 to 5).toVector.map(i =>
      summaryRow(
        "researcher-profile",
        f"p$i%02d",
        s"researcher number $i studies topic $i"
      )
    )
    val report = env.run(rows, batchSize = 2)
    val spy = env.bucket.asInstanceOf[SpyBucket]
    assertEquals(spy.sizes.toVector, Vector(2, 2, 1))
    assertEquals(report.perIndex.head.batches, 3)
    assertEquals(report.written, 5)
    assertEquals(
      stages(env).map(i => Attr.optS(i, "sk").get),
      Vector(
        s"STAGE#batch#${VectorIndexes.ResearcherProfile}#000000",
        s"STAGE#batch#${VectorIndexes.ResearcherProfile}#000001",
        s"STAGE#batch#${VectorIndexes.ResearcherProfile}#000002"
      )
    )
    val manifest = EmbeddingWorkManifest.read(env.store, report.manifestKey)
    val batches =
      WorkBatches.of(manifest, VectorIndexes.all(384, "cosine").map(_.name), 2)
    assertEquals(batches.map(_.items.size), Vector(2, 2, 1))
    assertEquals(batches.map(_.ordinal), Vector(0, 1, 2))
    assertEquals(
      code(
        WorkBatches.of(manifest, Vector(VectorIndexes.ResearcherProfile), 501)
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(
        WorkBatches.of(manifest, Vector(VectorIndexes.ResearcherProfile), 0)
      ),
      ErrorCode.BoundExceeded
    )
  }

  test("the renderer output is what is hashed, stored and embedded") {
    val renderer = new SummaryRenderer:
      def render(row: Row): RenderedDocument =
        RenderedDocument(
          s"TEMPLATE: ${Rows.str(row, "summaryText")}",
          "test-template/2"
        )
    val embedder = new HashEmbedder
    val env = new Env(renderer = renderer)
    env.run()
    val key = "summary:v1:p1"
    val rendered = s"TEMPLATE: $textGraph"
    val record = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector(key))
      .head
    assertEquals(record.embedding, embedder.embed(rendered))
    assertEquals(metaStr(record.metadata, "textHash"), Hashing.sha256(rendered))
    assertEquals(metaStr(record.metadata, "templateVersion"), "test-template/2")
    val fetched = EmbeddingIngest.fetchText(
      env.store,
      VectorMatch(key, 0.0, record.metadata)
    )
    assertEquals(fetched.text, rendered)
    assertEquals(fetched.templateVersion, "test-template/2")
    assertEquals(fetched.summaryId, key)
    assertEquals(fetched.runId, runId)
    assertEquals(fetched.documentType, "researcher-profile")
  }

  test("unchanged text and model are skipped: a rerun writes nothing") {
    val env = new Env()
    val first = env.run()
    val spy = env.bucket.asInstanceOf[SpyBucket]
    val callsAfterFirst = spy.calls
    val objects = env.store.list("")
    val before = VectorIndexes
      .all(384, "cosine")
      .map(i => env.bucket.count(i.name))
    val vectors = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
    assertEquals(first.written, 6)
    assertEquals(first.skipped, 0)
    val again = env.run(label = "2")
    assertEquals(again.written, 0)
    assertEquals(again.skipped, 6)
    assertEquals(again.perIndex.map(_.skippedUnchanged).sum, 6)
    assertEquals(again.rejected, Vector.empty)
    assertEquals(again.documentsCreated, 0)
    assertEquals(spy.calls, callsAfterFirst)
    assertEquals(env.store.list("").map(_.key), objects.map(_.key))
    assertEquals(
      VectorIndexes.all(384, "cosine").map(i => env.bucket.count(i.name)),
      before
    )
    assertEquals(
      env.bucket
        .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1")),
      vectors
    )
  }

  test("a completed execution rerun under the same label only resumes") {
    val env = new Env()
    env.run()
    val spy = env.bucket.asInstanceOf[SpyBucket]
    val calls = spy.calls
    val again = env.run()
    assertEquals(again.written, 0)
    assertEquals(again.perIndex.map(_.skippedResumed).sum, 6)
    assertEquals(spy.calls, calls)
    assertEquals(runStatus(env), "COMPLETED")
  }

  test("changed text under an existing key is refused and reported") {
    val env = new Env()
    env.run()
    val oldVector = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
      .head
    val edited = baseRows.updated(
      0,
      summaryRow(
        "researcher-profile",
        "p1",
        "Ada now studies something else entirely"
      )
    )
    val report = env.run(edited, label = "2")
    assertEquals(report.written, 0)
    assertEquals(report.skipped, 5)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector(("summary:v1:p1", ErrorCode.ChangedVectorContent))
    )
    assert(report.rejected.head.detail.contains("refused"))
    assertEquals(
      env.bucket
        .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
        .head,
      oldVector
    )
    assertEquals(runStatus(env, "2"), "COMPLETED_WITH_REJECTS")
    assert(report.rejectedKeys.nonEmpty)
    val persisted =
      new String(env.store.get(report.rejectedKeys.head).get, UTF_8)
    assert(persisted.contains("ChangedVectorContent"))
    assert(persisted.contains("summary:v1:p1"))
    assertEquals(report.perIndex.map(_.rejected).sum, 1)
  }

  test(
    "a new embedding model under existing keys is refused, not overwritten"
  ) {
    val env = new Env()
    env.run()
    val before = env.bucket.getVectors(
      VectorIndexes.ResearcherProfile,
      Vector("summary:v1:p1")
    )
    val other = new EmbeddingIngest(
      new FixedEmbedder(384),
      PassThroughRenderer,
      env.bucket,
      env.store,
      env.table
    )
    val report = other.run(
      tablesOf(baseRows),
      source,
      IngestConfig("2026-11-08T00:00:00Z", executionLabel = "model2")
    )
    assertEquals(report.written, 0)
    assertEquals(report.rejected.size, 6)
    assert(report.rejected.forall(_.code == ErrorCode.ChangedVectorContent))
    assert(report.rejected.head.detail.contains("hash-embed-v1"))
    assertEquals(
      env.bucket
        .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1")),
      before
    )
  }

  test(
    "duplicates: identical rows collapse, the same ID with other text is rejected"
  ) {
    val env = new Env()
    val rows = baseRows ++ Vector(
      baseRows.head,
      summaryRow(
        "researcher-profile",
        "p1",
        "A different text under the same summary ID"
      )
    )
    val report = env.run(rows)
    assertEquals(report.written, 6)
    assertEquals(report.duplicates, 1)
    assertEquals(
      report.rejected.map(_.code),
      Vector(ErrorCode.DuplicateVectorKey)
    )
    val stored = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
      .head
    assertEquals(
      metaStr(stored.metadata, "textHash"),
      Hashing.sha256(textGraph)
    )
  }

  test(
    "unmapped types, blank text, a foreign run and malformed rows are reported"
  ) {
    val env = new Env()
    val foreign = summaryRow("work", "w9", "text")
    val provenance = ujson.read(ujson.write(foreign("provenance")))
    provenance("runId") = "run:v1:22222222-2222-4222-8222-222222222222"
    val rows = baseRows ++ Vector(
      summaryRow("topic", "t1", "a topic summary"),
      summaryRow("work", "blank", "   "),
      summaryRow("work", "dashes", "!!! ---"),
      foreign.updated("provenance", provenance),
      Map("summaryType" -> ujson.Str("work"))
    )
    val report = env.run(rows)
    assertEquals(report.written, 6)
    assertEquals(report.unmapped.map(_.code), Vector(ErrorCode.UnmappedSummary))
    assertEquals(
      report.rejected.map(r => (r.key, r.code)).toSet,
      Set(
        ("summary:v1:blank", ErrorCode.EmbeddingInvalid),
        ("summary:v1:dashes", ErrorCode.EmbeddingInvalid),
        ("summary:v1:w9", ErrorCode.MalformedRow),
        ("<no summaryId>", ErrorCode.MalformedRow)
      )
    )
    // Rejected vectors leave no document and no vector behind.
    val documents = env.store.list(SemanticDocuments.prefix)
    assertEquals(documents.size, 6)
    assertEquals(env.bucket.count(VectorIndexes.PaperAbstract), 1)
  }

  test("a vector's textRef resolves to its document through the object store") {
    val env = new Env()
    env.run()
    val record = env.bucket
      .getVectors(VectorIndexes.CollaborationCommunity, Vector("summary:v1:c1"))
      .head
    val ref = metaStr(record.metadata, "textRef")
    assert(ref.startsWith("semantic-context/documents/v=v1/run="))
    assert(ref.length <= Limits.S3Vectors.MaxMetadataStringChars)
    val object1 = env.store.head(ref).get
    assertEquals(
      object1.sourceRef,
      s"spark-run:$runId/semantic-summaries#summary:v1:c1"
    )
    val doc = ujson.read(new String(env.store.get(ref).get, UTF_8))
    assertEquals(doc("summaryId").str, "summary:v1:c1")
    assertEquals(doc("runId").str, runId)
    assertEquals(doc("templateVersion").str, "spark-summary/1")
    assertEquals(doc("textHash").str, metaStr(record.metadata, "textHash"))
    val fetched = EmbeddingIngest.fetchText(
      env.store,
      VectorMatch("summary:v1:c1", 0.1, record.metadata)
    )
    assertEquals(
      fetched.text,
      "A community of researchers on graph neural networks"
    )
    assertEquals(fetched.key, ref)
  }

  /** An object store whose reads can be tampered with. */
  private final class TamperStore extends ObjectStore:
    val inner = new InMemoryObjectStore
    var replaced = Map.empty[String, Array[Byte]]
    var hidden = Set.empty[String]
    protected def write(
        key: String,
        bytes: Array[Byte],
        sourceRef: String
    ): Unit =
      inner.putImmutable(key, bytes, sourceRef)
    def head(key: String): Option[ObjectMeta] = inner.head(key)
    def get(key: String): Option[Array[Byte]] =
      if hidden.contains(key) then None
      else replaced.get(key).orElse(inner.get(key))
    def list(prefix: String): Vector[ObjectMeta] = inner.list(prefix)

  test("a tampered, missing, swapped or misdirected document is detected") {
    val store = new TamperStore
    val env = new Env(store = store)
    env.run()
    val a = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
      .head
    val b = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p2"))
      .head
    val hit = VectorMatch(a.key, 0.0, a.metadata)
    val keyA = metaStr(a.metadata, "textRef")
    val keyB = metaStr(b.metadata, "textRef")
    assertEquals(EmbeddingIngest.fetchText(store, hit).text, textGraph)

    val original = store.inner.get(keyA).get
    val edited =
      new String(original, UTF_8).replace("graph neural", "graph NEURAL")
    store.replaced = Map(keyA -> edited.getBytes(UTF_8))
    assertEquals(
      code(EmbeddingIngest.fetchText(store, hit)),
      ErrorCode.TextHashMismatch
    )

    store.replaced = Map(keyA -> store.inner.get(keyB).get)
    assertEquals(
      code(EmbeddingIngest.fetchText(store, hit)),
      ErrorCode.TextHashMismatch
    )

    store.replaced = Map(keyA -> "not json".getBytes(UTF_8))
    assertEquals(
      code(EmbeddingIngest.fetchText(store, hit)),
      ErrorCode.TextHashMismatch
    )

    store.replaced = Map.empty
    store.hidden = Set(keyA)
    assertEquals(
      code(EmbeddingIngest.fetchText(store, hit)),
      ErrorCode.MissingText
    )

    store.hidden = Set.empty
    val outside = VectorMatch(
      a.key,
      0.0,
      a.metadata + ("textRef" -> MetaValue.Str("graph/x/part"))
    )
    assertEquals(
      code(EmbeddingIngest.fetchText(store, outside)),
      ErrorCode.InvalidKey
    )
    val noHash = VectorMatch(a.key, 0.0, a.metadata - "textHash")
    assertEquals(
      code(EmbeddingIngest.fetchText(store, noHash)),
      ErrorCode.MissingText
    )
    val wrongHash = VectorMatch(
      a.key,
      0.0,
      a.metadata + ("textHash" -> MetaValue.Str("0" * 64))
    )
    assertEquals(
      code(EmbeddingIngest.fetchText(store, wrongHash)),
      ErrorCode.TextHashMismatch
    )
  }

  test(
    "metadata is compact, backed by Spark, carries the model and the text hash"
  ) {
    val env = new Env()
    env.run()
    val all = VectorIndexes.all(384, "cosine").flatMap { spec =>
      env.bucket
        .query(VectorQuery(spec.name, new HashEmbedder().embed("graph"), 100))
        .map(spec.name -> _)
    }
    assertEquals(all.size, 6)
    all.foreach { case (index, hit) =>
      val md = hit.metadata
      VectorMetadataIngest.validate(md)
      assert(
        VectorMetadata.sizeBytes(
          md
        ) <= Limits.S3Vectors.MaxFilterableMetadataBytes,
        index
      )
      assert(!md.keySet.exists(VectorMetadata.TextKeys.contains))
      assertEquals(metaStr(md, "embeddingModel"), "hash-embed-v1")
      assertEquals(metaStr(md, "runId"), runId)
      assertEquals(md("textHash").asInstanceOf[MetaValue.Str].value.length, 64)
      assert(!md.contains("topic") && !md.contains("institution"))
      md.values.foreach {
        case MetaValue.Str(v) =>
          assert(v.length <= 200 && !v.contains(textGraph))
        case MetaValue.Strs(v) => assert(v.forall(_.length <= 200))
        case _                 => ()
      }
    }
    val profile = env.meta(VectorIndexes.ResearcherProfile, "summary:v1:p1")
    assertEquals(metaStr(profile, "documentType"), "researcher-profile")
    assertEquals(metaStr(profile, "subjectId"), r1)
    assertEquals(metaStr(profile, "communityId"), "community-1")
    assertEquals(profile("windowStart"), MetaValue.Num(2000.0))
    assertEquals(profile("windowEnd"), MetaValue.Num(2009.0))
    assertEquals(metaStr(profile, "window"), "2000-2009")
    assertEquals(profile("source"), MetaValue.Strs(Vector("dblp")))
    assertEquals(metaStr(profile, "templateVersion"), "spark-summary/1")
    val noCommunity = env.meta(VectorIndexes.ResearcherProfile, "summary:v1:p2")
    assert(!noCommunity.contains("communityId"))
  }

  test("metadata of full-length real IDs stays within the filterable limit") {
    val env = new Env()
    val id = "a" * 64
    val row = summaryRow("researcher-profile", id, "Ada works on graphs")
    val report = env.run(Vector(row))
    assertEquals(report.written, 1)
    val md = env.meta(VectorIndexes.ResearcherProfile, s"summary:v1:$id")
    val size = VectorMetadata.sizeBytes(md)
    assert(size <= Limits.S3Vectors.MaxFilterableMetadataBytes, s"$size bytes")
    assert(
      metaStr(md, "textRef").length <= Limits.S3Vectors.MaxMetadataStringChars
    )
  }

  test("textHash is required on the ingest path and shaped as a SHA-256") {
    val good: Map[String, MetaValue] = Map(
      "summaryType" -> MetaValue.Str("work"),
      "subjectId" -> MetaValue.Str(w1),
      "textRef" -> MetaValue.Str("semantic-context/documents/x"),
      "runId" -> MetaValue.Str(runId),
      "embeddingModel" -> MetaValue.Str("hash-embed-v1"),
      "documentType" -> MetaValue.Str("work"),
      "textHash" -> MetaValue.Str(Hashing.sha256("x"))
    )
    VectorMetadataIngest.validate(good)
    Vector("textHash", "embeddingModel", "documentType").foreach { key =>
      assertEquals(
        code(VectorMetadataIngest.validate(good - key)),
        ErrorCode.MetadataInvalid,
        key
      )
    }
    assertEquals(
      code(
        VectorMetadata.validate(good + ("textHash" -> MetaValue.Str("xyz")))
      ),
      ErrorCode.MetadataInvalid
    )
    assertEquals(
      code(VectorMetadata.validate(good + ("textHash" -> MetaValue.Num(1)))),
      ErrorCode.MetadataInvalid
    )
    // Phase 7 vectors without it still validate on the shared path.
    VectorMetadata.validate(good - "textHash")
    assert(VectorMetadata.Allowed.contains("textHash"))
    assert(VectorMetadata.Allowed.contains("templateVersion"))
  }

  test("vector metadata and query results carry no graph metrics") {
    val metricNames = Set(
      "publicationCount",
      "distinctCoauthorCount",
      "totalCoauthorOccurrences",
      "weightedDegree",
      "componentId",
      "componentSize",
      "bridgeScore",
      "coreness",
      "shortestPath",
      "pathLength",
      "hopCount"
    )
    assert(VectorMetadata.Allowed.intersect(metricNames).isEmpty)
    val env = new Env()
    env.run()
    val hits = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        new HashEmbedder().embed(textGraph),
        10
      )
    )
    assert(hits.nonEmpty)
    hits.foreach(h => assert(h.metadata.keySet.intersect(metricNames).isEmpty))
    val bad: Map[String, MetaValue] = Map(
      "summaryType" -> MetaValue.Str("work"),
      "subjectId" -> MetaValue.Str(w1),
      "textRef" -> MetaValue.Str("semantic-context/documents/x"),
      "runId" -> MetaValue.Str(runId),
      "bridgeScore" -> MetaValue.Num(0.5)
    )
    assertEquals(code(VectorMetadata.validate(bad)), ErrorCode.MetadataInvalid)
  }

  test("ledger: run and batch records with counts, versions and provenance") {
    val env = new Env()
    val report = env.run(batchSize = 2)
    assertEquals(runStatus(env), "COMPLETED")
    val rows = stages(env)
    // researcher-profile has 3 items (2 batches), the other three indexes 1 each.
    assertEquals(rows.size, 5)
    assert(rows.forall(r => Attr.optS(r, "status").contains("FINISHED")))
    assert(rows.forall(r => r("version").num.toLong == 2L))
    assert(rows.forall(r => r("attempt").num.toLong == 1L))
    assertEquals(rows.map(_("written").num.toInt).sum, 6)
    assert(rows.forall(r => Attr.optS(r, "sourceRunId").contains(runId)))
    assert(
      rows.forall(r =>
        Attr.optS(r, "storageTransformVersion").contains("storage/1.0.0")
      )
    )
    assert(
      rows.forall(r =>
        Attr.optS(r, "sparkTransformVersion").contains("spark-analytics/1.1.0")
      )
    )
    val summary = env.table.get(s"RUN#embed:$runId:1", "STAGE#summary").get
    assertEquals(summary("written").num.toInt, 6)
    assertEquals(summary("failedBatches").num.toInt, 0)
    assertEquals(report.written, 6)
    // The ledger is a conditional-write table: a second start of the same
    // execution is the same run, a different run kind under its ID is refused.
    assertEquals(
      RunLedger.start(
        env.table,
        s"embed:$runId:1",
        "embedding-ingest",
        "spark-analytics/1.1.0",
        "2026-11-08T00:00:00Z"
      ),
      WriteOutcome.Unchanged
    )
    assertEquals(
      code(
        RunLedger.start(
          env.table,
          s"embed:$runId:1",
          "spark-analytics",
          "spark-analytics/1.1.0",
          "2026-11-08T00:00:00Z"
        )
      ),
      ErrorCode.ConditionalCheckFailed
    )
  }

  test(
    "ledger: a failed batch is recorded with its message, the others go on"
  ) {
    val bucket = new SpyBucket(call =>
      if call == 2 then Some(new RuntimeException("boom: disk gone")) else None
    )
    val env = new Env(bucket = bucket)
    val rows = (1 to 5).toVector.map(i =>
      summaryRow(
        "researcher-profile",
        f"p$i%02d",
        s"researcher $i studies topic $i"
      )
    )
    val report = env.run(rows, batchSize = 2)
    assertEquals(
      report.failedBatches.map(f => (f.index, f.ordinal)),
      Vector((VectorIndexes.ResearcherProfile, 1))
    )
    assert(report.failedBatches.head.reason.contains("boom: disk gone"))
    assertEquals(report.written, 3)
    assertEquals(report.perIndex.head.failedBatches, 1)
    assertEquals(runStatus(env), "FAILED")
    val failed =
      stages(env).find(r => Attr.optS(r, "status").contains("FAILED")).get
    assert(Attr.optS(failed, "failure").exists(_.contains("boom: disk gone")))
    assertEquals(failed("attempt").num.toLong, 1L)
    val summary = env.table.get(s"RUN#embed:$runId:1", "STAGE#summary").get
    assertEquals(summary("failedBatches").num.toInt, 1)
    assertEquals(bucket.count(VectorIndexes.ResearcherProfile), 3)
  }

  test(
    "a rerun of a failed execution redoes only the failed batch, with a higher attempt"
  ) {
    var broken = true
    val bucket = new SpyBucket(call =>
      if broken && call == 2 then Some(new RuntimeException("boom")) else None
    )
    val env = new Env(bucket = bucket)
    val rows = (1 to 5).toVector.map(i =>
      summaryRow(
        "researcher-profile",
        f"p$i%02d",
        s"researcher $i studies topic $i"
      )
    )
    env.run(rows, batchSize = 2)
    broken = false
    val callsBefore = bucket.calls
    val again = env.run(rows, batchSize = 2)
    assertEquals(bucket.calls - callsBefore, 1)
    assertEquals(again.written, 2)
    assertEquals(again.perIndex.head.skippedResumed, 3)
    assertEquals(again.failedBatches, Vector.empty)
    assertEquals(bucket.count(VectorIndexes.ResearcherProfile), 5)
    assertEquals(runStatus(env), "COMPLETED")
    val attempts = stages(env).map(r => r("attempt").num.toLong)
    assertEquals(attempts, Vector(1L, 2L, 1L))
    assert(stages(env).forall(r => Attr.optS(r, "status").contains("FINISHED")))
  }

  test("an interrupted run continues from the ledger") {
    var interrupt = true
    var embeds = 0
    val inner = new HashEmbedder
    val embedder = new Embedder:
      def modelVersion: String = inner.modelVersion
      def dimension: Int = inner.dimension
      def embed(text: String): Vector[Float] =
        embeds += 1
        if interrupt && embeds == 4 then
          throw new InterruptedException("killed")
        inner.embed(text)
    val env = new Env(embedder = embedder)
    val rows = (1 to 5).toVector.map(i =>
      summaryRow(
        "researcher-profile",
        f"p$i%02d",
        s"researcher $i studies topic $i"
      )
    )
    // munit's intercept rethrows InterruptedException, so catch it by hand.
    var interrupted = false
    try env.run(rows, batchSize = 2)
    catch case _: InterruptedException => interrupted = true
    assert(interrupted, "the run should have been interrupted")
    assertEquals(runStatus(env), "STARTED")
    assertEquals(env.bucket.count(VectorIndexes.ResearcherProfile), 2)
    assertEquals(
      stages(env).map(r => Attr.optS(r, "status").get),
      Vector("FINISHED", "STARTED")
    )
    interrupt = false
    val resumed = env.run(rows, batchSize = 2)
    assertEquals(resumed.perIndex.head.skippedResumed, 2)
    assertEquals(resumed.written, 3)
    assertEquals(env.bucket.count(VectorIndexes.ResearcherProfile), 5)
    assertEquals(runStatus(env), "COMPLETED")
    assertEquals(
      stages(env).map(r => r("attempt").num.toLong),
      Vector(1L, 2L, 1L)
    )
  }

  test(
    "retries: throttling is retried, counted and recorded; exhaustion is rejected, not lost"
  ) {
    val slept = mutable.ArrayBuffer.empty[Long]
    val sleeper = new Sleeper:
      def sleep(millis: Long): Unit = slept += millis
    val throttled = new SpyBucket(call =>
      if call <= 2 then Some(new TransientStorageException("throttled"))
      else None
    )
    val env = new Env(bucket = throttled, sleeper = sleeper)
    val report = env.run(Vector(baseRows.head))
    assertEquals(report.written, 1)
    assertEquals(report.retries, 2)
    assertEquals(slept.toVector, Vector(100L, 200L))
    assertEquals(stages(env).head("retries").num.toInt, 2)

    val dead =
      new SpyBucket(_ => Some(new TransientStorageException("throttled")))
    val env2 = new Env(bucket = dead)
    val r2 = env2.run(Vector(baseRows.head), retry = RetryPolicy(2, 1, 1))
    assertEquals(r2.written, 0)
    assertEquals(r2.rejected.map(_.code), Vector(ErrorCode.RetryExhausted))
    assertEquals(stages(env2).head("status").str, "FINISHED_WITH_REJECTS")
    assertEquals(stages(env2).head("rejected").num.toInt, 1)
    assertEquals(runStatus(env2), "COMPLETED_WITH_REJECTS")
    // Not finished, so a rerun of the same execution tries the batch again.
    val calls = dead.calls
    env2.run(Vector(baseRows.head), retry = RetryPolicy(2, 1, 1))
    assert(dead.calls > calls)
  }

  test(
    "all five indexes exist; topic-institution has no source and stays empty"
  ) {
    val env = new Env()
    val report = env.run()
    assertEquals(
      report.perIndex.map(_.index),
      Vector(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals(report.perIndex.map(_.vectorsInIndex), Vector(3, 1, 1, 1, 0))
    assertEquals(report.perIndex.map(_.planned), Vector(3, 1, 1, 1, 0))
    assertEquals(env.bucket.count(VectorIndexes.TopicInstitutionSummary), 0)
    VectorIndexes
      .all(384, "cosine")
      .foreach(spec => env.bucket.ensureIndex(spec))
  }

  test("an empty export still creates all five indexes") {
    val env = new Env()
    val report = env.run(Vector.empty)
    assertEquals(report.perIndex.map(_.vectorsInIndex), Vector(0, 0, 0, 0, 0))
    assertEquals(report.written, 0)
    assertEquals(runStatus(env), "COMPLETED")
  }

  test(
    "the report counts written, skipped, rejected and duplicate vectors and local timings"
  ) {
    val env = new Env()
    val rows =
      baseRows ++ Vector(baseRows.head, summaryRow("work", "blank", " "))
    val report = env.run(rows, batchSize = 2)
    assertEquals(report.written, 6)
    assertEquals(report.duplicates, 1)
    assertEquals(report.rejected.size, 1)
    assertEquals(report.manifestItems, 7)
    assertEquals(report.documentObjects, 6)
    assert(report.documentBytes > 0)
    assertEquals(report.documentsCreated, 6)
    assert(report.timings.label.startsWith("LOCAL"))
    assertEquals(report.timings.batches.size, 5)
    assertEquals(report.timings.batches.map(_.items).sum, 7)
    assert(report.timings.batches.forall(_.millis >= 0))
    assert(report.timings.totalMillis > 0)
    assert(report.timings.vectorsPerSecond(report.written) > 0.0)
    assertEquals(report.perIndex.map(_.vectorsInIndex).sum, 6)
  }

  test(
    "query smoke: filtered neighbours are the expected ones and the text is fetchable"
  ) {
    val env = new Env()
    env.run()
    val embedder = new HashEmbedder
    val graphQuery = embedder.embed("graph neural networks community detection")
    val hits = env.bucket.query(
      VectorQuery(VectorIndexes.ResearcherProfile, graphQuery, 3)
    )
    assertEquals(hits.size, 3)
    assertEquals(hits.last.key, "summary:v1:p2")
    assert(
      Set("summary:v1:p1", "summary:v1:p3") == hits.take(2).map(_.key).toSet
    )
    val protein = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        embedder.embed("protein folding enzymes"),
        1
      )
    )
    assertEquals(protein.head.key, "summary:v1:p2")
    val byResearcher = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        graphQuery,
        3,
        VectorFilter(researcher = Some(r2))
      )
    )
    assertEquals(byResearcher.map(_.key), Vector("summary:v1:p2"))
    val byCommunity = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        graphQuery,
        3,
        VectorFilter(community = Some("community-1"))
      )
    )
    assertEquals(
      byCommunity.map(_.key).toSet,
      Set("summary:v1:p1", "summary:v1:p3")
    )
    assert(
      byCommunity.forall(h =>
        metaStr(h.metadata, "communityId") == "community-1"
      )
    )
    val year = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        graphQuery,
        3,
        VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))
      )
    )
    assertEquals(year.size, 3)
    val none = env.bucket.query(
      VectorQuery(
        VectorIndexes.ResearcherProfile,
        graphQuery,
        3,
        VectorFilter(yearFrom = Some(2010))
      )
    )
    assertEquals(none, Vector.empty)
    val fetched = EmbeddingIngest.fetchText(env.store, hits.head)
    assertEquals(
      fetched.text,
      if hits.head.key == "summary:v1:p1" then textGraph
      else "Ada Chen graph embedding with neural network community detection"
    )
    assertEquals(fetched.textHash, Hashing.sha256(fetched.text))
    val work = env.bucket.query(
      VectorQuery(
        VectorIndexes.PaperAbstract,
        graphQuery,
        1,
        VectorFilter(source = Some("dblp"))
      )
    )
    assertEquals(work.map(_.key), Vector("summary:v1:w1"))
  }

  test("a JSON-lines export runs end to end and reloads with zero writes") {
    val dir = ExportFixtures.write(
      ExportFixtures.newDir(),
      Map(
        "semantic-summaries" -> baseRows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )
    val exported = JsonlExportSource.open(dir)
    val env = new Env()
    val config = IngestConfig("2026-11-08T00:00:00Z", batchSize = 4)
    val first = env.ingest.run(exported, exported.sourceRun, config)
    assertEquals(first.written, 6)
    assertEquals(first.rejected, Vector.empty)
    val objects = env.store.list("").map(o => (o.key, o.sha256))
    val spy = env.bucket.asInstanceOf[SpyBucket]
    val calls = spy.calls
    val second = env.ingest.run(
      exported,
      exported.sourceRun,
      config.copy(executionLabel = "2")
    )
    assertEquals(second.written, 0)
    assertEquals(second.skipped, 6)
    assertEquals(spy.calls, calls)
    assertEquals(env.store.list("").map(o => (o.key, o.sha256)), objects)
    assertEquals(second.manifestKey, first.manifestKey)
  }

  test("a document is a no-op on reload and a conflict if it would change") {
    val store = new InMemoryObjectStore
    val doc = RenderedDocument("some text", "t/1")
    val hash = Hashing.sha256("some text")
    val (key, first) =
      SemanticDocuments.put(store, runId, "summary:v1:z", "work", doc, hash)
    val (again, second) =
      SemanticDocuments.put(store, runId, "summary:v1:z", "work", doc, hash)
    assertEquals(key, again)
    assert(first.created && !second.created)
    // Other content under the same key (same summary, hash and template) is
    // still a conflict.
    assertEquals(
      code(
        SemanticDocuments.put(
          store,
          runId,
          "summary:v1:z",
          "work",
          doc.copy(text = "other text"),
          hash
        )
      ),
      ErrorCode.ImmutableKeyConflict
    )
    // A new template version is a new key, not a conflict (L4).
    val (bumped, third) = SemanticDocuments.put(
      store,
      runId,
      "summary:v1:z",
      "work",
      doc.copy(templateVersion = "t/2"),
      hash
    )
    assert(third.created && bumped != key)
    assert(bumped.length <= Limits.S3Vectors.MaxMetadataStringChars)
    assertEquals(
      code(
        SemanticDocuments.put(
          store,
          runId,
          "summary:v1:y",
          "work",
          RenderedDocument("a\u0000b", "t/1"),
          hash
        )
      ),
      ErrorCode.NullCharacter
    )
  }
