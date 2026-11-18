package scholarchain.evaluation.retrieval

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.evaluation.io.Digests
import scholarchain.evaluation.runtime.{
  CostGuard,
  CostLimits,
  LaptopPorts,
  Rates
}
import scholarchain.rag.{
  CollaborationWorkflow,
  RetrievalMode,
  Session,
  WorkflowResult
}

class RetrievalEvalSuite extends FunSuite:
  private def guard(titan: Int = 1000) =
    new CostGuard(CostLimits(titan, 1000, 1.0), Rates.Zero)

  private def tempDir(): Path = Files.createTempDirectory("retrieval-eval-")

  private def config(
      dir: Path,
      runId: String = "run-g",
      repeats: Int = 2,
      maxMinutes: Option[Int] = None
  ): RetrievalEval.Config =
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    RetrievalEval.Config(
      q,
      l,
      dir.resolve("out"),
      runId,
      repeats = repeats,
      resamples = 50,
      maxMinutes = maxMinutes
    )

  private def records(dir: Path): Vector[RankedRecord] =
    Files
      .readAllLines(dir.resolve("records.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => RankedRecord.fromJson(ujson.read(l)))

  test(
    "the offline run writes frozen raw records, then the summary, then seals"
  ) {
    val dir = tempDir()
    val wiring = LaptopPorts.offline(guard())
    val report = RetrievalEval.run(config(dir), wiring)
    assertEquals(report.status, "COMPLETE")
    assertEquals(report.planned, 2 * 6 * 3)
    assertEquals(report.records, 36)
    assertEquals(report.usable, 36)
    assertEquals(report.exitCode, 0)
    Vector(
      "records.jsonl",
      "RAW-SHA256SUMS",
      "summary.json",
      "run-manifest.json",
      "SHA256SUMS"
    ).foreach(n => assert(Files.exists(report.runDir.resolve(n)), n))
    val raw = Files.readString(report.runDir.resolve("RAW-SHA256SUMS"))
    assertEquals(
      raw.trim,
      s"${Digests.sha256File(report.runDir.resolve("records.jsonl"))}  records.jsonl"
    )
    val sums = Files.readString(report.runDir.resolve("SHA256SUMS"))
    Vector("records.jsonl", "summary.json", "run-manifest.json").foreach(n =>
      assert(sums.contains(s"  $n"), n)
    )
    val summary =
      ujson.read(Files.readString(report.runDir.resolve("summary.json")))
    assertEquals(
      summary.obj.keySet.toSet,
      Set("graph", "vector", "fused")
    )
    val manifest =
      ujson.read(Files.readString(report.runDir.resolve("run-manifest.json")))
    assertEquals(manifest("status").str, "COMPLETE")
    assertEquals(manifest("counts")("planned").num.toInt, 36)
    assertEquals(manifest("inputs")("questions").num.toInt, 6)
    assertEquals(manifest("cost")("stopReason"), ujson.Null)
    assert(manifest("cost")("basis").str.nonEmpty)
  }

  test("graph recall on the coauthor question is read from the evidence") {
    val dir = tempDir()
    val report = RetrievalEval.run(config(dir), LaptopPorts.offline(guard()))
    val all = records(report.runDir)
    val graph = all.filter(r => r.questionId == "Q-CA-01" && r.mode == "graph")
    assertEquals(graph.size, 2)
    val ids = DemoIds
    graph.foreach { r =>
      assertEquals(r.graphIds.toSet, Set(ids.boris, ids.fiona))
      assertEquals(r.vectorDeployed, Vector.empty[VectorHit])
    }
    val summary =
      ujson.read(Files.readString(report.runDir.resolve("summary.json")))
    val recall =
      summary("graph")("scopes")("all")("coauthor-lookup")("metrics")(
        "recall@2"
      )
    assertEquals(recall("mean").num, 1.0)
    val path = all.filter(r => r.questionId == "Q-SP-01" && r.mode == "graph")
    path.foreach(r => assertEquals(r.graphIds.size, 2))
    val absent = all.filter(r => r.questionId == "Q-SP-02" && r.mode == "graph")
    absent.foreach(r => assertEquals(r.pathStatuses, Vector("NotPrecomputed")))
    assertEquals(
      summary("graph")("outcomes")("acceptedOutcomeCorrect").num,
      1.0
    )
  }

  private object DemoIds:
    val boris: String = scholarchain.rag.DemoWorld.ids.boris
    val fiona: String = scholarchain.rag.DemoWorld.ids.fiona

  test(
    "vector and fused records hold the deployed hits and the direct searches"
  ) {
    val dir = tempDir()
    val report =
      RetrievalEval.run(config(dir, repeats = 1), LaptopPorts.offline(guard()))
    val all = records(report.runDir)
    val fused = all.filter(r => r.questionId == "Q-CA-01" && r.mode == "fused")
    assertEquals(fused.size, 1)
    val r = fused.head
    assert(r.vectorDeployed.nonEmpty)
    assertEquals(r.vectorDirect.map(_._1), Vector(5, 10))
    assert(r.vectorDirect.forall(_._2.nonEmpty))
    // The larger K asks for more hits than the deployed topK of 2.
    assert(
      r.vectorDirect.find(_._1 == 10).get._2.size >= r.vectorDeployed.size
    )
    val graph = all.filter(_.mode == "graph")
    assert(graph.forall(_.vectorDirect.isEmpty))
    val refused =
      all.filter(r => r.questionId == "Q-UN-01" && r.mode == "fused")
    assertEquals(refused.head.directNote, Some("no-plan"))
  }

  test(
    "only the question text reaches the workflow, in a session without labels"
  ) {
    val dir = tempDir()
    val cfg = config(dir, repeats = 1)
    val wiring = LaptopPorts.offline(guard())
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    val seen = mutable.ArrayBuffer.empty[(String, Session, RetrievalMode)]
    val spy: RetrievalEval.Ask = (text, session, mode) =>
      seen += ((text, session, mode))
      workflow.answer(text, session, mode)
    RetrievalEval.runWith(cfg, wiring, spy)
    val texts = BenchmarkFiles.readQuestions(cfg.questionsPath).map(_.text)
    assertEquals(seen.size, 6 * 3)
    assertEquals(seen.map(_._1).distinct.toVector, texts)
    val labels = BenchmarkFiles.readLabels(cfg.labelsPath)
    val secrets =
      labels.map(_.stratum).distinct ++ labels.flatMap(_.items.map(_._1)) ++
        labels.map(_.questionId) ++ Vector("acceptedOutcomes", "expectedCodes")
    seen.foreach { (text, session, _) =>
      secrets.foreach { s =>
        assert(!text.contains(s), s"the text carries label data: $s")
        assert(!session.sessionId.contains(s), s"the session carries $s")
      }
      assert(session.disambiguation.isEmpty)
    }
    // The question file may hold a stratum, but the record the entry point
    // reads from it has no field for one.
    assertEquals(
      classOf[QuestionRec].getDeclaredFields.map(_.getName).toSet,
      Set("questionId", "text")
    )
  }

  test(
    "a Titan stop ends the run, keeps the raw records and excludes the stopped one"
  ) {
    val dir = tempDir()
    // Each question's fused and vector queries embed once or twice; 5 calls
    // let the first questions finish and stop a later embed.
    val g = guard(titan = 5)
    val report = RetrievalEval.run(config(dir), LaptopPorts.offline(g))
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("titan-call-cap"))
    assertEquals(report.exitCode, 3)
    assert(report.records < report.planned)
    assert(report.usable < report.records)
    val all = records(report.runDir)
    assertEquals(all.count(_.costStopped) >= 1, true)
    assertEquals(all.count(_.usable), report.usable)
    assert(Files.exists(report.runDir.resolve("SHA256SUMS")))
    val manifest =
      ujson.read(Files.readString(report.runDir.resolve("run-manifest.json")))
    assertEquals(manifest("status").str, "HALTED")
    assertEquals(manifest("cost")("stopReason").str, "titan-call-cap")
    assertEquals(
      manifest("counts")("costStopped").num.toInt,
      all.count(_.costStopped)
    )
    // No later call was made after the stop: the Titan counter stays at the cap.
    assertEquals(g.titanCallCount, 5)
  }

  test("a stopped run still summarizes the usable records only") {
    val dir = tempDir()
    val report =
      RetrievalEval.run(config(dir), LaptopPorts.offline(guard(titan = 5)))
    val summary =
      ujson.read(Files.readString(report.runDir.resolve("summary.json")))
    val counted = Vector("graph", "vector", "fused")
      .flatMap(m => summary.obj.get(m).map(_("records").num.toInt))
      .sum
    assertEquals(counted, report.usable)
  }

  test("the wall-clock stop ends the run between questions") {
    val dir = tempDir()
    var t = 0L
    val nanos = () =>
      t += 90L * 1000000000L
      t
    val wiring = LaptopPorts.offline(guard())
    val report = RetrievalEval.runWith(
      config(dir, maxMinutes = Some(1)),
      wiring,
      RetrievalEval.defaultAsk(wiring),
      nanos
    )
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("wall-clock"))
  }

  private def ticking(stepSeconds: Long): () => Long =
    var t = 0L
    () =>
      t += stepSeconds * 1000000000L
      t

  test("a run killed mid-way keeps the records of the finished questions") {
    val dir = tempDir()
    val cfg = config(dir, repeats = 1)
    val wiring = LaptopPorts.offline(guard())
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    var asked = 0
    var onDiskAtKill = Vector.empty[RankedRecord]
    // InterruptedException is not NonFatal: it leaves the run loop as a kill
    // would, with no summary, no digest and no seal. The 4th call is the first
    // of the second question. Just before the throw, and before any close in
    // the run's finally, the file is read from disk: only per-append flushing
    // puts the finished question's three records there.
    val dying: RetrievalEval.Ask = (text, session, mode) =>
      asked += 1
      if asked == 4 then
        onDiskAtKill = records(dir.resolve("out").resolve("run-g"))
        throw new InterruptedException("killed")
      workflow.answer(text, session, mode)
    // (munit's intercept only catches NonFatal, so the catch is spelled out.)
    val killed =
      try
        RetrievalEval.runWith(cfg, wiring, dying)
        false
      catch case _: InterruptedException => true
    assert(killed, "the interrupted run should not have finished")
    assertEquals(
      onDiskAtKill.map(_.mode),
      Vector("graph", "vector", "fused"),
      "the finished records were on disk before the kill, not only at close"
    )
    val runDir = dir.resolve("out").resolve("run-g")
    val kept = records(runDir)
    assertEquals(kept.map(_.questionId).distinct, Vector("Q-RL-01"))
    assertEquals(kept.map(_.mode), Vector("graph", "vector", "fused"))
    // The finished question is complete: its vector side holds the direct
    // searches, as in a run that ended normally.
    assert(kept.filter(_.mode == "fused").forall(_.vectorDirect.nonEmpty))
    Vector("RAW-SHA256SUMS", "SHA256SUMS", "run-manifest.json", "summary.json")
      .foreach(n => assert(!Files.exists(runDir.resolve(n)), n))
  }

  test(
    "a wall-clock halt that skips the direct search does not mark cost-stopped"
  ) {
    val dir = tempDir()
    var t = 0L
    val nanos = () => t
    val g = guard()
    val wiring = LaptopPorts.offline(g)
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    var asked = 0
    // After the third call (the first question's last mode) the clock passes
    // the 1-minute bound: the direct search is skipped, no call is refused.
    val ask: RetrievalEval.Ask = (text, session, mode) =>
      asked += 1
      val result = workflow.answer(text, session, mode)
      if asked == 3 then t = 61L * 1000000000L
      result
    val report = RetrievalEval.runWith(
      config(dir, repeats = 1, maxMinutes = Some(1)),
      wiring,
      ask,
      nanos
    )
    assertEquals(report.haltReason, Some("wall-clock"))
    assertEquals(g.refusedCount, 0)
    val all = records(report.runDir)
    assertEquals(all.size, 3)
    val vectorSide = all.filter(_.mode != "graph")
    assertEquals(vectorSide.size, 2)
    vectorSide.foreach { r =>
      assert(!r.costStopped, s"${r.mode} was marked cost-stopped")
      assertEquals(r.directNote, Some("direct-search-not-completed"))
    }
  }

  test("an absolute deadline stops the run between questions") {
    val dir = tempDir()
    // nanos advance 1 s per reading, the wall clock reads 1,000,000 ms: the
    // deadline is 10 s after the first reading, though max-minutes allows 60.
    val cfg = config(dir, repeats = 1).copy(
      maxMinutes = Some(60),
      deadlineEpochMillis = Some(1000000L + 10000L)
    )
    val wiring = LaptopPorts.offline(guard())
    val report = RetrievalEval.runWith(
      cfg,
      wiring,
      RetrievalEval.defaultAsk(wiring),
      ticking(1),
      () => 1000000L
    )
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("wall-clock"))
    assert(report.records >= 1 && report.records < report.planned)
    assertEquals(records(report.runDir).size, report.records)
    assertEquals(
      Files.readString(report.runDir.resolve("RAW-SHA256SUMS")).trim,
      s"${Digests.sha256File(report.runDir.resolve("records.jsonl"))}  records.jsonl"
    )
  }

  test("a deadline already past runs nothing and says so") {
    val dir = tempDir()
    val cfg = config(dir).copy(deadlineEpochMillis = Some(500L))
    val wiring = LaptopPorts.offline(guard())
    val report = RetrievalEval.runWith(
      cfg,
      wiring,
      RetrievalEval.defaultAsk(wiring),
      ticking(1),
      () => 1000000L
    )
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("wall-clock"))
    assertEquals(report.records, 0)
    assertEquals(wiring.vectorQueries, 0L)
  }

  test("the command line takes an absolute deadline and stops at it") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val lines = mutable.ArrayBuffer.empty[String]
    val code = RetrievalEval.execute(
      Seq(
        "--offline",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("cli").toString,
        "--run-id",
        "cli-deadline",
        "--deadline-epoch-seconds",
        "1"
      ),
      lines += _
    )
    assertEquals(code, 3)
    assert(lines.exists(_.contains("halt=wall-clock")), lines.toString)
  }

  test("a run directory is written once") {
    val dir = tempDir()
    val cfg = config(dir)
    RetrievalEval.run(cfg, LaptopPorts.offline(guard()))
    intercept[IllegalStateException](
      RetrievalEval.run(cfg, LaptopPorts.offline(guard()))
    )
  }

  test("a stratified subset takes one question from each stratum in turn") {
    val dir = tempDir()
    val cfg = config(dir, repeats = 1).copy(maxQuestions = Some(4))
    val report = RetrievalEval.run(cfg, LaptopPorts.offline(guard()))
    val ids = records(report.runDir).map(_.questionId).distinct
    assertEquals(ids.size, 4)
    assertEquals(
      ids.toSet,
      Set("Q-RL-01", "Q-CA-01", "Q-SP-01", "Q-UN-01")
    )
  }

  test("an offline run through the command line prints counts and no text") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val lines = mutable.ArrayBuffer.empty[String]
    val code = RetrievalEval.execute(
      Seq(
        "--offline",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("cli").toString,
        "--run-id",
        "cli-g",
        "--repeats",
        "1",
        "--resamples",
        "20"
      ),
      lines += _
    )
    assertEquals(code, 0)
    assert(lines.exists(_.startsWith("RETRIEVAL_RESULT status=COMPLETE")))
    assert(lines.exists(_.startsWith("RETRIEVAL_COST")))
    lines.foreach { line =>
      assert(!line.contains("Ada Quillfeather"), line)
      assert(!line.contains("capital"), line)
    }
  }

  test("an unknown option or a live run without a ceiling is a usage error") {
    val lines = mutable.ArrayBuffer.empty[String]
    assertEquals(RetrievalEval.execute(Seq("--bogus", "1"), lines += _), 2)
    assertEquals(
      RetrievalEval.execute(
        Seq(
          "--questions",
          "q",
          "--labels",
          "l",
          "--out-dir",
          "o",
          "--run-id",
          "r",
          "--export-dir",
          "/nowhere",
          "--titan-max-calls",
          "10",
          "--titan-usd-per-mtok",
          "0.02"
        ),
        lines += _
      ),
      2
    )
    assert(lines.exists(_.contains("--usd-ceiling is required")))
    assertEquals(
      RetrievalEval.execute(
        Seq(
          "--questions",
          "q",
          "--labels",
          "l",
          "--out-dir",
          "o",
          "--run-id",
          "r",
          "--export-dir",
          "/nowhere",
          "--region",
          "us-west-2",
          "--titan-max-calls",
          "10",
          "--usd-ceiling",
          "0.1",
          "--titan-usd-per-mtok",
          "0.02"
        ),
        lines += _
      ),
      2
    )
    assert(lines.exists(_.contains("us-east-2")))
  }
