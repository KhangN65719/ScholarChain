package scholarchain.storage

import scala.collection.mutable
import scala.util.control.NonFatal

final case class IngestConfig(
    startedAt: String,
    batchSize: Int = Limits.S3Vectors.MaxBatchVectors,
    retry: RetryPolicy = RetryPolicy(5, 200, 5000),
    bounds: LoadBounds = LoadBounds.Default,
    distanceMetric: String = "cosine",
    executionLabel: String = "1"
)

final case class IndexIngestReport(
    index: String,
    planned: Int,
    written: Int,
    skippedUnchanged: Int,
    skippedResumed: Int,
    rejected: Int,
    duplicates: Int,
    retries: Int,
    batches: Int,
    failedBatches: Int,
    vectorsInIndex: Int,
    metadataDrift: Int = 0
)

final case class BatchTiming(
    index: String,
    ordinal: Int,
    items: Int,
    millis: Long
)

final case class FailedBatch(index: String, ordinal: Int, reason: String)

/** Wall-clock timings of an in-process run against the local fakes. They are
  * LOCAL numbers, kept so the Phase 12 AWS run can be compared with them.
  */
final case class LocalTimings(
    label: String,
    totalMillis: Long,
    batches: Vector[BatchTiming]
):
  def vectorsPerSecond(written: Int): Double =
    if totalMillis <= 0 then 0.0 else written * 1000.0 / totalMillis

final case class IngestReport(
    executionId: String,
    manifestKey: String,
    manifestSha256: String,
    manifestItems: Int,
    perIndex: Vector[IndexIngestReport],
    unmapped: Vector[RejectedVector],
    rejected: Vector[RejectedVector],
    rejectedKeys: Vector[String],
    failedBatches: Vector[FailedBatch],
    documentsCreated: Int,
    documentsUnchanged: Int,
    documentObjects: Int,
    documentBytes: Long,
    timings: LocalTimings,
    metadataDrift: Int = 0,
    metadataDriftKeys: Vector[String] = Vector.empty
):
  def written: Int = perIndex.map(_.written).sum
  def skipped: Int =
    perIndex.map(i => i.skippedUnchanged + i.skippedResumed).sum
  def duplicates: Int = perIndex.map(_.duplicates).sum
  def retries: Int = perIndex.map(_.retries).sum

object LocalTimings:
  val Label = "LOCAL: in-process fakes, not AWS"

/** Runs one JSON-lines export through the embedding work manifest into a vector
  * bucket, an object store and a RunLedger table. The library entry point that
  * the rag module's `IngestMain` calls. Everything here is local: the AWS S3
  * Vectors adapter, SQS and the bounded AWS ingestion are DEFERRED to Phase 12.
  *
  * Order of work: render, hash and plan every row; store the work manifest; for
  * each index and batch, store the documents in ordinary S3, skip vectors whose
  * text hash and model version are unchanged, refuse a changed one, embed and
  * write the rest. The document is stored before its vector, so every written
  * vector's `textRef` resolves.
  */
