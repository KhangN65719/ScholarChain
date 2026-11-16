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
  * Markers (one per line): `LOADER_PHASE`, `LOADER_PROGRESS`,
  * `LOADER_BATCH_FAILED`, `LOADER_INDEX`, `LOADER_TOTAL`, `LOADER_EMBEDDING`,
  * `NEPTUNE_LOAD=LOADED|LOADED_WITH_ERRORS|SKIPPED|FAILED`, `LOADER_REPORT`,
  * and exactly one `LOADER_RESULT=SUCCESS|SUCCEEDED_WITH_WARNINGS|FAILED` last
  * (the FAILED reason is the first reason line, at most 600 characters). The
  * ingest step's own result line is renamed `INGEST_STEP=`, so a later graph
  * failure never follows a line that reads SUCCESS in the same form.
  *
  * `LOADER_BATCH_FAILED index=<index> batch=<n> reason=<text>` is printed by
  * `EmbeddingIngest` the moment a batch fails, with the reason cut at 600
  * characters. Like `LOADER_PROGRESS` it passes through `stepOut` unchanged.
  * `ingest-run.json` (uploaded beside the report) lists every failed batch in
  * `failedBatches`; `ingest-report.json` holds only their count.
  *
  * `LOADER_PHASE name=<download|ingest|graph|report> state=<start|done>
  * elapsed_s=<n>` brackets each of the four phases of a run (download of the
  * export, `IngestMain.execute`, the graph step, the report upload); `n` is
  * whole seconds since the loader started. `done` means the phase returned, not
  * that it succeeded (`INGEST_STEP=`, `NEPTUNE_LOAD=` and `LOADER_REPORT=` say
  * that), and a phase that throws prints `start` and no `done`.
  *
  * Inside the graph phase, `LOADER_PHASE name=<graph-open|graph-project|
  * graph-export|graph-load> state=<start|done> elapsed_s=<n>` brackets its four
  * steps (same `n`, same rule for a step that throws). The per-job lines of the
  * bulk load are the existing `NEPTUNE_JOB` lines; they are printed when the
  * load returns, without a per-job time (that needs the storage loader).
  *
  * `--graph-only` (`AwsIngestMain <runId> --graph-only`, the flag anywhere
  * after the program name) reruns the graph phase of a run whose ingest already
  * completed. It prints `LOADER_MODE=graph-only` first and
  * `INGEST_STEP=SKIPPED(graph-only)` where the ingest would run, downloads the
  * export as a full run does, runs no ingest and no retrieval sample, no
  * embedder, vector bucket or ledger call, and prints
  * `LOADER_SCOPE=graph-only (...)` instead of the vectors scopes. It writes
  * `graph-report.json` and `graph-run.json` beside the run's loader reports
  * (same immutability rule); `ingest-report.json` and `ingest-run.json` are
  * never written, read or replaced by it. It needs a Neptune host, because the
  * graph load is its only work.
  *
  * `--tolerate-load-errors` (valid alone or with `--graph-only`; it needs a
  * Neptune host, refused before any AWS call otherwise) builds the bulk loader
  * with `failOnError` false, so Neptune skips a bad record and goes on, and
  * applies `LoadErrorPolicy` to the result. No errors: as without the flag.
  * Errors that are all named and at most 0.1% of the records Neptune counted
  * (`errors * 1000 <= records`, over all jobs):
  * `NEPTUNE_LOAD=LOADED_WITH_ERRORS ...`, one `NEPTUNE_LOAD_SKIPPED` line per
  * named record, a `NEPTUNE_LOAD_SKIPPED_SUMMARY` line, `LOADER_WARNING`, the
  * report with `graph.status` LOADED_WITH_ERRORS, `result`
  * SUCCEEDED_WITH_WARNINGS and a `graph.errors` object, then
  * `LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:<n>` last
  * and exit 0. Anything else (over the threshold, an error that is not named, a
  * truncated list, a failed job) is `NEPTUNE_LOAD=FAILED` and exit 1. Without
  * the flag nothing changes.
  *
  * An `OutOfMemoryError` in any phase prints a short `LOADER_OOM phase=<name>`
  * line, writes a FAILED report as every other failure does (the loader writes
  * no ledger entry for any failure), then prints `LOADER_RESULT=FAILED
  * reason=OOM phase=<name>` last and exits 3. `<name>` is the last phase or
  * graph step that started.
  *
  * `LOADER_PROGRESS ...` lines come from `EmbeddingIngest` while the ingest
  * phase runs (a start line with the item and batch totals, one line per batch,
  * one line every 100 embeds inside a batch). They pass through `stepOut`
  * unchanged and are written as they are produced, so a long embedding run is
  * visible before it ends. Each `out` call is one `println`, and the JVM's
  * `System.out` flushes on every `println`, so no explicit flush is added.
  */
