package scholarchain.services

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{
  ArrayBlockingQueue,
  CompletableFuture,
  ExecutionException,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.core.{AnswerResponse, YearWindow}
import scholarchain.rag.{RagRuntime, Session}
import scholarchain.storage.Hashing

enum QueryMode(val wire: String):
  case Sync extends QueryMode("sync")
  case Async extends QueryMode("async")

final case class QueryFilters(
    topic: Option[String],
    venue: Option[String],
    institution: Option[String]
):
  def entries: Vector[(String, String)] = Vector(
    topic.map("topic" -> _),
    venue.map("venue" -> _),
    institution.map("institution" -> _)
  ).flatten

// A validated and accepted query request. Every constraint in it is one the
// workflow can apply: `workflowQuestion` is the question text the workflow
// receives, with the structured window already carried in it. The filters and
// maxResults are parsed and validated, but a request that holds one never gets
// this far: `QueryRequests.resolve` refuses it with 422 UNSUPPORTED_CONSTRAINT
// (decision D13), so both are always empty here.
final case class QueryRequest(
    question: String,
    workflowQuestion: String,
    window: Option[YearWindow],
    filters: QueryFilters,
    mode: QueryMode,
    maxResults: Option[Int]
)

/** The request as parsed, before the mode is resolved against the `Prefer`
  * header and the configuration.
  */
final case class ParsedQuery(
    question: String,
    window: Option[YearWindow],
    filters: QueryFilters,
    mode: Option[QueryMode],
    maxResults: Option[Int]
)

object QueryRequests:
  private val topLevel =
    Set("question", "window", "filters", "mode", "limits")
  private val filterKeys = Set("topic", "venue", "institution")
  private val maxFilterChars = 200

  private def invalid(message: String): ApiException =
    new ApiException(ErrorCode.InvalidRequest, message)

  private def unsupported(message: String): ApiException =
    new ApiException(ErrorCode.UnsupportedConstraint, message)

  private def decode(body: Array[Byte]): String =
    try
      UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(body))
        .toString
    catch
      case _: CharacterCodingException =>
        throw new ApiException(
          ErrorCode.InvalidJson,
          "The request body is not valid UTF-8 JSON"
        )

  private def hasControl(text: String): Boolean =
    text.exists(c =>
      Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'
    )

  private def intField(obj: ujson.Obj, key: String): Int =
    obj.value.get(key) match
      case Some(ujson.Num(d))
          if d.isWhole && d >= Int.MinValue && d <= Int.MaxValue =>
        d.toInt
      case _ => throw invalid(s"$key must be a whole number")

  private def parseWindow(value: ujson.Value): Option[YearWindow] =
    value match
      case ujson.Null   => None
      case w: ujson.Obj =>
        if w.value.keys.exists(k => k != "startYear" && k != "endYear") then
          throw invalid("window holds only startYear and endYear")
        val start = intField(w, "startYear")
        val end = intField(w, "endYear")
        if start < 1 || end > 9999 || start > end then
          throw unsupported(
            "window must be years from 1 to 9999 with startYear not after endYear"
          )
        Some(YearWindow(start, end))
      case _ => throw invalid("window must be an object")

  private def parseFilters(value: ujson.Value): QueryFilters =
    value match
      case ujson.Null   => QueryFilters(None, None, None)
      case f: ujson.Obj =>
        if f.value.keys.exists(!filterKeys.contains(_)) then
          throw unsupported(
            "Only the topic, venue and institution filters exist"
          )
        def one(key: String): Option[String] =
          f.value.get(key).map {
            case ujson.Str(s) =>
              val text = s.trim
              if text.isEmpty || text.length > maxFilterChars || hasControl(
                  text
                )
              then throw invalid(s"The $key filter is not valid")
              text
            case _ => throw invalid(s"The $key filter must be a string")
          }
        QueryFilters(one("topic"), one("venue"), one("institution"))
      case _ => throw invalid("filters must be an object")

  private def parseMode(
      value: ujson.Value
  ): Option[QueryMode] =
    value match
      case ujson.Null                   => None
      case ujson.Str(s) if s == "sync"  => Some(QueryMode.Sync)
      case ujson.Str(s) if s == "async" => Some(QueryMode.Async)
      case ujson.Str(_)                 =>
        throw unsupported("mode must be sync or async")
      case _ => throw invalid("mode must be a string")

  private def parseLimits(
      value: ujson.Value,
      config: ServiceConfig
  ): Option[Int] =
    value match
      case ujson.Null   => None
      case l: ujson.Obj =>
        if l.value.keys.exists(_ != "maxResults") then
          throw unsupported("Only the maxResults limit exists")
        if !l.value.contains("maxResults") then None
        else
          val n = intField(l, "maxResults")
          if n < 1 || n > config.maxResultLimit then
            throw unsupported(
              s"maxResults must be from 1 to ${config.maxResultLimit}"
            )
          Some(n)
      case _ => throw invalid("limits must be an object")

  def parse(body: Array[Byte], config: ServiceConfig): ParsedQuery =
    val json =
      try ujson.read(decode(body))
      catch
        case e: ApiException => throw e
        case NonFatal(_)     =>
          throw new ApiException(
            ErrorCode.InvalidJson,
            "The request body is not valid JSON"
          )
    val obj = json match
      case o: ujson.Obj => o
      case _ => throw invalid("The request body must be a JSON object")
    if obj.value.keys.exists(!topLevel.contains(_)) then
      throw invalid("The request body holds an unknown field")
    val question = obj.value.get("question") match
      case Some(ujson.Str(s)) => s.trim
      case _                  => throw invalid("question must be a string")
    if question.isEmpty then throw invalid("question must not be blank")
    if question.length > config.maxQuestionChars then
      throw invalid(
        s"question must have at most ${config.maxQuestionChars} characters"
      )
    if hasControl(question) then
      throw invalid("question holds a control character")
    ParsedQuery(
      question,
      parseWindow(obj.value.getOrElse("window", ujson.Null)),
      parseFilters(obj.value.getOrElse("filters", ujson.Null)),
      parseMode(obj.value.getOrElse("mode", ujson.Null)),
      parseLimits(obj.value.getOrElse("limits", ujson.Null), config)
    )

  /** The `Prefer: respond-async` preference of RFC 7240. */
  def prefersAsync(header: Option[String]): Boolean =
    header.exists(
      _.split(",").exists(
        _.split(";")(0).trim.equalsIgnoreCase("respond-async")
      )
    )

  private val yearToken = "(?<![0-9])[0-9]{4}(?![0-9])".r
  private val yearRange =
    "(?i)(?<![0-9])([0-9]{4})\\s*(?:-|–|—|to|through)\\s*([0-9]{4})(?![0-9])".r
  private val yearCue = ("(?i)\\b(?:since|in|from|before|after|until|till|" +
    "between|during|and|year|for|of)\\s*$").r
  private val countCueBefore = ("(?i)\\b(?:top|first|most|best|at least|" +
    "at most|up to|last|limit(?:\\s+of|\\s+to)?)\\s*$").r
  private val countNounAfter = ("(?i)^\\s*(?:co-?authors?|collaborators?|" +
    "papers?|publications?|researchers?|authors?|articles?|results?|people|" +
    "scholars?|citations?|works|items?|nodes?|links?|edges?|names?|entries|" +
    "records?)\\b").r

  // Test-only (N-4). Production code does not call this: `compose` decides
  // with `planWindows` and `strayYears`. It is a coarser, older heuristic
  // (years 1900 to 2100 in a year context) kept so its tests still pin that
  // behavior. It is not the planner's reading and must not gate a request.
  def years(question: String): Set[Int] =
    val inRange = yearRange
      .findAllMatchIn(question)
      .flatMap(m => Vector(m.start(1), m.start(2)))
      .toSet
    yearToken
      .findAllMatchIn(question)
      .filter { m =>
        val n = m.matched.toInt
        val before = question.substring(0, m.start)
        val after = question.substring(m.end)
        n >= 1900 && n <= 2100 &&
        countCueBefore.findFirstIn(before).isEmpty &&
        countNounAfter.findFirstIn(after).isEmpty &&
        (inRange.contains(m.start) || yearCue.findFirstIn(before).isDefined)
      }
      .map(_.matched.toInt)
      .toSet

  // Repair 1 (M-1). `Planner.windowsOf` is private[rag], so services keep an
  // equivalent: the same five patterns, the same priority and the same span
  // overlap rule, run after the planner's own removal of researcher IDs. If
  // the planner changes its window reading, this copy must change with it.
  private val planBetween = "(?i)between\\s+(\\d{4})\\s+and\\s+(\\d{4})".r
  private val planDash =
    "(?i)\\b(\\d{4})\\s*(?:-|–|—|to|through)\\s*(\\d{4})\\b".r
  private val planSince = "(?i)since\\s+(\\d{4})".r
  private val planOne = "(?i)\\b(?:in|during|for|of|year)\\s+(\\d{4})\\b".r
  private val planAllTime =
    "(?i)all time|overall|all years|entire period|full period".r
  private val planIds =
    "(?:researcher|mention):v1:[A-Za-z0-9_\\-]+(?::[A-Za-z0-9_\\-]+)?".r

  // The windows the planner reads, with their spans, in text order.
  private def planWindows(text: String): Vector[(Int, Int, (Int, Int))] =
    def pair(r: scala.util.matching.Regex) =
      r.findAllMatchIn(text)
        .map(m => (m.start, m.end, (m.group(1).toInt, m.group(2).toInt)))
        .toVector
    def one(r: scala.util.matching.Regex, hi: Option[Int]) =
      r.findAllMatchIn(text)
        .map { m =>
          val y = m.group(1).toInt
          (m.start, m.end, (y, hi.getOrElse(y)))
        }
        .toVector
    val candidates = pair(planBetween) ++ pair(planDash) ++
      one(planSince, Some(9999)) ++ one(planOne, None) ++
      planAllTime
        .findAllMatchIn(text)
        .map(m => (m.start, m.end, (1, 9999)))
        .toVector
    candidates
      .foldLeft(Vector.empty[(Int, Int, (Int, Int))]) { (kept, next) =>
        if kept.exists(k => next._1 < k._2 && k._1 < next._2) then kept
        else kept :+ next
      }
      .sortBy(_._1)

  // A 4-digit number from 1000 to 9999 outside every window span, that is not
  // a count. "top 1000" and "2015 coauthors" are counts, but "in 2015
  // publications" is a year because a year cue precedes it.
  private def strayYears(
      text: String,
      spans: Vector[(Int, Int, (Int, Int))]
  ): Vector[Int] =
    yearToken
      .findAllMatchIn(text)
      .filter { m =>
        val n = m.matched.toInt
        val before = text.substring(0, m.start)
        val after = text.substring(m.end)
        val counted = countCueBefore.findFirstIn(before).isDefined ||
          (countNounAfter.findFirstIn(after).isDefined &&
            yearCue.findFirstIn(before).isEmpty)
        n >= 1000 && !counted &&
        !spans.exists(s => m.start >= s._1 && m.end <= s._2)
      }
      .map(_.matched.toInt)
      .toVector

  // The workflow takes its window from the question text, so the services must
  // know what the planner will read. A structured window is added as a fixed
  // four-digit clause only when the planner reads no window and the question
  // holds no other year. The question passes unchanged only when the planner reads
  // exactly one window, equal to the structured one. Anything else is refused
  // (422), never reinterpreted: "since 2015", "in 2005 and 2009", "in 1850",
  // "in 2015 publications" and "overall" all conflict. Counts such as "top
  // 1000 coauthors" are not years. The echo of the window always equals the
  // window the planner applies, or the request is refused.
  def compose(question: String, window: Option[YearWindow]): String =
    window match
      case None    => question
      case Some(w) =>
        val bare = planIds.replaceAllIn(question, " ")
        val spans = planWindows(bare)
        val stray = strayYears(bare, spans)
        if spans.isEmpty && stray.isEmpty then
          // Four digits each: the planner reads only `\d{4}` years, and
          // "0001-2009" reads as exactly (1, 2009) for every year 1 to 9999.
          val text = f"$question from ${w.startYear}%04d-${w.endYear}%04d"
          if text.length > RagRuntime.MaxQuestionChars then
            throw unsupported("The window does not fit in the question")
          text
        else if stray.isEmpty && spans.map(_._3) == Vector(
            (w.startYear, w.endYear)
          )
        then question
        else
          throw unsupported(
            "The window conflicts with the years named in the question"
          )

  // An explicit mode wins. Without one, `Prefer: respond-async` selects async
  // when it is enabled. An explicit async mode is 422 when it is disabled.
  //
  // Decision D13: a constraint the Phase 9 workflow cannot apply is refused
  // with 422, never accepted and ignored. The topic, venue and institution
  // filters and limits.maxResults stay in the schema and are validated by
  // `parse`, but the workflow has no structured input for them, so a request
  // that holds one is refused here until the workflow supports it (Phase 12).
  def resolve(
      parsed: ParsedQuery,
      preferAsync: Boolean,
      config: ServiceConfig
  ): QueryRequest =
    if parsed.filters.entries.nonEmpty then
      throw unsupported(
        "The topic, venue and institution filters are not supported"
      )
    if parsed.maxResults.isDefined then
      throw unsupported("The maxResults limit is not supported")
    val workflowQuestion = compose(parsed.question, parsed.window)
    val mode = parsed.mode match
      case Some(QueryMode.Async) if !config.asyncEnabled =>
        throw unsupported("Asynchronous queries are not enabled")
      case Some(m) => m
      case None    =>
        if preferAsync && config.asyncEnabled then QueryMode.Async
        else QueryMode.Sync
    QueryRequest(
      parsed.question,
      workflowQuestion,
      parsed.window,
      parsed.filters,
      mode,
      parsed.maxResults
    )

  // The idempotency fingerprint is byte-exact: it hashes the raw request body
  // and the resolved mode. The same JSON with reordered keys, different
  // whitespace or another escaping is a different request, so repeating it
  // under the same Idempotency-Key is 422 IDEMPOTENCY_KEY_REUSED. A client
  // that retries must resend the identical bytes.
  def fingerprint(body: Array[Byte], mode: QueryMode): String =
    Hashing.sha256(body) + "|" + mode.wire

