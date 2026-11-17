package scholarchain.storage

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

/** What the worker reads from its environment, as values. Defaults and ranges
  * are in `EmbeddingWorkerMain`.
  */
final case class WorkerSettings(
    workerId: String,
    batchSize: Int = 5,
    waitSeconds: Int = 10,
    visibilitySeconds: Int = 120,
    leaseSeconds: Int = 100,
    retryDelaySeconds: Int = 0,
    maxMessages: Long = 0L,
    shutdownGraceSeconds: Int = 25
):
  private def within(name: String, value: Long, low: Long, high: Long): Unit =
    Fail.unless(
      value >= low && value <= high,
      ErrorCode.InvalidQuery,
      s"Worker setting $name is outside $low to $high"
    )
  within("batchSize", batchSize, 1, 10)
  within("waitSeconds", waitSeconds, 0, 20)
  within("visibilitySeconds", visibilitySeconds, 30, 900)
  within("leaseSeconds", leaseSeconds, 10, visibilitySeconds - 5L)
  within("retryDelaySeconds", retryDelaySeconds, 0, 900)
  within("maxMessages", maxMessages, 0, Long.MaxValue)
  within("shutdownGraceSeconds", shutdownGraceSeconds, 1, 120)

  def leaseMillis: Long = leaseSeconds * 1000L

enum WorkOutcome(val label: String):
  /** Embedded, written, recorded and deleted. */
  case Done extends WorkOutcome("done")

  /** The ledger already said done: deleted with no embed and no write. */
  case AlreadyDone extends WorkOutcome("already_done")

  /** Another delivery holds the lease: left in the queue. */
  case Deferred extends WorkOutcome("deferred")

  /** A failure that may pass: left in the queue for redelivery. */
  case Transient(reason: String) extends WorkOutcome("transient")

  /** A message that can never succeed: never deleted, so the queue's redrive
    * policy moves it to the dead-letter queue.
    */
  case Poison(reason: String) extends WorkOutcome("poison")

  /** Handed back unprocessed because the worker is stopping. */
  case Released extends WorkOutcome("released")

final case class WorkerSummary(
    received: Long,
    counts: Map[String, Long],
    stopReason: String
)

/** The two ledger states the worker writes besides DONE. */
object EmbeddingWorkLedger:
  val Claimed = "CLAIMED"
  val Released = "RELEASED"
  val Done = "DONE"
  val MaxClaimTries = 3

  enum Claim:
    /** The caller holds the item at `version` until `leaseUntil` (epoch ms). */
    case Held(version: Long, attempts: Long, leaseUntil: Long)
    case AlreadyDone

    /** Another holder's lease runs another `remainingMillis`. */
    case Busy(remainingMillis: Long)

/** The per-chunk ledger in the RunLedger table: item `EMBEDWORK#<summaryId>`,
  * `WORK#<index>#<textHash>`. Every change is a conditional write, so two
  * workers cannot both hold a chunk: a new item with `attribute_not_exists`, a
  * changed item with the expected `version`. A claim carries a lease; a chunk
  * whose lease has passed (a crashed or slow holder) can be taken over, and
  * DONE is final.
  */
