package scholarchain.evaluation.answer

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal
import scholarchain.evaluation.io.{Digests, Js, RawAppender, RunOutput}
import scholarchain.evaluation.retrieval.{
  BenchmarkFiles,
  LabelRec,
  QuestionRec,
  Subsets
}
import scholarchain.evaluation.runtime.{
  CostGuard,
  CostLimits,
  CostStopped,
  EvalArgs,
  LaptopPorts,
  Rates,
  RunDeadline
}
import scholarchain.rag.{
  CollaborationWorkflow,
  RetrievalMode,
  Session,
  WorkflowResult
}

/** Run H: answer quality. Fused mode, Haiku, one workflow call per question on
  * the laptop runtime, behind the cost guard.
  *
  * Full mode answers every question once (the primary pass) and a seeded
  * stratified subset of the answerable ones a second time (the repeat pass,
  * temperature 0), then writes the frozen answer records, the stop-reason
  * summary, the per-call latency and token log and the rating sample.
  *
  * `--diagnostic` mode (decision P16-2) answers at most four questions of
  * different strata, with at most four answer-model calls in all, and writes
  * the stop reason and the rejection codes of every attempt. It writes no
  * question text and no answer text. Decision P16-28: the diagnostic is run
  * directly (no manifest or approval record); its bounds are in this process
  * only (at most four calls, whatever `--haiku-max-calls` says, and the USD
  * ceiling the human passes, 0.15 in the paste-ready command).
  *
  * Every answer record is appended to its file (`answers.jsonl`, or
  * `diagnostic.jsonl`) when the answer is done and flushed at once, and the
  * file is hashed before any summary, so a kill keeps the records made so far.
  * The wall-clock stop is the earlier of `maxMinutes` (from the start of the
  * loop) and an absolute `deadlineEpochMillis` (from the launcher's start); it
  * is checked between answers, so the run can pass it by one answer.
  */