/** The result of one answered query, as the services keep and replay it. */
final case class QueryOutcome(
    response: AnswerResponse,
    abstained: Boolean,
    abstentionReason: Option[String],
    request: QueryRequest
):
  def queryId: String = response.queryId

  // The core answer wire shape, untouched, plus the service fields. The
  // `request` echo holds only what was applied: the mode and the structured
  // window. There is no echo of filters or maxResults (a request that holds
  // one is refused with 422) and no `windowApplied` (a window is applied or the
  // request is refused).
  def toJson: ujson.Value =
    val core = upickle.default.writeJs(response)
    val window = request.window.fold[ujson.Value](ujson.Null)(w =>
      ujson.Obj("startYear" -> w.startYear, "endYear" -> w.endYear)
    )
    val extras = Vector[(String, ujson.Value)](
      "abstained" -> ujson.Bool(abstained),
      "abstentionReason" -> J.opt(abstentionReason),
      "request" -> ujson.Obj("mode" -> request.mode.wire, "window" -> window)
    )
    ujson.Obj.from(core.obj.value.toSeq ++ extras)

object QueryViews:
  // `state` is "queued" for a new job. A replayed 202 reports the job's current
  // state, so an idempotent repeat never claims "queued" for a job that has run.
  def accepted(job: Accepted, state: JobState): ujson.Value =
    ujson.Obj(
      "jobId" -> job.jobId,
      "state" -> state.wire,
      "createdAt" -> J.instant(job.createdAt),
      "statusLink" -> s"/v1/jobs/${job.jobId}"
    )

