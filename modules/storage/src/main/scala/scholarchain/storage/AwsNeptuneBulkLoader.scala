package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import scala.util.control.NonFatal
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusRequest,
  Mode,
  Parallelism,
  StartLoaderJobRequest
}

object AwsNeptuneBulkLoader:
  val Format = "opencypher"

  /** Statuses that mean the job is still going. Anything else is terminal. */
  val InProgress: Set[String] =
    Set("LOAD_NOT_STARTED", "LOAD_IN_QUEUE", "LOAD_IN_PROGRESS")

  /** The only status that counts as success. `LOAD_COMMITTED_W_WRITE_CONFLICTS`
    * is deliberately a failure: some writes may have been dropped.
    */
  val Success = "LOAD_COMPLETED"

  /** With `failOnError` false, the statuses a job with counted record errors
    * may end in and still be returned: the documented success, and
    * `LOAD_FAILED`, which the docs do not rule out for a load that skipped
    * records.
    */
  val RecordErrorStatuses: Set[String] = Set(Success, "LOAD_FAILED")

  val MaxErrorSamples = 5

  /** `NEPTUNE_LOAD_ERROR` lines printed per job, and the records named in the
    * failure text (before its character cap).
    */
  val MaxLoggedErrors = 20

  /** Hard safety cap on the error records fetched for one job. */
  val MaxFetchedErrors = 500

  /** `errorsPerPage` of each error-page request. */
  val ErrorsPerPage = 20

  /** Characters kept of one error message, in the live line and in the text. */
  val MaxErrorMessageChars = 300

  /** Characters of the error records in the failure text, all records together.
    * The live lines are not cut by this.
    */
  val MaxErrorTextChars = 2000

  /** Characters of a fetch-failure reason and of the shape note. */
  val MaxReasonChars = 300

  /** Account IDs are masked before the cut, so a cut cannot leave the first
    * digits of one.
    */
  private def clean(value: String, max: Int): String =
    Text
      .maskAccountIds(Option(value).getOrElse(""))
      .map(c => if c.isControl then ' ' else c)
      .trim
      .take(max)

  private def scalar(document: Document): Option[String] =
    if document == null then None
    else if document.isString then Some(document.asString)
    else if document.isNumber then Some(document.asNumber.stringValue)
    else if document.isBoolean then Some(document.asBoolean.toString)
    else None

  private def logsOf(payload: Document): Vector[Document] =
    field(payload, "errors")
      .flatMap(errors => field(errors, "errorLogs"))
      .filter(_.isList)
      .map(_.asList.asScala.toVector)
      .getOrElse(Vector.empty)

  private def recordOf(entry: Document): LoadErrorRecord =
    def text(name: String): String =
      field(entry, name).flatMap(scalar).getOrElse("")
    // An entry with no errorMessage still says what it holds: its scalar
    // fields, so an unexpected shape is visible instead of blank.
    val message = text("errorMessage") match
      case "" =>
        entry.asMap.asScala.toVector
          .flatMap((name, value) => scalar(value).map(v => s"$name=$v"))
          .mkString(" ")
      case present => present
    LoadErrorRecord(
      clean(text("errorCode"), 80),
      clean(
        text("fileName").substring(text("fileName").lastIndexOf('/') + 1),
        120
      ),
      clean(text("recordNum"), 40),
      clean(message, MaxErrorMessageChars)
    )

  /** Every `errors.errorLogs` entry of a payload, in order, cleaned. */
  def parseErrors(payload: Document): Vector[LoadErrorRecord] =
    logsOf(payload).filter(entry => entry != null && entry.isMap).map(recordOf)

  /** The keys of the payload and of its `errors` object, no values: what a
    * response that lists no error records looked like.
    */
  def shapeOf(payload: Document): String =
    def keys(document: Document): String =
      if document != null && document.isMap then
        document.asMap.keySet.asScala.toVector.sorted.mkString(",")
      else "-"
    clean(
      s"payload_keys=${keys(payload)} " +
        s"errors_keys=${keys(field(payload, "errors").orNull)}",
      MaxReasonChars
    )

  private def field(document: Document, name: String): Option[Document] =
    if document != null && document.isMap then Option(document.asMap.get(name))
    else None

  private def long(document: Document, name: String): Long =
    field(document, name)
      .filter(_.isNumber)
      .map(_.asNumber.longValue)
      .orElse(
        field(document, name)
          .filter(_.isString)
          .flatMap(_.asString.toLongOption)
      )
      .getOrElse(0L)

  /** The status text, the counts and the first error entries of a
    * GetLoaderJobStatus payload.
    */
  def parse(payload: Document): (String, LoaderCounts, Vector[String]) =
    val overall = field(payload, "overallStatus").orNull
    val status = field(overall, "status")
      .filter(_.isString)
      .map(_.asString)
      .getOrElse("UNKNOWN")
    val counts = LoaderCounts(
      long(overall, "totalRecords"),
      long(overall, "totalDuplicates"),
      long(overall, "parsingErrors"),
      long(overall, "datatypeMismatchErrors"),
      long(overall, "insertErrors")
    )
    val logs = logsOf(payload)
    val samples = logs.take(MaxErrorSamples).map { entry =>
      def text(name: String): String =
        field(entry, name)
          .map(d =>
            if d.isString then d.asString
            else if d.isNumber then d.asNumber.stringValue
            else d.toString
          )
          .getOrElse("")
      s"${text("errorCode")} ${text("fileName")} record " +
        s"${text("recordNum")}: ${text("errorMessage")}"
    }
    (status, counts, samples)