final class EmbeddingWorkLedger(table: DynamoTable, clock: () => Long):
  import EmbeddingWorkLedger.*

  private def pk(item: EmbeddingWorkItem) = s"EMBEDWORK#${item.summaryId}"
  private def sk(item: EmbeddingWorkItem) =
    s"WORK#${item.index}#${item.textHash}"

  private def record(
      message: EmbeddingWorkMessage,
      status: String,
      owner: String,
      leaseUntil: Long,
      attempts: Long,
      version: Long,
      failure: Option[String]
  ): Item =
    val item = message.item
    val base: Item = Map(
      "pk" -> Attr.s(pk(item)),
      "sk" -> Attr.s(sk(item)),
      "entityType" -> Attr.s("EMBEDWORK"),
      "runId" -> Attr.s(message.runId),
      "index" -> Attr.s(item.index),
      "summaryId" -> Attr.s(item.summaryId),
      "textHash" -> Attr.s(item.textHash),
      "embeddingModel" -> Attr.s(item.embeddingModel),
      "status" -> Attr.s(status),
      "claimedBy" -> Attr.s(owner),
      "leaseUntil" -> Attr.n(leaseUntil),
      "attempts" -> Attr.n(attempts),
      "version" -> Attr.n(version),
      "updatedAt" -> Attr.s(Instant.ofEpochMilli(clock()).toString)
    ) ++ Attr.provenance(item.sourceRef, None)
    failure.fold(base)(code => base + ("failure" -> Attr.s(code)))

  private def number(item: Item, name: String): Long =
    item.get(name).flatMap(_.numOpt).map(_.toLong).getOrElse(0L)

  def status(item: EmbeddingWorkItem): Option[String] =
    table.get(pk(item), sk(item)).flatMap(Attr.optS(_, "status"))

  def attempts(item: EmbeddingWorkItem): Long =
    table.get(pk(item), sk(item)).map(number(_, "attempts")).getOrElse(0L)

  def claim(
      message: EmbeddingWorkMessage,
      owner: String,
      leaseMillis: Long
  ): Claim =
    val item = message.item
    var tries = 0
    var result: Option[Claim] = None
    while result.isEmpty && tries < MaxClaimTries do
      tries += 1
      val now = clock()
      val until = now + leaseMillis
      table.get(pk(item), sk(item)) match
        case None =>
          try
            table.putIfAbsent(
              record(message, Claimed, owner, until, 1L, 1L, None)
            )
            result = Some(Claim.Held(1L, 1L, until))
          catch
            case error: StorageException
                if error.code == ErrorCode.ConditionalCheckFailed =>
              ()
        case Some(found) =>
          val state = Attr.optS(found, "status").getOrElse("")
          val leaseUntil = number(found, "leaseUntil")
          val version = number(found, "version")
          val attempt = number(found, "attempts") + 1
          if state == Done then result = Some(Claim.AlreadyDone)
          else if leaseUntil > now then
            result = Some(Claim.Busy(leaseUntil - now))
          else
            try
              table.putIfVersion(
                record(
                  message,
                  Claimed,
                  owner,
                  until,
                  attempt,
                  version + 1,
                  None
                ),
                version
              )
              result = Some(Claim.Held(version + 1, attempt, until))
            catch
              case error: StorageException
                  if error.code == ErrorCode.ConditionalCheckFailed =>
                ()
    result.getOrElse(Claim.Busy(1000L))

  /** DONE, only while the caller still holds the item at its version. False
    * when another holder took it over.
    */
  def markDone(
      message: EmbeddingWorkMessage,
      owner: String,
      held: Claim.Held
  ): Boolean =
    try
      table.putIfVersion(
        record(
          message,
          Done,
          owner,
          0L,
          held.attempts,
          held.version + 1,
          None
        ),
        held.version
      )
      true
    catch
      case error: StorageException
          if error.code == ErrorCode.ConditionalCheckFailed =>
        false

  /** Gives the item back so the next delivery can claim it at once. */
  def release(
      message: EmbeddingWorkMessage,
      owner: String,
      held: Claim.Held,
      failure: String
  ): Unit =
    try
      table.putIfVersion(
        record(
          message,
          Released,
          owner,
          0L,
          held.attempts,
          held.version + 1,
          Some(failure)
        ),
        held.version
      )
    catch
      case error: StorageException
          if error.code == ErrorCode.ConditionalCheckFailed =>
        ()

/** The SQS consumer of the `embedding-work` queue. One message is one chunk:
  *
  *   1. Parse and check it. A message that is not valid is poison.
  *   2. Claim the chunk in the ledger. DONE: delete with no embed and no write.
  *      Another live lease: leave it, hidden until that lease ends.
  *   3. A vector already stored for the key with the same text hash and model
  *      is not embedded or written again (a replay after a crash). A different
  *      one is poison.
  *   4. Embed, store the document in ordinary S3 (so `textRef` resolves), write
  *      the vector, mark the ledger DONE, delete the message.
  *
  * A transient failure leaves the message for redelivery (no delete); the
  * ledger claim is released. A poison message is never deleted, so `maxReceive
  * Count` moves it to the dead-letter queue. Delivery is at least once and the
  * vector write is guarded by the ledger claim and the stored-vector check, so
  * the effect is one write per chunk. One message is in flight at a time.
  *
  * Log lines carry IDs, outcome names and numbers only: never message text, an
  * exception message, a queue URL or a bucket name.
  */
