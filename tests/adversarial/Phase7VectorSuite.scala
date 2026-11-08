package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scala.collection.mutable
import scholarchain.core.Provenance
import scholarchain.storage.*
import Phase7Storage.*

/** Independent adversarial checks of the S3 Vectors metadata contract, key and
  * dimension rules, batching, retries, rejected-vector records and the
  * semantic-summary mapping.
  */
class Phase7VectorSuite extends FunSuite:
  private val limit = 2048

  private def base: Map[String, MetaValue] = Map(
    "summaryType" -> MetaValue.Str("work"),
    "subjectId" -> MetaValue.Str(w1),
    "textRef" -> MetaValue.Str("semantic-context/x#summary:v1:abc"),
    "runId" -> MetaValue.Str(runId)
  )

  private def rec(
      key: String,
      md: Map[String, MetaValue] = base,
      dim: Int = 3,
      fill: Float = 0.5f
  ) = VectorRecord(key, Vector.fill(dim)(fill), md)

  private def key(i: Int) = f"summary:v1:$i%064x"

  private def sizeOf(md: Map[String, MetaValue]): Int =
    md.map { case (k, v) =>
      k.getBytes(UTF_8).length + ujson.write(v.json).getBytes(UTF_8).length
    }.sum

  /** Pads two reference lists so the metadata is exactly `target` bytes. */
  private def padded(
      target: Int,
      wide: Boolean = false
  ): Map[String, MetaValue] =
    val slots = mutable.ArrayBuffer.fill(10)("a")
    def build =
      base ++ Map(
        "evidenceWorkIds" -> MetaValue.Strs(slots.take(5).toVector),
        "evidenceNodeIds" -> MetaValue.Strs(slots.drop(5).toVector)
      )
    var need = target - sizeOf(build)
    require(need >= 0, "base is already too big")
    while need > 0 do
      val i = slots.indexWhere(_.length < 200)
      require(i >= 0, "no room left in the slots")
      if wide && need >= 2 then
        slots(i) = slots(i) + "é"
        need -= 2
      else
        slots(i) = slots(i) + "a"
        need -= 1
    val md = build
    assertEquals(sizeOf(md), target)
    md

  private def bucketWith(dim: Int = 3, name: String = "idx-test") =
    val bucket = new InMemoryVectorBucket
    val spec = VectorIndexSpec(name, dim, "cosine", None)
    (bucket, spec)

  private def writer(bucket: VectorBucketPort, attempts: Int = 3) =
    new VectorWriter(bucket, RetryPolicy(attempts, 10, 100), NoSleep)

  // 4. Metadata boundaries ---------------------------------------------------

  test("filterable metadata of exactly 2 KB is accepted, one byte more is not"):
    VectorMetadata.validate(padded(limit))
    assertEquals(
      code(VectorMetadata.validate(padded(limit + 1))),
      ErrorCode.MetadataInvalid
    )
    VectorMetadata.validate(padded(limit - 1))

  test("the 2 KB limit is bytes: multibyte text is counted by its UTF-8 size"):
    VectorMetadata.validate(padded(limit, wide = true))
    assertEquals(
      code(VectorMetadata.validate(padded(limit + 1, wide = true))),
      ErrorCode.MetadataInvalid
    )
    // Fewer than 2048 characters in total, more than 2048 bytes.
    val md = padded(limit + 200, wide = true)
    val chars = md.map { case (k, v) =>
      k.length + ujson.write(v.json).length
    }.sum
    assert(chars < limit, s"chars=$chars")
    assertEquals(code(VectorMetadata.validate(md)), ErrorCode.MetadataInvalid)

  test("the writer applies the same 2 KB rule per record"):
    val (bucket, spec) = bucketWith()
    val report = writer(bucket).write(
      spec,
      Vector(rec(key(1), padded(limit)), rec(key(2), padded(limit + 1)))
    )
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector((key(2), ErrorCode.MetadataInvalid))
    )
    assertEquals(bucket.count(spec.name), 1)

  test("more than 50 metadata keys is refused; so are unknown keys"):
    val fifty =
      (1 to 50).map(i => s"k$i" -> (MetaValue.Flag(true): MetaValue)).toMap
    val tooMany = base ++ fifty
    assertEquals(tooMany.size, 54)
    assertEquals(
      code(VectorMetadata.validate(tooMany)),
      ErrorCode.MetadataInvalid
    )
    val exactly50 =
      base ++ (1 to 46).map(i => s"k$i" -> (MetaValue.Flag(true): MetaValue))
    assertEquals(exactly50.size, 50)
    // Fifty keys is allowed by AWS, but only declared keys are accepted here.
    assertEquals(
      code(VectorMetadata.validate(exactly50)),
      ErrorCode.MetadataInvalid
    )
    assertEquals(
      code(
        VectorMetadata.validate(base + ("surprise" -> MetaValue.Flag(true)))
      ),
      ErrorCode.MetadataInvalid
    )

  test("required keys are all needed, one at a time"):
    VectorMetadata.Required.foreach { k =>
      assertEquals(
        code(VectorMetadata.validate(base - k)),
        ErrorCode.MetadataInvalid,
        k
      )
    }
    assertEquals(
      code(VectorMetadata.validate(Map.empty)),
      ErrorCode.MetadataInvalid
    )

  test("long text cannot be smuggled through any value shape"):
    def bad(name: String, v: MetaValue) =
      assertEquals(
        code(VectorMetadata.validate(base + (name -> v))),
        ErrorCode.MetadataInvalid,
        s"$name $v"
      )
    val longText = "word " * 60
    bad("window", MetaValue.Str("x" * 201))
    bad("nodeKind", MetaValue.Str(longText.trim))
    bad("window", MetaValue.Str("1" * 5000)) // numeric-looking
    bad("window", MetaValue.Str("1234567890." * 40))
    bad("evidenceNodeIds", MetaValue.Strs(Vector("x" * 201)))
    bad("evidenceNodeIds", MetaValue.Strs(Vector("a", "b", "c", "d", "e", "f")))
    bad("evidenceWorkIds", MetaValue.Strs(Vector("fine", longText)))
    bad("nodeKind", MetaValue.Str("line one\nline two"))
    bad("nodeKind", MetaValue.Str((1 to 13).map(i => s"w$i").mkString(" ")))
    bad("window", MetaValue.Num(Double.NaN))
    bad("window", MetaValue.Num(Double.PositiveInfinity))
    // The reserved text keys and look-alikes.
    VectorMetadata.TextKeys.foreach(k => bad(k, MetaValue.Str("short")))
    Vector("Text", "TEXT", "text ", "summary_text", "summaryText2", "abstract​")
      .foreach(k => bad(k, MetaValue.Str("short")))
    // textRef is itself bounded, so the reference cannot carry the text.
    assertEquals(
      code(
        VectorMetadata.validate(
          base + ("textRef" -> MetaValue.Str("s3://" + "x" * 300))
        )
      ),
      ErrorCode.MetadataInvalid
    )

  test("a reference of exactly 200 characters is accepted, 201 is not"):
    VectorMetadata.validate(base + ("textRef" -> MetaValue.Str("r" * 200)))
    assertEquals(
      code(
        VectorMetadata.validate(base + ("textRef" -> MetaValue.Str("r" * 201)))
      ),
      ErrorCode.MetadataInvalid
    )
    // 200 multibyte characters stay within the character cap.
    VectorMetadata.validate(base + ("textRef" -> MetaValue.Str("é" * 200)))

  test("a null character in any metadata string is refused"):
    assertEquals(
      code(
        VectorMetadata.validate(base + ("window" -> MetaValue.Str("a\u0000b")))
      ),
      ErrorCode.NullCharacter
    )
    assertEquals(
      code(
        VectorMetadata.validate(
          base + ("evidenceWorkIds" -> MetaValue.Strs(Vector("a\u0000")))
        )
      ),
      ErrorCode.NullCharacter
    )

  // Keys ---------------------------------------------------------------------

  test(
    "vector keys: 1,024 characters pass, 1,025 and malformed forms are refused"
  ):
    VectorKeys.validate("k" * 1024)
    VectorKeys.validate("é" * 1024) // 1,024 characters, 2,048 bytes
    Vector(
      "",
      " ",
      " k",
      "k ",
      "k" * 1025,
      "a\nb",
      "a\u0000b",
      "a\tb",
      "é" * 1025
    ).foreach { bad =>
      assertEquals(
        code(VectorKeys.validate(bad)),
        ErrorCode.VectorKeyInvalid,
        bad.take(20)
      )
    }
    assertEquals(VectorKeys.stable(key(7)), key(7))

  test("writer rejects bad keys per record and writes the rest"):
    val (bucket, spec) = bucketWith()
    val keys = Vector("k" * 1024, "k" * 1025, "", "ok", " lead")
    val report = writer(bucket).write(spec, keys.map(rec(_)))
    assertEquals(report.written, 2)
    assertEquals(
      report.rejected.map(_.code).distinct,
      Vector(ErrorCode.VectorKeyInvalid)
    )
    assertEquals(report.rejected.size, 3)

  // Dimensions and embeddings -----------------------------------------------

  test("index dimensions run 1 to 4096"):
    Vector(1, 4096).foreach(d => VectorIndexSpec("i", d, "cosine", None))
    Vector(0, -1, 4097).foreach(d =>
      assertEquals(
        code(VectorIndexSpec("i", d, "cosine", None)),
        ErrorCode.EmbeddingInvalid
      )
    )
    val (bucket, spec) = bucketWith(dim = 4096)
    assertEquals(
      writer(bucket).write(spec, Vector(rec(key(1), dim = 4096))).written,
      1
    )

  test("wrong dimension and non-finite floats are rejected, not written"):
    val (bucket, spec) = bucketWith()
    val nan = VectorRecord(key(2), Vector(0.1f, Float.NaN, 0.3f), base)
    val inf =
      VectorRecord(key(3), Vector(0.1f, Float.PositiveInfinity, 0.3f), base)
    val report = writer(bucket).write(
      spec,
      Vector(rec(key(1), dim = 2), nan, inf, rec(key(4)))
    )
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(_.code).distinct,
      Vector(ErrorCode.EmbeddingInvalid)
    )
    assertEquals(report.rejected.size, 3)

  // Batches ------------------------------------------------------------------

  test("500 vectors go in one batch, 501 in two, 1,001 in three"):
    Vector((500, 1), (501, 2), (1000, 2), (1001, 3)).foreach {
      case (n, batches) =>
        val (bucket, spec) = bucketWith()
        val report =
          writer(bucket).write(spec, (1 to n).toVector.map(i => rec(key(i))))
        assertEquals((n, report.batches, report.written), (n, batches, n))
        assertEquals(bucket.count(spec.name), n)
    }

  test(
    "batches never exceed the bucket limit even if the port is probed directly"
  ):
    val (bucket, spec) = bucketWith()
    bucket.ensureIndex(spec)
    assertEquals(
      code(
        bucket.putVectors(spec.name, (1 to 501).toVector.map(i => rec(key(i))))
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(bucket.count(spec.name), 0)
    bucket.putVectors(spec.name, (1 to 500).toVector.map(i => rec(key(i))))
    assertEquals(bucket.count(spec.name), 500)

  test(
    "one bad record in a batch writes nothing from that batch (all or nothing)"
  ):
    val (bucket, spec) = bucketWith()
    bucket.ensureIndex(spec)
    val batch = Vector(rec(key(1)), rec(key(2)), rec(key(3), dim = 7))
    code(bucket.putVectors(spec.name, batch))
    assertEquals(bucket.count(spec.name), 0)
    val noText =
      Vector(rec(key(4)), rec(key(5), base + ("text" -> MetaValue.Str("x"))))
    code(bucket.putVectors(spec.name, noText))
    assertEquals(bucket.count(spec.name), 0)

  test("writer batch size must be 1 to 500; get is capped at 100 keys"):
    val (bucket, spec) = bucketWith()
    Vector(0, -5, 501).foreach { size =>
      intercept[IllegalArgumentException](
        new VectorWriter(bucket, RetryPolicy(1, 1, 1), NoSleep, size)
      )
    }
    bucket.ensureIndex(spec)
    assertEquals(
      code(bucket.getVectors(spec.name, (1 to 101).toVector.map(key))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      bucket.getVectors(spec.name, (1 to 100).toVector.map(key)),
      Vector.empty
    )

  test("an unknown index is refused, not created implicitly"):
    val bucket = new InMemoryVectorBucket
    assertEquals(
      code(bucket.putVectors("ghost", Vector(rec(key(1))))),
      ErrorCode.InvalidKey
    )
    assertEquals(bucket.count("ghost"), 0)

  // Duplicates and idempotent reload -----------------------------------------

  test("identical duplicates collapse; conflicting duplicates keep the first"):
    val (bucket, spec) = bucketWith()
    val a = rec(key(1))
    val b = rec(key(1), fill = 0.9f)
    val report = writer(bucket).write(spec, Vector(a, a, b, rec(key(2))))
    assertEquals(report.duplicatesCollapsed, 1)
    assertEquals(report.written, 2)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector((key(1), ErrorCode.DuplicateVectorKey))
    )
    assertEquals(
      bucket.getVectors(spec.name, Vector(key(1))).head.embedding,
      a.embedding
    )

  test("reload and interleaved reload leave the same state"):
    val bucket = new InMemoryVectorBucket
    val specA = VectorIndexSpec("idx-a", 3, "cosine", None)
    val specB = VectorIndexSpec("idx-b", 3, "cosine", None)
    val recs = (1 to 40).toVector.map(i => rec(key(i)))
    val w = writer(bucket)
    w.write(specA, recs)
    w.write(specB, recs.take(10))
    w.write(specA, recs)
    w.write(specB, recs.take(10))
    w.write(specA, recs.take(5))
    assertEquals(bucket.count("idx-a"), 40)
    assertEquals(bucket.count("idx-b"), 10)
    assertEquals(
      bucket.getVectors("idx-a", recs.take(3).map(_.key)),
      recs.take(3)
    )

  test("an index cannot be redefined with another dimension or metric"):
    val bucket = new InMemoryVectorBucket
    bucket.ensureIndex(VectorIndexSpec("idx", 3, "cosine", None))
    bucket.ensureIndex(VectorIndexSpec("idx", 3, "cosine", None))
    assertEquals(
      code(bucket.ensureIndex(VectorIndexSpec("idx", 4, "cosine", None))),
      ErrorCode.MetadataInvalid
    )
    assertEquals(
      code(bucket.ensureIndex(VectorIndexSpec("idx", 3, "euclidean", None))),
      ErrorCode.MetadataInvalid
    )

  test("a changed embedding under an existing key is refused, not overwritten"):
    val (bucket, spec) = bucketWith()
    val w = writer(bucket)
    val original = rec(key(1))
    w.write(spec, Vector(original))
    val outcome =
      try Right(w.write(spec, Vector(rec(key(1), fill = 0.9f))))
      catch case e: StorageException => Left(e.code)
    val stored = bucket.getVectors(spec.name, Vector(key(1))).head
    assertEquals(
      stored.embedding,
      original.embedding,
      s"overwritten; outcome=$outcome"
    )
    outcome match
      case Right(report) =>
        assertEquals(report.rejected.map(_.key), Vector(key(1)))
      case Left(_) => ()

  test("changed metadata under an existing key is refused, not overwritten"):
    val (bucket, spec) = bucketWith()
    val w = writer(bucket)
    w.write(spec, Vector(rec(key(1))))
    val outcome =
      try
        Right(
          w.write(
            spec,
            Vector(rec(key(1), base + ("window" -> MetaValue.Str("2020-2021"))))
          )
        )
      catch case e: StorageException => Left(e.code)
    val stored = bucket.getVectors(spec.name, Vector(key(1))).head
    assertEquals(stored.metadata, base, s"overwritten; outcome=$outcome")

  // Retries ------------------------------------------------------------------

  private final class Flaky(failOn: Int => Boolean)
      extends InMemoryVectorBucket:
    var calls = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      calls += 1
      if failOn(calls) then throw new TransientStorageException("throttled")
      super.putVectors(index, batch)

  private final class Recording extends Sleeper:
    val delays = mutable.ArrayBuffer.empty[Long]
    def sleep(millis: Long): Unit = delays += millis

  test("a transient failure is retried with capped exponential backoff"):
    val flaky = new Flaky(_ <= 2)
    val sleeper = new Recording
    val w = new VectorWriter(flaky, RetryPolicy(4, 10, 15), sleeper)
    val spec = VectorIndexSpec("idx", 3, "cosine", None)
    val report = w.write(spec, Vector(rec(key(1))))
    assertEquals(
      (report.written, report.retries, report.rejected),
      (1, 2, Vector.empty)
    )
    assertEquals(sleeper.delays.toVector, Vector(10L, 15L))
    assertEquals(flaky.count("idx"), 1)

  test("retries are bounded; exhausted records are reported, never dropped"):
    val flaky = new Flaky(_ => true)
    val sleeper = new Recording
    val w = new VectorWriter(flaky, RetryPolicy(3, 10, 100), sleeper)
    val spec = VectorIndexSpec("idx", 3, "cosine", None)
    val report = w.write(spec, Vector(rec(key(1)), rec(key(2))))
    assertEquals(flaky.calls, 3)
    assertEquals(report.written, 0)
    assertEquals(
      report.rejected.map(_.code).distinct,
      Vector(ErrorCode.RetryExhausted)
    )
    assertEquals(report.rejected.map(_.key).toSet, Set(key(1), key(2)))
    assertEquals(flaky.count("idx"), 0)
    assertEquals(sleeper.delays.size, 2)

  test("a failed batch does not stop the other batches"):
    val flaky = new Flaky(c => c == 2 || c == 3 || c == 4)
    val w = new VectorWriter(flaky, RetryPolicy(3, 1, 1), NoSleep)
    val spec = VectorIndexSpec("idx", 3, "cosine", None)
    val report = w.write(spec, (1 to 1001).toVector.map(i => rec(key(i))))
    assertEquals(report.batches, 3)
    assertEquals(report.written, 501)
    assertEquals(report.rejected.size, 500)
    assertEquals(report.written + report.rejected.size, 1001)

  test("backoff stays non-negative and capped for any attempt number"):
    val p = RetryPolicy(100, 1L << 50, 1L << 55)
    (1 to 100).foreach { a =>
      val d = p.delayAfter(a)
      assert(d >= 0 && d <= p.maxDelayMillis, s"attempt $a gave $d")
    }
    intercept[IllegalArgumentException](RetryPolicy(0, 1, 1))

  // Rejected-vector records --------------------------------------------------

  test("rejected vectors persist once, deterministically, under logs/"):
    val store = new InMemoryObjectStore
    assertEquals(
      RejectedVectors.persist(store, runId, "idx", Vector.empty),
      None
    )
    val rejects = Vector(
      RejectedVector("idx", key(2), ErrorCode.MetadataInvalid, "two"),
      RejectedVector(
        "idx",
        key(1),
        ErrorCode.VectorKeyInvalid,
        "one, \"quoted\"\nnewline"
      )
    )
    val k = RejectedVectors.persist(store, runId, "idx", rejects).get
    assert(k.startsWith("logs/"), k)
    val lines = new String(store.get(k).get, UTF_8).split("\n").toVector
    assertEquals(lines.size, 2)
    assertEquals(
      lines.map(l => ujson.read(l)("key").str),
      Vector(key(1), key(2))
    )
    assertEquals(
      ujson.read(lines.head)("detail").str,
      "one, \"quoted\"\nnewline"
    )
    assertEquals(
      RejectedVectors.persist(store, runId, "idx", rejects.reverse),
      Some(k)
    )
    // An identical repeat is a no-op: no new object, same bytes.
    val objectsAfterFirst = store.list("").map(m => (m.key, m.sha256))
    val bytesAfterFirst = store.get(k).get.toVector
    RejectedVectors.persist(store, runId, "idx", rejects)
    assertEquals(store.list("").map(m => (m.key, m.sha256)), objectsAfterFirst)
    assertEquals(store.get(k).get.toVector, bytesAfterFirst)
    // A different list for the same run and index gets its own key, never an
    // ImmutableKeyConflict, and leaves the original object untouched.
    val other =
      RejectedVectors.persist(store, runId, "idx", rejects.take(1)).get
    assertNotEquals(other, k)
    assert(other.startsWith("logs/"), other)
    assertEquals(store.get(k).get.toVector, bytesAfterFirst)
    assertEquals(store.list("").size, objectsAfterFirst.size + 1)
    val otherLines =
      new String(store.get(other).get, UTF_8).split("\n").toVector
    assertEquals(otherLines.map(l => ujson.read(l)("key").str), Vector(key(2)))
    // The different list repeated is itself a no-op.
    assertEquals(
      RejectedVectors.persist(store, runId, "idx", rejects.take(1)),
      Some(other)
    )
    assertEquals(store.list("").size, objectsAfterFirst.size + 1)
    // Another index under the same run does not share the key.
    val third = RejectedVectors.persist(store, runId, "idx2", rejects).get
    assert(third != k && third != other)

  // Semantic summaries -------------------------------------------------------

  private object Model extends Embedder:
    val modelVersion = "test-embed-1"
    val dimension = 3
    def embed(text: String): Vector[Float] =
      Vector(text.length.toFloat % 7, 1f, 2f)

  private def summaryRow(
      id: String,
      kind: String = "work",
      text: String = "A long summary. " * 500,
      workIds: Seq[String] = Seq(w1, w2, w3, m1, m2, r1, r2),
      p: Provenance = provenance()
  ): Row =
    Map(
      "summaryId" -> s(id),
      "summaryType" -> s(kind),
      "subjectId" -> s(w1),
      "summaryText" -> s(text),
      "evidenceWorkIds" -> arr(workIds*),
      "evidenceNodeIds" -> arr(r1, r2),
      "window" -> s(windowLabel),
      "nodeKind" -> s("RESEARCHER"),
      "windowStart" -> n(2019),
      "windowEnd" -> n(2023),
      "provenance" -> provJson(p)
    )

  private val specs = VectorIndexes.all(3, "cosine")

  test("summary text is embedded and never copied into metadata"):
    val text = "SECRET-PROSE " + ("lorem ipsum " * 2000)
    val r = SemanticVectorLoader.record(
      summaryRow(key(1), text = text),
      Model,
      "semantic-context/summaries.jsonl"
    )
    val dumped = r.metadata.values.map(v => ujson.write(v.json)).mkString
    assert(!dumped.contains("SECRET-PROSE"))
    assert(!dumped.contains("lorem"))
    assert(sizeOf(r.metadata) <= limit)
    VectorMetadata.validate(r.metadata)
    assertEquals(r.key, key(1))
    assertEquals(r.metadata("evidenceWorkCount"), MetaValue.Num(7.0))
    val listed = r.metadata("evidenceWorkIds") match
      case MetaValue.Strs(v) => v.size
      case _                 => -1
    assertEquals(listed, 5)
    assertEquals(r.metadata("runId"), MetaValue.Str(runId))
    assertEquals(r.metadata("embeddingModel"), MetaValue.Str("test-embed-1"))
    val ref = r.metadata("textRef") match
      case MetaValue.Str(v) => v
      case _                => ""
    assert(ref.endsWith(key(1)), ref)

  test("summary load: provenance runId follows the row and reload is stable"):
    val bucket = new InMemoryVectorBucket
    val w = writer(bucket)
    val rows = (1 to 6).toVector.map(i =>
      summaryRow(key(i), p = provenance(run = otherRun))
    )
    val first = SemanticVectorLoader.load(rows, specs, Model, "ref", w, Bounds)
    assertEquals(first.perIndex.map(_.written).sum, 6)
    assertEquals(first.unmapped, Vector.empty)
    val stored = bucket.getVectors(
      "paper-abstract-v1",
      rows.take(3).map(r => r("summaryId").str)
    )
    stored.foreach(v =>
      assertEquals(v.metadata("runId"), MetaValue.Str(otherRun))
    )
    SemanticVectorLoader.load(rows, specs, Model, "ref", w, Bounds)
    SemanticVectorLoader.load(rows.reverse, specs, Model, "ref", w, Bounds)
    assertEquals(bucket.count("paper-abstract-v1"), 6)

  test("vector metadata keeps storage and Spark transform versions apart"):
    val p = provenance(transform = "scholarchain.spark/7")
    val r = SemanticVectorLoader.record(
      summaryRow(key(1), p = p),
      Model,
      "semantic-context/summaries.jsonl"
    )
    assertEquals(
      r.metadata("storageTransformVersion"),
      MetaValue.Str(StorageProvenance.TransformVersion)
    )
    assertEquals(
      r.metadata("sparkTransformVersion"),
      MetaValue.Str("scholarchain.spark/7")
    )
    assertNotEquals(
      r.metadata("storageTransformVersion"),
      r.metadata("sparkTransformVersion")
    )
    assertEquals(r.metadata("runId"), MetaValue.Str(runId))
    // still compact, valid and within the key count
    VectorMetadata.validate(r.metadata)
    assert(sizeOf(r.metadata) <= limit, sizeOf(r.metadata).toString)
    assert(r.metadata.size <= 50)
    // and no long text came along with the provenance
    val dumped = r.metadata.values.map(v => ujson.write(v.json)).mkString
    assert(!dumped.contains("A long summary"))

  test("provenance versions are accepted metadata keys, not text keys"):
    Vector("storageTransformVersion", "sparkTransformVersion").foreach { k =>
      assert(VectorMetadata.Allowed.contains(k), k)
      assert(!VectorMetadata.TextKeys.contains(k), k)
      VectorMetadata.validate(base + (k -> MetaValue.Str("storage/1.0.0")))
      assertEquals(
        code(VectorMetadata.validate(base + (k -> MetaValue.Str("x" * 201)))),
        ErrorCode.MetadataInvalid
      )
    }

  test("a loaded vector keeps both versions through the writer"):
    val bucket = new InMemoryVectorBucket
    val rows = Vector(summaryRow(key(1), p = provenance(transform = "tx/4")))
    SemanticVectorLoader.load(rows, specs, Model, "ref", writer(bucket), Bounds)
    val stored = bucket.getVectors("paper-abstract-v1", Vector(key(1))).head
    assertEquals(
      stored.metadata("sparkTransformVersion"),
      MetaValue.Str("tx/4")
    )
    assertEquals(
      stored.metadata("storageTransformVersion"),
      MetaValue.Str(StorageProvenance.TransformVersion)
    )

  test("an unmapped summary type is reported; all five indexes exist"):
    val bucket = new InMemoryVectorBucket
    val rows = Vector(
      summaryRow(key(1)),
      summaryRow(key(2), kind = "mystery"),
      summaryRow(key(3), kind = "topic-institution")
    )
    val report = SemanticVectorLoader.load(
      rows,
      specs,
      Model,
      "ref",
      writer(bucket),
      Bounds
    )
    assertEquals(
      report.unmapped.map(u => (u.key, u.code)).toSet,
      Set(
        (key(2), ErrorCode.UnmappedSummary),
        (key(3), ErrorCode.UnmappedSummary)
      )
    )
    assertEquals(
      specs.map(_.name).toSet,
      Set(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals(report.perIndex.size, 5)
    assertEquals(report.perIndex.map(_.written).sum, 1)

  test(
    "an oversized subject or key is rejected or refused, never silently dropped"
  ):
    val bucket = new InMemoryVectorBucket
    val longSubject = summaryRow(key(1)).updated("subjectId", s("s" * 500))
    val outcome =
      try
        Right(
          SemanticVectorLoader.load(
            Vector(longSubject),
            specs,
            Model,
            "ref",
            writer(bucket),
            Bounds
          )
        )
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(r) =>
        assertEquals(r.perIndex.map(_.written).sum, 0)
        assertEquals(r.perIndex.flatMap(_.rejected).size, 1)
      case Left(c) => assertEquals(c, ErrorCode.MetadataInvalid)
    val hugeKey = summaryRow("k" * 1100)
    val outcome2 =
      try
        Right(
          SemanticVectorLoader.load(
            Vector(hugeKey),
            specs,
            Model,
            "ref",
            writer(bucket),
            Bounds
          )
        )
      catch case e: StorageException => Left(e.code)
    outcome2 match
      case Right(r) =>
        assertEquals(r.perIndex.map(_.written).sum, 0)
        assertEquals(r.perIndex.flatMap(_.rejected).size + r.unmapped.size, 1)
      case Left(c) => assertEquals(c, ErrorCode.VectorKeyInvalid)
    assertEquals(specs.map(i => bucket.count(i.name)).sum, 0)

  test("rows over the bound write nothing, not even an index"):
    val bucket = new InMemoryVectorBucket
    val rows = (1 to 5).toVector.map(i => summaryRow(key(i)))
    val tight = LoadBounds(maxRows = 4, maxFiles = 10, maxBytes = 1000)
    assertEquals(
      code(
        SemanticVectorLoader
          .load(rows, specs, Model, "ref", writer(bucket), tight)
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(specs.map(i => bucket.count(i.name)).sum, 0)

  test("a malformed summary row fails as MalformedRow"):
    val bucket = new InMemoryVectorBucket
    val noId = summaryRow(key(1)) - "summaryId"
    assertEquals(
      code(
        SemanticVectorLoader
          .load(Vector(noId), specs, Model, "ref", writer(bucket), Bounds)
      ),
      ErrorCode.MalformedRow
    )
    val noText = summaryRow(key(1)) - "summaryText"
    assertEquals(
      code(
        SemanticVectorLoader
          .load(Vector(noText), specs, Model, "ref", writer(bucket), Bounds)
      ),
      ErrorCode.MalformedRow
    )