/** `NeptuneBulkLoader` over the Neptune data API (StartLoaderJob and
  * GetLoaderJobStatus). Neptune is private: this class runs only inside the
  * VPC, and no test reaches it.
  *
  * `load(manifestKey)` reads the export manifest, checks every listed file
  * against the object store (present, SHA-256 equal), and loads the files one
  * job at a time, every vertex file before any edge file, each job's source
  * being that single file (`s3://bucket/key`). A single-file source means a
  * stray object under the run prefix (the manifest, for one) is never loaded.
  * Each job runs with `format` opencypher, the role ARN, `s3BucketRegion`,
  * `userProvidedEdgeIds` true (the relationship `:ID` column), `failOnError`
  * (the constructor option, default true) and `queueRequest` true, and is
  * polled to a terminal state. Edge jobs carry `parallelism` `edgeParallelism`
  * (default LOW: concurrent writes to a hub vertex are the suspected cause of
  * insert errors); vertex jobs send no `parallelism`, so Neptune's default
  * applies. The first job that does not end in `LOAD_COMPLETED` with zero
  * errors stops the load with `BulkLoadFailed`, whose message carries the
  * status, the counts and the first error records. Later files are not started.
  *
  * Every job starts with `mode` NEW, so a rerun never resumes an earlier load
  * of the same source (the service default, AUTO, would).
  *
  * With `failOnError` false, a job with error counters above zero that ends
  * `LOAD_COMPLETED` or `LOAD_FAILED` (the docs do not say which a load that
  * skipped records ends in) is not thrown: it is returned in
  * `BulkLoadResult.jobs` with its real status, its counts and its error
  * records, and the aggregate accessors of `BulkLoadResult` say how many errors
  * there were, whether each was named and how each job ended. Whether that is
  * acceptable is the caller's decision; this class holds no threshold. Any
  * other non-success status (cancelled, S3 errors, write conflicts, an unknown
  * state) and a `LOAD_FAILED` with no counted error still throw.
  *
  * Whenever a job is not a clean success, its error records are fetched with a
  * separate GetLoaderJobStatus call (`details=true&errors=true`, pages of
  * `ErrorsPerPage`, at most `MaxFetchedErrors` records), at most
  * `MaxLoggedErrors` are printed as `NEPTUNE_LOAD_ERROR` lines, and a
  * `NEPTUNE_LOAD_ERRORS_TOTAL` line states the counted and the named totals.
  * The fetch never throws: a failed fetch prints
  * `NEPTUNE_LOAD_ERROR_FETCH_FAILED` and the original outcome stands. A clean
  * success makes no error call. The routine status polls ask for no errors.
  *
  * Polling is bounded: after `maxPolls` polls the load fails with the load ID,
  * and the job is left running (nothing here cancels a job).
  */
