package scholarchain.rag

import scholarchain.storage.*

/** One load job as the report shows it: the base name of its S3 source, its
  * final status and how many errors it counted (the count is for the policy and
  * is not written to the report).
  */
final case class JobStatus(source: String, status: String, errors: Long)

/** What the Neptune bulk load reported as errors, aggregated over all jobs and
  * masked of account IDs. A plain copy of `BulkLoadResult`'s accessors; nothing
  * here is derived from CSV row counts, because Neptune counts property
  * records, not CSV lines.
  */
final case class LoadErrors(
    insertErrors: Long,
    parsingErrors: Long,
    datatypeMismatchErrors: Long,
    totalErrors: Long,
    totalRecords: Long,
    records: Vector[LoadErrorRecord],
    errorsTruncated: Boolean,
    allErrorsNamed: Boolean,
    jobs: Vector[JobStatus] = Vector.empty
):
  /** Named records per error code, in code order. */
  def codes: Map[String, Int] =
    scala.collection.immutable.TreeMap.from(
      records.groupBy(_.code).view.mapValues(_.size)
    )

  def failedJobs: Int = jobs.count(_.status == LoadErrorPolicy.LoadFailed)

object LoadErrors:
  private def masked(record: LoadErrorRecord): LoadErrorRecord =
    LoadErrorRecord(
      Text.maskAccountIds(record.code),
      Text.maskAccountIds(record.file),
      Text.maskAccountIds(record.record),
      Text.maskAccountIds(record.message)
    )

  private def baseName(source: String): String =
    Text.maskAccountIds(source.substring(source.lastIndexOf('/') + 1))

  def of(load: BulkLoadResult): LoadErrors =
    LoadErrors(
      load.insertErrors,
      load.parsingErrors,
      load.datatypeMismatchErrors,
      load.totalErrors,
      load.totalRecords,
      load.errorRecords.map(masked),
      load.errorsTruncated,
      load.allErrorsNamed,
      load.jobs.map(j =>
        JobStatus(baseName(j.source), j.status, j.counts.errors)
      )
    )

/** The loader's tolerance for load errors, applied only when the operator
  * passed `--tolerate-load-errors`. The policy lives here and never in storage,
  * which only reports what Neptune said.
  *
  * A load with errors is acceptable, with a warning, when
  *   - every job ended LOAD_COMPLETED, or LOAD_FAILED with record-level errors
  *     counted;
  *   - every named record has a tolerated error code (`ToleratedCodes`);
  *   - every error is named and not truncated;
  *   - the errors are at most 0.1% of the records Neptune counted:
  *     `totalErrors * 1000 <= totalRecords`, aggregated over all load jobs.
  *
  * A named record with any other code is never tolerated: the three job-level
  * codes in `NeverToleratedCodes` and every code this table does not list.
  */