final class EmbeddingIngest(
    embedder: Embedder,
    renderer: SummaryRenderer,
    bucket: VectorBucketPort,
    store: ObjectStore,
    ledgerTable: DynamoTable,
    sleeper: Sleeper = NoSleep,
    nanoClock: () => Long = () => System.nanoTime(),
    progress: String => Unit = _ => ()
):
  /** Progress never changes the outcome of a run: a callback that throws is
    * ignored. Lines carry counts and index names only.
    */
  private def emit(line: => String): Unit =
    try progress(line)
    catch case NonFatal(_) => ()

  private final case class Planned(
      item: EmbeddingWorkItem,
      row: Row,
      document: RenderedDocument,
      runId: String
  )

  private final case class Plan(
      planned: Vector[Planned],
      rejected: Vector[RejectedVector],
      unmapped: Vector[RejectedVector],
      duplicates: Map[String, Int]
  )

  private def plan(
      rows: Vector[Row],
      specs: Vector[VectorIndexSpec],
      source: SourceRun
  ): Plan =
    val byType = specs.flatMap(i => i.summaryType.map(_ -> i)).toMap
    val accepted = mutable.LinkedHashMap.empty[(String, String), Planned]
    val rejected = Vector.newBuilder[RejectedVector]
    val unmapped = Vector.newBuilder[RejectedVector]
    val duplicates = mutable.Map.empty[String, Int].withDefaultValue(0)
    rows.foreach { row =>
      val id = Rows.optStr(row, "summaryId").getOrElse("<no summaryId>")
      try
        val summaryId = Rows.str(row, "summaryId")
        val kind = Rows.str(row, "summaryType")
        byType.get(kind) match
          case None =>
            unmapped += RejectedVector(
              "none",
              summaryId,
              ErrorCode.UnmappedSummary,
              s"No index takes summary type $kind"
            )
          case Some(spec) =>
            VectorKeys.validate(summaryId)
            val runId = Rows.provenance(row).runId
            Fail.unless(
              runId == source.runId,
              ErrorCode.MalformedRow,
              s"$summaryId carries run $runId, the export is of ${source.runId}"
            )
            val document = renderer.render(row)
            Text.requireNoNul(document.text, "Document text")
            val hash = Hashing.sha256(document.text)
            val item = EmbeddingWorkItem(
              summaryId,
              spec.name,
              kind,
              hash,
              document.templateVersion,
              embedder.modelVersion,
              SemanticDocuments.sourceRef(runId, summaryId)
            )
            accepted.get((spec.name, summaryId)) match
              case None =>
                accepted.update(
                  (spec.name, summaryId),
                  Planned(item, row, document, runId)
                )
              case Some(first) if first.item == item =>
                duplicates.update(spec.name, duplicates(spec.name) + 1)
              case Some(_) =>
                rejected += RejectedVector(
                  spec.name,
                  summaryId,
                  ErrorCode.DuplicateVectorKey,
                  "same summary ID with different text; the first is kept"
                )
      catch
        case error: StorageException =>
          rejected += RejectedVector("none", id, error.code, error.getMessage)
    }
    val order = specs.map(_.name).zipWithIndex.toMap
    Plan(
      accepted.values.toVector.sortBy(p =>
        (order(p.item.index), p.item.summaryId)
      ),
      rejected.result(),
      unmapped.result(),
      duplicates.toMap
    )

  private final case class BatchResult(
      written: Int,
      skippedUnchanged: Int,
      rejected: Vector[RejectedVector],
      duplicates: Int,
      retries: Int,
      documentsCreated: Int,
      documentsUnchanged: Int,
      metadataDrift: Vector[String]
  )

  private def storedHash(record: VectorRecord): String =
    record.metadata.get("textHash") match
      case Some(MetaValue.Str(v)) => v
      case _                      => "none"

  private def storedModel(record: VectorRecord): String =
    record.metadata.get("embeddingModel") match
      case Some(MetaValue.Str(v)) => v
      case _                      => "none"

  private def runBatch(
      spec: VectorIndexSpec,
      batch: Vector[Planned],
      writer: VectorWriter,
      facets: SummaryFacets
  ): BatchResult =
    var docsCreated = 0
    var docsUnchanged = 0
    def putDocument(p: Planned): Unit =
      val (_, put) = SemanticDocuments.put(
        store,
        p.runId,
        p.item.summaryId,
        p.item.documentType,
        p.document,
        p.item.textHash
      )
      if put.created then docsCreated += 1 else docsUnchanged += 1
    val stored = batch
      .map(_.item.summaryId)
      .grouped(Limits.S3Vectors.MaxGetBatch)
      .flatMap(keys => bucket.getVectors(spec.name, keys))
      .map(r => r.key -> r)
      .toMap
    val rejected = Vector.newBuilder[RejectedVector]
    val drift = Vector.newBuilder[String]
    var unchanged = 0
    val fresh = Vector.newBuilder[VectorRecord]
    var embedAttempts = 0
    // Items with no stored vector are the only ones that reach the embedder,
    // so this is the denominator a long batch can actually reach.
    val toEmbed = batch.count(p => !stored.contains(p.item.summaryId))
    def expectedMetadata(p: Planned): Map[String, MetaValue] =
      SemanticVectorLoader.metadataFor(
        p.row,
        embedder.modelVersion,
        SemanticDocuments.key(
          p.runId,
          p.item.summaryId,
          p.item.textHash,
          p.item.templateVersion
        ),
        facets
      ) ++ Map(
        "textHash" -> MetaValue.Str(p.item.textHash),
        "templateVersion" -> MetaValue.Str(p.item.templateVersion)
      )
    batch.foreach { p =>
      val id = p.item.summaryId
      stored.get(id) match
        case Some(old)
            if storedHash(old) == p.item.textHash &&
              storedModel(old) == p.item.embeddingModel =>
          try
            // Nothing is rewritten (D7). Metadata the row would now produce
            // differently is only reported, as drift.
            val expected = expectedMetadata(p)
            val differing = (expected.keySet ++ old.metadata.keySet).toVector
              .filter(k => expected.get(k) != old.metadata.get(k))
              .sorted
            if differing.nonEmpty then
              drift += s"$id[${differing.mkString(",")}]"
            // The document is checked too: a missing one is put back, but only
            // at the key this vector's textRef names, so a template bump
            // leaves no unreferenced document behind.
            if !differing.contains("textRef") then putDocument(p)
            unchanged += 1
          catch
            case error: StorageException =>
              rejected += RejectedVector(
                spec.name,
                id,
                error.code,
                error.getMessage
              )
        case Some(old) =>
          rejected += RejectedVector(
            spec.name,
            id,
            ErrorCode.ChangedVectorContent,
            s"key holds textHash ${storedHash(old).take(12)} and model " +
              s"${storedModel(old)}; refused ${p.item.textHash.take(12)} and " +
              s"${p.item.embeddingModel}. A changed text or model needs a new key " +
              "or a new index version"
          )
        case None =>
          try
            val embedding =
              try embedder.embed(p.document.text)
              finally
                embedAttempts += 1
                if embedAttempts % EmbeddingIngest.BatchProgressEvery == 0 then
                  emit(
                    s"LOADER_PROGRESS index=${spec.name} " +
                      s"batch_items=$embedAttempts/$toEmbed"
                  )
            val metadata = expectedMetadata(p)
            VectorMetadataIngest.validate(metadata)
            putDocument(p)
            fresh += VectorRecord(id, embedding, metadata)
          catch
            case error: StorageException =>
              rejected += RejectedVector(
                spec.name,
                id,
                error.code,
                error.getMessage
              )
    }
    val report = writer.write(spec, fresh.result())
    BatchResult(
      report.written,
      unchanged,
      rejected.result() ++ report.rejected,
      report.duplicatesCollapsed,
      report.retries,
      docsCreated,
      docsUnchanged,
      drift.result()
    )

  /** Ingests the `semantic-summaries` of `tables`. `executionLabel` and the
    * source run name the ledger run: the same label resumes it.
    */
  def run(
      tables: TableSource,
      source: SourceRun,
      config: IngestConfig
  ): IngestReport =
    val started = nanoClock()
    val rows = tables.rows("semantic-summaries")
    config.bounds.checkRows(rows.size.toLong, "semantic-summaries")
    val facets = SummaryFacets.fromTables(tables, config.bounds)
    val specs = VectorIndexes.all(embedder.dimension, config.distanceMetric)
    val planned = plan(rows, specs, source)
    val manifest = EmbeddingWorkManifest(
      EmbeddingWorkManifest.Version,
      source.runId,
      source.transformVersion,
      embedder.modelVersion,
      embedder.dimension,
      planned.planned.map(_.item),
      StorageProvenance.forRun(
        source.runId,
        "embedding-work-manifest",
        source.runId
      )
    )
    val manifestSha = Hashing.sha256(EmbeddingWorkManifest.encode(manifest))
    val batches =
      WorkBatches.of(manifest, specs.map(_.name), config.batchSize)
    val byId =
      planned.planned.map(p => (p.item.index, p.item.summaryId) -> p).toMap
    val executionId = s"embed:${source.runId}:${config.executionLabel}"
    val ledger = new EmbeddingLedger(
      ledgerTable,
      executionId,
      source.runId,
      source.transformVersion
    )
    val digests = batches.map(b => WorkBatches.digest(b.items))
    // Before anything is started, skipped, embedded or written.
    ledger.verifyInput(
      manifestSha,
      batches.zip(digests).map((b, d) => (b.index, b.ordinal, d))
    )
    specs.foreach(bucket.ensureIndex)
    val (manifestKey, _, _) = EmbeddingWorkManifest.put(store, manifest)
    ledger.begin(config.startedAt, manifestSha)
    val writer =
      new VectorWriter(bucket, config.retry, sleeper, config.batchSize)
    val rejected = Vector.newBuilder[RejectedVector]
    rejected ++= planned.rejected
    val failed = Vector.newBuilder[FailedBatch]
    val timings = Vector.newBuilder[BatchTiming]
    var created = 0
    var unchangedDocs = 0
    val totals = mutable.LinkedHashMap.empty[String, Array[Int]]
    val driftKeys = Vector.newBuilder[String]
    // written, unchanged, resumed, rejected, duplicates, retries, batches,
    // failed, metadata drift
    specs.foreach(spec =>
      totals.update(
        spec.name,
        Array(
          0,
          0,
          0,
          0,
          planned.duplicates.getOrElse(spec.name, 0),
          0,
          0,
          0,
          0
        )
      )
    )
    val totalItems = planned.planned.size
    val batchesPerIndex = batches.groupBy(_.index).view.mapValues(_.size).toMap
    var itemsDone = 0
    emit(s"LOADER_PROGRESS start items=$totalItems batches=${batches.size}")
    batches.zip(digests).foreach { (work, digest) =>
      val t = totals(work.index)
      val spec = specs.find(_.name == work.index).get
      val items = work.items.map(i => byId((i.index, i.summaryId)))
      t(6) += 1
      val prior = ledger.batch(work.index, work.ordinal)
      if prior.exists(_._1 == "FINISHED") then t(2) += items.size
      else
        val attempt = prior.map(_._2).getOrElse(0L) + 1
        ledger.recordBatch(
          work.index,
          work.ordinal,
          "STARTED",
          attempt,
          Map("items" -> items.size.toLong),
          None,
          digest
        )
        val begun = nanoClock()
        try
          val result = runBatch(spec, items, writer, facets)
          val millis = (nanoClock() - begun) / 1000000L
          t(0) += result.written
          t(1) += result.skippedUnchanged
          t(3) += result.rejected.size
          t(4) += result.duplicates
          t(5) += result.retries
          t(8) += result.metadataDrift.size
          driftKeys ++= result.metadataDrift
          created += result.documentsCreated
          unchangedDocs += result.documentsUnchanged
          rejected ++= result.rejected
          timings += BatchTiming(work.index, work.ordinal, items.size, millis)
          ledger.recordBatch(
            work.index,
            work.ordinal,
            if result.rejected.isEmpty then "FINISHED"
            else "FINISHED_WITH_REJECTS",
            attempt,
            Map(
              "items" -> items.size.toLong,
              "written" -> result.written.toLong,
              "skippedUnchanged" -> result.skippedUnchanged.toLong,
              "rejected" -> result.rejected.size.toLong,
              "duplicates" -> result.duplicates.toLong,
              "retries" -> result.retries.toLong,
              "metadataDrift" -> result.metadataDrift.size.toLong,
              "millis" -> millis
            ),
            result.rejected.headOption.map(r =>
              s"${result.rejected.size} rejected, first ${r.key}: ${r.code}"
            ),
            digest
          )
        catch
          case NonFatal(error) =>
            t(7) += 1
            val reason = s"${error.getClass.getSimpleName}: ${error.getMessage}"
            failed += FailedBatch(work.index, work.ordinal, reason)
            ledger.recordBatch(
              work.index,
              work.ordinal,
              "FAILED",
              attempt,
              Map("items" -> items.size.toLong),
              Some(reason),
              digest
            )
      itemsDone += items.size
      emit(
        s"LOADER_PROGRESS index=${work.index} " +
          s"batch=${work.ordinal + 1}/${batchesPerIndex(work.index)} " +
          s"items_done=$itemsDone/$totalItems " +
          s"written=${totals.values.map(_(0)).sum} " +
          s"elapsed_s=${(nanoClock() - started) / 1000000000L} " +
          s"skipped=${totals.values.map(t => t(1) + t(2)).sum} " +
          s"failed_batches=${totals.values.map(_(7)).sum}"
      )
    }
    val unmappedKeys = planned.unmapped
    val allRejected = rejected.result()
    val rejectedKeys = (allRejected ++ unmappedKeys)
      .groupBy(_.index)
      .toVector
      .sortBy(_._1)
      .flatMap { case (index, list) =>
        RejectedVectors.persist(store, source.runId, index, list)
      }
    val failures = failed.result()
    val perIndex = specs.map { spec =>
      val t = totals(spec.name)
      IndexIngestReport(
        spec.name,
        planned.planned.count(_.item.index == spec.name),
        t(0),
        t(1),
        t(2),
        t(3),
        t(4),
        t(5),
        t(6),
        t(7),
        bucket.count(spec.name),
        t(8)
      )
    }
    val drifted = driftKeys.result()
    // Key and size only: on AWS `list` makes one HEAD per document, and this
    // prefix holds the documents of every run.
    val stored = store.listSizes(SemanticDocuments.prefix)
    val status =
      if failures.nonEmpty then "FAILED"
      else if allRejected.nonEmpty then "COMPLETED_WITH_REJECTS"
      else "COMPLETED"
    ledger.finish(
      status,
      Map(
        "items" -> planned.planned.size.toLong,
        "written" -> perIndex.map(_.written).sum.toLong,
        "skipped" -> perIndex
          .map(i => i.skippedUnchanged + i.skippedResumed)
          .sum
          .toLong,
        "rejected" -> allRejected.size.toLong,
        "unmapped" -> unmappedKeys.size.toLong,
        "duplicates" -> perIndex.map(_.duplicates).sum.toLong,
        "retries" -> perIndex.map(_.retries).sum.toLong,
        "failedBatches" -> failures.size.toLong,
        "metadataDrift" -> drifted.size.toLong
      ),
      Map(
        "metadataDriftKeys" -> drifted
          .take(EmbeddingIngest.MaxDriftKeys)
          .mkString(";")
      ).filter(_._2.nonEmpty)
    )
    IngestReport(
      executionId,
      manifestKey,
      manifestSha,
      planned.planned.size,
      perIndex,
      unmappedKeys,
      allRejected,
      rejectedKeys,
      failures,
      created,
      unchangedDocs,
      stored.size,
      stored.map(_._2).sum,
      LocalTimings(
        LocalTimings.Label,
        (nanoClock() - started) / 1000000L,
        timings.result()
      ),
      drifted.size,
      drifted.take(EmbeddingIngest.MaxDriftKeys)
    )

object EmbeddingIngest:
  /** How many drifted keys the report and the ledger summary list. */
  val MaxDriftKeys = 20

  /** A long batch reports `batch_items=<n>/<toEmbed>` after every this many
    * embedding attempts. `n` counts embed attempts (a failed one counts) and
    * `toEmbed` is the number of items of the batch with no stored vector, so a
    * batch that is half skipped still reaches its denominator.
    */
  val BatchProgressEvery = 100

  /** Follows the `textRef` of a vector result to its document and verifies the
    * hash. See `SemanticDocuments.fetch`.
    */
  def fetchText(store: ObjectStore, hit: VectorMatch): FetchedText =
    SemanticDocuments.fetch(store, hit)