// Accepts query requests that are already valid and already free of
// constraints the workflow cannot apply (D13), applies idempotency, then
// answers synchronously or hands the work to the job service. The answer comes
// from the rag workflow through the workflow port. This class composes nothing
// but the question text, and it does not itself retrieve, rank or verify:
// retrieval, ranking and verification happen inside the workflow. The graph
// exploration read-through lives in GraphService (D16), not here.
final class QueryService(
    config: ServiceConfig,
    workflow: WorkflowPort,
    call: Downstream,
    records: QueryRecordStore,
    idempotency: IdempotencyPort,
    jobs: JobService,
    clock: () => Instant = () => Instant.now()
):
  def run(request: QueryRequest, deadline: Deadline): QueryOutcome =
    val session = Session("svc-" + UUID.randomUUID().toString, 0)
    val result =
      call.call(deadline, idempotent = false)(
        workflow.answer(request.workflowQuestion, session)
      )
    val bundle = result.response.evidence
    records.put(
      QueryRecord(
        result.response.queryId,
        session.sessionId,
        session.ordinal,
        bundle.bundleId,
        bundle.items.map(_.evidenceId),
        clock()
      )
    )
    QueryOutcome(
      result.response,
      result.abstained,
      result.abstentionReason,
      request
    )

  private def dispatch(
      request: QueryRequest,
      deadline: Deadline
  ): StoredResult =
    request.mode match
      case QueryMode.Sync  => Answered(run(request, deadline))
      case QueryMode.Async =>
        jobs.submit(
          request,
          r => run(r, new Deadline(config.slowDeadlineMillis))
        )

  private def replay(
      future: CompletableFuture[StoredResult],
      deadline: Deadline
  ): StoredResult =
    val stored =
      try
        future.get(
          math.max(1L, deadline.remainingMillis),
          TimeUnit.MILLISECONDS
        )
      catch
        case _: TimeoutException =>
          throw new ApiException(
            ErrorCode.Timeout,
            "The original request did not finish within the deadline"
          )
        case e: ExecutionException =>
          e.getCause match
            case a: ApiException => throw a
            case _               =>
              throw new ApiException(
                ErrorCode.DownstreamUnavailable,
                "The original request failed"
              )
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          throw new ApiException(
            ErrorCode.Internal,
            "The request was interrupted"
          )
    stored match
      case accepted: Accepted => accepted.copy(replayed = true)
      case other              => other

  def submit(
      request: QueryRequest,
      key: Option[String],
      fingerprint: String,
      deadline: Deadline
  ): StoredResult =
    key match
      case None    => dispatch(request, deadline)
      case Some(k) =>
        idempotency.claim(k, fingerprint) match
          case Claim.Replay(future)   => replay(future, deadline)
          case Claim.Owner(_, future) =>
            try
              val result = dispatch(request, deadline)
              future.complete(result)
              result
            catch
              case NonFatal(e) =>
                future.completeExceptionally(e)
                idempotency.release(k, future)
                throw e
              // Decision P11-S1: a fatal Throwable (an Error, an interrupt)
              // also settles and releases the reservation, so a replay of the
              // key neither waits for a future nobody will complete (a
              // permanent 504) nor finds a stale record. Callers already
              // waiting on the future get a typed 500; a later replay finds the
              // key free and runs the query again. Nothing partial is stored.
              // The Throwable is logged by class only and rethrown, so the
              // HTTP layer still answers 500 with the trace ID and rethrows it.
              case t: Throwable =>
                try
                  future.completeExceptionally(
                    new ApiException(
                      ErrorCode.Internal,
                      "The request could not be completed"
                    )
                  )
                finally
                  idempotency.release(k, future)
                  QueryService.log
                    .error("query failed fatally type={}", t.getClass.getName)
                throw t

