package scholarchain.rag

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal
import scholarchain.storage.*

/** An `Embedder` that counts its calls, so the report states how many Titan
  * calls the run made.
  */
final class CountingEmbedder(inner: Embedder) extends Embedder:
  private val count = new AtomicLong(0L)
  val modelVersion: String = inner.modelVersion
  val dimension: Int = inner.dimension

  def embed(text: String): Vector[Float] =
    count.incrementAndGet()
    inner.embed(text)

  def calls: Long = count.get

/** The AWS loader entry point, run as `java -cp <lib directory wildcard>
  * scholarchain.rag.AwsIngestMain <runId>` with the jars under
  * /opt/scholarchain/lib on the class path.
  *
  * It downloads the export prefix `semantic-context/runs/<runId>/export/` from
  * the data bucket, runs `IngestMain.execute` against the AWS backend (Titan
  * 256, S3 Vectors, S3, DynamoDB), runs the graph step when the export holds
  * the tables the projector reads, writes `ingest-report.json` and
  * `ingest-run.json` to `reports/runs/<runId>/loader/` and prints greppable
  * markers. The exit code is nonzero on any failure.
  *
  * This object is the only place that reads the process environment (`main`
  * passes `sys.env` on); every other function takes the configuration as a
  * value. No credential is read, held or printed: clients come from
  * `AwsClients`, whose credentials are the SDK default provider chain.
  *
  * Markers (one per line): `LOADER_INDEX`, `LOADER_TOTAL`, `LOADER_EMBEDDING`,
  * `NEPTUNE_LOAD=LOADED|SKIPPED|FAILED`, `LOADER_REPORT`, and exactly one
  * `LOADER_RESULT=SUCCESS|FAILED` last. The ingest step's own result line is
  * renamed `INGEST_STEP=`, so a later graph failure never follows a line that
  * reads SUCCESS in the same form.
  */