object AwsIngestMain:
  val EnvRegion = "AWS_REGION"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"
  val EnvNeptuneHost = "SCHOLARCHAIN_NEPTUNE_HOST"
  val EnvLoadRoleArn = "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
  val DefaultNeptunePort = 8182
  val GraphOnlyFlag = "--graph-only"
  val TolerateFlag = "--tolerate-load-errors"
  val KnownOptions: Vector[String] = Vector(GraphOnlyFlag, TolerateFlag)
  val Usage = s"usage: AwsIngestMain <runId> [$GraphOnlyFlag] [$TolerateFlag]"
  val GraphOnlyMode = "graph-only"
  val IngestSkipped = "SKIPPED(graph-only)"
  val ResultWithWarnings = "SUCCEEDED_WITH_WARNINGS"
  val GraphReportName = "graph-report.json"
  val GraphRunName = "graph-run.json"
  val GraphReportVersion = "scholarchain.rag.aws-graph-report/1"
  val GraphRunVersion = "scholarchain.rag.aws-graph-run/1"

  /** Process exit code of an `OutOfMemoryError` (1 is any other failure, 2 a
    * configuration error); the same code as `GraphRehearsal`.
    */
  val ExitOom = 3

  /** The objects the loader lists under one run's documents and exports. */
  val MaxListedObjects = 100000

  val ReportVersion = "scholarchain.rag.aws-ingest-report/1"

  /** Characters of the first reason line on `LOADER_RESULT=FAILED`. 500 cut the
    * S run 7 PutVectors reason; this equals `IngestMain.MaxReasonChars`.
    */
  val MaxResultReasonChars = 600

  final case class Config(
      region: String,
      dataBucket: String,
      vectorBucket: String,
      tablePrefix: String,
      neptuneHost: Option[String],
      neptunePort: Int,
      loadRoleArn: Option[String],
      runId: String,
      graphOnly: Boolean = false,
      tolerateLoadErrors: Boolean = false
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
    // The pipeline script passes any `--x` option through unchecked, so an
    // unknown option stops here, before a client is built.
    val (options, positional) = args.toVector.partition(_.startsWith("--"))
    options.find(!KnownOptions.contains(_)).foreach { option =>
      refuse(
        s"Unknown option ${option.take(40).filterNot(_.isControl)}. $Usage"
      )
    }
    KnownOptions.foreach { name =>
      Fail.unless(
        options.count(_ == name) <= 1,
        ErrorCode.InvalidQuery,
        s"The option $name is given more than once. $Usage"
      )
    }
    Fail.unless(positional.size == 1, ErrorCode.InvalidQuery, Usage)
    val graphOnly = options.contains(GraphOnlyFlag)
    val tolerate = options.contains(TolerateFlag)
    val runId = positional.head.trim
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
    Fail.unless(
      !graphOnly || neptune.isDefined,
      ErrorCode.InvalidQuery,
      s"$GraphOnlyFlag needs $EnvNeptuneHost: the graph load is its only work"
    )
    Fail.unless(
      !tolerate || neptune.isDefined,
      ErrorCode.InvalidQuery,
      s"$TolerateFlag needs $EnvNeptuneHost: it applies to the graph load"
    )
    Config(
      checked(EnvRegion, required(env, EnvRegion), regionPattern),
      checked(EnvDataBucket, required(env, EnvDataBucket), bucketPattern),
      checked(EnvVectorBucket, required(env, EnvVectorBucket), bucketPattern),
      checked(EnvTablePrefix, required(env, EnvTablePrefix), prefixPattern),
      neptune.map(_._1),
      neptune.fold(DefaultNeptunePort)(_._2),
      neptune.map(_._3),
      runId,
      graphOnly,
      tolerate
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
      role,
      // Neptune skips a bad record only when the operator asked for it; the
      // policy that decides whether the result is acceptable is `LoadErrorPolicy`.
      failOnError = !config.tolerateLoadErrors
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

  /** `assessed` is present only under `--tolerate-load-errors` and only for a
    * load that returned. A tolerated load is `LOADED_WITH_ERRORS` with its
    * errors and a warning; a rejected one stays `FAILED`, keeping the errors it
    * was judged on. Without the flag the JSON is exactly what it always was.
    */
  private def graphJson(
      result: GraphStep.Result,
      assessed: Option[LoadErrorPolicy.Assessment]
  ): ujson.Value = result match
    case GraphStep.Result.Skipped(reason, missing) =>
      ujson.Obj(
        "status" -> "SKIPPED",
        "reason" -> reason,
        "missingTables" -> ujson.Arr.from(missing.map(ujson.Str(_)))
      )
    case GraphStep.Result.Failed(reason) =>
      val failed = ujson.Obj("status" -> "FAILED", "reason" -> reason)
      assessed.foreach(a =>
        failed("errors") = LoadErrorPolicy.errorsJson(a.errors)
      )
      failed
    case loaded: GraphStep.Result.Loaded =>
      val json = loadedJson(loaded)
      assessed.filter(_.tolerated).foreach { a =>
        json("status") = "LOADED_WITH_ERRORS"
        json("warning") = LoadErrorPolicy.warning(a.errors)
        json("errors") = LoadErrorPolicy.toleratedErrorsJson(a.errors)
      }
      json

  private def loadedJson(loaded: GraphStep.Result.Loaded): ujson.Obj =
    val GraphStep.Result.Loaded(exported, load) = loaded
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

  private def graphLines(
      result: GraphStep.Result,
      assessed: Option[LoadErrorPolicy.Assessment]
  ): Vector[String] =
    result match
      case GraphStep.Result.Loaded(_, load) if assessed.exists(_.tolerated) =>
        val errors = assessed.get.errors
        Vector(LoadErrorPolicy.loadedWithErrorsLine(errors)) ++
          LoadErrorPolicy.skippedLines(errors) ++
          load.jobs.map(jobLine) ++ LoadErrorPolicy.jobStatusLines(errors) ++
          Vector(
            LoadErrorPolicy.summaryLine(errors),
            s"LOADER_WARNING ${LoadErrorPolicy.warning(errors)}",
            LoadErrorPolicy.scopeText(errors)
          )
      case GraphStep.Result.Skipped(reason, _) =>
        Vector(
          s"NEPTUNE_LOAD=SKIPPED reason=$reason",
          "LOADER_SCOPE=vectors-only (the graph was NOT loaded)"
        )
      case GraphStep.Result.Failed(reason) =>
        Vector(s"NEPTUNE_LOAD=FAILED reason=$reason") ++
          assessed.toVector.flatMap(a =>
            LoadErrorPolicy.jobStatusLines(a.errors)
          )
      case GraphStep.Result.Loaded(exported, load) =>
        Vector(
          s"NEPTUNE_LOAD=LOADED vertices=${exported.vertexCount} " +
            s"edges=${exported.edgeCount} files=${exported.files.size} " +
            s"jobs=${load.jobs.size}"
        ) ++ load.jobs.map(jobLine) :+ "LOADER_SCOPE=vectors-and-graph"

  private def jobLine(j: LoaderJobReport): String =
    s"NEPTUNE_JOB source=${j.source} status=${j.status} " +
      s"records=${j.counts.totalRecords} " +
      s"duplicates=${j.counts.totalDuplicates} errors=${j.counts.errors}"

  /** The head of every object a graph-only run writes: it says in the object
    * itself that no vector was ingested, so it cannot pass for an ingest
    * report.
    */
  private def graphOnlyBody(version: String): ujson.Obj =
    ujson.Obj(
      "reportVersion" -> version,
      "label" -> IngestMain.AwsLabel,
      "loaderMode" -> GraphOnlyMode,
      "ingest" -> IngestSkipped,
      "ingestPerformed" -> false,
      "note" -> ("this run did not ingest vectors and ran no retrieval " +
        "samples; ingest-report.json of the earlier run is unchanged")
    )

  private def readJson(path: Path): Option[ujson.Obj] =
    if Files.isRegularFile(path) then
      try
        ujson.read(Files.readAllBytes(path)).objOpt.map(o => ujson.Obj.from(o))
      catch case NonFatal(_) => None
    else None

  /** Runs the whole loader and returns the exit code: 0 for SUCCESS, 2 for a
    * configuration or command-line error, 3 for an `OutOfMemoryError`
    * (`ExitOom`), 1 for any other failure. Prints exactly one `LOADER_RESULT=`
    * line, last.
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
          s"LOADER_RESULT=FAILED reason=${reason.linesIterator.nextOption().getOrElse("").take(MaxResultReasonChars)}"
      )
      if success then 0 else code
    // A load with tolerated errors is a success that says so in its last line.
    def finishWarned(warnings: String): Int =
      out(s"LOADER_RESULT=$ResultWithWarnings warnings=$warnings")
      0
    val config =
      try Right(parseConfig(args, env))
      catch case error: StorageException => Left(error.getMessage)
    config match
      case Left(message) => finish(false, message, 2)
      case Right(cfg)    =>
        runConfigured(cfg, out, wiringFor, clock, began, finish, finishWarned)

  private def runConfigured(
      config: Config,
      out: String => Unit,
      wiringFor: Config => Wiring,
      clock: () => Instant,
      began: Long,
      finish: (Boolean, String, Int) => Int,
      finishWarned: String => Int
  ): Int =
    val work = Files.createTempDirectory("scholarchain-loader-")
    val graphOnly = config.graphOnly
    val reportName =
      if graphOnly then GraphReportName else IngestMain.ReportName
    val runName = if graphOnly then GraphRunName else IngestMain.RunName
    // The last phase or graph step that started; an OOM line names it.
    var current = "init"
    def phase(name: String, state: String): Unit =
      if state == "start" then current = name
      out(
        s"LOADER_PHASE name=$name state=$state " +
          s"elapsed_s=${(System.nanoTime() - began) / 1000000000L}"
      )
    var close: () => Unit = () => ()
    var wired: Option[ObjectStore] = None
    var sourceRunId: Option[String] = None
    // A failure before any report exists still leaves a FAILED record beside
    // the run, when the data bucket can be written at all.
    def recordFailure(why: String): Unit =
      wired.foreach { store =>
        try
          val body =
            if graphOnly then graphOnlyBody(GraphReportVersion)
            else
              ujson.Obj(
                "reportVersion" -> IngestMain.ReportVersion,
                "loaderReportVersion" -> ReportVersion,
                "label" -> IngestMain.AwsLabel
              )
          if graphOnly then body("loaderReportVersion") = ReportVersion
          body("runIdArgument") = config.runId
          body("result") = "FAILED"
          body("reason") = why
          val key = putReport(
            store,
            config.reportBase,
            reportName,
            (ujson.write(body, indent = 2) + "\n").getBytes(UTF_8),
            compactStamp(clock()),
            s"loader-run:${config.runId}"
          )
          out(s"LOADER_REPORT=s3://${config.dataBucket}/$key")
        catch
          case NonFatal(inner) =>
            out(s"LOADER_REPORT=FAILED reason=${inner.getClass.getSimpleName}")
      }
    try
      if graphOnly then out(s"LOADER_MODE=$GraphOnlyMode")
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
      phase("download", "start")
      val files = downloadExport(store, config, exportDir)
      phase("download", "done")
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
      // A graph-only run never reaches `IngestMain.execute`, so no embedder,
      // vector bucket or ledger call is made and no retrieval sample runs.
      val outcome: Option[IngestMain.Outcome] =
        if graphOnly then
          out(s"INGEST_STEP=$IngestSkipped")
          None
        else
          phase("ingest", "start")
          val ingested = IngestMain.execute(
            IngestMain.Args(exportDir, outDir, None),
            stepOut,
            identity,
            _ => backend
          )
          phase("ingest", "done")
          Some(ingested)
      phase("graph", "start")
      val graphRun: GraphStep.Result =
        if outcome.exists(!_.success) then
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
                phase("graph-open", "start")
                val source = JsonlExportSource.open(exportDir)
                phase("graph-open", "done")
                sourceRunId = Some(source.sourceRun.runId)
                GraphStep.run(
                  source,
                  source.sourceRun.runId,
                  store,
                  load,
                  phase
                )
              catch
                case NonFatal(error) =>
                  GraphStep.Result.Failed(
                    s"${error.getClass.getSimpleName}: ${error.getMessage}"
                  )
      // The tolerance applies only when the operator passed the flag. A load
      // that Neptune ended with errors is judged here; one the policy rejects
      // becomes a failed graph step, exactly as a thrown load does.
      val assessed: Option[LoadErrorPolicy.Assessment] = graphRun match
        case GraphStep.Result.Loaded(_, load) if config.tolerateLoadErrors =>
          Some(LoadErrorPolicy.assessment(load))
        case _ => None
      val graph: GraphStep.Result = assessed match
        case Some(LoadErrorPolicy.Assessment(_, rejected)) =>
          rejected match
            case LoadErrorPolicy.Verdict.Rejected(why) =>
              GraphStep.Result.Failed(why)
            case _ => graphRun
        case None => graphRun
      val warned = assessed.filter(_.tolerated)
      val lines = graphLines(graph, assessed)
      (if graphOnly then
         lines.filterNot(_.startsWith("LOADER_SCOPE=")) :+
           "LOADER_SCOPE=graph-only (the vector ingest was NOT run by this run)"
       else lines).foreach(out)
      phase("graph", "done")
      phase("report", "start")
      // With a Neptune host configured the graph is required: a skipped graph
      // is a failure. Without one the run is vectors-only and may succeed.
      val graphFailed = graph match
        case GraphStep.Result.Failed(_)     => true
        case GraphStep.Result.Skipped(_, _) => config.graphRequired
        case _                              => false
      val success = outcome.forall(_.success) && !graphFailed
      val reason = outcome match
        case Some(o) if !o.success =>
          s"the vector ingest or retrieval check failed (${o.reason})"
        case _ =>
          graph match
            case GraphStep.Result.Failed(r) => s"the Neptune load failed: $r"
            case GraphStep.Result.Skipped(r, _) if config.graphRequired =>
              s"the Neptune load was required and the graph was skipped: $r"
            case _ => ""
      val elapsed = (System.nanoTime() - began) / 1e9
      val reportPath = outDir.resolve(IngestMain.ReportName)
      val runPath = outDir.resolve(IngestMain.RunName)
      // A graph-only run starts from its own objects: it never reads, reuses
      // or imitates the ingest report of the earlier run.
      val report =
        if graphOnly then graphOnlyBody(GraphReportVersion)
        else
          readJson(reportPath).getOrElse(
            ujson.Obj(
              "reportVersion" -> IngestMain.ReportVersion,
              "label" -> IngestMain.AwsLabel,
              "perIndex" -> ujson.Obj(),
              "reason" -> reason
            )
          )
      val run =
        if graphOnly then graphOnlyBody(GraphRunVersion)
        else readJson(runPath).getOrElse(ujson.Obj())
      val exportRunId =
        if graphOnly then sourceRunId
        else report.obj.get("sourceRunId").flatMap(_.strOpt)
      report("loaderReportVersion") = ReportVersion
      report("runIdArgument") = config.runId
      report("region") = config.region
      report("scope") =
        if graphOnly then GraphOnlyMode
        else if graph.isInstanceOf[GraphStep.Result.Loaded] then
          "vectors-and-graph"
        else "vectors-only"
      val resultText =
        if !success then "FAILED"
        else if warned.isDefined then ResultWithWarnings
        else "SUCCESS"
      report("graph") = graphJson(graph, assessed)
      report("graphRequired") = config.graphRequired
      report("result") = resultText
      if !success then report("reason") = reason
      run("elapsedSeconds") = elapsed
      run("graph") = graphJson(graph, assessed)
      run("result") = resultText
      warned.foreach { a =>
        Vector(report, run).foreach { body =>
          body("warning") = LoadErrorPolicy.warning(a.errors)
          body("warnings") =
            ujson.Arr(ujson.Str(LoadErrorPolicy.warningTag(a.errors)))
        }
      }
      exportRunId.foreach { id =>
        out(
          s"RUN_ID_MATCH=${id == config.runId} export_run=$id argument=${config.runId}"
        )
      }
      if !graphOnly then
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
            reportName,
            (ujson.write(report, indent = 2) + "\n").getBytes(UTF_8),
            stamp,
            ref
          )
          val b = putReport(
            store,
            config.reportBase,
            runName,
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
      phase("report", "done")
      uploaded match
        case Some(why) =>
          out(s"LOADER_REPORT=FAILED reason=$why")
          finish(false, s"the report could not be written: $why", 1)
        case None =>
          warned match
            case Some(a) if success =>
              finishWarned(LoadErrorPolicy.warningTag(a.errors))
            case _ => finish(success, reason, 1)
    catch
      case _: OutOfMemoryError =>
        // The frames that held the data are gone, so these lines can be built.
        // The short line goes first so it survives a second failure while the
        // record is written; the result line stays the last line.
        out(
          s"LOADER_OOM phase=$current " +
            s"max_heap_mb=${Runtime.getRuntime.maxMemory / 1048576L}"
        )
        try recordFailure(s"OutOfMemoryError in phase $current")
        catch case _: OutOfMemoryError => ()
        finish(false, s"OOM phase=$current", ExitOom)
      case NonFatal(error) =>
        val why = s"${error.getClass.getSimpleName}: ${error.getMessage}"
        recordFailure(why)
        finish(false, why, 1)
    finally
      close()
      deleteTree(work)

  def main(args: Array[String]): Unit =
    // The one read of the process environment in the loader.
    val code =
      try run(args, sys.env.toMap, println)
      catch
        case _: OutOfMemoryError =>
          println("LOADER_RESULT=FAILED reason=OOM phase=main")
          ExitOom
    sys.exit(code)
