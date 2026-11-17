package scholarchain.storage

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import scala.util.control.NonFatal
import scholarchain.core.YearWindow

/** Bounds of one export load. Only the tables the read models use are
  * downloaded and held, so the object bound is checked on the whole listing and
  * the byte bound on the listed size of the marker, the manifest and the used
  * tables, before any object is downloaded. Row bounds come from the verified
  * manifest.
  *
  * The default fits tier S (about 3,500 works): 1,000 objects, 128 MiB of
  * export files to load, 250,000 rows in any one table and 1,000,000 rows in
  * all loaded tables. A larger load is refused with `BoundExceeded`; raise the
  * limit deliberately, with the task memory, for a bigger tier.
  */
final case class ExportLimits(
    maxObjects: Int,
    maxBytes: Long,
    maxRowsPerTable: Long,
    maxTotalRows: Long
):
  Fail.unless(
    maxObjects >= 1 && maxBytes >= 1 && maxRowsPerTable >= 1 &&
      maxTotalRows >= 1,
    ErrorCode.BoundExceeded,
    "Every export limit must be at least 1"
  )

  def loadBounds: LoadBounds = LoadBounds(maxRowsPerTable, maxObjects, maxBytes)

object ExportLimits:
  val Default: ExportLimits =
    ExportLimits(1000, 128L * 1024 * 1024, 250000L, 1000000L)

/** What was loaded, for the runtime's start-up log and for provenance: the run,
  * the Spark transform, the export format version, the SHA-256 of the manifest
  * every file was verified against, and the files, rows and bytes per table.
  */
final case class ExportSummary(
    runId: String,
    transformVersion: String,
    exportVersion: String,
    manifestSha256: String,
    exportPrefix: String,
    objects: Int,
    bytes: Long,
    tables: Vector[JsonlExportSource.TableStats],
    worksWithoutTitle: Vector[String],
    skippedTables: Vector[String] = Vector.empty,
    skippedBytes: Long = 0L
)

/** The read models one Spark export of one graph run backs. `metrics` and
  * `workRows` are plain storage data on purpose: `MetricRanking`, `WorkCatalog`
  * and their in-memory implementations belong to rag, which storage cannot
  * depend on, so services builds them with `new InMemoryMetricRanking(metrics)`
  * and `new TableWorkCatalog(workRows)`.
  *
  * `metricCache` is an in-memory `researcher-metric-cache` table filled from
  * the export's `researcher-metrics` (the same rows and the same
  * `DynamoRunLoader.putMetrics` as a DynamoDB load), because the Phase 14
  * loader writes no metric cache. It answers `ResearcherMetricCache.get` pinned
  * to the run.
  */
final case class ExportReadModels(
    paths: PathEvidencePort,
    components: ComponentReadPort,
    communities: CommunityReadPort,
    directory: ResearcherDirectoryPort,
    metrics: Vector[CachedMetrics],
    metricCache: DynamoTable,
    workRows: Vector[Row],
    summary: ExportSummary
)

/** The read models of the live runtime: the export-backed ones and the Neptune
  * query port. `close` releases the Neptune client.
  */
final case class AwsReadModels(
    neptune: NeptuneQueryPort,
    paths: PathEvidencePort,
    components: ComponentReadPort,
    communities: CommunityReadPort,
    directory: ResearcherDirectoryPort,
    metrics: Vector[CachedMetrics],
    metricCache: DynamoTable,
    workRows: Vector[Row],
    summary: ExportSummary,
    close: () => Unit = () => ()
)

/** The settings of `AwsReadModels.live`. No default for an identity: the
  * region, bucket, host and runs come from the caller. `graphRunId` is the
  * Spark `sourceRunId` (`run:v1:<uuid>`), checked against the manifest and
  * pinned on metrics; `exportRunId` is the pipeline slug (`run-v1-<uuid>`) the
  * export is published under, and builds the key prefix. `exportRunId` is last
  * so existing positional callers still compile; its empty default is not a
  * valid slug, so `live` refuses it rather than guess.
  */
final case class AwsReadModelSettings(
    region: String,
    dataBucket: String,
    neptuneHost: String,
    graphRunId: String,
    neptunePort: Int = AwsNeptuneQuery.DefaultPort,
    limits: ExportLimits = ExportLimits.Default,
    timeouts: NeptuneQueryTimeouts = NeptuneQueryTimeouts.Default,
    exportRunId: String = ""
)