object AwsIngestMain:
  val EnvRegion = "AWS_REGION"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"
  val EnvNeptuneHost = "SCHOLARCHAIN_NEPTUNE_HOST"
  val EnvLoadRoleArn = "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
  val DefaultNeptunePort = 8182
  val Usage = "usage: AwsIngestMain <runId>"

  /** The objects the loader lists under one run's documents and exports. */
  val MaxListedObjects = 100000

  val ReportVersion = "scholarchain.rag.aws-ingest-report/1"

  final case class Config(
      region: String,
      dataBucket: String,
      vectorBucket: String,
      tablePrefix: String,
      neptuneHost: Option[String],
      neptunePort: Int,
      loadRoleArn: Option[String],
      runId: String
  ):
    /** True when Neptune is part of the session: the graph step is required. */
    def graphRequired: Boolean = neptuneHost.isDefined

    def exportPrefix: String =
      s"${S3Prefix.SemanticContext.dir}/runs/$runId/export/"
    def reportBase: String = s"${S3Prefix.Reports.dir}/runs/$runId/loader"

  /** What `awsWiring` (or a test) supplies: the backend and a way to close the
    * clients it holds.
    */
  final case class Wiring(
      backend: IngestMain.Backend,
      close: () => Unit = () => ()
  )

  private def refuse(message: String): Nothing =
    Fail(ErrorCode.InvalidQuery, message)

  private def required(env: Map[String, String], name: String): String =
    env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
      refuse(s"Missing required environment variable $name")
    }

  private def checked(name: String, value: String, pattern: String): String =
    // The message names the variable and never the value.
    if value.matches(pattern) then value
    else refuse(s"Environment variable $name has an invalid format")

  private val bucketPattern = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"
  private val regionPattern = "[a-z]{2}(-[a-z]+)+-[0-9]{1,2}"
  private val prefixPattern = "[A-Za-z0-9_.-]{1,64}"
  private val hostPattern = "[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?"
  private val runIdPattern = "[A-Za-z0-9._=:#-]{1,200}"

  private def hostAndPort(value: String): (String, Int) =
    val parts = value.split(":", -1).toVector
    parts match
      case Vector(host) =>
        (checked(EnvNeptuneHost, host, hostPattern), DefaultNeptunePort)
      case Vector(host, port) =>
        val number = port.toIntOption.filter(p => p >= 1 && p <= 65535)
        (
          checked(EnvNeptuneHost, host, hostPattern),
          number.getOrElse(
            refuse(s"Environment variable $EnvNeptuneHost has an invalid port")
          )
        )
      case _ =>
        refuse(s"Environment variable $EnvNeptuneHost has an invalid format")

  /** Pure over its arguments: `env` is the environment as a map. Names the
    * missing or malformed variable, never its value.
    */
  def parseConfig(args: Array[String], env: Map[String, String]): Config =
    Fail.unless(args.length == 1, ErrorCode.InvalidQuery, Usage)
    val runId = args(0).trim
    Fail.unless(
      runId.matches(runIdPattern),
      ErrorCode.InvalidQuery,
      s"The run id argument is not valid. $Usage"
    )
    // An absent host variable means a vectors-only run. A host variable that
    // is set but blank is a configuration error, never a silent skip. The
    // load role is required exactly when a host is set.
    val neptune = env.get(EnvNeptuneHost).map { _ =>
      val (host, port) = hostAndPort(required(env, EnvNeptuneHost))
      val role = required(env, EnvLoadRoleArn)
      Fail.unless(
        role.startsWith("arn:") && role.contains(":role/"),
        ErrorCode.InvalidQuery,
        s"Environment variable $EnvLoadRoleArn is not an IAM role ARN"
      )
      (host, port, role)
    }
    Config(
      checked(EnvRegion, required(env, EnvRegion), regionPattern),
      checked(EnvDataBucket, required(env, EnvDataBucket), bucketPattern),
      checked(EnvVectorBucket, required(env, EnvVectorBucket), bucketPattern),
      checked(EnvTablePrefix, required(env, EnvTablePrefix), prefixPattern),
      neptune.map(_._1),
      neptune.fold(DefaultNeptunePort)(_._2),
      neptune.map(_._3),
      runId
    )

  /** Builds every client through `AwsClients` (a region and nothing else), the
    * adapters over them, and the AWS backend. Building a client contacts
    * nothing.
    */
  def awsWiring(
      config: Config,
      clientsFor: String => AwsClients = AwsClients.forRegion
  ): Wiring =
    val clients = clientsFor(config.region)
    val s3 = clients.s3()
    val vectors = clients.s3Vectors()
    val bedrock = clients.bedrockRuntime()
    val neptune = config.neptuneHost.map { host =>
      clients.neptune(URI.create(s"https://$host:${config.neptunePort}"))
    }
    val dynamo = clients.dynamo()
    val store = new AwsObjectStore(s3, config.dataBucket, MaxListedObjects)
    val embedder = new CountingEmbedder(new TitanEmbedder(bedrock))
    val spec =
      try RunLedger.spec(TableNames(config.tablePrefix))
      catch
        case _: StorageException =>
          refuse(s"Environment variable $EnvTablePrefix is not usable")
    val ledger = new AwsDynamoTable(dynamo, spec)
    val loader = for
      client <- neptune
      role <- config.loadRoleArn
    yield new AwsNeptuneBulkLoader(
      client,
      store,
      config.dataBucket,
      config.region,
      role
    )
    val backend = IngestMain.Backend(
      embedder,
      EmbeddingModels.TitanTextV2D256,
      store,
      new AwsVectorBucket(vectors, config.vectorBucket),
      ledger,
      graph = loader.map(l => GraphLoad(l)),
      aws = true,
      label = IngestMain.AwsLabel,
      sleeper = ThreadSleeper,
      embedCalls = () => Some(embedder.calls)
    )
    Wiring(
      backend,
      () =>
        (Vector(s3, vectors, bedrock, dynamo) ++ neptune.toVector).foreach {
          client =>
            try client.close()
            catch case NonFatal(_) => ()
        }
    )

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

  /** Copies every object under the export prefix to `target`, keeping the
    * relative layout. An empty prefix is a missing export.
    */
  def downloadExport(
      store: ObjectStore,
      config: Config,
      target: Path
  ): Int =
    val prefix = config.exportPrefix
    ObjectKeys.requireValid(prefix.stripSuffix("/"))
    val keys = store.listKeys(prefix).filterNot(_.endsWith("/"))
    Fail.unless(
      keys.nonEmpty,
      ErrorCode.MissingSuccessMarker,
      s"No export objects under $prefix"
    )
    val base = target.toAbsolutePath.normalize()
    keys.foreach { key =>
      val path = base.resolve(key.stripPrefix(prefix)).normalize()
      Fail.unless(
        path.startsWith(base) && path != base,
        ErrorCode.InvalidKey,
        s"Export key escapes the download directory: $key"
      )
      val bytes = store
        .get(key)
        .getOrElse(Fail(ErrorCode.MissingText, s"Export object $key vanished"))
      Files.createDirectories(path.getParent)
      Files.write(path, bytes)
    }
    keys.size

  private def compactStamp(at: Instant): String =
    DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(ZoneOffset.UTC)
      .format(at)

  private def putReport(
      store: ObjectStore,
      base: String,
      name: String,
      bytes: Array[Byte],
      stamp: String,
      ref: String
  ): String =
    // Report keys are immutable. A rerun of a run id writes different counts,
    // so a different report goes beside the first under a timestamp, and the
    // first is never overwritten.
    val key = s"$base/$name"
    val chosen = store.head(key) match
      case Some(existing) if existing.sha256 != Hashing.sha256(bytes) =>
        s"$base/$stamp/$name"
      case _ => key
    store.putImmutable(chosen, bytes, ref)
    chosen

  private def perIndexLines(report: ujson.Value): Vector[String] =
    report("perIndex").obj.toVector.map { case (name, i) =>
      def n(key: String): Long = i(key).num.toLong
      val noSource =
        name == VectorIndexes.TopicInstitutionSummary && n("planned") == 0L
      s"LOADER_INDEX name=$name planned=${n("planned")} " +
        s"written=${n("written")} skipped_unchanged=${n("skippedUnchanged")} " +
        s"skipped_resumed=${n("skippedResumed")} rejected=${n("rejected")} " +
        s"duplicates=${n("duplicates")} retries=${n("retries")} " +
        s"failed_batches=${n("failedBatches")} " +
        s"vectors_in_index=${n("vectorsInIndex")}" +
        (if noSource then
           " status=NO_SOURCE (no Spark summary feeds this index)"
         else "")
    }

  private def graphJson(result: GraphStep.Result): ujson.Value = result match
    case GraphStep.Result.Skipped(reason, missing) =>
      ujson.Obj(
        "status" -> "SKIPPED",
        "reason" -> reason,
        "missingTables" -> ujson.Arr.from(missing.map(ujson.Str(_)))
      )
    case GraphStep.Result.Failed(reason) =>
      ujson.Obj("status" -> "FAILED", "reason" -> reason)
    case GraphStep.Result.Loaded(exported, load) =>
      ujson.Obj(
        "status" -> "LOADED",
        "vertices" -> exported.vertexCount,
        "edges" -> exported.edgeCount,
        "normalizedValues" -> exported.normalizedValues,
        "manifestKey" -> exported.manifestKey,
        "files" -> ujson.Arr.from(exported.files.map { f =>
          ujson.Obj(
            "key" -> f.key,
            "kind" -> f.kind,
            "label" -> f.label,
            "rows" -> f.rows
          )
        }),
        "inserted" -> load.inserted,
        "unchanged" -> load.unchanged,
        "jobs" -> ujson.Arr.from(load.jobs.map(GraphStep.jobJson))
      )

  private def graphLines(result: GraphStep.Result): Vector[String] =
    result match
      case GraphStep.Result.Skipped(reason, _) =>
        Vector(
          s"NEPTUNE_LOAD=SKIPPED reason=$reason",
          "LOADER_SCOPE=vectors-only (the graph was NOT loaded)"
        )
      case GraphStep.Result.Failed(reason) =>
        Vector(s"NEPTUNE_LOAD=FAILED reason=$reason")
      case GraphStep.Result.Loaded(exported, load) =>
        Vector(
          s"NEPTUNE_LOAD=LOADED vertices=${exported.vertexCount} " +
            s"edges=${exported.edgeCount} files=${exported.files.size} " +
            s"jobs=${load.jobs.size}"
        ) ++ load.jobs.map { j =>
          s"NEPTUNE_JOB source=${j.source} status=${j.status} " +
            s"records=${j.counts.totalRecords} " +
            s"duplicates=${j.counts.totalDuplicates} errors=${j.counts.errors}"
        } :+ "LOADER_SCOPE=vectors-and-graph"

  private def readJson(path: Path): Option[ujson.Obj] =
    if Files.isRegularFile(path) then
      try
        ujson.read(Files.readAllBytes(path)).objOpt.map(o => ujson.Obj.from(o))
      catch case NonFatal(_) => None
    else None

  /** Runs the whole loader and returns the exit code: 0 for SUCCESS, 2 for a
    * configuration error, 1 for any other failure. Prints exactly one
    * `LOADER_RESULT=` line, last.
    */
  def run(
      args: Array[String],
      env: Map[String, String],
      out: String => Unit,
      wiringFor: Config => Wiring = c => awsWiring(c),
      clock: () => Instant = () => Instant.now()
  ): Int =
    val began = System.nanoTime()
    def finish(success: Boolean, reason: String, code: Int): Int =
      out(
        if success then "LOADER_RESULT=SUCCESS"
        else
          s"LOADER_RESULT=FAILED reason=${reason.linesIterator.nextOption().getOrElse("").take(500)}"
      )
      if success then 0 else code
    val config =
      try Right(parseConfig(args, env))
      catch case error: StorageException => Left(error.getMessage)
    config match
      case Left(message) => finish(false, message, 2)
      case Right(cfg)    =>
        runConfigured(cfg, out, wiringFor, clock, began, finish)

  private def runConfigured(
      config: Config,
      out: String => Unit,
      wiringFor: Config => Wiring,
      clock: () => Instant,
      began: Long,
      finish: (Boolean, String, Int) => Int
  ): Int =
    val work = Files.createTempDirectory("scholarchain-loader-")
    var close: () => Unit = () => ()
    var wired: Option[ObjectStore] = None
    try
      val wiring = wiringFor(config)
      close = wiring.close
      // One ledger execution per loader run: a finished execution cannot be
      // started again with a new start time, so a rerun gets its own label
      // and skips unchanged vectors by their text hash and model instead.
      val backend =
        wiring.backend.copy(executionLabel = compactStamp(clock()))
      val store = backend.store
      wired = Some(store)
      val exportDir = work.resolve("export")
      val outDir = work.resolve("out")
      val files = downloadExport(store, config, exportDir)
      out(
        s"LOADER_INPUT run=${config.runId} prefix=${config.exportPrefix} " +
          s"objects=$files"
      )
      val stepOut: String => Unit = line =>
        out(
          if line.startsWith(IngestMain.ResultPrefix) then
            "INGEST_STEP=" + line.stripPrefix(IngestMain.ResultPrefix)
          else line
        )
      val outcome = IngestMain.execute(
        IngestMain.Args(exportDir, outDir, None),
        stepOut,
        identity,
        _ => backend
      )
      val graph: GraphStep.Result =
        if !outcome.success then
          GraphStep.Result.Skipped(
            "the vector ingest did not succeed",
            Vector.empty
          )
        else if !config.graphRequired then
          GraphStep.Result.Skipped(
            "no Neptune host configured",
            Vector.empty
          )
        else
          backend.graph match
            case None =>
              GraphStep.Result.Skipped(
                "no graph loader configured",
                Vector.empty
              )
            case Some(load) =>
              try
                val source = JsonlExportSource.open(exportDir)
                GraphStep.run(source, source.sourceRun.runId, store, load)
              catch
                case NonFatal(error) =>
                  GraphStep.Result.Failed(
                    s"${error.getClass.getSimpleName}: ${error.getMessage}"
                  )
      graphLines(graph).foreach(out)
      // With a Neptune host configured the graph is required: a skipped graph
      // is a failure. Without one the run is vectors-only and may succeed.
      val graphFailed = graph match
        case GraphStep.Result.Failed(_)     => true
        case GraphStep.Result.Skipped(_, _) => config.graphRequired
        case _                              => false
      val success = outcome.success && !graphFailed
      val reason =
        if !outcome.success then
          s"the vector ingest or retrieval check failed (${outcome.reason})"
        else
          graph match
            case GraphStep.Result.Failed(r) => s"the Neptune load failed: $r"
            case GraphStep.Result.Skipped(r, _) if config.graphRequired =>
              s"the Neptune load was required and the graph was skipped: $r"
            case _ => ""
      val elapsed = (System.nanoTime() - began) / 1e9
      val reportPath = outDir.resolve(IngestMain.ReportName)
      val runPath = outDir.resolve(IngestMain.RunName)
      val report = readJson(reportPath).getOrElse(
        ujson.Obj(
          "reportVersion" -> IngestMain.ReportVersion,
          "label" -> IngestMain.AwsLabel,
          "perIndex" -> ujson.Obj(),
          "reason" -> reason
        )
      )
      val run = readJson(runPath).getOrElse(ujson.Obj())
      val exportRunId = report.obj.get("sourceRunId").flatMap(_.strOpt)
      report("loaderReportVersion") = ReportVersion
      report("runIdArgument") = config.runId
      report("region") = config.region
      report("scope") =
        if graph.isInstanceOf[GraphStep.Result.Loaded] then "vectors-and-graph"
        else "vectors-only"
      report("graph") = graphJson(graph)
      report("graphRequired") = config.graphRequired
      report("result") = if success then "SUCCESS" else "FAILED"
      if !success then report("reason") = reason
      run("elapsedSeconds") = elapsed
      run("graph") = graphJson(graph)
      run("result") = if success then "SUCCESS" else "FAILED"
      exportRunId.foreach { id =>
        out(
          s"RUN_ID_MATCH=${id == config.runId} export_run=$id argument=${config.runId}"
        )
      }
      readJson(reportPath).foreach { _ =>
        perIndexLines(report).foreach(out)
        val per = report("perIndex").obj.values.toVector
        def sum(key: String): Long = per.map(_(key).num.toLong).sum
        out(
          s"LOADER_TOTAL vectors_written=${sum("written")} " +
            s"skipped_unchanged=${sum("skippedUnchanged")} " +
            s"skipped_resumed=${sum("skippedResumed")} " +
            s"rejected=${sum("rejected")} duplicates=${sum("duplicates")} " +
            s"retries=${sum("retries")} failed_batches=${sum("failedBatches")} " +
            s"vectors_in_index=${sum("vectorsInIndex")}"
        )
      }
      out(
        s"LOADER_EMBEDDING model=${backend.embedder.modelVersion} " +
          s"dimension=${backend.embedder.dimension} " +
          s"calls=${backend.embedCalls().fold("unknown")(_.toString)}"
      )
      out(f"LOADER_ELAPSED_SECONDS=$elapsed%.1f")
      val stamp = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(clock())
      val ref = s"loader-run:${config.runId}"
      val uploaded =
        try
          val a = putReport(
            store,
            config.reportBase,
            IngestMain.ReportName,
            (ujson.write(report, indent = 2) + "\n").getBytes(UTF_8),
            stamp,
            ref
          )
          val b = putReport(
            store,
            config.reportBase,
            IngestMain.RunName,
            (ujson.write(run, indent = 2) + "\n").getBytes(UTF_8),
            stamp,
            ref
          )
          out(s"LOADER_REPORT=s3://${config.dataBucket}/$a")
          out(s"LOADER_RUN_RECORD=s3://${config.dataBucket}/$b")
          None
        catch
          case NonFatal(error) =>
            Some(s"${error.getClass.getSimpleName}: ${error.getMessage}")
      uploaded match
        case Some(why) =>
          out(s"LOADER_REPORT=FAILED reason=$why")
          finish(false, s"the report could not be written: $why", 1)
        case None => finish(success, reason, 1)
    catch
      case NonFatal(error) =>
        val why = s"${error.getClass.getSimpleName}: ${error.getMessage}"
        // A failure before any report exists still leaves a FAILED record
        // beside the run, when the data bucket can be written at all.
        wired.foreach { store =>
          try
            val body = ujson.Obj(
              "reportVersion" -> IngestMain.ReportVersion,
              "loaderReportVersion" -> ReportVersion,
              "label" -> IngestMain.AwsLabel,
              "runIdArgument" -> config.runId,
              "result" -> "FAILED",
              "reason" -> why
            )
            val key = putReport(
              store,
              config.reportBase,
              IngestMain.ReportName,
              (ujson.write(body, indent = 2) + "\n").getBytes(UTF_8),
              DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(clock()),
              s"loader-run:${config.runId}"
            )
            out(s"LOADER_REPORT=s3://${config.dataBucket}/$key")
          catch
            case NonFatal(inner) =>
              out(
                s"LOADER_REPORT=FAILED reason=${inner.getClass.getSimpleName}"
              )
        }
        finish(false, why, 1)
    finally
      close()
      deleteTree(work)

  def main(args: Array[String]): Unit =
    // The one read of the process environment in the loader.
    val code = run(args, sys.env.toMap, println)
    sys.exit(code)
