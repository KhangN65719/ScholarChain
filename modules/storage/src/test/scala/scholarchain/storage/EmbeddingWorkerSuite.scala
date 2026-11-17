package scholarchain.storage

import scala.collection.mutable
import scala.util.control.ControlThrowable

/** The SQS embedding worker over the local fakes: a fake queue with SQS
  * visibility, receive count and redrive, the in-memory ledger table, vector
  * bucket and object store. No AWS call.
  */
class EmbeddingWorkerSuite extends munit.FunSuite:
  private val model = HashEmbedder.ModelVersion
  private val index = VectorIndexes.PaperAbstract
  private val text = "graph neural networks for coauthorship analysis"

  /** A stand-in for a process that dies: `ControlThrowable` is not caught by
    * `NonFatal`, so it leaves the worker the way a kill would.
    */
  private final class Crash extends ControlThrowable

  /** munit's `intercept` does not catch a `ControlThrowable`, so the crash is
    * caught here.
    */
  private def crashes(thunk: => Any): Unit =
    val died =
      try
        thunk
        false
      catch case _: Crash => true
    assert(died, "expected the simulated crash")

  private def work(
      id: String = "summary:v1:aaa",
      body: String = text,
      idx: String = index,
      docType: String = "work",
      embeddingModel: String = model
  ): EmbeddingWorkMessage =
    EmbeddingWorkMessage.of(
      "run:v1:r1",
      "subject-1",
      id,
      idx,
      docType,
      "spark-summary/1",
      embeddingModel,
      "chunk-file:c1",
      body
    )

  private class CountingEmbedder extends Embedder:
    private val inner = new HashEmbedder
    var calls = 0
    var failWith: Option[RuntimeException] = None
    var onEmbed: () => Unit = () => ()
    val modelVersion: String = inner.modelVersion
    val dimension: Int = inner.dimension
    def embed(value: String): Vector[Float] =
      calls += 1
      onEmbed()
      failWith.foreach(e => throw e)
      inner.embed(value)

  private class SpyBucket extends InMemoryVectorBucket:
    var puts = 0
    var failNext: Option[RuntimeException] = None
    override def putVectors(idx: String, batch: Vector[VectorRecord]): Unit =
      failNext.foreach { e =>
        failNext = None
        throw e
      }
      super.putVectors(idx, batch)
      puts += batch.size

  private class TableProxy(inner: DynamoTable) extends DynamoTable:
    var crashOnDone = false
    def spec: DynamoTableSpec = inner.spec
    def putIfAbsent(item: Item): Unit = inner.putIfAbsent(item)
    def putIfVersion(item: Item, expectedVersion: Long): Unit =
      if crashOnDone && Attr.optS(item, "status").contains("DONE") then
        throw new Crash
      inner.putIfVersion(item, expectedVersion)
    def get(pk: String, sk: String): Option[Item] = inner.get(pk, sk)
    def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item] =
      inner.query(pk, skPrefix, limit)
    def queryIndex(
        name: String,
        pk: String,
        skPrefix: Option[String],
        limit: Int
    ): Vector[Item] = inner.queryIndex(name, pk, skPrefix, limit)

  private class QueueProxy(inner: WorkQueue) extends WorkQueue:
    var deleteFails: Option[Throwable] = None
    var onReceive: () => Vector[QueueMessage] = () => null
    var closed = false
    def receive(max: Int, wait: Int, visibility: Int): Vector[QueueMessage] =
      val special = onReceive()
      if special != null then special
      else inner.receive(max, wait, visibility)
    def delete(message: QueueMessage): Unit =
      deleteFails.foreach(e => throw e)
      inner.delete(message)
    def changeVisibility(message: QueueMessage, seconds: Int): Unit =
      inner.changeVisibility(message, seconds)
    override def close(): Unit = closed = true

  private final class Env(base: WorkerSettings = WorkerSettings("w1")):
    var time = 1700000000000L
    private var nano = 0L
    val lines = mutable.ArrayBuffer.empty[String]
    val queue = new InMemoryWorkQueue(() => time)
    val store = new InMemoryObjectStore
    val bucket = new SpyBucket
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val embedder = new CountingEmbedder
    val sleeps = mutable.ArrayBuffer.empty[Long]

    def worker(
        id: String = "w1",
        ledger: DynamoTable = table,
        queueFor: WorkQueue = queue,
        embedderFor: Embedder = embedder
    ): EmbeddingWorker =
      new EmbeddingWorker(
        queueFor,
        embedderFor,
        store,
        bucket,
        ledger,
        base.copy(workerId = id),
        line => lines += line,
        () => time,
        () =>
          nano += 2000000L
          nano
        ,
        (millis: Long) => sleeps += millis
      )

    def send(message: EmbeddingWorkMessage): String =
      queue.send(EmbeddingWorkMessage.encode(message))

    def receiveOne(): QueueMessage = queue.receive(1, 0, 120).head

    /** Past the message's visibility and the ledger lease. */
    def later(): Unit = time += 121000L

    def ledgerItem(message: EmbeddingWorkMessage): Option[Item] =
      table.get(
        s"EMBEDWORK#${message.item.summaryId}",
        s"WORK#${message.item.index}#${message.item.textHash}"
      )

    def ledgerStatus(message: EmbeddingWorkMessage): Option[String] =
      ledgerItem(message).flatMap(Attr.optS(_, "status"))

    def stored(message: EmbeddingWorkMessage): Vector[VectorRecord] =
      bucket.getVectors(message.item.index, Vector(message.item.summaryId))

  // Schema

  test("message schema: the encoded form parses back to the same message") {
    val message = work()
    assertEquals(
      EmbeddingWorkMessage.parse(EmbeddingWorkMessage.encode(message)),
      Right(message)
    )
  }

  private def parseReason(body: String): String =
    EmbeddingWorkMessage.parse(body).left.map(_.reason).left.getOrElse("parsed")

  private def encodedWith(change: ujson.Obj => Unit): String =
    val json = EmbeddingWorkMessage.toJson(work()).obj
    val copy = ujson.Obj.from(json)
    change(copy)
    ujson.write(copy)

  private val poisonCases: Vector[(String, String, String)] = Vector(
    ("not json", "this is not json {", "not_json"),
    ("not an object", "[1,2]", "not_object"),
    (
      "wrong version",
      encodedWith(_("schemaVersion") = 2),
      "unsupported_version"
    ),
    (
      "missing version",
      encodedWith(_.value.remove("schemaVersion")),
      "missing_field:schemaVersion"
    ),
    ("missing text", encodedWith(_.value.remove("text")), "missing_field:text"),
    (
      "wrong type",
      encodedWith(_("subjectId") = 5),
      "wrong_type:subjectId"
    ),
    ("unknown field", encodedWith(_("extra") = "x"), "unknown_field"),
    (
      "text hash mismatch",
      encodedWith(_("textHash") = "0" * 64),
      "text_hash_mismatch"
    ),
    (
      "empty text",
      encodedWith { o =>
        o("text") = "   "
        o("textHash") = Hashing.sha256("   ")
      },
      "empty_text"
    ),
    (
      "oversized body",
      "x" * (EmbeddingWorkMessage.MaxBodyBytes + 1),
      "body_too_large"
    ),
    (
      "oversized text",
      EmbeddingWorkMessage.encode(work(body = "a" * 60000)),
      "text_too_large"
    ),
    (
      "model mismatch",
      EmbeddingWorkMessage.encode(work(embeddingModel = "other-model")),
      "model_mismatch"
    ),
    (
      "unknown index",
      EmbeddingWorkMessage.encode(work(idx = "nope-v1")),
      "unknown_index"
    ),
    (
      "index type mismatch",
      EmbeddingWorkMessage.encode(work(docType = "community")),
      "index_type_mismatch"
    ),
    (
      "invalid vector key",
      EmbeddingWorkMessage.encode(work(id = " padded")),
      "invalid_key"
    )
  )

  test("message schema: the two-line example in the report parses") {
    val example =
      """{"schemaVersion":1,"runId":"run:v1:r1","subjectId":"chunk-0001","summaryId":"chunk:v1:0001","index":"paper-abstract-v1","documentType":"work","templateVersion":"spark-summary/1","embeddingModel":"hash-embed-v1","textHash":"%s","sourceRef":"chunk-file:tier-s/0001","text":"graph neural networks"}"""
        .format(Hashing.sha256("graph neural networks"))
    assert(EmbeddingWorkMessage.parse(example).isRight)
  }

  // Happy path, provenance and the log line

  test("a valid message is embedded, written, recorded and deleted") {
    val e = new Env
    val message = work()
    e.send(message)
    val received = e.receiveOne()
    assertEquals(e.worker().processOne(received), WorkOutcome.Done)
    assertEquals(e.queue.depth, 0)
    assertEquals(e.bucket.puts, 1)
    assertEquals(e.embedder.calls, 1)
    assertEquals(e.stored(message).size, 1)
    assertEquals(e.ledgerStatus(message), Some("DONE"))
    val pattern =
      """WORKER_MSG id=msg-1 outcome=done receive_count=1 latency_ms=\d+ queue_ms=\d+ at=\d{4}-\d\d-\d\dT[\d:.]+Z""".r
    assert(pattern.matches(e.lines.last), e.lines.last)
  }

  test("provenance reaches the ledger, the vector and the stored document") {
    val e = new Env
    val message = work()
    e.send(message)
    e.worker().processOne(e.receiveOne())
    val item = e.ledgerItem(message).get
    assertEquals(Attr.optS(item, "sourceRef"), Some("chunk-file:c1"))
    assertEquals(
      Attr.optS(item, "storageTransformVersion"),
      Some(StorageProvenance.TransformVersion)
    )
    assertEquals(Attr.optS(item, "runId"), Some("run:v1:r1"))
    val record = e.stored(message).head
    assertEquals(
      record.metadata("textHash"),
      MetaValue.Str(message.item.textHash)
    )
    assertEquals(record.metadata("embeddingModel"), MetaValue.Str(model))
    assertEquals(record.metadata("runId"), MetaValue.Str("run:v1:r1"))
    val fetched = SemanticDocuments.fetch(
      e.store,
      VectorMatch(record.key, 0.0, record.metadata)
    )
    assertEquals(fetched.text, text)
    assertEquals(fetched.runId, "run:v1:r1")
    assertEquals(fetched.templateVersion, "spark-summary/1")
  }

  // Idempotency

  test("a message whose ledger entry is done is deleted with no second embed") {
    val e = new Env
    val message = work()
    e.send(message)
    e.worker().processOne(e.receiveOne())
    e.send(message)
    assertEquals(e.worker().processOne(e.receiveOne()), WorkOutcome.AlreadyDone)
    assertEquals(e.queue.depth, 0)
    assertEquals(e.embedder.calls, 1)
    assertEquals(e.bucket.puts, 1)
  }

  test("a duplicate delivered during processing makes no second write") {
    val e = new Env
    val message = work()
    e.send(message)
    val received = e.receiveOne()
    val other = e.worker(id = "w2", embedderFor = new HashEmbedder)
    var inner: Option[WorkOutcome] = None
    e.embedder.onEmbed = () =>
      inner = Some(
        other.processOne(
          received.copy(messageId = "dup-1", receiptHandle = "dup-handle")
        )
      )
    assertEquals(e.worker().processOne(received), WorkOutcome.Done)
    assertEquals(inner, Some(WorkOutcome.Deferred))
    assertEquals(e.bucket.puts, 1)
    assertEquals(e.embedder.calls, 1)
    assert(
      e.lines.exists(l =>
        l.contains("outcome=deferred") && l.contains("retry_in_s=100")
      )
    )
  }

  test("replay after a crash between the vector write and the ledger") {
    val e = new Env
    val message = work()
    val proxy = new TableProxy(e.table)
    proxy.crashOnDone = true
    e.send(message)
    crashes(e.worker(ledger = proxy).processOne(e.receiveOne()))
    assertEquals(e.bucket.puts, 1)
    assertEquals(e.queue.depth, 1)
    assertEquals(e.ledgerStatus(message), Some("CLAIMED"))
    proxy.crashOnDone = false
    e.later()
    val again = new CountingEmbedder
    assertEquals(
      e.worker(id = "w2", embedderFor = again).processOne(e.receiveOne()),
      WorkOutcome.Done
    )
    assertEquals(again.calls, 0)
    assertEquals(e.bucket.puts, 1)
    assertEquals(e.ledgerStatus(message), Some("DONE"))
    assertEquals(e.queue.depth, 0)
  }

  test("replay after a crash between the ledger and the delete") {
    val e = new Env
    val message = work()
    val queue = new QueueProxy(e.queue)
    queue.deleteFails = Some(new Crash)
    e.send(message)
    crashes(e.worker(queueFor = queue).processOne(e.receiveOne()))
    assertEquals(e.ledgerStatus(message), Some("DONE"))
    assertEquals(e.queue.depth, 1)
    e.later()
    assertEquals(e.worker().processOne(e.receiveOne()), WorkOutcome.AlreadyDone)
    assertEquals(e.embedder.calls, 1)
    assertEquals(e.bucket.puts, 1)
    assertEquals(e.queue.depth, 0)
  }

  test("a failed delete is logged and the redelivery is already done") {
    val e = new Env
    val message = work()
    val queue = new QueueProxy(e.queue)
    queue.deleteFails = Some(new RuntimeException("boom"))
    e.send(message)
    assertEquals(
      e.worker(queueFor = queue).processOne(e.receiveOne()),
      WorkOutcome.Done
    )
    assert(e.lines.last.contains("delete=failed"))
    queue.deleteFails = None
    e.later()
    assertEquals(e.worker().processOne(e.receiveOne()), WorkOutcome.AlreadyDone)
    assertEquals(e.bucket.puts, 1)
  }

  // Transient failures

  test("a transient vector write leaves the message and releases the claim") {
    val e = new Env
    val message = work()
    e.bucket.failNext = Some(new TransientStorageException("throttled"))
    e.send(message)
    assertEquals(
      e.worker().processOne(e.receiveOne()),
      WorkOutcome.Transient("transient")
    )
    assertEquals(e.queue.depth, 1)
    assertEquals(e.ledgerStatus(message), Some("RELEASED"))
    assertEquals(e.bucket.puts, 0)
    e.later()
    assertEquals(e.worker().processOne(e.receiveOne()), WorkOutcome.Done)
    assertEquals(e.bucket.puts, 1)
    assert(e.lines.exists(_.contains("receive_count=2")))
  }

  test("a transient embed failure leaves the message and writes nothing") {
    val e = new Env
    val message = work()
    e.embedder.failWith = Some(new TransientStorageException("throttled"))
    e.send(message)
    assertEquals(
      e.worker().processOne(e.receiveOne()),
      WorkOutcome.Transient("transient")
    )
    assertEquals(e.queue.depth, 1)
    assertEquals(e.stored(message), Vector.empty)
    assertEquals(e.ledgerStatus(message), Some("RELEASED"))
  }

  test("the retry delay setting bounds when a failed message returns") {
    val delayed = new Env(WorkerSettings("w1", retryDelaySeconds = 7))
    delayed.embedder.failWith = Some(new TransientStorageException("x"))
    delayed.send(work())
    delayed.worker().processOne(delayed.receiveOne())
    delayed.time += 8000L
    assertEquals(delayed.queue.receive(1, 0, 120).size, 1)

    val plain = new Env
    plain.embedder.failWith = Some(new TransientStorageException("x"))
    plain.send(work())
    plain.worker().processOne(plain.receiveOne())
    plain.time += 8000L
    assertEquals(plain.queue.receive(1, 0, 120).size, 0)
  }

  // Poison

  poisonCases.foreach { (name, body, reason) =>
    test(s"poison message ($name) is never deleted and logs only its reason") {
      val e = new Env
      e.queue.send(body)
      val received = e.receiveOne()
      assertEquals(
        e.worker().processOne(received),
        WorkOutcome.Poison(reason)
      )
      assertEquals(e.queue.depth, 1)
      assertEquals(e.embedder.calls, 0)
      assertEquals(
        e.table.query("EMBEDWORK#summary:v1:aaa", None, 10),
        Vector.empty
      )
      val line = e.lines.last
      assert(line.contains("id=msg-1"), line)
      assert(line.contains(s"reason=$reason"), line)
      assert(!line.contains("graph neural"), line)
      assert(!line.contains("this is not json"), line)
    }
  }

  test("a poison message reaches the dead-letter queue after three receives") {
    val e = new Env
    e.queue.send("this is not json {")
    val w = e.worker()
    (1 to 3).foreach { _ =>
      w.processOne(e.receiveOne())
      e.later()
    }
    assertEquals(e.queue.receive(1, 0, 120), Vector.empty)
    assertEquals(e.queue.deadLetters.map(_._1), Vector("msg-1"))
    assertEquals(e.queue.depth, 0)
    assertEquals(e.lines.count(_.contains("outcome=poison")), 3)
  }

  test("text the embedder refuses is poison and releases the claim") {
    val e = new Env
    val message = work(body = "!!!")
    e.send(message)
    assertEquals(
      e.worker().processOne(e.receiveOne()),
      WorkOutcome.Poison("EmbeddingInvalid")
    )
    assertEquals(e.queue.depth, 1)
    assertEquals(e.stored(message), Vector.empty)
    assertEquals(e.ledgerStatus(message), Some("RELEASED"))
  }

  test("a key that holds other text is poison and is not overwritten") {
    val e = new Env
    val first = work()
    e.send(first)
    e.worker().processOne(e.receiveOne())
    val changed = work(body = "completely different words here")
    e.send(changed)
    assertEquals(
      e.worker().processOne(e.receiveOne()),
      WorkOutcome.Poison("ChangedVectorContent")
    )
    assertEquals(e.bucket.puts, 1)
    assertEquals(
      e.stored(first).head.metadata("textHash"),
      MetaValue.Str(first.item.textHash)
    )
  }

  // Loop, shutdown and bounds

  test(
    "stop finishes the message in flight and releases the rest of the batch"
  ) {
    val e = new Env(WorkerSettings("w1", batchSize = 3))
    (1 to 3).foreach(i => e.send(work(id = s"summary:v1:$i")))
    val w = e.worker()
    e.embedder.onEmbed = () => w.stop()
    val summary = w.run()
    assertEquals(summary.stopReason, "sigterm")
    assertEquals(summary.counts.get("done"), Some(1L))
    assertEquals(summary.counts.get("released"), Some(2L))
    assertEquals(e.queue.depth, 2)
    assertEquals(e.queue.receive(5, 0, 120).size, 2)
    assert(e.lines.exists(_.contains("outcome=released")))
    assert(e.lines.last.startsWith("WORKER_STOP reason=sigterm"))
  }

  test("the loop stops at the maximum message count") {
    val e = new Env(WorkerSettings("w1", maxMessages = 2))
    (1 to 5).foreach(i => e.send(work(id = s"summary:v1:$i")))
    val summary = e.worker().run()
    assertEquals(summary.received, 2L)
    assertEquals(summary.stopReason, "limit")
    assertEquals(e.queue.depth, 3)
  }

  test("a queue that keeps failing ends the run after bounded backoff") {
    val e = new Env
    val queue = new QueueProxy(e.queue)
    queue.onReceive = () => throw new RuntimeException("arn:aws:sqs:secret")
    val error = intercept[StorageException](e.worker(queueFor = queue).run())
    assertEquals(error.code, ErrorCode.RetryExhausted)
    assertEquals(e.sleeps.size, 9)
    assert(e.sleeps.forall(_ <= 10000L))
    assertEquals(e.sleeps.toVector, e.sleeps.toVector.sorted)
    assert(e.lines.exists(_.startsWith("WORKER_STOP reason=receive_failures")))
    assert(!e.lines.exists(_.contains("arn:aws")))
  }

  // Settings and entry point

  private val env = Map(
    "AWS_REGION" -> "us-east-2",
    "SCHOLARCHAIN_EMBEDDING_QUEUE_URL" ->
      "https://sqs.us-east-2.amazonaws.com/123456789012/sc-embedding-work",
    "SCHOLARCHAIN_DATA_BUCKET" -> "data-bucket-x",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "vector-bucket-x",
    "SCHOLARCHAIN_TABLE_PREFIX" -> "sc"
  )

  private def configError(vars: Map[String, String]): String =
    intercept[StorageException](
      EmbeddingWorkerMain.parseConfig(vars)
    ).getMessage

  test("settings: defaults") {
    val s = EmbeddingWorkerMain.parseConfig(env).settings
    assertEquals(
      (s.batchSize, s.waitSeconds, s.visibilitySeconds, s.leaseSeconds),
      (5, 10, 120, 100)
    )
    assertEquals(
      (s.retryDelaySeconds, s.maxMessages, s.shutdownGraceSeconds),
      (0, 0L, 25)
    )
  }

  test(
    "settings: only Region us-east-2 is accepted and the value is not echoed"
  ) {
    val message = configError(env + ("AWS_REGION" -> "us-east-1"))
    assert(message.contains("AWS_REGION"))
    assert(!message.contains("us-east-1"))
  }

  test("settings: a missing or malformed variable is named, its value is not") {
    assert(
      configError(env - "SCHOLARCHAIN_EMBEDDING_QUEUE_URL")
        .contains("SCHOLARCHAIN_EMBEDDING_QUEUE_URL")
    )
    val bad = configError(
      env + ("SCHOLARCHAIN_EMBEDDING_QUEUE_URL" -> "http://example.com/q")
    )
    assert(!bad.contains("example.com"))
    assert(
      configError(env + ("SCHOLARCHAIN_WORKER_BATCH" -> "many"))
        .contains("SCHOLARCHAIN_WORKER_BATCH")
    )
  }

  test("settings: a lease that outlives the visibility timeout is refused") {
    configError(
      env ++ Map(
        "SCHOLARCHAIN_WORKER_VISIBILITY_SECONDS" -> "60",
        "SCHOLARCHAIN_WORKER_LEASE_SECONDS" -> "60"
      )
    )
  }

  private def noWiring(
      config: EmbeddingWorkerMain.Config
  ): EmbeddingWorkerMain.Wiring =
    fail("no wiring expected")

  test("entry point: --help lists the settings and touches nothing") {
    val lines = mutable.ArrayBuffer.empty[String]
    val code = EmbeddingWorkerMain.run(
      Array("--help"),
      Map.empty,
      lines += _,
      noWiring
    )
    assertEquals(code, 0)
    assert(lines.exists(_.contains("SCHOLARCHAIN_EMBEDDING_QUEUE_URL")))
  }

  test(
    "entry point: --check-config validates without wiring or the queue URL"
  ) {
    val lines = mutable.ArrayBuffer.empty[String]
    val code = EmbeddingWorkerMain.run(
      Array("--check-config"),
      env,
      lines += _,
      noWiring
    )
    assertEquals(code, 0)
    assert(lines.forall(!_.contains("sqs.us-east-2")))
    assertEquals(
      EmbeddingWorkerMain
        .run(Array("--check-config"), Map.empty, _ => (), noWiring),
      2
    )
  }

  private def wiringOver(e: Env, queue: WorkQueue) =
    (_: EmbeddingWorkerMain.Config) =>
      EmbeddingWorkerMain.Wiring(
        queue,
        e.embedder,
        e.store,
        e.bucket,
        e.table
      )

  test("entry point: runs to the message limit, exits 0 and closes the queue") {
    val e = new Env
    e.send(work())
    val queue = new QueueProxy(e.queue)
    val lines = mutable.ArrayBuffer.empty[String]
    val code = EmbeddingWorkerMain.run(
      Array.empty,
      env + ("SCHOLARCHAIN_WORKER_MAX_MESSAGES" -> "1"),
      lines += _,
      wiringOver(e, queue)
    )
    assertEquals(code, 0)
    assertEquals(lines.last, "WORKER_RESULT=STOPPED")
    assert(queue.closed)
    assertEquals(e.bucket.puts, 1)
  }

  test("entry point: the SIGTERM hook stops the loop with exit 0") {
    val e = new Env
    val queue = new QueueProxy(e.queue)
    var stop: () => Unit = () => ()
    queue.onReceive = () =>
      stop()
      Vector.empty
    val lines = mutable.ArrayBuffer.empty[String]
    val code = EmbeddingWorkerMain.run(
      Array.empty,
      env,
      lines += _,
      wiringOver(e, queue),
      (stopAction, _) => stop = stopAction
    )
    assertEquals(code, 0)
    assert(lines.exists(_.startsWith("WORKER_STOP reason=sigterm")))
  }

  test("entry point: a wiring failure is exit 1 and names no resource") {
    val lines = mutable.ArrayBuffer.empty[String]
    val code = EmbeddingWorkerMain.run(
      Array.empty,
      env,
      lines += _,
      _ => throw new RuntimeException("arn:aws:iam::123456789012:role/x")
    )
    assertEquals(code, 1)
    assertEquals(lines.last, "WORKER_RESULT=FAILED reason=RuntimeException")
  }