/** Loads the read models of one graph run from the Spark JSON-lines export in
  * the data bucket, and builds the Neptune query adapter beside them.
  *
  * Export key layout (the layout `AwsIngestMain` downloads):
  * `semantic-context/runs/<exportRunId>/export/` holding `_SUCCESS`,
  * `export-manifest.json` and `<table>/part-NNNNN.jsonl`. The prefix uses the
  * pipeline slug (`run-v1-<uuid>`), not the Spark run ID (`run:v1:<uuid>`).
  *
  * The export is copied to a private temporary directory and opened with
  * `JsonlExportSource`, so every file is checked against the manifest (size,
  * SHA-256, row count) and `_SUCCESS` is required, exactly as for the loader.
  * The directory is deleted before the call returns. The manifest's
  * `sourceRunId` must equal `graphRunId`, so a row's provenance run ID cannot
  * differ from the run the service was told to answer from.
  */
object AwsReadModels:
  val RequiredTables: Vector[String] = Vector(
    "nodes-researcher",
    "nodes-work",
    "researcher-metrics",
    "path-evidence",
    "components",
    "component-membership",
    "communities",
    "community-membership",
    "community-cross-edges"
  )

  /** Far future (2100-01-01): the in-memory cache needs a TTL value only to
    * satisfy the table's rule.
    */
  val CacheTtlEpochSeconds = 4102444800L

  private val runIdPattern =
    "run:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

  def requireRunId(runId: String): String =
    Fail.unless(
      runId.matches(runIdPattern),
      ErrorCode.InvalidQuery,
      "The graph run ID must have the form run:v1:<uuid>"
    )
    runId

  private val exportRunIdPattern = "run-v1-[0-9a-f-]{36}"

  /** The pipeline's run slug (`run-v1-<uuid>`), the name the export is
    * published under. It differs from the Spark `sourceRunId`.
    */
  def requireExportRunId(exportRunId: String): String =
    Fail.unless(
      exportRunId.matches(exportRunIdPattern),
      ErrorCode.InvalidQuery,
      "The export run ID must have the form run-v1-<uuid>"
    )
    exportRunId

  /** The slug a graph run ID maps to when both name the same run (local and
    * test exports). A deployed export must pass its own slug.
    */
  def slugOf(graphRunId: String): String =
    requireRunId(graphRunId).replaceFirst("^run:v1:", "run-v1-")

  /** The key prefix of the export published under `exportRunId`. */
  def exportPrefixOf(exportRunId: String): String =
    s"${S3Prefix.SemanticContext.dir}/runs/${requireExportRunId(exportRunId)}/export/"

  /** The prefix for a graph run whose slug is `slugOf(graphRunId)`. */
  def exportPrefix(graphRunId: String): String =
    exportPrefixOf(slugOf(graphRunId))

  private def deleteTree(root: Path): Unit =
    try
      if Files.exists(root) then
        Files.walkFileTree(
          root,
          new SimpleFileVisitor[Path]:
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult =
              Files.delete(file)
              FileVisitResult.CONTINUE

            override def postVisitDirectory(
                dir: Path,
                error: IOException
            ): FileVisitResult =
              Files.delete(dir)
              FileVisitResult.CONTINUE
        )
    catch case NonFatal(_) => ()

  /** Lists, bounds, downloads and verifies the export, then runs `build` on the
    * opened source and deletes the copy.
    */
  private final case class Fetched(
      objects: Int,
      bytes: Long,
      skippedTables: Vector[String],
      skippedBytes: Long
  )

  private val SuccessKey = RunDirectory.SuccessMarker

  /** The table a listed export key belongs to (`<table>/part-N.jsonl`). */
  private def tableOfKey(relative: String): Option[String] =
    relative.indexOf('/') match
      case -1 => None
      case at => Some(relative.take(at))

  private def wanted(relative: String, tables: Set[String]): Boolean =
    relative == SuccessKey || relative == JsonlExportSource.ManifestName ||
      tableOfKey(relative).exists(tables.contains)

  private def withSource[A](
      store: ObjectStore,
      prefix: String,
      limits: ExportLimits,
      tempRoot: Option[Path],
      tables: Set[String]
  )(build: (JsonlExportSource, Fetched) => A): A =
    ObjectKeys.requireValid(prefix.stripSuffix("/"))
    val everything = store.listSizes(prefix).filterNot(_._1.endsWith("/"))
    Fail.unless(
      everything.nonEmpty,
      ErrorCode.MissingSuccessMarker,
      s"No export objects under $prefix; the Spark export of this run is " +
        "not in the data bucket"
    )
    Fail.unless(
      everything.size <= limits.maxObjects,
      ErrorCode.BoundExceeded,
      s"${everything.size} export objects under $prefix, bound is " +
        s"${limits.maxObjects}"
    )
    // Only the marker, the manifest and the tables the read models use are
    // downloaded; the bound is on those bytes, known from the listing.
    val (listed, skipped) = everything.partition { case (key, _) =>
      wanted(key.stripPrefix(prefix), tables)
    }
    val present =
      listed.flatMap(e => tableOfKey(e._1.stripPrefix(prefix))).toSet
    val absent = tables.toVector.sorted.filterNot(present.contains)
    Fail.unless(
      absent.isEmpty,
      ErrorCode.MalformedManifest,
      s"The export under $prefix lacks required tables: ${absent.mkString(", ")}"
    )
    val skippedTables =
      skipped.flatMap(e => tableOfKey(e._1.stripPrefix(prefix))).distinct.sorted
    val declared = listed.map(_._2).sum
    Fail.unless(
      declared <= limits.maxBytes,
      ErrorCode.BoundExceeded,
      s"The export tables to load under $prefix hold $declared bytes, bound " +
        s"is ${limits.maxBytes}"
    )
    val work = tempRoot match
      case Some(root) =>
        Files.createDirectories(root)
        Files.createTempDirectory(root, "scholarchain-export-")
      case None => Files.createTempDirectory("scholarchain-export-")
    try
      val base = work.toAbsolutePath.normalize()
      var downloaded = 0L
      listed.foreach { case (key, _) =>
        val path = base.resolve(key.stripPrefix(prefix)).normalize()
        Fail.unless(
          key.startsWith(prefix) && path.startsWith(base) && path != base,
          ErrorCode.InvalidKey,
          s"Export key escapes the download directory: $key"
        )
        val bytes = store
          .get(key)
          .getOrElse(
            Fail(ErrorCode.MissingText, s"Export object $key vanished")
          )
        downloaded += bytes.length.toLong
        Fail.unless(
          downloaded <= limits.maxBytes,
          ErrorCode.BoundExceeded,
          s"The export under $prefix passed ${limits.maxBytes} bytes while " +
            "downloading"
        )
        Files.createDirectories(path.getParent)
        Files.write(path, bytes)
      }
      val source =
        JsonlExportSource.open(base, limits.loadBounds, Some(tables))
      build(
        source,
        Fetched(listed.size, downloaded, skippedTables, skipped.map(_._2).sum)
      )
    finally deleteTree(work)

  /** The export-backed read models of `graphRunId`. */
  def fromExport(
      store: ObjectStore,
      graphRunId: String,
      limits: ExportLimits = ExportLimits.Default,
      tempRoot: Option[Path] = None,
      exportRunId: Option[String] = None
  ): ExportReadModels =
    requireRunId(graphRunId)
    val prefix = exportPrefixOf(exportRunId.getOrElse(slugOf(graphRunId)))
    withSource(store, prefix, limits, tempRoot, RequiredTables.toSet) {
      (source, fetched) =>
        Fail.unless(
          source.sourceRun.runId == graphRunId,
          ErrorCode.MalformedManifest,
          s"The export under $prefix is for run ${source.sourceRun.runId}, " +
            s"not for $graphRunId"
        )
        val missing = RequiredTables.filterNot(source.tableNames.contains)
        Fail.unless(
          missing.isEmpty,
          ErrorCode.MalformedManifest,
          s"The export lacks required tables: ${missing.mkString(", ")}"
        )
        val stats = source.tableStats
        Fail.unless(
          stats.map(_.rows).sum <= limits.maxTotalRows,
          ErrorCode.BoundExceeded,
          s"The export holds ${stats.map(_.rows).sum} rows, bound is " +
            s"${limits.maxTotalRows}"
        )
        build(source, graphRunId, limits, prefix, fetched, stats)
    }

  private def build(
      source: JsonlExportSource,
      runId: String,
      limits: ExportLimits,
      prefix: String,
      fetched: Fetched,
      stats: Vector[JsonlExportSource.TableStats]
  ): ExportReadModels =
    val bounds = limits.loadBounds
    val (cache, metrics) = metricModels(source, runId, bounds)
    val (works, untitled) = workModels(source, bounds)
    ExportReadModels(
      InMemoryPathEvidence.from(source, bounds),
      InMemoryComponents.from(source, bounds),
      InMemoryCommunities.from(source, bounds),
      InMemoryResearcherDirectory.from(source, bounds),
      metrics,
      cache,
      works,
      ExportSummary(
        runId,
        source.sourceRun.transformVersion,
        source.exportVersion,
        source.manifestSha256,
        prefix,
        fetched.objects,
        fetched.bytes,
        stats,
        untitled,
        fetched.skippedTables,
        fetched.skippedBytes
      )
    )

  /** The metric cache and the metrics, from `researcher-metrics`. A row whose
    * provenance names another run is refused: the cache is read pinned to the
    * run, and a value must name the run it came from.
    */
  private def metricModels(
      source: JsonlExportSource,
      runId: String,
      bounds: LoadBounds
  ): (DynamoTable, Vector[CachedMetrics]) =
    bounds.checkRows(
      source.tableStats
        .filter(_.table == "researcher-metrics")
        .map(_.rows)
        .sum,
      "researcher-metrics"
    )
    val cache = new InMemoryDynamoTable(
      ResearcherMetricCache.spec(TableNames("export-read-model"))
    )
    // Rows are folded into the cache one at a time; the table is never held.
    val keys = scala.collection.mutable.LinkedHashSet
      .empty[(String, YearWindow)]
    source.foreachRow("researcher-metrics") { row =>
      val provenanceRun = Rows.provenance(row).runId
      Fail.unless(
        provenanceRun == runId,
        ErrorCode.MalformedRow,
        s"researcher-metrics row of ${Rows.str(row, "researcherId")} names " +
          s"run $provenanceRun, not $runId"
      )
      DynamoRunLoader.putMetrics(cache, row, runId, CacheTtlEpochSeconds)
      keys += (
        (
          Rows.str(row, "researcherId"),
          YearWindow(Rows.int(row, "windowStart"), Rows.int(row, "windowEnd"))
        )
      )
    }
    val metrics = keys.toVector.map { case (id, window) =>
      ResearcherMetricCache
        .get(cache, id, window, Some(runId))
        .getOrElse(
          Fail(ErrorCode.MalformedRow, s"Metrics of $id were not cached")
        )
    }
    (cache, metrics)

  /** The `nodes-work` rows the work catalog reads. A row with no work ID or no
    * provenance is refused, a repeated work ID is refused, and a row with no
    * title is left out and named in the summary: a title is what the catalog
    * answers with, and a work without one cannot support a claim.
    */
  private def workModels(
      source: JsonlExportSource,
      bounds: LoadBounds
  ): (Vector[Row], Vector[String]) =
    val rows = source.rows("nodes-work")
    bounds.checkRows(rows.size.toLong, "nodes-work")
    rows.foreach { row =>
      Rows.str(row, "canonicalWorkId")
      Rows.provenance(row)
    }
    val ids = rows.map(Rows.str(_, "canonicalWorkId"))
    Fail.unless(
      ids.distinct.size == ids.size,
      ErrorCode.DuplicateId,
      "nodes-work repeats a work ID"
    )
    val (titled, untitled) =
      rows.partition(row => Rows.optStr(row, "title").exists(_.nonEmpty))
    (titled, untitled.map(Rows.str(_, "canonicalWorkId")))

  /** The read models with an explicit query port: the export-backed models of
    * `exported` plus `neptune`.
    */
  def assemble(
      exported: ExportReadModels,
      neptune: NeptuneQueryPort,
      close: () => Unit = () => ()
  ): AwsReadModels =
    AwsReadModels(
      neptune,
      exported.paths,
      exported.components,
      exported.communities,
      exported.directory,
      exported.metrics,
      exported.metricCache,
      exported.workRows,
      exported.summary,
      close
    )

  /** The one constructor the runtime calls. Reads the export from the data
    * bucket (S3 `ListObjectsV2` and `GetObject` on the export prefix), builds
    * the Neptune adapter for `https://<host>:<port>` and returns both. No
    * Neptune call is made here. The S3 client is closed before the call
    * returns; `close()` releases the Neptune client.
    *
    * Credentials are the SDK default provider chain (the ECS task role). No
    * secret is read or held.
    */
  def live(
      settings: AwsReadModelSettings,
      log: String => Unit = _ => ()
  ): AwsReadModels =
    requireRunId(settings.graphRunId)
    requireExportRunId(settings.exportRunId)
    val endpoint =
      AwsNeptuneQuery.endpoint(settings.neptuneHost, settings.neptunePort)
    val clients = AwsClients.forRegion(settings.region)
    val s3 = clients.s3()
    val exported =
      try
        fromExport(
          new AwsObjectStore(
            s3,
            settings.dataBucket,
            settings.limits.maxObjects
          ),
          settings.graphRunId,
          settings.limits,
          exportRunId = Some(settings.exportRunId)
        )
      finally
        try s3.close()
        catch case NonFatal(_) => ()
    val neptune =
      AwsNeptuneQuery.open(clients, endpoint, settings.timeouts, log)
    assemble(exported, neptune, () => neptune.close())
