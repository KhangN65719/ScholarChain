package scholarchain.storage

import scala.collection.mutable

/** Work order 85 item 1: the `progress` callback of `EmbeddingIngest`. The
  * clock moves one second per emitted line, so every `elapsed_s` is exact.
  */
class EmbeddingIngestProgressSuite extends munit.FunSuite:
  import Fixtures.*

  private val source = SourceRun(runId, "spark-analytics/1.1.0")
  private val profile = VectorIndexes.ResearcherProfile
  private val community = VectorIndexes.CollaborationCommunity

  private def profiles(n: Int): Vector[Row] =
    (1 to n).toVector.map(i =>
      summaryRow(
        "researcher-profile",
        f"p$i%03d",
        s"researcher $i studies topic number $i"
      )
    )

  private val communityRow =
    summaryRow(
      "community",
      "c1",
      "A community of researchers on graph networks"
    )

  private def tablesOf(rows: Vector[Row]): TableSource =
    new InMemoryTableSource(
      Map(
        "semantic-summaries" -> rows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  /** Fails the chosen PutVectors calls (1-based). */
  private final class FlakyBucket(var failOn: Set[Int] = Set.empty)
      extends InMemoryVectorBucket:
    var calls = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      calls += 1
      if failOn.contains(calls) then throw new RuntimeException("boom")
      super.putVectors(index, batch)

  private final class Rig(
      val bucket: FlakyBucket = new FlakyBucket(),
      progress: Option[String => Unit] = None,
      leaveProgressDefault: Boolean = false
  ):
    val lines = mutable.ArrayBuffer.empty[String]
    var now = 0L
    val store = new InMemoryObjectStore
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    private val record: String => Unit = line =>
      lines += line
      now += 1000000000L
    val ingest =
      if leaveProgressDefault then
        new EmbeddingIngest(
          new HashEmbedder,
          PassThroughRenderer,
          bucket,
          store,
          table,
          NoSleep,
          () => now
        )
      else
        new EmbeddingIngest(
          new HashEmbedder,
          PassThroughRenderer,
          bucket,
          store,
          table,
          NoSleep,
          () => now,
          progress.getOrElse(record)
        )

    def run(
        rows: Vector[Row],
        label: String = "1",
        batchSize: Int = 2
    ): IngestReport =
      ingest.run(
        tablesOf(rows),
        source,
        IngestConfig(
          "2026-11-08T00:00:00Z",
          batchSize,
          RetryPolicy(3, 100, 1000),
          executionLabel = label
        )
      )

    def restartClock(): Unit =
      lines.clear()
      now = 0L

  test("progress: one start line, then one line per batch, exact counts") {
    val rig = new Rig()
    val report = rig.run(profiles(5) :+ communityRow)
    assertEquals(report.written, 6)
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=6 batches=4",
        s"LOADER_PROGRESS index=$profile batch=1/3 items_done=2/6 written=2 elapsed_s=1 skipped=0 failed_batches=0",
        s"LOADER_PROGRESS index=$profile batch=2/3 items_done=4/6 written=4 elapsed_s=2 skipped=0 failed_batches=0",
        s"LOADER_PROGRESS index=$profile batch=3/3 items_done=5/6 written=5 elapsed_s=3 skipped=0 failed_batches=0",
        s"LOADER_PROGRESS index=$community batch=1/1 items_done=6/6 written=6 elapsed_s=4 skipped=0 failed_batches=0"
      )
    )
  }

  test("progress: a failed batch and then a resumed one are both reported") {
    val rig = new Rig(new FlakyBucket(Set(2)))
    val first = rig.run(profiles(5))
    assertEquals(first.failedBatches.map(_.ordinal), Vector(1))
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=5 batches=3",
        s"LOADER_PROGRESS index=$profile batch=1/3 items_done=2/5 written=2 elapsed_s=1 skipped=0 failed_batches=0",
        // The failed batch says why first, then counts its items as done, adds
        // nothing written and is the only progress line that raises
        // failed_batches. The test clock ticks once per emitted line, so the
        // reason line moves every later elapsed_s by one second.
        s"LOADER_BATCH_FAILED index=$profile batch=2 reason=RuntimeException: boom",
        s"LOADER_PROGRESS index=$profile batch=2/3 items_done=4/5 written=2 elapsed_s=3 skipped=0 failed_batches=1",
        s"LOADER_PROGRESS index=$profile batch=3/3 items_done=5/5 written=3 elapsed_s=4 skipped=0 failed_batches=1"
      )
    )

    rig.bucket.failOn = Set.empty
    rig.restartClock()
    val again = rig.run(profiles(5))
    assertEquals(again.perIndex.head.skippedResumed, 3)
    assertEquals(again.written, 2)
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=5 batches=3",
        // Resumed: finished earlier, so nothing is written now and the items
        // count as skipped. The retried batch writes and skips nothing.
        s"LOADER_PROGRESS index=$profile batch=1/3 items_done=2/5 written=0 elapsed_s=1 skipped=2 failed_batches=0",
        s"LOADER_PROGRESS index=$profile batch=2/3 items_done=4/5 written=2 elapsed_s=2 skipped=2 failed_batches=0",
        s"LOADER_PROGRESS index=$profile batch=3/3 items_done=5/5 written=2 elapsed_s=3 skipped=3 failed_batches=0"
      )
    )
  }

  test("progress: an empty export reports only the start line") {
    val rig = new Rig()
    rig.run(Vector.empty)
    assertEquals(
      rig.lines.toVector,
      Vector("LOADER_PROGRESS start items=0 batches=0")
    )
  }

  test("progress: a long batch reports every 100 embeds, then its batch line") {
    val rig = new Rig()
    val report = rig.run(profiles(250), batchSize = 250)
    assertEquals(report.written, 250)
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=250 batches=1",
        s"LOADER_PROGRESS index=$profile batch_items=100/250",
        s"LOADER_PROGRESS index=$profile batch_items=200/250",
        s"LOADER_PROGRESS index=$profile batch=1/1 items_done=250/250 written=250 elapsed_s=3 skipped=0 failed_batches=0"
      )
    )
    // Nothing is embedded on a rerun, so a rerun has no in-batch lines.
    rig.restartClock()
    rig.run(profiles(250), label = "2", batchSize = 250)
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=250 batches=1",
        s"LOADER_PROGRESS index=$profile batch=1/1 items_done=250/250 written=0 elapsed_s=1 skipped=250 failed_batches=0"
      )
    )
  }

  test("progress: a half-skipped batch reaches its batch_items denominator") {
    val rig = new Rig()
    rig.run(profiles(150), batchSize = 150)
    rig.restartClock()
    // p001 to p150 hold a vector already, so only p151 to p300 are embedded.
    val report = rig.run(profiles(300), label = "2", batchSize = 300)
    assertEquals(report.written, 150)
    assertEquals(report.perIndex.head.skippedUnchanged, 150)
    assertEquals(
      rig.lines.toVector,
      Vector(
        "LOADER_PROGRESS start items=300 batches=1",
        s"LOADER_PROGRESS index=$profile batch_items=100/150",
        s"LOADER_PROGRESS index=$profile batch=1/1 items_done=300/300 written=150 elapsed_s=2 skipped=150 failed_batches=0"
      )
    )
  }

  test("progress: a skipped batch and a failed batch no longer look alike") {
    val rig = new Rig(new FlakyBucket(Set(1)))
    rig.run(profiles(2))
    val failedLine = rig.lines.last
    assert(failedLine.endsWith("skipped=0 failed_batches=1"), failedLine)
    rig.bucket.failOn = Set.empty
    rig.restartClock()
    rig.run(profiles(2), label = "2")
    val rerunLine = rig.lines.last
    assert(rerunLine.endsWith("skipped=0 failed_batches=0"), rerunLine)
    rig.restartClock()
    rig.run(profiles(2), label = "2")
    val skippedLine = rig.lines.last
    assert(skippedLine.endsWith("skipped=2 failed_batches=0"), skippedLine)
  }

  test("progress: the in-batch counter restarts for each batch") {
    val rig = new Rig()
    rig.run(profiles(300), batchSize = 150)
    assertEquals(
      rig.lines.toVector.filter(_.contains("batch_items")),
      Vector(
        s"LOADER_PROGRESS index=$profile batch_items=100/150",
        s"LOADER_PROGRESS index=$profile batch_items=100/150"
      )
    )
  }

  test("progress lines carry counts and index names only") {
    val rig = new Rig()
    rig.run(profiles(250) :+ communityRow, batchSize = 250)
    val shape =
      raw"LOADER_PROGRESS (start items=\d+ batches=\d+|index=[a-z0-9-]+ (batch=\d+/\d+ items_done=\d+/\d+ written=\d+ elapsed_s=\d+ skipped=\d+ failed_batches=\d+|batch_items=\d+/\d+))".r
    assert(rig.lines.nonEmpty)
    rig.lines.foreach { line =>
      assert(shape.matches(line), line)
      assert(!line.contains("summary:"), line)
      assert(!line.contains("researcher "), line)
    }
  }

  /** The report, the stored objects and the ledger items of one rig's run. The
    * Rig's clock stands still unless its own recorder runs, so two runs with
    * silent callbacks see the same timings and compare whole.
    */
  private def outcome(rig: Rig, rows: Vector[Row]) =
    val report = rig.run(rows)
    (
      report,
      rig.store.list("").map(o => (o.key, o.sha256)),
      rig.table.query(s"RUN#embed:$runId:1", None, 1000)
    )

  private val mixed = profiles(5) :+ communityRow

  test("the default callback leaves the report, store and ledger alone") {
    val explicit = outcome(
      new Rig(new FlakyBucket(Set(2)), Some(_ => ())),
      mixed
    )
    val default = outcome(
      new Rig(new FlakyBucket(Set(2)), leaveProgressDefault = true),
      mixed
    )
    assertEquals(default, explicit)
    assert(explicit._1.failedBatches.nonEmpty)
    assert(explicit._2.nonEmpty)
    assert(explicit._3.nonEmpty)
  }

  test("a listening callback leaves the report, store and ledger alone") {
    val seen = mutable.ArrayBuffer.empty[String]
    val silent = outcome(new Rig(new FlakyBucket(Set(2)), Some(_ => ())), mixed)
    val listened = outcome(
      new Rig(
        new FlakyBucket(Set(2)),
        Some { line =>
          seen += line
          ()
        }
      ),
      mixed
    )
    assertEquals(listened, silent)
    // Four batch lines, the start line and the failed batch's own line.
    assertEquals(seen.size, 6)
    assertEquals(seen.count(_.startsWith("LOADER_BATCH_FAILED ")), 1)
  }

  test("a callback that throws never changes the outcome of the run") {
    val expected = outcome(new Rig(progress = Some(_ => ())), profiles(5))
    val thrown = mutable.ArrayBuffer.empty[String]
    val actual = outcome(
      new Rig(progress = Some { line =>
        thrown += line
        throw new IllegalStateException("log sink is gone")
      }),
      profiles(5)
    )
    assertEquals(actual, expected)
    assert(actual._2.nonEmpty)
    assert(actual._3.nonEmpty)
    assertEquals(thrown.size, 4)
  }

  /** A run whose only batch fails with `message`: the progress lines, the
    * report and the ledger table.
    */
  private def failingRun(message: String) =
    val lines = mutable.ArrayBuffer.empty[String]
    val bucket = new InMemoryVectorBucket:
      override def putVectors(i: String, batch: Vector[VectorRecord]): Unit =
        throw new IllegalStateException(message)
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val ingest = new EmbeddingIngest(
      new HashEmbedder,
      PassThroughRenderer,
      bucket,
      new InMemoryObjectStore,
      table,
      NoSleep,
      () => 0L,
      line =>
        lines += line
        ()
    )
    val report = ingest.run(
      tablesOf(profiles(1)),
      source,
      IngestConfig("2026-11-08T00:00:00Z", 2)
    )
    (lines.toVector, report, table)

  private def storedFailure(table: InMemoryDynamoTable): String =
    Attr
      .optS(
        table.get(s"RUN#embed:$runId:1", s"STAGE#batch#$profile#000000").get,
        "failure"
      )
      .get

  test("a failed batch emits one LOADER_BATCH_FAILED line with its reason") {
    val (lines, report, _) = failingRun("PutVectors said no")
    assertEquals(report.failedBatches.size, 1)
    assertEquals(
      lines.filter(_.startsWith("LOADER_BATCH_FAILED")),
      Vector(
        s"LOADER_BATCH_FAILED index=$profile batch=1 reason=IllegalStateException: PutVectors said no"
      )
    )
    // The line comes before the progress line of the same batch.
    assertEquals(
      lines.map(_.takeWhile(_ != ' ')),
      Vector("LOADER_PROGRESS", "LOADER_BATCH_FAILED", "LOADER_PROGRESS")
    )
  }

  test("a reason is cut to 600 characters on the line and in the ledger") {
    val message = "m" * 1000
    val full = s"IllegalStateException: $message"
    val (lines, report, table) = failingRun(message)
    val failedLine = lines.find(_.startsWith("LOADER_BATCH_FAILED")).get
    val prefix = s"LOADER_BATCH_FAILED index=$profile batch=1 reason="
    assert(failedLine.startsWith(prefix), failedLine)
    assertEquals(failedLine.drop(prefix.length), full.take(600))
    assertEquals(failedLine.drop(prefix.length).length, 600)
    assertEquals(storedFailure(table), full.take(600))
    assertEquals(EmbeddingLedger.MaxFailureChars, 600)
    // The report keeps the whole reason.
    assertEquals(report.failedBatches.head.reason, full)
  }

  test("a reason that was cut at 500 now keeps text up to 600") {
    val marker =
      "Invalid record for key 'summary:v1:x': Empty arrays are not " +
        "allowed in metadata"
    val message = ("p" * 520) + marker
    val (lines, _, table) = failingRun(message)
    assert(storedFailure(table).contains("Empty arrays"), storedFailure(table))
    assert(lines.exists(_.contains("Empty arrays")), lines.mkString("\n"))
  }

  test("a reason with line breaks and controls is one line") {
    val (lines, _, table) = failingRun("first\nsecond\r\nthird\tfourth")
    val failedLine = lines.find(_.startsWith("LOADER_BATCH_FAILED")).get
    assert(!failedLine.exists(_.isControl), failedLine)
    assert(failedLine.endsWith("first second  third fourth"), failedLine)
    assert(storedFailure(table).contains("first\nsecond"))
  }

  test(
    "a callback that throws on LOADER_BATCH_FAILED does not change the run"
  ) {
    val bucket = new FlakyBucket(Set(1))
    val rig = new Rig(bucket, Some(_ => throw new IllegalStateException("x")))
    val report = rig.run(profiles(2))
    assertEquals(report.failedBatches.size, 1)
    assertEquals(
      Attr.optS(
        rig.table.get(s"RUN#embed:$runId:1", "META").get,
        "status"
      ),
      Some("FAILED")
    )
  }

  /** An object store that counts both listings of the document prefix. */
  private final class CountingStore(inner: InMemoryObjectStore)
      extends ObjectStore:
    var lists = 0
    var sizeLists = 0
    protected def write(key: String, bytes: Array[Byte], ref: String): Unit =
      inner.putImmutable(key, bytes, ref)
      ()
    def head(key: String): Option[ObjectMeta] = inner.head(key)
    def get(key: String): Option[Array[Byte]] = inner.get(key)
    def list(prefix: String): Vector[ObjectMeta] =
      lists += 1
      inner.list(prefix)
    override def listSizes(prefix: String): Vector[(String, Long)] =
      sizeLists += 1
      inner.listSizes(prefix)

  test("the ingest sizes the document prefix with one listSizes, no list") {
    def ingestOn(store: ObjectStore) =
      new EmbeddingIngest(
        new HashEmbedder,
        PassThroughRenderer,
        new InMemoryVectorBucket,
        store,
        new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
      ).run(
        tablesOf(profiles(5) :+ communityRow),
        source,
        IngestConfig("2026-11-08T00:00:00Z")
      )
    val plain = new InMemoryObjectStore
    val counting = new CountingStore(new InMemoryObjectStore)
    val expected = ingestOn(plain)
    val actual = ingestOn(counting)
    assertEquals(counting.lists, 0)
    assertEquals(counting.sizeLists, 1)
    assertEquals(actual.documentObjects, expected.documentObjects)
    assertEquals(actual.documentBytes, expected.documentBytes)
    val documents = plain.list(SemanticDocuments.prefix)
    assertEquals(actual.documentObjects, documents.size)
    assertEquals(actual.documentBytes, documents.map(_.bytes).sum)
    assertEquals(actual.documentObjects, 6)
  }
