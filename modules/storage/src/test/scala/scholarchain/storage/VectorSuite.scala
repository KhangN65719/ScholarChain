package scholarchain.storage

class VectorSuite extends munit.FunSuite:
  import Fixtures.*

  private val dim = 4
  private val spec = VectorIndexSpec(
    VectorIndexes.ResearcherProfile,
    dim,
    "cosine",
    Some("researcher-profile")
  )

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val goodMeta: Map[String, MetaValue] = Map(
    "summaryType" -> MetaValue.Str("researcher-profile"),
    "subjectId" -> MetaValue.Str(r1),
    "textRef" -> MetaValue.Str("semantic-context/x/part.parquet#summary:v1:a"),
    "runId" -> MetaValue.Str(runId)
  )

  private def record(
      key: String,
      meta: Map[String, MetaValue] = goodMeta
  ): VectorRecord =
    VectorRecord(key, Vector.fill(dim)(0.5f), meta)

  private def writer(
      bucket: VectorBucketPort,
      sleeper: Sleeper = NoSleep,
      attempts: Int = 3
  ) = new VectorWriter(bucket, RetryPolicy(attempts, 100, 1000), sleeper)

  test("no long text in vector metadata: text keys and long strings refused") {
    Vector("summaryText", "abstract", "text", "body").foreach { key =>
      val meta = goodMeta + (key -> MetaValue.Str("short"))
      assertEquals(
        code(VectorMetadata.validate(meta)),
        ErrorCode.MetadataInvalid
      )
    }
    val long = goodMeta + ("subjectId" -> MetaValue.Str(longText))
    assertEquals(code(VectorMetadata.validate(long)), ErrorCode.MetadataInvalid)
    val prose = goodMeta + ("subjectId" -> MetaValue.Str(
      "one two three four five six seven eight nine ten eleven twelve thirteen"
    ))
    assertEquals(
      code(VectorMetadata.validate(prose)),
      ErrorCode.MetadataInvalid
    )
    val newline = goodMeta + ("subjectId" -> MetaValue.Str("a\nb"))
    assertEquals(
      code(VectorMetadata.validate(newline)),
      ErrorCode.MetadataInvalid
    )
  }

  test("the semantic loader embeds the text but never stores it in metadata") {
    val bucket = new InMemoryVectorBucket
    val indexes = VectorIndexes.all(dim, "cosine")
    val rows = Vector(
      summaryRow("researcher-profile", "p1", longText),
      summaryRow("community", "c1", longText),
      summaryRow("work", "w1", longText),
      summaryRow("collaboration-path", "x1", longText)
    )
    val report = SemanticVectorLoader.load(
      rows,
      indexes,
      new FixedEmbedder(dim),
      "semantic-context/summaries",
      writer(bucket),
      LoadBounds.Default
    )
    assertEquals(report.perIndex.map(_.written), Vector(1, 1, 1, 1, 0))
    assertEquals(report.perIndex.flatMap(_.rejected), Vector.empty)
    assertEquals(report.unmapped, Vector.empty)
    val stored = bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
      .head
    stored.metadata.values.foreach {
      case MetaValue.Str(v) =>
        assert(v.length <= Limits.S3Vectors.MaxMetadataStringChars)
      case MetaValue.Strs(v) => assert(v.forall(_.length <= 200))
      case _                 => ()
    }
    assert(!stored.metadata.keySet.exists(VectorMetadata.TextKeys.contains))
    assert(!stored.metadata.toString.contains("has many publications"))
    assertEquals(
      stored.metadata("textRef"),
      MetaValue.Str("semantic-context/summaries#summary:v1:p1")
    )
    assertEquals(stored.metadata("evidenceWorkCount"), MetaValue.Num(7.0))
    assertEquals(
      stored
        .metadata("evidenceWorkIds")
        .asInstanceOf[MetaValue.Strs]
        .values
        .size,
      5
    )
    assert(
      VectorMetadata.sizeBytes(
        stored.metadata
      ) <= Limits.S3Vectors.MaxFilterableMetadataBytes
    )
  }

  test("an unknown summary type is reported, not dropped") {
    val bucket = new InMemoryVectorBucket
    val report = SemanticVectorLoader.load(
      Vector(summaryRow("topic", "t1", "x")),
      VectorIndexes.all(dim, "cosine"),
      new FixedEmbedder(dim),
      "base",
      writer(bucket),
      LoadBounds.Default
    )
    assertEquals(report.unmapped.map(_.code), Vector(ErrorCode.UnmappedSummary))
  }

  test("all five required indexes exist with the required names") {
    assertEquals(
      VectorIndexes.all(dim, "cosine").map(_.name),
      Vector(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
  }

  test("missing and malformed metadata are rejected, not written") {
    val bucket = new InMemoryVectorBucket
    val records = Vector(
      record("ok"),
      record("no-ref", goodMeta - "textRef"),
      record("unknown-key", goodMeta + ("surprise" -> MetaValue.Str("x"))),
      record("nan", goodMeta + ("windowStart" -> MetaValue.Num(Double.NaN))),
      record(
        "too-many",
        goodMeta ++ (1 to 60).map(i => s"k$i" -> MetaValue.Str("v"))
      )
    )
    val report = writer(bucket).write(spec, records)
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(_.key).toSet,
      Set("no-ref", "unknown-key", "nan", "too-many")
    )
    assert(report.rejected.forall(_.code == ErrorCode.MetadataInvalid))
    assertEquals(bucket.count(spec.name), 1)
  }

  test("filterable metadata over 2 KB is rejected") {
    val big = goodMeta + ("evidenceNodeIds" -> MetaValue.Strs(
      Vector.fill(5)("n" * 199)
    )) +
      ("evidenceWorkIds" -> MetaValue.Strs(Vector.fill(5)("w" * 199)))
    assert(
      VectorMetadata.sizeBytes(
        big
      ) > Limits.S3Vectors.MaxFilterableMetadataBytes
    )
    assertEquals(code(VectorMetadata.validate(big)), ErrorCode.MetadataInvalid)
  }

  test("bad keys and embeddings are rejected") {
    val bucket = new InMemoryVectorBucket
    val long = "k" * (Limits.S3Vectors.MaxVectorKeyChars + 1)
    val ok = "k" * Limits.S3Vectors.MaxVectorKeyChars
    val records = Vector(
      record(long),
      record(" "),
      record("a\u0001b"),
      record(ok),
      VectorRecord("short-dim", Vector(1f), goodMeta),
      VectorRecord("nan-vec", Vector(Float.NaN, 0f, 0f, 0f), goodMeta)
    )
    val report = writer(bucket).write(spec, records)
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(_.code).toSet,
      Set(ErrorCode.VectorKeyInvalid, ErrorCode.EmbeddingInvalid)
    )
    assertEquals(report.rejected.size, 5)
  }

  test("duplicate keys: identical collapse, conflicting are rejected") {
    val bucket = new InMemoryVectorBucket
    val a = record("dup")
    val conflicting = a.copy(embedding = Vector.fill(dim)(0.9f))
    val report =
      writer(bucket).write(spec, Vector(a, a, conflicting, record("other")))
    assertEquals(report.written, 2)
    assertEquals(report.duplicatesCollapsed, 1)
    assertEquals(
      report.rejected.map(_.code),
      Vector(ErrorCode.DuplicateVectorKey)
    )
    assertEquals(bucket.getVectors(spec.name, Vector("dup")).head, a)
  }

  test("writing the same vectors twice leaves the same state") {
    val bucket = new InMemoryVectorBucket
    val records = (1 to 30).map(i => record(s"key-$i")).toVector
    writer(bucket).write(spec, records)
    val before =
      bucket.getVectors(spec.name, records.take(30).map(_.key).take(100))
    val again = writer(bucket).write(spec, records)
    assertEquals(bucket.count(spec.name), 30)
    assertEquals(bucket.getVectors(spec.name, records.map(_.key)), before)
    assertEquals(again.rejected, Vector.empty)
  }

  test("batches never exceed 500 vectors") {
    val sizes = scala.collection.mutable.ArrayBuffer.empty[Int]
    val spy = new InMemoryVectorBucket:
      override def putVectors(
          index: String,
          batch: Vector[VectorRecord]
      ): Unit =
        sizes += batch.size
        super.putVectors(index, batch)
    val records = (1 to 1201).map(i => record(s"key-$i")).toVector
    val report = writer(spy).write(spec, records)
    assertEquals(sizes.toVector, Vector(500, 500, 201))
    assertEquals(report.batches, 3)
    assertEquals(report.written, 1201)
    intercept[IllegalArgumentException](
      new VectorWriter(spy, RetryPolicy(1, 1, 1), NoSleep, 501)
    )
  }

  private final class Flaky(failures: Int) extends InMemoryVectorBucket:
    var calls = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      calls += 1
      if calls <= failures then throw new TransientStorageException("throttled")
      super.putVectors(index, batch)

  test("throttling retries with bounded exponential backoff") {
    val slept = scala.collection.mutable.ArrayBuffer.empty[Long]
    val sleeper = new Sleeper:
      def sleep(millis: Long): Unit = slept += millis
    val bucket = new Flaky(2)
    val report =
      writer(bucket, sleeper, attempts = 3).write(spec, Vector(record("a")))
    assertEquals(report.written, 1)
    assertEquals(report.retries, 2)
    assertEquals(slept.toVector, Vector(100L, 200L))
    assertEquals(RetryPolicy(10, 100, 1000).delayAfter(8), 1000L)
  }

  test("retry exhaustion yields rejected-vector records and continues") {
    val bucket = new Flaky(Int.MaxValue)
    val report =
      writer(bucket, attempts = 3).write(spec, Vector(record("a"), record("b")))
    assertEquals(report.written, 0)
    assertEquals(bucket.calls, 3)
    assertEquals(
      report.rejected.map(_.code).toSet,
      Set(ErrorCode.RetryExhausted)
    )
    assertEquals(report.rejected.size, 2)
  }

  test("rejected-vector records persist as JSON lines, idempotently") {
    val store = new InMemoryObjectStore
    val rejected = Vector(
      RejectedVector(spec.name, "b", ErrorCode.MetadataInvalid, "why"),
      RejectedVector(spec.name, "a", ErrorCode.VectorKeyInvalid, "why")
    )
    val key = RejectedVectors.persist(store, runId, spec.name, rejected).get
    val again = RejectedVectors.persist(store, runId, spec.name, rejected).get
    assertEquals(key, again)
    val lines = new String(store.get(key).get, "UTF-8").linesIterator.toVector
    assertEquals(lines.map(l => ujson.read(l)("key").str), Vector("a", "b"))
    assertEquals(
      RejectedVectors.persist(store, runId, spec.name, Vector.empty),
      None
    )
  }

  test("index spec dimension is bounded by S3 Vectors") {
    assertEquals(
      code(VectorIndexSpec("x", 0, "cosine", None)),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(VectorIndexSpec("x", 4097, "cosine", None)),
      ErrorCode.EmbeddingInvalid
    )
  }
