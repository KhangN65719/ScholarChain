package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusRequest,
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

  val MaxErrorSamples = 5

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
    val logs = field(payload, "errors")
      .flatMap(errors => field(errors, "errorLogs"))
      .filter(_.isList)
      .map(_.asList.asScala.toVector)
      .getOrElse(Vector.empty)
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
  * true and `queueRequest` true, and is polled to a terminal state. The first
  * job that does not end in `LOAD_COMPLETED` with zero errors stops the load
  * with `BulkLoadFailed`, whose message carries the status, the counts and the
  * first error entries. Later files are not started.
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
    maxPolls: Int = 720
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

  private def start(source: String): String =
    val response = AwsErrors.call("Neptune StartLoaderJob")(
      client.startLoaderJob(
        StartLoaderJobRequest
          .builder()
          .source(source)
          .format(AwsNeptuneBulkLoader.Format)
          .s3BucketRegion(bucketRegion)
          .iamRoleArn(roleArn)
          .failOnError(true)
          .userProvidedEdgeIds(true)
          .queueRequest(true)
          .build()
      )
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
            .errors(true)
            .page(1)
            .errorsPerPage(AwsNeptuneBulkLoader.MaxErrorSamples)
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

  private def describe(job: LoaderJobReport): String =
    s"${job.source} load ${job.loadId} ended ${job.status}: " +
      s"records ${job.counts.totalRecords}, duplicates " +
      s"${job.counts.totalDuplicates}, parsing errors " +
      s"${job.counts.parsingErrors}, datatype mismatches " +
      s"${job.counts.datatypeMismatchErrors}, insert errors " +
      s"${job.counts.insertErrors}" +
      (if job.errorSamples.isEmpty then ""
       else job.errorSamples.mkString("; first errors: ", " | ", ""))

  def load(manifestKey: String): BulkLoadResult =
    val files = plan(manifestKey)
    val done = Vector.newBuilder[LoaderJobReport]
    files.foreach { file =>
      val source = s"s3://$bucket/${file.key}"
      val job = poll(source, start(source))
      done += job
      Fail.unless(failed(job) == false, ErrorCode.BulkLoadFailed, describe(job))
    }
    val jobs = done.result()
    val total = jobs.map(_.counts.totalRecords).sum
    val duplicates = jobs.map(_.counts.totalDuplicates).sum
    def toInt(value: Long): Int = math.min(value, Int.MaxValue.toLong).toInt
    BulkLoadResult(
      inserted = toInt(math.max(0L, total - duplicates)),
      updated = 0,
      unchanged = toInt(duplicates),
      jobs = jobs
    )