object LoadErrorPolicy:
  val ThresholdPercent: Double = 0.1
  val ThresholdText = "0.1%"
  val RecordUnit = "Neptune records (property-level, not CSV lines)"
  val LoadCompleted = "LOAD_COMPLETED"
  val LoadFailed = "LOAD_FAILED"

  /** Neptune's bucket for a record whose problem it could not classify. */
  val Unclassified = "INTERNAL_ERROR"

  val ToleratedCodes: Set[String] = Set(
    "PARSING_ERROR",
    "FROM_OR_TO_VERTEX_ARE_MISSING",
    "ID_ASSIGNED_TO_MULTIPLE_EDGES",
    "SINGLE_CARDINALITY_VIOLATION",
    Unclassified
  )

  /** Job-level failures: the load, not a record, went wrong. */
  val NeverToleratedCodes: Set[String] = Set(
    "OUT_OF_MEMORY_ERROR",
    "S3_ACCESS_DENIED_ERROR",
    "FILE_MODIFICATION_OR_DELETION_ERROR"
  )

  enum Verdict:
    case Clean
    case Tolerated
    case Rejected(reason: String)

  /** `errors * 1000 <= records` as integers. For whole numbers this is the same
    * as `errors <= records / 1000` (integer division), which cannot overflow.
    */
  def withinThreshold(errors: Long, records: Long): Boolean =
    errors <= records / 1000L

  private def badJob(errors: LoadErrors): Option[String] =
    errors.jobs.collectFirst {
      case j
          if j.status != LoadCompleted &&
            !(j.status == LoadFailed && j.errors > 0L) =>
        val why =
          if j.status == LoadFailed then
            "LOAD_FAILED with no record-level errors"
          else s"status ${j.status}"
        s"the load job ${j.source} ended $why"
    }

  private def badCodes(errors: LoadErrors): Option[String] =
    val bad = errors.codes.filterNot((code, _) => ToleratedCodes(code))
    Option.when(bad.nonEmpty) {
      bad.toVector
        .map { (code, n) =>
          val kind =
            if NeverToleratedCodes(code) then "never tolerated"
            else "not a known code, so not tolerated"
          s"$n named record(s) have the error code $code, which is $kind"
        }
        .mkString("the load had ", "; ", "")
    }

  def assess(errors: LoadErrors): Verdict =
    badJob(errors) match
      case Some(why) => Verdict.Rejected(why)
      case None      =>
        if errors.totalErrors == 0L then Verdict.Clean
        else
          badCodes(errors) match
            case Some(why) => Verdict.Rejected(why)
            case None      => assessCounts(errors)

  private def assessCounts(errors: LoadErrors): Verdict =
    if errors.errorsTruncated then
      Verdict.Rejected(
        s"the load had ${errors.totalErrors} errors and the error list is " +
          "incomplete; every error must be named to be tolerated"
      )
    else if !errors.allErrorsNamed then
      Verdict.Rejected(
        s"the load had ${errors.totalErrors} errors but only " +
          s"${errors.records.size} were named; every error must be named " +
          "to be tolerated"
      )
    else if !withinThreshold(errors.totalErrors, errors.totalRecords) then
      Verdict.Rejected(
        s"the load had ${errors.totalErrors} errors in ${errors.totalRecords} " +
          s"records, over the $ThresholdText tolerance"
      )
    else Verdict.Tolerated

  /** What is and is not known about jobs that ended LOAD_FAILED. */
  def unverified(failedJobs: Int): String =
    s"$failedJobs job(s) ended LOAD_FAILED; Neptune reported only " +
      "record-level errors for them, but it is UNVERIFIED whether the rest " +
      "of each file was loaded; compare the NEPTUNE_JOB records= count of " +
      "each job."

  /** The `LOADER_SCOPE` text of a tolerated load. */
  def scopeText(errors: LoadErrors): String =
    if errors.failedJobs > 0 then
      "LOADER_SCOPE=vectors-and-graph (the graph load ended WITH ERRORS; " +
        unverified(errors.failedJobs).stripSuffix(".") + ")"
    else
      "LOADER_SCOPE=vectors-and-graph (the graph loaded WITH ERRORS: " +
        "the named records were skipped)"

  /** `graph.errors` of a tolerated result. `loadCompletenessVerified` is
    * present, and false, only when a LOAD_FAILED job was tolerated.
    */
  def toleratedErrorsJson(errors: LoadErrors): ujson.Obj =
    val json = errorsJson(errors)
    if errors.failedJobs > 0 then json("loadCompletenessVerified") = false
    json

  /** The plain sentence that stands beside every tolerated result. It says what
    * is unusual about this load (unclassified records, jobs that ended
    * LOAD_FAILED) and always ends in the negated claim.
    */
  def warning(errors: LoadErrors): String =
    val unclassified = errors.codes.getOrElse(Unclassified, 0)
    val failed = errors.failedJobs
    // With a LOAD_FAILED job nobody has verified that the rest of its file was
    // loaded, so the sentence makes no claim that records were skipped.
    (if failed > 0 then
       s"The graph load reported ${errors.totalErrors} errors. " +
         unverified(failed)
     else
       s"The graph loaded with ${errors.totalErrors} errors; the named " +
         "records were skipped by Neptune."
    ) +
      (if unclassified > 0 then
         s" $unclassified records were reported by Neptune as $Unclassified, " +
           "i.e. unclassified."
       else "") +
      " This is NOT a clean load."

  def warningTag(errors: LoadErrors): String =
    s"graph-load-errors:${errors.totalErrors}"

  /** A verdict with the errors it was made from. */
  final case class Assessment(errors: LoadErrors, verdict: Verdict):
    def tolerated: Boolean = verdict == Verdict.Tolerated

  def assessment(load: BulkLoadResult): Assessment =
    val errors = LoadErrors.of(load)
    Assessment(errors, assess(errors))

  def errorsJson(errors: LoadErrors): ujson.Obj =
    ujson.Obj(
      "insertErrors" -> ujson.Num(errors.insertErrors.toDouble),
      "parsingErrors" -> ujson.Num(errors.parsingErrors.toDouble),
      "datatypeMismatchErrors" ->
        ujson.Num(errors.datatypeMismatchErrors.toDouble),
      "totalErrors" -> ujson.Num(errors.totalErrors.toDouble),
      "totalRecords" -> ujson.Num(errors.totalRecords.toDouble),
      "thresholdPercent" -> ThresholdPercent,
      "recordUnit" -> RecordUnit,
      "codes" -> ujson.Obj.from(
        errors.codes.toVector.map((code, n) => code -> ujson.Num(n.toDouble))
      ),
      "jobStatuses" -> ujson.Arr.from(errors.jobs.map { j =>
        ujson.Obj("source" -> j.source, "status" -> j.status)
      }),
      "errorRecords" -> ujson.Arr.from(errors.records.map { r =>
        ujson.Obj(
          "code" -> r.code,
          "file" -> r.file,
          "record" -> r.record,
          "message" -> r.message
        )
      }),
      "errorsTruncated" -> errors.errorsTruncated,
      "allErrorsNamed" -> errors.allErrorsNamed
    )

  def loadedWithErrorsLine(errors: LoadErrors): String =
    s"NEPTUNE_LOAD=LOADED_WITH_ERRORS errors=${errors.totalErrors} " +
      s"records=${errors.totalRecords} named=${errors.records.size} " +
      s"threshold=$ThresholdText"

  /** One line per job that did not end LOAD_COMPLETED. */
  def jobStatusLines(errors: LoadErrors): Vector[String] =
    errors.jobs
      .filter(_.status != LoadCompleted)
      .map(j => s"NEPTUNE_JOB_STATUS source=${j.source} status=${j.status}")

  /** One line per named record, and one summary line. Neither prefix starts
    * with storage's live `NEPTUNE_LOAD_ERROR`, so a count of those never
    * doubles.
    */
  def skippedLines(errors: LoadErrors): Vector[String] =
    // A record of a LOAD_FAILED job is named, not claimed to be skipped.
    val failedFiles =
      errors.jobs.filter(_.status == LoadFailed).map(_.source).toSet
    errors.records.map { r =>
      val prefix =
        if failedFiles(r.file) then "NEPTUNE_LOAD_NAMED_UNVERIFIED"
        else "NEPTUNE_LOAD_SKIPPED"
      s"$prefix file=${r.file} code=${r.code} " +
        s"record=${r.record} message=${r.message}"
    }

  def summaryLine(errors: LoadErrors): String =
    val byFile = errors.records
      .groupBy(_.file)
      .toVector
      .sortBy(_._1)
      .map((file, group) => s"$file:${group.size}")
    val byCode = errors.codes.toVector.map((code, n) => s"$code:$n")
    s"NEPTUNE_LOAD_SKIPPED_SUMMARY total=${errors.totalErrors} " +
      s"insert=${errors.insertErrors} parsing=${errors.parsingErrors} " +
      s"datatype=${errors.datatypeMismatchErrors} " +
      s"records=${errors.totalRecords} named=${errors.records.size} " +
      "unit=neptune-property-records-not-csv-lines " +
      s"by_file=${if byFile.isEmpty then "none" else byFile.mkString(",")} " +
      s"by_code=${if byCode.isEmpty then "none" else byCode.mkString(",")}" +
      (if errors.failedJobs > 0 then
         s" load_completeness=UNVERIFIED failed_jobs=${errors.failedJobs}"
       else "")