object AnswerEval:
  val DiagnosticMaxCalls = 4

  /** The USD ceiling a diagnostic may run with (decision P16-28). */
  val DiagnosticMaxUsd = 0.15
  val RepeatRun = "repeat"
  val PrimaryRun = "primary"

  val ExitComplete = 0
  val ExitFailed = 1
  val ExitUsage = 2
  val ExitHalted = 3

  /** The strata the diagnostic draws from, in order. */
  val DiagnosticStrata: Vector[String] = Vector(
    "researcher-lookup",
    "ranking",
    "coauthor-lookup",
    "shortest-path",
    "community-component",
    "temporal-comparison"
  )

  final case class Config(
      questionsPath: Path,
      labelsPath: Path,
      outBase: Path,
      runId: String,
      diagnostic: Boolean = false,
      repeatCount: Int = 20,
      maxQuestions: Option[Int] = None,
      maxMinutes: Option[Int] = None,
      seed: Long = 20261118L,
      ratingSampleSize: Int = 20,
      sessionPrefix: String = "p16-h",
      deadlineEpochMillis: Option[Long] = None
  ):
    require(repeatCount >= 0, "repeatCount must not be negative")
    require(ratingSampleSize >= 0, "ratingSampleSize must not be negative")

  final case class Report(
      status: String,
      runDir: Path,
      planned: Int,
      records: Int,
      usable: Int,
      haltReason: Option[String]
  ):
    def exitCode: Int = status match
      case "COMPLETE" => ExitComplete
      case "HALTED"   => ExitHalted
      case _          => ExitFailed

  type Ask = (String, Session, RetrievalMode) => WorkflowResult

  def defaultAsk(wiring: LaptopPorts.Wiring): Ask =
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    (text, session, mode) => workflow.answer(text, session, mode)

  /** The diagnostic's questions: the first answerable question of each of up to
    * four different strata, in `DiagnosticStrata` order.
    */
  def diagnosticPairs(
      pairs: Vector[(QuestionRec, LabelRec)]
  ): Vector[(QuestionRec, LabelRec)] =
    DiagnosticStrata
      .flatMap(s =>
        pairs.find((_, l) => l.stratum == s && !l.abstain && l.hasItems)
      )
      .take(DiagnosticMaxCalls)

  def run(config: Config, wiring: LaptopPorts.Wiring): Report =
    runWith(config, wiring, defaultAsk(wiring))

  def runWith(
      config: Config,
      wiring: LaptopPorts.Wiring,
      ask: Ask,
      nanos: () => Long = () => System.nanoTime(),
      wallMillis: () => Long = () => System.currentTimeMillis()
  ): Report =
    if config.diagnostic then
      require(
        wiring.guard.limits.haikuMaxCalls <= DiagnosticMaxCalls,
        s"--diagnostic allows at most $DiagnosticMaxCalls answer-model calls; " +
          s"the guard allows ${wiring.guard.limits.haikuMaxCalls}"
      )
      require(
        wiring.guard.limits.usdCeiling <= DiagnosticMaxUsd,
        s"--diagnostic allows a USD ceiling of at most $DiagnosticMaxUsd; " +
          s"the guard allows ${wiring.guard.limits.usdCeiling}"
      )
    val startedAt = Instant.now().toString
    val all = BenchmarkFiles.pair(
      BenchmarkFiles.readQuestions(config.questionsPath),
      BenchmarkFiles.readLabels(config.labelsPath)
    )
    val chosen =
      if config.diagnostic then diagnosticPairs(all)
      else
        config.maxQuestions.fold(all)(n =>
          Subsets.stratified(all, p => p._2.stratum, n)
        )
    val runDir = RunOutput.createRunDir(config.outBase, config.runId)
    val deadline = RunDeadline.nanosAt(
      config.maxMinutes,
      config.deadlineEpochMillis,
      nanos,
      wallMillis
    )
    val records = mutable.ArrayBuffer.empty[AnswerRecord]
    val labelById = chosen.map(_._2).map(l => l.questionId -> l).toMap
    var halt: Option[String] = None
    var failed = false

    // Every record goes to the raw file when its answer is done, flushed at
    // once, so a kill keeps the answers made so far.
    val rawName =
      if config.diagnostic then "diagnostic.jsonl" else "answers.jsonl"
    val raw = new RawAppender(runDir, rawName)
    def lineOf(r: AnswerRecord): String =
      if config.diagnostic then diagnosticLine(r, labelById)
      else ujson.write(r.toJson)
    var flushed = 0
    def flushRecords(): Unit =
      while flushed < records.size do
        raw.append(lineOf(records(flushed)))
        flushed += 1

    def stopNow(): Boolean =
      if halt.isEmpty then
        wiring.guard.stopReason.foreach(r => halt = Some(r))
        if halt.isEmpty && deadline.exists(nanos() >= _) then
          halt = Some("wall-clock")
      halt.isDefined || failed

    def answerOne(
        pair: (QuestionRec, LabelRec),
        index: Int,
        run: String
    ): Unit =
      val (question, label) = pair
      val sessionId = s"${config.sessionPrefix}-$run"
      val session = Session(sessionId, index + 1)
      val refusedBefore = wiring.guard.refusedCount
      val began = nanos()
      try
        val result = ask(question.text, session, RetrievalMode.Fused)
        val millis = (nanos() - began) / 1000000L
        records += AnswerRecord.of(
          label.questionId,
          run,
          sessionId,
          index + 1,
          question.text,
          result,
          millis,
          costStopped = wiring.guard.refusedCount > refusedBefore
        )
      catch
        case stop: CostStopped =>
          halt = Some(stop.reason)
          records += AnswerRecord.failed(
            label.questionId,
            run,
            sessionId,
            index + 1,
            question.text,
            costStopped = true,
            None
          )
        case NonFatal(e) =>
          failed = true
          records += AnswerRecord.failed(
            label.questionId,
            run,
            sessionId,
            index + 1,
            question.text,
            costStopped = false,
            Some(e.getClass.getSimpleName)
          )
      flushRecords()

    var repeatPairs = Vector.empty[(QuestionRec, LabelRec)]
    try
      var index = 0
      while index < chosen.size && !stopNow() do
        answerOne(chosen(index), index, PrimaryRun)
        index += 1

      repeatPairs =
        if config.diagnostic || config.repeatCount == 0 then Vector.empty
        else
          val answered = records.toVector
            .filter(r => r.usable && r.run == PrimaryRun && !r.abstained)
            .map(_.questionId)
            .toSet
          val eligible =
            chosen.filter((q, l) =>
              !l.abstain && answered.contains(q.questionId)
            )
          val shuffled =
            new Random(config.seed).shuffle(eligible.sortBy(_._1.questionId))
          val picked =
            Subsets.stratified(shuffled, p => p._2.stratum, config.repeatCount)
          picked.sortBy(p =>
            chosen.indexWhere(_._1.questionId == p._1.questionId)
          )
      var repeatIndex = 0
      while repeatIndex < repeatPairs.size && !stopNow() do
        val pair = repeatPairs(repeatIndex)
        answerOne(
          pair,
          chosen.indexWhere(_._1.questionId == pair._1.questionId),
          RepeatRun
        )
        repeatIndex += 1
    finally raw.close()

    val planned = chosen.size + repeatPairs.size
    val status =
      if failed then "FAILED"
      else if halt.nonEmpty then "HALTED"
      else "COMPLETE"
    val usableRecords = records.toVector.filter(_.usable)

    // The raw records are already on disk; their digest comes before any
    // summary.
    RunOutput.writeRawDigest(runDir, rawName)
    if config.diagnostic then
      writeDiagnosticSummary(runDir, records.toVector, wiring)
    else
      RunOutput.write(
        runDir,
        "model-attempts-summary.json",
        ujson.write(attemptSummary(usableRecords), indent = 2) + "\n"
      )
      RunOutput.write(
        runDir,
        "model-calls.jsonl",
        wiring.guard.calls
          .map(c => ujson.write(c.toJson))
          .mkString("", "\n", "\n")
      )
      RunOutput.write(
        runDir,
        "answer-summary.json",
        ujson.write(answerSummary(usableRecords), indent = 2) + "\n"
      )
      val picked = RatingSample.select(
        usableRecords.flatMap(r => labelById.get(r.questionId).map(r -> _)),
        config.ratingSampleSize,
        config.seed
      )
      if picked.nonEmpty then
        RunOutput.write(runDir, "rating-sample.csv", RatingSample.toCsv(picked))
        RunOutput.write(
          runDir,
          "rating-sample-key.json",
          ujson.write(RatingSample.keyJson(picked), indent = 2) + "\n"
        )
    val manifest = Js.obj(
      Vector(
        "schema" -> Js.s("scholarchain.evaluation.answer-run/1"),
        "runId" -> Js.s(config.runId),
        "mode" -> Js.s(if config.diagnostic then "diagnostic" else "full"),
        "status" -> Js.s(status),
        "haltReason" -> Js.opt(halt),
        "startedAt" -> Js.s(startedAt),
        "finishedAt" -> Js.s(Instant.now().toString),
        "inputs" -> Js.obj(
          Vector(
            "questionsSha256" ->
              Js.s(Digests.sha256File(config.questionsPath)),
            "labelsSha256" -> Js.s(Digests.sha256File(config.labelsPath)),
            "questionsRun" -> Js.l(chosen.size.toLong),
            "repeatQuestions" -> Js.l(repeatPairs.size.toLong)
          )
        ),
        "config" -> Js.obj(
          Vector(
            "seed" -> Js.l(config.seed),
            "repeatCount" -> Js.l(config.repeatCount.toLong),
            "ratingSampleSize" -> Js.l(config.ratingSampleSize.toLong),
            "retrievalMode" -> Js.s("fused")
          )
        ),
        "counts" -> Js.obj(
          Vector(
            "planned" -> Js.l(planned.toLong),
            "records" -> Js.l(records.size.toLong),
            "usable" -> Js.l(usableRecords.size.toLong),
            "costStopped" -> Js.l(records.count(_.costStopped).toLong),
            "errors" -> Js.l(records.count(_.error.isDefined).toLong),
            "vectorQueries" -> Js.l(wiring.vectorQueries)
          )
        ),
        "wiring" -> wiring.description,
        "cost" -> wiring.guard.snapshot
      )
    )
    RunOutput.write(
      runDir,
      "run-manifest.json",
      ujson.write(manifest, indent = 2) + "\n"
    )
    RunOutput.seal(runDir)
    Report(status, runDir, planned, records.size, usableRecords.size, halt)

  /** Counts of stop reasons and rejection codes over every recorded attempt. */
  def attemptSummary(records: Vector[AnswerRecord]): ujson.Value =
    val attempts = records.flatMap(_.attempts)
    def counts(values: Vector[String]): ujson.Value =
      Js.counts(
        values.groupBy(identity).map((k, v) => k -> v.size.toLong)
      )
    Js.obj(
      Vector(
        "attempts" -> Js.l(attempts.size.toLong),
        "accepted" -> Js.l(attempts.count(_.accepted).toLong),
        "rejected" -> Js.l(attempts.count(!_.accepted).toLong),
        "stopReasons" -> counts(attempts.map(_.stopReason.getOrElse("none"))),
        "stopReasonsOfRejected" ->
          counts(
            attempts.filterNot(_.accepted).map(_.stopReason.getOrElse("none"))
          ),
        "rejectionCodes" -> counts(attempts.flatMap(_.rejections)),
        "maxTokensAttempts" ->
          Js.l(attempts.count(_.stopReason.contains("max_tokens")).toLong),
        "outputTokensMax" ->
          Js.l(attempts.map(_.outputTokens).maxOption.getOrElse(0).toLong)
      )
    )

  private def percentile(sorted: Vector[Long], p: Double): ujson.Value =
    if sorted.isEmpty then ujson.Null
    else
      val index =
        math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1)
      Js.l(sorted(math.max(0, index)))

  /** Counts, tokens and wall-clock latency per answer (laptop to Bedrock, not
    * in-VPC; the per-call figures are in `model-calls.jsonl`).
    */
  def answerSummary(records: Vector[AnswerRecord]): ujson.Value =
    val primary = records.filter(_.run == PrimaryRun)
    val latency = primary.map(_.latencyMillis).sorted
    Js.obj(
      Vector(
        "primaryAnswers" -> Js.l(primary.size.toLong),
        "abstained" -> Js.l(primary.count(_.abstained).toLong),
        "answered" -> Js.l(primary.count(!_.abstained).toLong),
        "repeatAnswers" -> Js.l(records.count(_.run == RepeatRun).toLong),
        "modelCalls" -> Js.l(records.map(_.modelCalls).sum.toLong),
        "promptTokens" -> Js.l(records.map(_.promptTokens).sum.toLong),
        "completionTokens" -> Js.l(records.map(_.completionTokens).sum.toLong),
        "tokensEstimated" -> Js.b(records.exists(_.tokensEstimated)),
        "answerLatencyMillisP50" -> percentile(latency, 0.50),
        "answerLatencyMillisP95" -> percentile(latency, 0.95)
      )
    )

  /** One line of the diagnostic's raw file: the attempts (stop reason,
    * rejection codes, output tokens) of one question. No question text and no
    * answer text.
    */
  private def diagnosticLine(
      r: AnswerRecord,
      labels: Map[String, LabelRec]
  ): String =
    ujson.write(
      Js.obj(
        Vector(
          "questionId" -> Js.s(r.questionId),
          "stratum" ->
            Js.s(labels.get(r.questionId).map(_.stratum).getOrElse("")),
          "abstained" -> Js.b(r.abstained),
          "abstentionReason" -> Js.opt(r.abstentionReason),
          "modelCalls" -> Js.l(r.modelCalls.toLong),
          "promptTokens" -> Js.l(r.promptTokens.toLong),
          "completionTokens" -> Js.l(r.completionTokens.toLong),
          "attempts" -> Js.arr(r.attempts.map(_.toJson)),
          "costStopped" -> Js.b(r.costStopped),
          "error" -> Js.opt(r.error)
        )
      )
    )

  /** The diagnostic's summary, written after its raw file is hashed. */
  private def writeDiagnosticSummary(
      runDir: Path,
      records: Vector[AnswerRecord],
      wiring: LaptopPorts.Wiring
  ): Unit =
    RunOutput.write(
      runDir,
      "diagnostic-summary.json",
      ujson.write(
        Js.obj(
          Vector(
            "questions" -> Js.l(records.size.toLong),
            "calls" -> Js.l(wiring.guard.haikuCallCount.toLong),
            // All records: the question the cap cut off made real calls too.
            "attempts" -> attemptSummary(records),
            "calls_log" -> Js.arr(wiring.guard.calls.map(_.toJson))
          )
        ),
        indent = 2
      ) + "\n"
    )

  val Options: Set[String] = Set(
    "questions",
    "labels",
    "out-dir",
    "run-id",
    "repeat-count",
    "max-questions",
    "max-minutes",
    "deadline-epoch-seconds",
    "seed",
    "rating-sample-size",
    "export-dir",
    "region",
    "data-bucket",
    "vector-bucket",
    "model-id",
    "titan-max-calls",
    "haiku-max-calls",
    "usd-ceiling",
    "haiku-input-usd-per-mtok",
    "haiku-output-usd-per-mtok",
    "titan-usd-per-mtok"
  )
  val Switches: Set[String] = Set("offline", "diagnostic")

  val Usage: String =
    "usage: AnswerEval --questions FILE --labels FILE --out-dir DIR " +
      "--run-id ID [--diagnostic] [--repeat-count N] [--max-questions N] " +
      "[--max-minutes N] [--deadline-epoch-seconds N] [--seed N] " +
      "[--rating-sample-size N] " +
      "(--offline | --export-dir DIR --data-bucket B --vector-bucket V " +
      "--model-id ID --titan-max-calls N --haiku-max-calls N " +
      "--usd-ceiling USD --haiku-input-usd-per-mtok USD " +
      "--haiku-output-usd-per-mtok USD --titan-usd-per-mtok USD " +
      "[--region us-east-2])"

  def main(args: Array[String]): Unit =
    sys.exit(execute(args.toSeq, println))

  /** Returns the exit code. Prints identifiers, counts and costs only. */
  def execute(args: Seq[String], out: String => Unit): Int =
    val parsed =
      try Right(EvalArgs.parse(args, Options, Switches))
      catch case e: IllegalArgumentException => Left(e.getMessage)
    parsed match
      case Left(message) =>
        out(s"ANSWER_USAGE_ERROR $message")
        out(Usage)
        ExitUsage
      case Right(a) =>
        try
          val offline = a.flag("offline")
          val diagnostic = a.flag("diagnostic")
          val haikuCap =
            if diagnostic then
              math.min(
                a.int("haiku-max-calls", DiagnosticMaxCalls),
                DiagnosticMaxCalls
              )
            else if offline then a.int("haiku-max-calls", 100000)
            else a.required("haiku-max-calls").toInt
          def clampedUsd(requested: Double): Double =
            if diagnostic then math.min(requested, DiagnosticMaxUsd)
            else requested
          val limits =
            if offline then
              CostLimits(
                a.int("titan-max-calls", 100000),
                haikuCap,
                clampedUsd(a.double("usd-ceiling").getOrElse(1.0))
              )
            else
              CostLimits(
                a.required("titan-max-calls").toInt,
                haikuCap,
                clampedUsd(a.requiredDouble("usd-ceiling"))
              )
          val rates =
            if offline then Rates.Zero
            else
              Rates(
                a.requiredDouble("haiku-input-usd-per-mtok"),
                a.requiredDouble("haiku-output-usd-per-mtok"),
                a.requiredDouble("titan-usd-per-mtok")
              )
          val guard = new CostGuard(limits, rates)
          val wiring =
            if offline then LaptopPorts.offline(guard)
            else
              LaptopPorts.live(
                LaptopPorts.LiveSettings(
                  Paths.get(a.required("export-dir")),
                  a.get("region").getOrElse(LaptopPorts.ApprovedRegion),
                  a.required("data-bucket"),
                  a.required("vector-bucket"),
                  Some(a.required("model-id"))
                ),
                guard
              )
          try
            val config = Config(
              Paths.get(a.required("questions")),
              Paths.get(a.required("labels")),
              Paths.get(a.required("out-dir")),
              a.required("run-id"),
              diagnostic,
              a.int("repeat-count", 20),
              a.get("max-questions").map(_ => a.int("max-questions", 1)),
              a.get("max-minutes").map(_ => a.int("max-minutes", 1)),
              a.long("seed", 20261118L),
              a.int("rating-sample-size", 20),
              deadlineEpochMillis = a
                .get("deadline-epoch-seconds")
                .map(_ => a.long("deadline-epoch-seconds", 0L) * 1000L)
            )
            val report = run(config, wiring)
            val snap = wiring.guard.snapshot
            out(
              s"ANSWER_RESULT status=${report.status} " +
                s"mode=${if diagnostic then "diagnostic" else "full"} " +
                s"planned=${report.planned} records=${report.records} " +
                s"usable=${report.usable} halt=${report.haltReason.getOrElse("none")}"
            )
            out(
              s"ANSWER_COST spentUsd=${snap("spentUsd").num} " +
                s"haikuCalls=${snap("haikuCalls").num.toInt} " +
                s"titanCalls=${snap("titanCalls").num.toInt} " +
                s"vectorQueries=${wiring.vectorQueries}"
            )
            if diagnostic then
              val attempts = wiring.guard.calls
              val stops = attempts
                .groupBy(_.stopReason.getOrElse("none"))
                .toVector
                .sortBy(_._1)
                .map((k, v) => s"$k:${v.size}")
                .mkString(",")
              out(
                s"DIAGNOSTIC calls=${attempts.size} stopReasons=$stops " +
                  s"maxTokens=${attempts.count(_.stopReason.contains("max_tokens"))}"
              )
            out(s"ANSWER_RUN_DIR ${report.runDir}")
            report.exitCode
          finally wiring.close()
        catch
          case e: IllegalArgumentException =>
            out(s"ANSWER_USAGE_ERROR ${e.getMessage}")
            out(Usage)
            ExitUsage
          case NonFatal(e) =>
            out(
              s"ANSWER_RESULT status=FAILED error=${e.getClass.getSimpleName}"
            )
            ExitFailed
