package scholarchain.evaluation.retrieval

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.evaluation.io.{Digests, Js, RawAppender, RunOutput}
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
  RetrievedHit,
  SemanticContext,
  SemanticContextAgent,
  SemanticRetrieval,
  Session,
  WorkflowResult
}
import scholarchain.storage.{
  MetaValue,
  StorageException,
  TransientStorageException,
  VectorFilter,
  VectorIndexes,
  VectorMatch
}

/** Run G: retrieval quality in graph, vector and fused mode on the laptop
  * runtime, with the stub answer model (no answer text is judged here).
  *
  * For every question, repeat and mode it asks the workflow for the question
  * `text` and nothing else. It records the ids the graph evidence asserts, the
  * hits the deployed workflow retrieved (topK 2 per index, read from the vector
  * bucket's recorder, no second call) and, once per question and repeat, the
  * hits of a direct `SemanticRetrieval.search` at the larger K values with the
  * same query text, index choice and filter as the workflow. The raw records
  * are appended to `records.jsonl` as they become final and flushed to the
  * operating system at once (a question's records are final when its direct
  * searches have finished, so a kill loses at most the question in flight), and
  * the file is hashed before any summary exists.
  *
  * A record made while a call was refused by the cost guard, or whose direct
  * searches did not finish, is marked `costStopped` and left out of every
  * metric.
  *
  * The wall-clock stop is the earlier of `maxMinutes` (from the start of the
  * loop) and an absolute `deadlineEpochMillis` (from the launcher's start, so
  * sbt start, compile and the graph load are inside it). It is checked between
  * questions, so the run can pass it by one question.
  */