object QueryService:
  private[services] val log =
    LoggerFactory.getLogger("scholarchain.services.query")

object JobViews:
  def status(job: Job): ujson.Value =
    ujson.Obj(
      "jobId" -> job.jobId,
      "state" -> job.state.wire,
      "createdAt" -> J.instant(job.createdAt),
      "updatedAt" -> J.instant(job.updatedAt),
      "stage" -> job.stage,
      "error" -> job.error.fold[ujson.Value](ujson.Null)(e =>
        ujson.Obj("code" -> e.code.wire, "message" -> e.message)
      ),
      "queryId" -> J.opt(job.result.map(_.queryId)),
      "resultLink" -> (if job.state == JobState.Succeeded then
                         ujson.Str(s"/v1/jobs/${job.jobId}/result")
                       else ujson.Null)
    )

/** Runs query jobs on a bounded in-process executor. A full queue is 503. */
final class JobService(
    config: ServiceConfig,
    store: JobStore,
    metrics: Metrics,
    clock: () => Instant = () => Instant.now()
):
  private val executor = new ThreadPoolExecutor(
    config.jobExecutorSize,
    config.jobExecutorSize,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](config.jobQueueCapacity),
    Threads.daemon("jobs"),
    new ThreadPoolExecutor.AbortPolicy
  )

  private def fail(jobId: String, code: ErrorCode, message: String): Unit =
    store.update(jobId)(job =>
      job.copy(
        state = JobState.Failed,
        stage = "failed",
        updatedAt = clock(),
        error = Some(JobError(code, message))
      )
    )
    metrics.inc("jobs_total", "state" -> "failed")

  private def execute(
      jobId: String,
      request: QueryRequest,
      run: QueryRequest => QueryOutcome
  ): Unit =
    store.update(jobId)(job =>
      job.copy(state = JobState.Running, stage = "running", updatedAt = clock())
    )
    try
      val outcome = run(request)
      store.update(jobId)(job =>
        job.copy(
          state = JobState.Succeeded,
          stage = "completed",
          updatedAt = clock(),
          result = Some(outcome)
        )
      )
      metrics.inc("jobs_total", "state" -> "succeeded")
    catch
      case e: ApiException => fail(jobId, e.code, e.getMessage)
      case NonFatal(_)     =>
        fail(jobId, ErrorCode.Internal, "The query failed unexpectedly")
      // Decision P11-S1: a fatal Throwable ends the job as Failed too, never
      // leaving it Running. An interrupt (a shutdown) only restores the flag;
      // any other fatal Throwable is logged by class and rethrown.
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        fail(jobId, ErrorCode.Internal, "The query was interrupted")
      case t: Throwable =>
        try fail(jobId, ErrorCode.Internal, "The query failed unexpectedly")
        finally
          QueryService.log
            .error("job failed fatally type={}", t.getClass.getName)
        throw t

  def submit(
      request: QueryRequest,
      run: QueryRequest => QueryOutcome
  ): Accepted =
    val now = clock()
    val jobId = "job-" + UUID.randomUUID().toString
    store.create(Job(jobId, JobState.Queued, now, now, "queued", None, None))
    try executor.execute(() => execute(jobId, request, run))
    catch
      case _: RejectedExecutionException =>
        // The caller never receives this job ID, so no record of it is kept.
        store.remove(jobId)
        metrics.inc("jobs_total", "state" -> "rejected")
        throw new ApiException(
          ErrorCode.Overloaded,
          "Too many jobs are waiting",
          Vector.empty,
          Map("Retry-After" -> "1")
        )
    metrics.inc("jobs_total", "state" -> "queued")
    Accepted(jobId, now)

  // The state a 202 reports. A new job is queued. A replayed reference reports
  // the job's current state, or queued if the bounded store no longer holds it.
  def stateOf(accepted: Accepted): JobState =
    if !accepted.replayed then JobState.Queued
    else store.get(accepted.jobId).fold(JobState.Queued)(_.state)

  def get(jobId: String): Job =
    store
      .get(jobId)
      .getOrElse(throw new ApiException(ErrorCode.NotFound, "No such job"))

  def result(jobId: String): QueryOutcome =
    val job = get(jobId)
    job.state match
      case JobState.Succeeded =>
        job.result.getOrElse(
          throw new ApiException(ErrorCode.Internal, "The job has no result")
        )
      case JobState.Failed =>
        // The GET completed, so it never replays the job's own status (a
        // timed-out job would look like a retryable 504). It is 409 JOB_FAILED
        // with the job's original error code as the one entry of `details`.
        val e = job.error.getOrElse(
          JobError(ErrorCode.Internal, "The query failed unexpectedly")
        )
        throw new ApiException(
          ErrorCode.JobFailed,
          "The job ended in failure; its error is in the job status",
          Vector(e.code.wire)
        )
      case _ =>
        throw new ApiException(
          ErrorCode.ResultNotReady,
          "The job has not finished",
          Vector.empty,
          Map("Retry-After" -> "1")
        )

  def close(): Unit = executor.shutdownNow()