final class AwsNeptuneBulkLoader(
    client: NeptunedataClient,
    store: ObjectStore,
    bucket: String,
    bucketRegion: String,
    roleArn: String,
    sleeper: Sleeper = ThreadSleeper,
    pollMillis: Long = 5000L,
    maxPolls: Int = 720,
    failOnError: Boolean = true,
    edgeParallelism: Parallelism = Parallelism.LOW,
    log: String => Unit = line => println(line)
) extends NeptuneBulkLoader:
  Fail.unless(
    bucket.trim.nonEmpty && bucketRegion.trim.nonEmpty,
    ErrorCode.InvalidKey,
    "A bucket and its region are required"
  )
  Fail.unless(
    roleArn.startsWith("arn:") && roleArn.contains(":role/"),
    ErrorCode.InvalidKey,
    "The Neptune bulk-load role must be an IAM role ARN"
  )
  Fail.unless(maxPolls >= 1, ErrorCode.BoundExceeded, "maxPolls is below 1")

  private final case class Planned(key: String, kind: String, sha: String)

  private def plan(manifestKey: String): Vector[Planned] =
    val bytes = store
      .get(manifestKey)
      .getOrElse(Fail(ErrorCode.MalformedManifest, s"No $manifestKey"))
    val manifest =
      try ujson.read(new String(bytes, UTF_8))
      catch
        case error: Exception =>
          Fail(
            ErrorCode.MalformedManifest,
            s"$manifestKey: ${error.getMessage}"
          )
    Fail.unless(
      manifest.objOpt.exists(
        _.get("format").exists(_.strOpt.contains("opencypher"))
      ),
      ErrorCode.MalformedManifest,
      s"$manifestKey is not an opencypher load manifest"
    )
    val files = manifest("files").arr.toVector.map { file =>
      Planned(file("key").str, file("kind").str, file("sha256").str)
    }
    files.foreach { file =>
      Fail.unless(
        file.key.endsWith(".csv") && file.key != manifestKey,
        ErrorCode.MalformedManifest,
        s"${file.key} is not a loadable CSV file"
      )
      Fail.unless(
        Set("vertex", "edge").contains(file.kind),
        ErrorCode.MalformedManifest,
        s"${file.key} has kind ${file.kind}"
      )
      val head = store
        .head(file.key)
        .getOrElse(Fail(ErrorCode.MalformedManifest, s"Missing ${file.key}"))
      Fail.unless(
        head.sha256 == file.sha,
        ErrorCode.ChecksumMismatch,
        s"${file.key} does not match the load manifest"
      )
    }
    // Nodes first: a relationship file names its endpoints by node ID.
    files.filter(_.kind == "vertex").sortBy(_.key) ++
      files.filter(_.kind == "edge").sortBy(_.key)

  private def start(source: String, kind: String): String =
    val base = StartLoaderJobRequest
      .builder()
      .source(source)
      .format(AwsNeptuneBulkLoader.Format)
      .s3BucketRegion(bucketRegion)
      .iamRoleArn(roleArn)
      // Not sent, the service default is AUTO, which resumes an earlier load
      // of the same source. A rerun must start a new load.
      .mode(Mode.NEW)
      .failOnError(failOnError)
      .userProvidedEdgeIds(true)
      .queueRequest(true)
    val request =
      if kind == "edge" then base.parallelism(edgeParallelism) else base
    val response = AwsErrors.call("Neptune StartLoaderJob")(
      client.startLoaderJob(request.build())
    )
    Option(response.payload)
      .flatMap(p => Option(p.get("loadId")))
      .filter(_.nonEmpty)
      .getOrElse(
        Fail(
          ErrorCode.BulkLoadFailed,
          s"StartLoaderJob for $source returned no loadId"
        )
      )

  private def poll(source: String, loadId: String): LoaderJobReport =
    var polls = 0
    var report: Option[LoaderJobReport] = None
    while report.isEmpty do
      val response = AwsErrors.call("Neptune GetLoaderJobStatus")(
        client.getLoaderJobStatus(
          GetLoaderJobStatusRequest
            .builder()
            .loadId(loadId)
            .details(true)
            .build()
        )
      )
      val (status, counts, samples) =
        AwsNeptuneBulkLoader.parse(response.payload)
      if AwsNeptuneBulkLoader.InProgress.contains(status) then
        polls += 1
        Fail.unless(
          polls < maxPolls,
          ErrorCode.BulkLoadFailed,
          s"Load $loadId of $source was still $status after $polls polls; " +
            "the job is left running and was not cancelled"
        )
        sleeper.sleep(pollMillis)
      else
        report = Some(LoaderJobReport(source, loadId, status, counts, samples))
    report.get

  private def failed(job: LoaderJobReport): Boolean =
    job.status != AwsNeptuneBulkLoader.Success || job.counts.errors > 0

  /** The failure text: status, counts and as many of the first error records as
    * fit under `MaxErrorTextChars`, on one line.
    */
  private def describe(job: LoaderJobReport): String =
    val texts = job.errorRecords.take(AwsNeptuneBulkLoader.MaxLoggedErrors)
    val sums = texts.map(_.text).scanLeft(0)((n, t) => n + t.length + 3).tail
    val shown = texts
      .map(_.text)
      .zip(sums)
      .takeWhile((_, sum) => sum <= AwsNeptuneBulkLoader.MaxErrorTextChars)
      .map(_._1)
    val omitted = job.errorRecords.size - shown.size
    s"${job.source} load ${job.loadId} ended ${job.status}: " +
      s"records ${job.counts.totalRecords}, duplicates " +
      s"${job.counts.totalDuplicates}, parsing errors " +
      s"${job.counts.parsingErrors}, datatype mismatches " +
      s"${job.counts.datatypeMismatchErrors}, insert errors " +
      s"${job.counts.insertErrors}" +
      (if shown.isEmpty then ""
       else shown.mkString("; first errors: ", " | ", "")) +
      (if omitted > 0 && shown.nonEmpty then
         s" | (+$omitted more error records not shown here)"
       else "") +
      (if job.errorsTruncated then "; the error list is incomplete" else "")

  private def safeLog(line: String): Unit =
    try log(line)
    catch case NonFatal(_) => ()

  private def page(loadId: String, number: Int) =
    AwsErrors
      .call("Neptune GetLoaderJobStatus errors")(
        client.getLoaderJobStatus(
          GetLoaderJobStatusRequest
            .builder()
            .loadId(loadId)
            .details(true)
            .errors(true)
            .page(number)
            .errorsPerPage(AwsNeptuneBulkLoader.ErrorsPerPage)
            .build()
        )
      )
      .payload

  /** The job with its error records, fetched page by page until the counted
    * errors are covered or `MaxFetchedErrors` is reached. Never throws: a
    * failed page ends the fetch, prints `NEPTUNE_LOAD_ERROR_FETCH_FAILED` and
    * marks the list truncated. An empty first page for a job that counted
    * errors is read once more after a poll interval, in case the loader writes
    * its error log after it reports the final status.
    */
  private def withErrors(number: Int, job: LoaderJobReport): LoaderJobReport =
    import AwsNeptuneBulkLoader.*
    val counted = job.counts.errors
    var records = Vector.empty[LoadErrorRecord]
    var next = 1
    var retried = false
    var truncated = false
    var more = true
    var shape = ""
    // A record seen on an earlier page is not named twice, so a server that
    // ignores `page` cannot make the count look covered.
    val seen = scala.collection.mutable.Set.empty[(String, String, String)]
    while more do
      try
        val payload = page(job.loadId, next)
        val got = parseErrors(payload)
        shape = shapeOf(payload)
        if got.isEmpty && next == 1 && counted > 0 && !retried then
          retried = true
          sleeper.sleep(pollMillis)
        else
          val fresh = got.filter(r => seen.add((r.file, r.record, r.code)))
          records = records ++ fresh
          next += 1
          more = counted > 0 && got.size >= ErrorsPerPage && fresh.nonEmpty &&
            records.size < counted && records.size < MaxFetchedErrors
      catch
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          truncated = true
          more = false
        case NonFatal(error) =>
          val reason = clean(
            s"${error.getClass.getSimpleName}: ${error.getMessage}",
            MaxReasonChars
          )
          safeLog(s"NEPTUNE_LOAD_ERROR_FETCH_FAILED reason=$reason")
          truncated = true
          more = false
    if records.size >= MaxFetchedErrors then
      truncated = truncated || counted > MaxFetchedErrors
      records = records.take(MaxFetchedErrors)
    if records.isEmpty && !truncated && counted > 0 then
      safeLog(
        s"NEPTUNE_LOAD_ERROR_NONE job=$number load=${job.loadId} " +
          s"counted=$counted $shape"
      )
    records.take(MaxLoggedErrors).foreach(r => safeLog(r.line(number)))
    safeLog(
      s"NEPTUNE_LOAD_ERRORS_TOTAL n=$counted named=${records.size} job=$number"
    )
    job.copy(
      errorSamples = records.take(MaxLoggedErrors).map(_.text),
      errorRecords = records,
      errorsTruncated = truncated
    )

  def load(manifestKey: String): BulkLoadResult =
    val files = plan(manifestKey)
    val done = Vector.newBuilder[LoaderJobReport]
    files.zipWithIndex.foreach { (file, index) =>
      val source = s"s3://$bucket/${file.key}"
      val job = poll(source, start(source, file.kind))
      if failed(job) then
        val named = withErrors(index + 1, job)
        // Only a finished load whose sole defect is record-level errors may be
        // returned, and only when the caller asked Neptune to skip bad records.
        // The docs do not say whether such a load ends LOAD_COMPLETED or
        // LOAD_FAILED, so both count when error counters are above zero; the
        // real status stays in the job report. Every other status, and a
        // LOAD_FAILED with no counted error, throws.
        val tolerated =
          !failOnError && job.counts.errors > 0 &&
            AwsNeptuneBulkLoader.RecordErrorStatuses.contains(job.status)
        Fail.unless(tolerated, ErrorCode.BulkLoadFailed, describe(named))
        done += named
      else done += job
    }
    val jobs = done.result()
    val total = jobs.map(_.counts.totalRecords).sum
    val duplicates = jobs.map(_.counts.totalDuplicates).sum
    val errors = jobs.map(_.counts.errors).sum
    def toInt(value: Long): Int = math.min(value, Int.MaxValue.toLong).toInt
    BulkLoadResult(
      inserted = toInt(math.max(0L, total - duplicates - errors)),
      updated = 0,
      unchanged = toInt(duplicates),
      jobs = jobs
    )