final class EmbeddingWorker(
    queue: WorkQueue,
    embedder: Embedder,
    store: ObjectStore,
    bucket: VectorBucketPort,
    ledgerTable: DynamoTable,
    settings: WorkerSettings,
    out: String => Unit = println,
    clock: () => Long = () => System.currentTimeMillis(),
    nanoClock: () => Long = () => System.nanoTime(),
    sleeper: Sleeper = ThreadSleeper
):
  import EmbeddingWorkLedger.Claim

  private final class LeaseLost extends RuntimeException("lease lost")

  private final case class Prepared(
      spec: VectorIndexSpec,
      metadata: Map[String, MetaValue]
  )

  private final case class Handled(outcome: WorkOutcome, detail: String = "")

  private val ledger = new EmbeddingWorkLedger(ledgerTable, clock)
  private val specs: Map[String, VectorIndexSpec] =
    VectorIndexes.all(embedder.dimension, "cosine").map(s => s.name -> s).toMap
  private val ensured = mutable.Set.empty[String]
  private val stopping = new AtomicBoolean(false)

  /** Asks the loop to stop after the message in flight. */
  def stop(): Unit = stopping.set(true)

  def isStopping: Boolean = stopping.get

  private def now: String = Instant.ofEpochMilli(clock()).toString

  private def safe(text: String): String =
    text.filter(c => c.isLetterOrDigit || "-_.:".contains(c)).take(100)

  private def logMessage(
      message: QueueMessage,
      handled: Handled,
      began: Long
  ): Unit =
    val latency = (nanoClock() - began) / 1000000L
    val queueMs = message.sentAtMillis
      .map(sent => s" queue_ms=${math.max(0L, clock() - sent)}")
      .getOrElse("")
    val reason = handled.outcome match
      case WorkOutcome.Transient(r) => s" reason=${safe(r)}"
      case WorkOutcome.Poison(r)    => s" reason=${safe(r)}"
      case _                        => ""
    // The detail is built inside this class from numbers and fixed words.
    val detail = if handled.detail.isEmpty then "" else s" ${handled.detail}"
    out(
      s"WORKER_MSG id=${safe(message.messageId)} " +
        s"outcome=${handled.outcome.label} " +
        s"receive_count=${message.receiveCount} latency_ms=$latency$queueMs" +
        s"$reason$detail at=$now"
    )

  private def prepare(work: EmbeddingWorkMessage): Either[String, Prepared] =
    val item = work.item
    if item.embeddingModel != embedder.modelVersion then Left("model_mismatch")
    else
      specs.get(item.index) match
        case None       => Left("unknown_index")
        case Some(spec) =>
          if !spec.summaryType.contains(item.documentType) then
            Left("index_type_mismatch")
          else
            try
              VectorKeys.validate(item.summaryId)
              val textRef = SemanticDocuments.key(
                work.runId,
                item.summaryId,
                item.textHash,
                item.templateVersion
              )
              val metadata = Map(
                "summaryType" -> MetaValue.Str(item.documentType),
                "subjectId" -> MetaValue.Str(work.subjectId),
                "textRef" -> MetaValue.Str(textRef),
                "runId" -> MetaValue.Str(work.runId),
                "embeddingModel" -> MetaValue.Str(item.embeddingModel),
                "storageTransformVersion" ->
                  MetaValue.Str(StorageProvenance.TransformVersion),
                "documentType" -> MetaValue.Str(item.documentType),
                "textHash" -> MetaValue.Str(item.textHash),
                "templateVersion" -> MetaValue.Str(item.templateVersion)
              )
              VectorMetadataIngest.validate(metadata)
              Right(Prepared(spec, metadata))
            catch
              case error: StorageException =>
                Left(
                  if error.code == ErrorCode.MetadataInvalid then
                    "metadata_invalid"
                  else "invalid_key"
                )

  private val poisonCodes: Set[ErrorCode] = Set(
    ErrorCode.EmbeddingInvalid,
    ErrorCode.MetadataInvalid,
    ErrorCode.NullCharacter,
    ErrorCode.ChangedVectorContent,
    ErrorCode.ImmutableKeyConflict,
    ErrorCode.VectorKeyInvalid,
    ErrorCode.InvalidKey,
    ErrorCode.TextHashMismatch,
    ErrorCode.MalformedRow,
    ErrorCode.InvalidItem,
    ErrorCode.ItemTooLarge,
    ErrorCode.DuplicateVectorKey,
    ErrorCode.BoundExceeded
  )

  /** Only the code or the class name: an exception message can hold an ARN or
    * an account ID.
    */
  private def classify(error: Throwable): WorkOutcome = error match
    case _: LeaseLost                 => WorkOutcome.Transient("lease_lost")
    case _: TransientStorageException => WorkOutcome.Transient("transient")
    case failure: StorageException    =>
      if poisonCodes.contains(failure.code) then
        WorkOutcome.Poison(failure.code.toString)
      else WorkOutcome.Transient(failure.code.toString)
    case other =>
      WorkOutcome.Transient(s"unexpected:${other.getClass.getSimpleName}")

  private def storedText(record: VectorRecord, key: String): String =
    record.metadata.get(key) match
      case Some(MetaValue.Str(v)) => v
      case _                      => "none"

  private def ensureLease(held: Claim.Held): Unit =
    if clock() >= held.leaseUntil then throw new LeaseLost

  private def transfer(
      work: EmbeddingWorkMessage,
      prepared: Prepared,
      held: Claim.Held
  ): Unit =
    val item = work.item
    val spec = prepared.spec
    if !ensured.contains(spec.name) then
      bucket.ensureIndex(spec)
      ensured += spec.name
    val existing =
      bucket.getVectors(spec.name, Vector(item.summaryId)).headOption
    val present = existing.exists { old =>
      Fail.unless(
        storedText(old, "textHash") == item.textHash &&
          storedText(old, "embeddingModel") == item.embeddingModel,
        ErrorCode.ChangedVectorContent,
        "the key holds a vector of other text or another model"
      )
      true
    }
    val embedding =
      if present then None
      else
        val made = embedder.embed(work.text)
        Fail.unless(
          made.size == spec.dimension,
          ErrorCode.EmbeddingInvalid,
          s"embedding has ${made.size} values, index expects ${spec.dimension}"
        )
        Some(made)
    SemanticDocuments.put(
      store,
      work.runId,
      item.summaryId,
      item.documentType,
      RenderedDocument(work.text, item.templateVersion),
      item.textHash
    )
    embedding.foreach { vector =>
      ensureLease(held)
      bucket.putVectors(
        spec.name,
        Vector(VectorRecord(item.summaryId, vector, prepared.metadata))
      )
    }
    ensureLease(held)
    if !ledger.markDone(work, settings.workerId, held) then throw new LeaseLost

  private def deleteNote(message: QueueMessage): String =
    try
      queue.delete(message)
      ""
    catch case NonFatal(_) => "delete=failed"

  private def retryAfterFailure(message: QueueMessage): Unit =
    if settings.retryDelaySeconds > 0 then
      try queue.changeVisibility(message, settings.retryDelaySeconds)
      catch case NonFatal(_) => ()

  private def claimed(
      message: QueueMessage,
      work: EmbeddingWorkMessage,
      prepared: Prepared
  ): Handled =
    val claim: Either[WorkOutcome, Claim] =
      try Right(ledger.claim(work, settings.workerId, settings.leaseMillis))
      catch case NonFatal(error) => Left(classify(error))
    claim match
      case Left(outcome)            => Handled(outcome)
      case Right(Claim.AlreadyDone) =>
        Handled(WorkOutcome.AlreadyDone, deleteNote(message))
      case Right(Claim.Busy(millis)) =>
        val seconds = math.min(
          settings.visibilitySeconds.toLong,
          math.max(1L, (millis + 999L) / 1000L)
        )
        try queue.changeVisibility(message, seconds.toInt)
        catch case NonFatal(_) => ()
        Handled(WorkOutcome.Deferred, s"retry_in_s=$seconds")
      case Right(held: Claim.Held) =>
        try
          transfer(work, prepared, held)
          Handled(WorkOutcome.Done, deleteNote(message))
        catch
          case NonFatal(error) =>
            if !error.isInstanceOf[LeaseLost] then
              try
                ledger.release(
                  work,
                  settings.workerId,
                  held,
                  classify(error) match
                    case WorkOutcome.Poison(r)    => r
                    case WorkOutcome.Transient(r) => r
                    case _                        => "failed"
                )
              catch case NonFatal(_) => ()
            val outcome = classify(error)
            outcome match
              case _: WorkOutcome.Transient => retryAfterFailure(message)
              case _                        => ()
            Handled(outcome)

  private def handle(message: QueueMessage): Handled =
    EmbeddingWorkMessage.parse(message.body) match
      case Left(refused) => Handled(WorkOutcome.Poison(refused.reason))
      case Right(work)   =>
        prepare(work) match
          case Left(reason)    => Handled(WorkOutcome.Poison(reason))
          case Right(prepared) => claimed(message, work, prepared)

  /** Processes one received message and logs one line for it. */
  def processOne(message: QueueMessage): WorkOutcome =
    val began = nanoClock()
    val handled =
      try handle(message)
      catch case NonFatal(error) => Handled(classify(error))
    logMessage(message, handled, began)
    handled.outcome

  private def releaseUnprocessed(message: QueueMessage): Unit =
    val began = nanoClock()
    try queue.changeVisibility(message, 0)
    catch case NonFatal(_) => ()
    logMessage(message, Handled(WorkOutcome.Released), began)

  val MaxConsecutiveReceiveFailures = 10
  private val receiveBackoff = RetryPolicy(10, 500L, 10000L)

  /** Polls until `stop()` or `maxMessages`. Returns what it did. A queue that
    * fails to answer `MaxConsecutiveReceiveFailures` times in a row ends the
    * run with `RetryExhausted`, so the container restarts instead of idling.
    */
  def run(): WorkerSummary =
    out(
      s"WORKER_START worker=${safe(settings.workerId)} " +
        s"batch=${settings.batchSize} wait_s=${settings.waitSeconds} " +
        s"visibility_s=${settings.visibilitySeconds} " +
        s"lease_s=${settings.leaseSeconds} " +
        s"max_messages=${settings.maxMessages} at=$now"
    )
    val counts = mutable.LinkedHashMap.empty[String, Long]
    var received = 0L
    var failures = 0
    def limitReached =
      settings.maxMessages > 0 && received >= settings.maxMessages
    def finish(reason: String): WorkerSummary =
      val summary = WorkerSummary(received, counts.toMap, reason)
      out(
        s"WORKER_STOP reason=$reason received=$received " +
          counts.toVector
            .sortBy(_._1)
            .map((label, n) => s"$label=$n")
            .mkString(" ") + s" at=$now"
      )
      summary
    while !stopping.get && !limitReached do
      val remaining =
        if settings.maxMessages > 0 then settings.maxMessages - received
        else Long.MaxValue
      val size = math.min(settings.batchSize.toLong, remaining).toInt
      val batch =
        try
          val got =
            queue.receive(
              size,
              settings.waitSeconds,
              settings.visibilitySeconds
            )
          failures = 0
          got
        catch
          case NonFatal(error) =>
            failures += 1
            out(
              s"WORKER_RECEIVE_FAILED consecutive=$failures " +
                s"error=${safe(error.getClass.getSimpleName)} at=$now"
            )
            if failures >= MaxConsecutiveReceiveFailures then
              finish("receive_failures")
              Fail(
                ErrorCode.RetryExhausted,
                s"The queue failed to answer $failures times in a row"
              )
            sleeper.sleep(receiveBackoff.delayAfter(failures))
            Vector.empty[QueueMessage]
      batch.foreach { message =>
        if stopping.get || limitReached then
          releaseUnprocessed(message)
          counts.update("released", counts.getOrElse("released", 0L) + 1)
        else
          val outcome = processOne(message)
          received += 1
          counts.update(
            outcome.label,
            counts.getOrElse(outcome.label, 0L) + 1
          )
      }
    finish(if stopping.get then "sigterm" else "limit")
