package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scholarchain.core.Provenance
import upickle.default.ReadWriter

/** One unit of embedding work: the vector key, where it goes and what text it
  * stands for. `sourceRef` points at the Spark row it came from.
  */
final case class EmbeddingWorkItem(
    summaryId: String,
    index: String,
    documentType: String,
    textHash: String,
    templateVersion: String,
    embeddingModel: String,
    sourceRef: String
) derives ReadWriter

/** The manifest-driven work queue (decision E3; SQS is DEFERRED to Phase 12).
  * It lists the work in a fixed order (index, then summary ID), carries no
  * clock and no batch size, and is stored immutably in ordinary S3 under a key
  * that holds a digest of its content. The same input gives the same manifest.
  */
final case class EmbeddingWorkManifest(
    manifestVersion: Int,
    sourceRunId: String,
    sparkTransformVersion: String,
    embeddingModel: String,
    dimension: Int,
    items: Vector[EmbeddingWorkItem],
    provenance: Provenance
) derives ReadWriter

object EmbeddingWorkManifest:
  val Version = 1

  def encode(manifest: EmbeddingWorkManifest): Array[Byte] =
    upickle.default.write(manifest, indent = 2).getBytes(UTF_8)

  def key(manifest: EmbeddingWorkManifest, sha256: String): String =
    ObjectKeys.versioned(
      S3Prefix.SemanticContext,
      "embedding-work",
      "v1",
      manifest.sourceRunId,
      s"manifest-${sha256.take(16)}.json"
    )

  /** Stores the manifest; returns its key and SHA-256. */
  def put(
      store: ObjectStore,
      manifest: EmbeddingWorkManifest
  ): (String, String, PutResult) =
    val bytes = encode(manifest)
    val sha = Hashing.sha256(bytes)
    val objectKey = key(manifest, sha)
    (
      objectKey,
      sha,
      store.putImmutable(
        objectKey,
        bytes,
        s"spark-run:${manifest.sourceRunId}"
      )
    )

  /** Reads a stored manifest and checks it against the digest in its key. */
  def read(store: ObjectStore, objectKey: String): EmbeddingWorkManifest =
    val bytes = store
      .get(objectKey)
      .getOrElse(Fail(ErrorCode.InvalidKey, s"No work manifest at $objectKey"))
    val manifest =
      try upickle.default.read[EmbeddingWorkManifest](new String(bytes, UTF_8))
      catch
        case _: Exception =>
          Fail(
            ErrorCode.MalformedManifest,
            s"$objectKey is not a work manifest"
          )
    Fail.unless(
      objectKey == key(manifest, Hashing.sha256(bytes)),
      ErrorCode.ChecksumMismatch,
      s"$objectKey does not match its content digest"
    )
    manifest

/** One bounded batch: items of one index, in manifest order. */
final case class WorkBatch(
    index: String,
    ordinal: Int,
    items: Vector[EmbeddingWorkItem]
)

object WorkBatches:
  /** SHA-256 over the ordered items of a batch: summary ID, text hash, model
    * version and template version of each. A batch with the same position but
    * other content has another digest.
    */
  def digest(items: Vector[EmbeddingWorkItem]): String =
    Hashing.sha256(
      items
        .map(i =>
          s"${i.summaryId}|${i.textHash}|${i.embeddingModel}|${i.templateVersion}"
        )
        .mkString("\n")
    )

  /** Batches per index in `indexOrder`, each of at most `batchSize` items, in
    * manifest order. `batchSize` stays within the PutVectors limit.
    */
  def of(
      manifest: EmbeddingWorkManifest,
      indexOrder: Vector[String],
      batchSize: Int
  ): Vector[WorkBatch] =
    Fail.unless(
      batchSize >= 1 && batchSize <= Limits.S3Vectors.MaxBatchVectors,
      ErrorCode.BoundExceeded,
      s"Batch size $batchSize is outside 1 to ${Limits.S3Vectors.MaxBatchVectors}"
    )
    indexOrder.flatMap { index =>
      manifest.items
        .filter(_.index == index)
        .grouped(batchSize)
        .zipWithIndex
        .map { case (items, ordinal) => WorkBatch(index, ordinal, items) }
        .toVector
    }

object EmbeddingLedger:
  /** Characters of a batch failure the ledger keeps. 500 cut the S run 7
    * ValidationException before its reason, so the cap is 600.
    */
  val MaxFailureChars = 600

/** RunLedger records of one embedding execution, through the existing RunLedger
  * table design. The run item (`RUN#<executionId>`, `META`) holds the status;
  * each batch has a stage item `STAGE#batch#<index>#<ordinal>`. Items are
  * written with conditional writes: a new item with `attribute_not_exists`, a
  * changed item with the expected `version`, so two writers cannot overwrite
  * each other. A failure is a stored record with its message, never a dropped
  * exception. The execution ID names the run: a rerun with the same ID resumes
  * it (finished batches are skipped); a new ID is a new run over the same work
  * and relies on the unchanged-text skip.
  */