object RetrievalEval:
  val DeployedK = 2
  val AllModes: Vector[RetrievalMode] =
    Vector(RetrievalMode.Graph, RetrievalMode.Vector, RetrievalMode.Fused)

  val ExitComplete = 0
  val ExitFailed = 1
  val ExitUsage = 2
  val ExitHalted = 3

  final case class Config(
      questionsPath: Path,
      labelsPath: Path,
      outBase: Path,
      runId: String,
      repeats: Int = 2,
      ks: Vector[Int] = Vector(2, 5, 10),
      modes: Vector[RetrievalMode] = AllModes,
      resamples: Int = 1000,
      seed: Long = 20261118L,
      maxQuestions: Option[Int] = None,
      maxMinutes: Option[Int] = None,
      sessionPrefix: String = "p16-g",
      deadlineEpochMillis: Option[Long] = None
  ):
    require(repeats >= 1, "repeats must be at least 1")
    require(
      ks.nonEmpty && ks.forall(k => k >= 1 && k <= SemanticContext.MaxItems),
      s"every K must be 1 to ${SemanticContext.MaxItems}"
    )
    require(ks.contains(DeployedK), s"K = $DeployedK (as deployed) is required")
    require(modes.nonEmpty, "at least one mode is required")

    def directKs: Vector[Int] = ks.filter(_ != DeployedK)

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

  /** The ask: the question text, the session and the mode, nothing else. */
  type Ask = (String, Session, RetrievalMode) => WorkflowResult

  def defaultAsk(wiring: LaptopPorts.Wiring): Ask =
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    (text, session, mode) => workflow.answer(text, session, mode)

  private def metaText(hit: VectorMatch, name: String): String =
    hit.metadata.get(name) match
      case Some(MetaValue.Str(v)) => v
      case _                      => ""

  def vectorHit(index: String, hit: VectorMatch): VectorHit =
    VectorHit(
      index,
      metaText(hit, "subjectId"),
      hit.distance,
      metaText(hit, "documentType")
    )

  private def hitsOf(hits: Vector[RetrievedHit]): Vector[VectorHit] =
    hits.map(h => vectorHit(h.index, h.hit))

  /** The direct searches of one question: the workflow's own query text, index
    * choice and filter (`SemanticContextAgent.context`), at each K. The note
    * names an index whose search failed, or `no-plan` when the workflow never
    * planned the question (nothing to search for).
    */
  def directSearch(
      retrieval: SemanticRetrieval,
      result: WorkflowResult,
      ks: Vector[Int]
  ): (Vector[(Int, Vector[VectorHit])], Option[String]) =
    (result.planDraft, result.plan) match
      case (Some(d), Some(p)) =>
        val text = (d.names ++ d.topic.toVector :+ d.question)
          .mkString(" ")
          .take(SemanticContext.MaxQueryChars)
        val window = if d.windowExplicit then Some(d.window) else None
        val indexes =
          new SemanticContextAgent(retrieval).indexesFor(d.operation)
        var failed = Vector.empty[String]
        val byK = ks.map { k =>
          k -> indexes.flatMap { index =>
            val researcher =
              if index == VectorIndexes.ResearcherProfile ||
                index == VectorIndexes.CollaborationPath
              then p.researcherIds.headOption
              else None
            val filter = VectorFilter(
              researcher = researcher,
              yearFrom = window.map(_.startYear).filter(_ > 1),
              yearTo = window.map(_.endYear).filter(_ < 9999)
            )
            try hitsOf(retrieval.search(index, text, k, filter))
            catch
              case _: StorageException | _: TransientStorageException =>
                failed = failed :+ index
                Vector.empty
          }
        }
        val note =
          if failed.isEmpty then None
          else Some("search-failed:" + failed.distinct.mkString(","))
        (byK, note)
      case _ => (Vector.empty, Some("no-plan"))

  private def recordOf(
      label: LabelRec,
      mode: RetrievalMode,
      repeat: Int,
      result: WorkflowResult,
      deployed: Vector[VectorHit],
      millis: Long,
      costStopped: Boolean
  ): RankedRecord =
    val items = result.response.evidence.items
    val stratum = EvidenceIds.effectiveStratum(label)
    RankedRecord(
      label.questionId,
      mode.wire,
      repeat,
      result.abstained,
      result.abstentionReason,
      EvidenceIds.graphIds(stratum, items),
      EvidenceIds.pathStatuses(items),
      deployed,
      Vector.empty,
      items.size,
      result.planDraft.map(_.operation.wire),
      result.planDraft.map(_.classifiedBy),
      result.audit.payload("modelCalls").num.toInt,
      millis,
      None,
      costStopped,
      None
    )

  private def placeholder(
      label: LabelRec,
      mode: RetrievalMode,
      repeat: Int,
      costStopped: Boolean,
      error: Option[String]
  ): RankedRecord =
    RankedRecord(
      label.questionId,
      mode.wire,
      repeat,
      abstained = false,
      None,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      0,
      None,
      None,
      0,
      0L,
      None,
      costStopped,
      error
    )

  def run(config: Config, wiring: LaptopPorts.Wiring): Report =
    runWith(config, wiring, defaultAsk(wiring))

  def runWith(
      config: Config,
      wiring: LaptopPorts.Wiring,
      ask: Ask,
      nanos: () => Long = () => System.nanoTime(),
      wallMillis: () => Long = () => System.currentTimeMillis()
  ): Report =
    val startedAt = Instant.now().toString
    val all = BenchmarkFiles.pair(
      BenchmarkFiles.readQuestions(config.questionsPath),
      BenchmarkFiles.readLabels(config.labelsPath)
    )
    val pairs = config.maxQuestions.fold(all)(n =>
      Subsets.stratified(all, p => p._2.stratum, n)
    )
    val runDir = RunOutput.createRunDir(config.outBase, config.runId)
    val deadline = RunDeadline.nanosAt(
      config.maxMinutes,
      config.deadlineEpochMillis,
      nanos,
      wallMillis
    )
    val records = mutable.ArrayBuffer.empty[RankedRecord]
    var halt: Option[String] = None
    var failed = false
    val directNeeded =
      config.modes.exists(_.runsVector) && config.directKs.nonEmpty

    def stopNow(): Boolean =
      if halt.isEmpty then
        wiring.guard.stopReason.foreach(r => halt = Some(r))
        if halt.isEmpty && deadline.exists(nanos() >= _) then
          halt = Some("wall-clock")
      halt.isDefined || failed

    def vectorRecordsOf(label: LabelRec, repeat: Int): Vector[Int] =
      records.indices.toVector.filter { i =>
        val r = records(i)
        r.questionId == label.questionId && r.repeat == repeat &&
        r.mode != RetrievalMode.Graph.wire
      }

    // Raw records go to the file as they become final (a question's records
    // are final once its direct searches finished) and are flushed at once.
    val raw = new RawAppender(runDir, "records.jsonl")
    var flushed = 0
    def flushRecords(): Unit =
      while flushed < records.size do
        raw.append(ujson.write(records(flushed).toJson))
        flushed += 1

    try
      var repeat = 1
      while repeat <= config.repeats && !stopNow() do
        var index = 0
        while index < pairs.size && !stopNow() do
          val (question, label) = pairs(index)
          val results = mutable.ArrayBuffer.empty[WorkflowResult]
          val refusedAtQuestion = wiring.guard.refusedCount
          var modeIndex = 0
          while modeIndex < config.modes.size && !stopNow() do
            val mode = config.modes(modeIndex)
            val session = Session(
              s"${config.sessionPrefix}-r$repeat-${mode.wire}",
              index + 1
            )
            wiring.recorder.foreach(_.drain())
            val refusedBefore = wiring.guard.refusedCount
            val began = nanos()
            try
              val result = ask(question.text, session, mode)
              val millis = (nanos() - began) / 1000000L
              val deployed = wiring.recorder
                .map(_.drain().flatMap(q => q.hits.map(vectorHit(q.index, _))))
                .getOrElse(Vector.empty)
              records += recordOf(
                label,
                mode,
                repeat,
                result,
                deployed,
                millis,
                costStopped = wiring.guard.refusedCount > refusedBefore
              )
              results += result
            catch
              case stop: CostStopped =>
                halt = Some(stop.reason)
                records += placeholder(label, mode, repeat, true, None)
              case NonFatal(e) =>
                failed = true
                records += placeholder(
                  label,
                  mode,
                  repeat,
                  false,
                  Some(e.getClass.getSimpleName)
                )
            modeIndex += 1
          var directOk = !directNeeded
          if directNeeded && !stopNow() then
            (wiring.direct, results.find(_.plan.isDefined)) match
              case (Some(make), Some(result)) =>
                try
                  val (byK, note) =
                    directSearch(make(), result, config.directKs)
                  vectorRecordsOf(label, repeat).foreach { i =>
                    records(i) =
                      records(i).copy(vectorDirect = byK, directNote = note)
                  }
                  directOk = true
                catch
                  case stop: CostStopped => halt = Some(stop.reason)
                  case NonFatal(_)       => failed = true
              case (Some(_), None) =>
                // Never planned (refused or invalid): no query text to search.
                vectorRecordsOf(label, repeat).foreach { i =>
                  records(i) = records(i).copy(directNote = Some("no-plan"))
                }
                directOk = true
              case (None, _) =>
                vectorRecordsOf(label, repeat).foreach { i =>
                  records(i) =
                    records(i).copy(directNote = Some("no-vector-retrieval"))
                }
                directOk = true
          if !directOk then
            // The vector side of this question cannot be scored at K above 2.
            vectorRecordsOf(label, repeat).foreach { i =>
              records(i) = records(i).copy(
                // Cost-stopped only when the guard refused a call during this
                // question; a wall-clock halt or a spend that crossed the
                // ceiling with nothing refused leaves the answer as it was.
                costStopped = wiring.guard.refusedCount > refusedAtQuestion ||
                  records(i).costStopped,
                error =
                  if failed then Some("direct-search-not-completed") else None,
                directNote = Some("direct-search-not-completed")
              )
            }
          flushRecords()
          index += 1
        repeat += 1
    finally raw.close()

    val planned = config.repeats * pairs.size * config.modes.size
    val status =
      if failed then "FAILED"
      else if halt.nonEmpty then "HALTED"
      else "COMPLETE"
    val usableRecords = records.toVector.filter(_.usable)
    // The raw records are already on disk; their digest comes before any
    // summary.
    RunOutput.writeRawDigest(runDir, "records.jsonl")
    val labelById = pairs.map(_._2).map(l => l.questionId -> l).toMap
    val scored = usableRecords.map(r =>
      Scoring.score(r, labelById(r.questionId), config.ks)
    )
    val summary =
      Aggregate.summarize(scored, config.ks, config.resamples, config.seed)
    RunOutput.write(
      runDir,
      "summary.json",
      ujson.write(summary, indent = 2) + "\n"
    )
    val manifest = Js.obj(
      Vector(
        "schema" -> Js.s("scholarchain.evaluation.retrieval-run/1"),
        "runId" -> Js.s(config.runId),
        "status" -> Js.s(status),
        "haltReason" -> Js.opt(halt),
        "startedAt" -> Js.s(startedAt),
        "finishedAt" -> Js.s(Instant.now().toString),
        "inputs" -> Js.obj(
          Vector(
            "questionsSha256" ->
              Js.s(Digests.sha256File(config.questionsPath)),
            "labelsSha256" -> Js.s(Digests.sha256File(config.labelsPath)),
            "questions" -> Js.l(pairs.size.toLong)
          )
        ),
        "config" -> Js.obj(
          Vector(
            "repeats" -> Js.l(config.repeats.toLong),
            "ks" -> Js.arr(config.ks.map(k => Js.l(k.toLong))),
            "modes" -> Js.arr(config.modes.map(m => Js.s(m.wire))),
            "resamples" -> Js.l(config.resamples.toLong),
            "seed" -> Js.l(config.seed),
            "maxQuestions" ->
              config.maxQuestions
                .fold[ujson.Value](ujson.Null)(n => Js.l(n.toLong)),
            "answerModel" -> Js.s("stub synthesis (retrieval only)")
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

  val Options: Set[String] = Set(
    "questions",
    "labels",
    "out-dir",
    "run-id",
    "repeats",
    "max-questions",
    "max-minutes",
    "deadline-epoch-seconds",
    "seed",
    "resamples",
    "modes",
    "export-dir",
    "region",
    "data-bucket",
    "vector-bucket",
    "titan-max-calls",
    "usd-ceiling",
    "titan-usd-per-mtok"
  )
  val Switches: Set[String] = Set("offline", "no-vectors")

  val Usage: String =
    "usage: RetrievalEval --questions FILE --labels FILE --out-dir DIR " +
      "--run-id ID [--repeats N] [--modes graph,vector,fused] " +
      "[--max-questions N] [--max-minutes N] [--deadline-epoch-seconds N] " +
      "[--seed N] [--resamples N] " +
      "(--offline | --export-dir DIR --data-bucket B --vector-bucket V " +
      "--titan-max-calls N --usd-ceiling USD --titan-usd-per-mtok USD " +
      "[--region us-east-2] [--no-vectors])"

  private def modesOf(text: String): Vector[RetrievalMode] =
    text
      .split(",")
      .toVector
      .map(w =>
        RetrievalMode
          .fromWire(w.trim)
          .getOrElse(throw new IllegalArgumentException(s"unknown mode '$w'"))
      )

  def main(args: Array[String]): Unit =
    sys.exit(execute(args.toSeq, println))

  /** Returns the exit code. Prints identifiers, counts and costs only. */
  def execute(args: Seq[String], out: String => Unit): Int =
    val parsed =
      try Right(EvalArgs.parse(args, Options, Switches))
      catch case e: IllegalArgumentException => Left(e.getMessage)
    parsed match
      case Left(message) =>
        out(s"RETRIEVAL_USAGE_ERROR $message")
        out(Usage)
        ExitUsage
      case Right(a) =>
        try
          val offline = a.flag("offline")
          val limits =
            if offline then
              CostLimits(
                a.int("titan-max-calls", 100000),
                100000,
                a.double("usd-ceiling").getOrElse(1.0)
              )
            else
              CostLimits(
                a.required("titan-max-calls").toInt,
                100000,
                a.requiredDouble("usd-ceiling")
              )
          val rates =
            if offline then Rates.Zero
            else Rates(0.0, 0.0, a.requiredDouble("titan-usd-per-mtok"))
          val guard = new CostGuard(limits, rates)
          val wiring =
            if offline then LaptopPorts.offline(guard)
            else
              LaptopPorts.live(
                LaptopPorts.LiveSettings(
                  Paths.get(a.required("export-dir")),
                  a.get("region").getOrElse(LaptopPorts.ApprovedRegion),
                  a.get("data-bucket").getOrElse(""),
                  a.get("vector-bucket").getOrElse(""),
                  None,
                  useVectors = !a.flag("no-vectors")
                ),
                guard
              )
          try
            val config = Config(
              Paths.get(a.required("questions")),
              Paths.get(a.required("labels")),
              Paths.get(a.required("out-dir")),
              a.required("run-id"),
              a.int("repeats", 2),
              Vector(2, 5, 10),
              a.get("modes").map(modesOf).getOrElse(AllModes),
              a.int("resamples", 1000),
              a.long("seed", 20261118L),
              a.get("max-questions").map(_ => a.int("max-questions", 1)),
              a.get("max-minutes").map(_ => a.int("max-minutes", 1)),
              deadlineEpochMillis = a
                .get("deadline-epoch-seconds")
                .map(_ => a.long("deadline-epoch-seconds", 0L) * 1000L)
            )
            val report = run(config, wiring)
            val snap = wiring.guard.snapshot
            out(
              s"RETRIEVAL_RESULT status=${report.status} " +
                s"planned=${report.planned} records=${report.records} " +
                s"usable=${report.usable} halt=${report.haltReason.getOrElse("none")}"
            )
            out(
              s"RETRIEVAL_COST spentUsd=${snap("spentUsd").num} " +
                s"titanCalls=${snap("titanCalls").num.toInt} " +
                s"vectorQueries=${wiring.vectorQueries}"
            )
            out(s"RETRIEVAL_RUN_DIR ${report.runDir}")
            report.exitCode
          finally wiring.close()
        catch
          case e: IllegalArgumentException =>
            out(s"RETRIEVAL_USAGE_ERROR ${e.getMessage}")
            out(Usage)
            ExitUsage
          case NonFatal(e) =>
            out(
              s"RETRIEVAL_RESULT status=FAILED error=${e.getClass.getSimpleName}"
            )
            ExitFailed