final class EmbeddingLedger(
    table: DynamoTable,
    val executionId: String,
    sourceRunId: String,
    sparkTransformVersion: String
):
  private val pk = s"RUN#$executionId"

  private def upsert(item: Item): Unit =
    val sk = Attr.optS(item, "sk").getOrElse("")
    table.get(pk, sk) match
      case None      => table.putIfAbsent(item + ("version" -> Attr.n(1L)))
      case Some(old) =>
        val version = old("version").num.toLong
        table.putIfVersion(item + ("version" -> Attr.n(version + 1)), version)

  /** Read-only check, made before `begin` and before any batch is skipped or
    * run. A run that exists must have been started with the same work manifest
    * and, for every batch that has a record, the same items. A difference, or a
    * record that holds no digest at all, is `ExecutionInputChanged`: nothing is
    * skipped, embedded or written, and the caller must use a new label.
    */
  def verifyInput(
      manifestSha256: String,
      batches: Vector[(String, Int, String)]
  ): Unit =
    table.get(pk, "META").foreach { meta =>
      val recorded = Attr.optS(meta, "manifestSha256")
      Fail.unless(
        recorded.contains(manifestSha256),
        ErrorCode.ExecutionInputChanged,
        s"Execution $executionId was started with work manifest " +
          s"${recorded.getOrElse("<none recorded>")}, this input is " +
          s"$manifestSha256. Use a new executionLabel for changed input"
      )
      batches.foreach { case (index, ordinal, digest) =>
        table.get(pk, batchSk(index, ordinal)).foreach { record =>
          val old = Attr.optS(record, "itemsDigest")
          Fail.unless(
            old.contains(digest),
            ErrorCode.ExecutionInputChanged,
            s"Execution $executionId batch $index#$ordinal was recorded with " +
              s"items digest ${old.getOrElse("<none recorded>")}, this input " +
              s"has $digest. Use a new executionLabel for changed input"
          )
        }
      }
    }

  def begin(startedAt: String, manifestSha256: String): Unit =
    RunLedger.start(
      table,
      executionId,
      "embedding-ingest",
      sparkTransformVersion,
      startedAt,
      Map("manifestSha256" -> Attr.s(manifestSha256))
    )
    val status = table.get(pk, "META").flatMap(Attr.optS(_, "status"))
    status
      .filter(_ != "STARTED")
      .foreach(old => RunLedger.transition(table, executionId, old, "STARTED"))

  def status: Option[String] =
    table.get(pk, "META").flatMap(Attr.optS(_, "status"))

  private def batchSk(index: String, ordinal: Int): String =
    f"STAGE#batch#$index#$ordinal%06d"

  /** Status and attempt number of a batch, if it was ever started. */
  def batch(index: String, ordinal: Int): Option[(String, Long)] =
    table.get(pk, batchSk(index, ordinal)).map { item =>
      (
        Attr.optS(item, "status").getOrElse(""),
        item.get("attempt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
      )
    }

  private def clean(message: String): String =
    Text
      .maskAccountIds(message.filterNot(_ == Text.Nul))
      .take(EmbeddingLedger.MaxFailureChars)

  def recordBatch(
      index: String,
      ordinal: Int,
      status: String,
      attempt: Long,
      counts: Map[String, Long],
      failure: Option[String],
      itemsDigest: String
  ): Unit =
    val base: Item = Map(
      "pk" -> Attr.s(pk),
      "sk" -> Attr.s(batchSk(index, ordinal)),
      "entityType" -> Attr.s("BATCH"),
      "runId" -> Attr.s(executionId),
      "sourceRunId" -> Attr.s(sourceRunId),
      "index" -> Attr.s(index),
      "ordinal" -> Attr.n(ordinal.toLong),
      "status" -> Attr.s(status),
      "attempt" -> Attr.n(attempt),
      "itemsDigest" -> Attr.s(itemsDigest)
    ) ++ counts.map { case (name, value) => name -> Attr.n(value) } ++
      Attr.provenance(s"spark-run:$sourceRunId", Some(sparkTransformVersion))
    upsert(failure.fold(base)(m => base + ("failure" -> Attr.s(clean(m)))))

  /** Run totals, then the final status of the run item. */
  def finish(
      status: String,
      totals: Map[String, Long],
      notes: Map[String, String] = Map.empty
  ): Unit =
    upsert(
      Map(
        "pk" -> Attr.s(pk),
        "sk" -> Attr.s("STAGE#summary"),
        "entityType" -> Attr.s("STAGE"),
        "runId" -> Attr.s(executionId),
        "sourceRunId" -> Attr.s(sourceRunId),
        "stage" -> Attr.s("summary"),
        "status" -> Attr.s(status)
      ) ++ totals.map { case (name, value) => name -> Attr.n(value) } ++
        notes.map { case (name, value) =>
          name -> Attr.s(value.filterNot(_ == Text.Nul).take(2000))
        } ++
        Attr.provenance(s"spark-run:$sourceRunId", Some(sparkTransformVersion))
    )
    RunLedger.transition(table, executionId, "STARTED", status)
