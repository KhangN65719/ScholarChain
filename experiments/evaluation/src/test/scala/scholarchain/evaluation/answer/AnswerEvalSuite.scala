package scholarchain.evaluation.answer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.evaluation.io.Digests
import scholarchain.evaluation.retrieval.{
  BenchFixture,
  BenchmarkFiles,
  LabelRec,
  QuestionRec
}
import scholarchain.evaluation.runtime.{
  CostGuard,
  CostLimits,
  LaptopPorts,
  Rates
}
import scholarchain.rag.{
  CollaborationWorkflow,
  ModelPort,
  ModelRequest,
  ModelResponse,
  RetrievalMode,
  Session
}

class AnswerEvalSuite extends FunSuite:
  private def tempDir(): Path = Files.createTempDirectory("answer-eval-")

  private def guard(haiku: Int = 1000, usd: Double = 1.0) =
    new CostGuard(CostLimits(1000, haiku, usd), Rates(1.0, 5.0, 0.02))

  private def config(
      dir: Path,
      runId: String = "run-h",
      diagnostic: Boolean = false,
      repeatCount: Int = 20,
      sample: Int = 20
  ): AnswerEval.Config =
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    AnswerEval.Config(
      q,
      l,
      dir.resolve("out"),
      runId,
      diagnostic = diagnostic,
      repeatCount = repeatCount,
      ratingSampleSize = sample
    )

  /** A model that counts its calls and always answers the same unusable text
    * with a stop reason, so every attempt is rejected.
    */
  private final class Broken(stop: Option[String]) extends ModelPort:
    var calls = 0
    def complete(r: ModelRequest): ModelResponse =
      calls += 1
      ModelResponse("not json", "fake", 1000, 1000, false, stop)

  private def records(dir: Path): Vector[AnswerRecord] =
    Files
      .readAllLines(dir.resolve("answers.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => AnswerRecord.fromJson(ujson.read(l)))

  private def allText(dir: Path): String =
    Files
      .list(dir)
      .iterator
      .asScala
      .filter(Files.isRegularFile(_))
      .map(p => Files.readString(p))
      .mkString("\n")

  // --- full mode ---

  test(
    "the offline full run freezes the answers, then the summaries, then seals"
  ) {
    val dir = tempDir()
    val wiring = LaptopPorts.offline(guard())
    val report = AnswerEval.run(config(dir), wiring)
    assertEquals(report.status, "COMPLETE")
    assertEquals(report.exitCode, 0)
    Vector(
      "answers.jsonl",
      "RAW-SHA256SUMS",
      "model-attempts-summary.json",
      "model-calls.jsonl",
      "answer-summary.json",
      "rating-sample.csv",
      "rating-sample-key.json",
      "run-manifest.json",
      "SHA256SUMS"
    ).foreach(n => assert(Files.exists(report.runDir.resolve(n)), n))
    assertEquals(
      Files.readString(report.runDir.resolve("RAW-SHA256SUMS")).trim,
      s"${Digests.sha256File(report.runDir.resolve("answers.jsonl"))}  answers.jsonl"
    )
    val all = records(report.runDir)
    val primary = all.filter(_.run == "primary")
    assertEquals(primary.size, 6)
    assert(primary.forall(_.mode == "fused"))
    val answered = primary.filter(!_.abstained)
    assert(answered.nonEmpty)
    assert(answered.forall(a => a.claims.nonEmpty && a.evidence.nonEmpty))
    assert(answered.forall(_.verification.isDefined))
    // The repeat pass answers the answerable, answered questions again.
    val repeat = all.filter(_.run == "repeat")
    assert(repeat.nonEmpty)
    assert(
      repeat.map(_.questionId).toSet.subsetOf(answered.map(_.questionId).toSet)
    )
    assert(repeat.size <= 20)
    assertEquals(wiring.guard.calls.size, wiring.guard.haikuCallCount)
    val sums = Files.readString(report.runDir.resolve("SHA256SUMS"))
    assert(
      sums.contains("  answers.jsonl") && sums.contains("  rating-sample.csv")
    )
  }

  test(
    "only the question text reaches the workflow, in the primary and the repeat pass"
  ) {
    val dir = tempDir()
    val cfg = config(dir)
    val wiring = LaptopPorts.offline(guard())
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    val seen = mutable.ArrayBuffer.empty[(String, Session, RetrievalMode)]
    val spy: AnswerEval.Ask = (text, session, mode) =>
      seen += ((text, session, mode))
      workflow.answer(text, session, mode)
    val report = AnswerEval.runWith(cfg, wiring, spy)
    assertEquals(report.status, "COMPLETE")
    val texts = BenchmarkFiles.readQuestions(cfg.questionsPath).map(_.text)
    assertEquals(seen.map(_._1).distinct.toVector, texts)
    assert(seen.size > texts.size, "the repeat pass asked some questions again")
    assert(seen.forall(_._3 == RetrievalMode.Fused))
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
    assertEquals(
      classOf[QuestionRec].getDeclaredFields.map(_.getName).toSet,
      Set("questionId", "text")
    )
  }

  test("the repeat pass can be dropped and the question set trimmed") {
    val dir = tempDir()
    val cfg = config(dir, repeatCount = 0).copy(maxQuestions = Some(3))
    val report = AnswerEval.run(cfg, LaptopPorts.offline(guard()))
    val all = records(report.runDir)
    assertEquals(all.size, 3)
    assert(all.forall(_.run == "primary"))
  }

  test(
    "a record carries the claims, the evidence, the verifier report, tokens and latency"
  ) {
    val dir = tempDir()
    val report =
      AnswerEval.run(config(dir, repeatCount = 0), LaptopPorts.offline(guard()))
    val coauthor = records(report.runDir).find(_.questionId == "Q-CA-01").get
    assert(coauthor.answer.nonEmpty)
    assert(coauthor.evidence.exists(_.kind.contains("coauthor")))
    assert(coauthor.evidence.forall(_.text.isDefined))
    assert(
      coauthor.claims.forall(
        _.evidenceIds.forall(id => coauthor.evidence.exists(_.evidenceId == id))
      )
    )
    assert(coauthor.verification.exists(v => v("claims").arr.nonEmpty))
    assert(coauthor.modelCalls >= 1)
    assert(coauthor.promptTokens > 0 && coauthor.completionTokens > 0)
    assert(coauthor.latencyMillis >= 0L)
    assert(coauthor.answerHash.nonEmpty)
    val back = AnswerRecord.fromJson(ujson.read(ujson.write(coauthor.toJson)))
    assertEquals(ujson.write(back.toJson), ujson.write(coauthor.toJson))
    assertEquals(back.claims, coauthor.claims)
    assertEquals(back.evidence, coauthor.evidence)
    assertEquals(back.attempts, coauthor.attempts)
  }

  test("the abstention fields of an unanswerable question are recorded") {
    val dir = tempDir()
    val report =
      AnswerEval.run(config(dir, repeatCount = 0), LaptopPorts.offline(guard()))
    val all = records(report.runDir)
    val refused = all.find(_.questionId == "Q-UN-01").get
    assert(refused.abstained)
    assertEquals(refused.abstentionReason, Some("planner-refused"))
    val unknown = all.find(_.questionId == "Q-UN-02").get
    assertEquals(unknown.abstentionReason, Some("identity-unresolved"))
  }

  test(
    "the USD stop ends the run before the call that could pass the ceiling"
  ) {
    val dir = tempDir()
    // Every attempt is metered at 1000 in and 1000 out: 0.006 USD. The worst
    // case of one synthesis call (4096 output tokens, 0.02 USD, plus the prompt
    // and the schema) is a little over 0.02, so the ceiling stops the run after
    // some calls, and the call that could pass it is refused unmade.
    val g = guard(usd = 0.05)
    val fake = new Broken(Some("end_turn"))
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(config(dir, repeatCount = 0), wiring)
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("usd-ceiling"))
    assertEquals(report.exitCode, 3)
    assert(fake.calls >= 3, s"calls made: ${fake.calls}")
    assertEquals(
      g.haikuCallCount,
      fake.calls,
      "no call was made after the stop"
    )
    assertEqualsDouble(g.usd, fake.calls * 0.006, 1e-9)
    assert(g.usd <= 0.05)
    val all = records(report.runDir)
    assertEquals(all.count(_.costStopped), 1)
    assert(all.last.costStopped)
    assertEquals(report.usable, all.size - 1)
    val manifest =
      ujson.read(Files.readString(report.runDir.resolve("run-manifest.json")))
    assertEquals(manifest("cost")("stopReason").str, "usd-ceiling")
    assert(manifest("cost")("basis").str.contains("worst case"))
  }

  test("the call cap ends the run and counts only the calls that were made") {
    val dir = tempDir()
    val g = guard(haiku = 2)
    val fake = new Broken(None)
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(config(dir, repeatCount = 0), wiring)
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("haiku-call-cap"))
    assertEquals(fake.calls, 2)
    assertEquals(g.haikuCallCount, 2)
  }

  private def meteredSummary(stop: String, attemptsExpected: Int): Unit =
    val dir = tempDir()
    val g = guard()
    val fake = new Broken(Some(stop))
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(
      config(dir, repeatCount = 0).copy(maxQuestions = Some(1)),
      wiring
    )
    val n = attemptsExpected
    assertEquals(fake.calls, n)
    val summary =
      ujson.read(
        Files.readString(report.runDir.resolve("model-attempts-summary.json"))
      )
    assertEquals(summary("attempts").num.toInt, n)
    assertEquals(summary("rejected").num.toInt, n)
    assertEquals(summary("stopReasons")(stop).num.toInt, n)
    assertEquals(
      summary("maxTokensAttempts").num.toInt,
      if stop == "max_tokens" then n else 0
    )
    assertEquals(
      summary("rejectionCodes")("output is not valid JSON").num.toInt,
      n
    )
    val calls = Files
      .readAllLines(report.runDir.resolve("model-calls.jsonl"))
      .asScala
      .toVector
    assertEquals(calls.count(_.trim.nonEmpty), n)
    val first = ujson.read(calls.head)
    assertEquals(first("promptTokens").num.toInt, 1000)
    assertEquals(first("stopReason").str, stop)
    assertEqualsDouble(g.usd, n * (1000 * 1.0 / 1e6 + 1000 * 5.0 / 1e6), 1e-12)

  test(
    "a max_tokens model is metered, makes one call per answer, and the attempt reaches the summary"
  ) {
    meteredSummary("max_tokens", 1)
  }

  test(
    "a model with another invalid reply is still retried to the bound and metered per attempt"
  ) {
    meteredSummary("end_turn", 3)
  }

  // --- the diagnostic ---

  test(
    "the diagnostic answers questions of distinct strata and writes no text"
  ) {
    val dir = tempDir()
    val g = guard(haiku = 4, usd = 0.15)
    val wiring = LaptopPorts.offline(g)
    val report = AnswerEval.run(config(dir, diagnostic = true), wiring)
    assertEquals(report.status, "COMPLETE")
    assert(g.haikuCallCount <= 4)
    assert(!Files.exists(report.runDir.resolve("answers.jsonl")))
    assert(!Files.exists(report.runDir.resolve("rating-sample.csv")))
    val lines = Files
      .readAllLines(report.runDir.resolve("diagnostic.jsonl"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => ujson.read(l))
    assertEquals(
      lines.map(_("stratum").str),
      Vector("researcher-lookup", "coauthor-lookup", "shortest-path")
    )
    val text = allText(report.runDir)
    Vector("Quillfeather", "capital", "Who is", "coauthors of").foreach { w =>
      assert(!text.contains(w), s"the diagnostic output holds text: $w")
    }
    assert(Files.exists(report.runDir.resolve("diagnostic-summary.json")))
  }

  test(
    "the diagnostic stops at four calls and records the stop reason and codes"
  ) {
    val dir = tempDir()
    val g = guard(haiku = 4, usd = 0.15)
    // end_turn: an invalid reply with this stop reason is retried to the
    // bound. (A max_tokens reply is not retried: see the next test.)
    val fake = new Broken(Some("end_turn"))
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(config(dir, diagnostic = true), wiring)
    assertEquals(fake.calls, 4, "at most four answer-model calls")
    assertEquals(g.haikuCallCount, 4)
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("haiku-call-cap"))
    val diag = Files
      .readAllLines(report.runDir.resolve("diagnostic.jsonl"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => ujson.read(l))
    // The first question uses three attempts; the second makes one call, and
    // its second attempt is refused by the guard (recorded as a failed call).
    val attempts = diag.flatMap(d => d("attempts").arr)
    assertEquals(attempts.size, 5)
    assert(attempts.forall(_("rejections").arr.nonEmpty))
    assert(
      attempts.exists(a =>
        a("rejections").arr.exists(_.str == "output is not valid JSON")
      )
    )
    assert(
      attempts.exists(a =>
        a("rejections").arr
          .exists(_.str == "model call failed: cost-stop-haiku-call-cap")
      )
    )
    assertEquals(
      attempts.count(a => a("stopReason") == ujson.Str("end_turn")),
      4
    )
    val summary = ujson.read(
      Files.readString(report.runDir.resolve("diagnostic-summary.json"))
    )
    assertEquals(summary("calls").num.toInt, 4)
    assertEquals(summary("attempts")("attempts").num.toInt, 5)
    assertEquals(summary("attempts")("maxTokensAttempts").num.toInt, 0)
    assertEquals(summary("retryDecisions").obj.size, 0)
  }

  test(
    "a max_tokens diagnostic makes one call per question and carries the per-attempt measures, with no text"
  ) {
    val dir = tempDir()
    val g = guard(haiku = 4, usd = 0.15)
    val fake = new Broken(Some("max_tokens"))
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(config(dir, diagnostic = true), wiring)
    // Three strata in the fixture, one call each: the cap is not reached.
    assertEquals(fake.calls, 3)
    assertEquals(report.status, "COMPLETE")
    val diag = Files
      .readAllLines(report.runDir.resolve("diagnostic.jsonl"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => ujson.read(l))
    val attempts = diag.flatMap(d => d("attempts").arr)
    assertEquals(attempts.size, 3)
    attempts.foreach { a =>
      assertEquals(a("stopReason").str, "max_tokens")
      assertEquals(a("retryDecision").str, "max_tokens-no-retry")
      assertEquals(a("replyChars").num.toInt, "not json".length)
      assertEquals(a("claimsStarted").num.toInt, 0)
      assertEquals(a("repeatRatio").num, 0.0)
      assert(a("evidenceItems").num.toInt >= 0)
      // No ranking in these questions' bundles: the ranking measures are absent.
      assert(!a.obj.contains("rankingEntries"))
      assert(!a.obj.contains("tieAtCutoff"))
    }
    val summary = ujson.read(
      Files.readString(report.runDir.resolve("diagnostic-summary.json"))
    )
    assertEquals(
      summary("retryDecisions")("max_tokens-no-retry").num.toInt,
      3
    )
    val measures = summary("attemptMeasures").arr
    assertEquals(measures.size, 3)
    assertEquals(
      measures.map(_("questionId").str).toVector,
      diag.map(_("questionId").str)
    )
    measures.foreach { m =>
      assertEquals(m("replyChars").num.toInt, "not json".length)
      assertEquals(m("retryDecision").str, "max_tokens-no-retry")
      assert(!m.obj.contains("rejections") && !m.obj.contains("promptId"))
    }
    val text = allText(report.runDir)
    Vector("not json", "Quillfeather", "Who is").foreach { w =>
      assert(!text.contains(w), s"the diagnostic output holds text: $w")
    }
  }

  test(
    "an attempt record round-trips every new measure and writes only those that are set"
  ) {
    val bare = AttemptRec("p", 1, false, Vector("r"), Some("end_turn"), 7)
    val bareJson = bare.toJson
    Vector(
      "replyChars",
      "claimsStarted",
      "repeatRatio",
      "evidenceItems",
      "rankingEntries",
      "rankedTotal",
      "cutoffTies",
      "tieAtCutoff",
      "retryDecision"
    ).foreach(k => assert(!bareJson.obj.contains(k), s"$k written when absent"))
    // An older record (no new keys) reads back with every measure absent.
    assertEquals(AttemptRec.fromJson(bareJson), bare)
    val full = bare.copy(
      replyChars = Some(4096),
      claimsStarted = Some(3),
      repeatRatio = Some(0.8125),
      evidenceItems = Some(9),
      rankingEntries = Some(25),
      rankedTotal = Some(858),
      cutoffTies = Some(2),
      tieAtCutoff = Some(true),
      retryDecision = Some("max_tokens-no-retry")
    )
    val back = AttemptRec.fromJson(ujson.read(ujson.write(full.toJson)))
    assertEquals(back, full)
    assertEquals(full.toJson("tieAtCutoff"), ujson.Bool(true))
    assertEquals(full.toJson("repeatRatio").num, 0.8125)
    // A partial record: only the set ones are written.
    val some = bare.copy(replyChars = Some(5), tieAtCutoff = Some(false))
    assertEquals(
      some.toJson.obj.keySet -- bareJson.obj.keySet,
      Set("replyChars", "tieAtCutoff")
    )
  }

  test("AttemptRec.of copies the measures of a model attempt, set or not") {
    val raw = scholarchain.rag.ModelAttempt(
      "p",
      2,
      false,
      Vector("output is not valid JSON"),
      Some("max_tokens"),
      4096,
      reply = Some(scholarchain.rag.ReplyShape(9000, 4, 0.5)),
      input = scholarchain.rag.InputShape(
        Some(7),
        Some(25),
        Some(858),
        Some(3),
        Some(true)
      ),
      retryDecision = Some("max_tokens-no-retry")
    )
    assertEquals(
      AttemptRec.of(raw),
      AttemptRec(
        "p",
        2,
        false,
        Vector("output is not valid JSON"),
        Some("max_tokens"),
        4096,
        Some(9000),
        Some(4),
        Some(0.5),
        Some(7),
        Some(25),
        Some(858),
        Some(3),
        Some(true),
        Some("max_tokens-no-retry")
      )
    )
    val plain =
      scholarchain.rag.ModelAttempt("p", 1, true, Vector.empty, None, 3)
    assertEquals(
      AttemptRec.of(plain),
      AttemptRec("p", 1, true, Vector.empty, None, 3)
    )
  }

  test(
    "the diagnostic refuses a guard that allows more than four calls, before writing"
  ) {
    val dir = tempDir()
    val cfg = config(dir, diagnostic = true)
    val e = intercept[IllegalArgumentException](
      AnswerEval.run(cfg, LaptopPorts.offline(guard(haiku = 5)))
    )
    assert(e.getMessage.contains("at most 4"))
    assert(!Files.exists(dir.resolve("out").resolve("run-h")))
  }

  test(
    "the diagnostic picks the first answerable question of up to four strata"
  ) {
    def lab(
        id: String,
        stratum: String,
        abstain: Boolean = false,
        items: Int = 1
    ) =
      scholarchain.evaluation.retrieval.QuestionRec(id, "t") ->
        LabelRec(
          id,
          stratum,
          stratum,
          Vector.fill(items)(("x", 1)),
          Vector.empty,
          abstain,
          None,
          Vector.empty,
          Vector.empty,
          None,
          None,
          Vector.empty,
          None
        )
    val pairs = Vector(
      lab("U1", "unanswerable", abstain = true, items = 0),
      lab("K1", "coauthor-lookup"),
      lab("K2", "coauthor-lookup"),
      lab("R1", "ranking"),
      lab("S0", "shortest-path", items = 0),
      lab("S1", "shortest-path"),
      lab("C1", "community-component"),
      lab("T1", "temporal-comparison"),
      lab("L1", "researcher-lookup")
    )
    assertEquals(
      AnswerEval.diagnosticPairs(pairs).map(_._1.questionId),
      Vector("L1", "R1", "K1", "S1")
    )
  }

  private def pairOf(
      id: String,
      stratum: String,
      abstain: Boolean = false
  ): (QuestionRec, LabelRec) =
    QuestionRec(id, "t") ->
      LabelRec(
        id,
        stratum,
        stratum,
        if abstain then Vector.empty else Vector(("x", 1)),
        Vector.empty,
        abstain,
        None,
        Vector.empty,
        Vector.empty,
        None,
        None,
        Vector.empty,
        None
      )

  test(
    "--question-ids selects exactly the named questions in the order given, answerable or not"
  ) {
    val pairs = Vector(
      pairOf("A", "researcher-lookup"),
      pairOf("B", "ranking"),
      pairOf("C", "ranking"),
      pairOf("U", "unanswerable", abstain = true)
    )
    assertEquals(
      AnswerEval
        .diagnosticPairs(pairs, Some(Vector("C", "A")))
        .map(_._1.questionId),
      Vector("C", "A")
    )
    assertEquals(
      AnswerEval
        .diagnosticPairs(pairs, Some(Vector("U", "B", "C")))
        .map(_._1.questionId),
      Vector("U", "B", "C")
    )
    // Without ids the default selection is unchanged.
    assertEquals(
      AnswerEval.diagnosticPairs(pairs).map(_._1.questionId),
      Vector("A", "B")
    )
  }

  test(
    "--question-ids refuses an unknown, repeated, empty or surplus id list"
  ) {
    val pairs = Vector(pairOf("A", "ranking"), pairOf("B", "ranking"))
    val unknown = intercept[IllegalArgumentException](
      AnswerEval.diagnosticPairs(pairs, Some(Vector("A", "Z9", "Y8")))
    )
    assert(unknown.getMessage.contains("Z9,Y8"), unknown.getMessage)
    val repeated = intercept[IllegalArgumentException](
      AnswerEval.diagnosticPairs(pairs, Some(Vector("A", "A")))
    )
    assert(repeated.getMessage.contains("repeats"), repeated.getMessage)
    intercept[IllegalArgumentException](
      AnswerEval.diagnosticPairs(pairs, Some(Vector.empty))
    )
    val many = (1 to 5).map(i => pairOf(s"Q$i", "ranking")).toVector
    val surplus = intercept[IllegalArgumentException](
      AnswerEval.diagnosticPairs(many, Some(many.map(_._1.questionId)))
    )
    assert(surplus.getMessage.contains("at most 4"), surplus.getMessage)
  }

  private def cliArgs(dir: Path, runId: String, extra: Seq[String]) =
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    Seq(
      "--offline",
      "--diagnostic",
      "--questions",
      q.toString,
      "--labels",
      l.toString,
      "--out-dir",
      dir.resolve("cli").toString,
      "--run-id",
      runId
    ) ++ extra

  test(
    "a diagnostic command line with --question-ids answers those questions in that order and keeps the clamps"
  ) {
    val dir = tempDir()
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
      cliArgs(
        dir,
        "ids-ok",
        Seq(
          "--question-ids",
          "Q-SP-01,Q-RL-01",
          "--usd-ceiling",
          "5",
          "--haiku-max-calls",
          "50"
        )
      ),
      lines += _
    )
    assertEquals(code, 0, lines.toString)
    val run = dir.resolve("cli/ids-ok")
    val diag = Files
      .readAllLines(run.resolve("diagnostic.jsonl"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => ujson.read(l)("questionId").str)
    assertEquals(diag, Vector("Q-SP-01", "Q-RL-01"))
    val manifest =
      ujson.read(Files.readString(run.resolve("run-manifest.json")))
    assertEquals(manifest("cost")("limits")("usdCeiling").num, 0.15)
    assertEquals(manifest("cost")("limits")("haikuMaxCalls").num.toInt, 4)
    assertEquals(
      manifest("config")("questionIds").arr.map(_.str).toVector,
      Vector("Q-SP-01", "Q-RL-01")
    )
    assertEquals(manifest("inputs")("questionsRun").num.toInt, 2)
  }

  test(
    "a diagnostic command line refuses an unknown question id before writing anything"
  ) {
    val dir = tempDir()
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
      cliArgs(dir, "ids-bad", Seq("--question-ids", "Q-RL-01,Q-NOPE-9")),
      lines += _
    )
    assertEquals(code, 2)
    assert(lines.exists(_.contains("Q-NOPE-9")), lines.toString)
    assert(!Files.exists(dir.resolve("cli")), "nothing was written")
    // --question-ids without --diagnostic is refused too.
    val full = AnswerEval.execute(
      cliArgs(dir, "ids-full", Seq("--question-ids", "Q-RL-01"))
        .filterNot(_ == "--diagnostic"),
      lines += _
    )
    assertEquals(full, 2)
    assert(!Files.exists(dir.resolve("cli")), "nothing was written")
    assert(AnswerEval.Usage.contains("--question-ids"))
  }

  test(
    "a diagnostic run with --question-ids still refuses a guard above the caps, before writing"
  ) {
    val dir = tempDir()
    val cfg = config(dir, diagnostic = true)
      .copy(questionIds = Some(Vector("Q-RL-01", "Q-CA-01")))
    val e = intercept[IllegalArgumentException](
      AnswerEval.run(cfg, LaptopPorts.offline(guard(haiku = 5)))
    )
    assert(e.getMessage.contains("at most 4"))
    assert(!Files.exists(dir.resolve("out").resolve("run-h")))
  }

  test(
    "the diagnostic refuses a USD ceiling above 0.15, before writing, and the command line clamps it"
  ) {
    val dir = tempDir()
    val cfg = config(dir, diagnostic = true)
    val e = intercept[IllegalArgumentException](
      AnswerEval.run(cfg, LaptopPorts.offline(guard(haiku = 4, usd = 0.2)))
    )
    assert(e.getMessage.contains("0.15"), e.getMessage)
    assert(!Files.exists(dir.resolve("out").resolve("run-h")))
    val (q, l) = BenchFixture.write(dir.resolve("cli-bench"))
    val code = AnswerEval.execute(
      Seq(
        "--offline",
        "--diagnostic",
        "--usd-ceiling",
        "5",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("cli").toString,
        "--run-id",
        "clamped"
      ),
      _ => ()
    )
    assertEquals(code, 0)
    val manifest = ujson.read(
      Files.readString(dir.resolve("cli/clamped/run-manifest.json"))
    )
    assertEquals(manifest("cost")("limits")("usdCeiling").num, 0.15)
    assertEquals(manifest("cost")("limits")("haikuMaxCalls").num.toInt, 4)
  }

  // --- raw records survive a kill; the deadline ---

  test("a run killed mid-way keeps the records made so far") {
    val dir = tempDir()
    val cfg = config(dir, repeatCount = 0)
    val wiring = LaptopPorts.offline(guard())
    val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
    var asked = 0
    var onDiskAtKill = Vector.empty[String]
    // InterruptedException is not NonFatal: it leaves the run loop as a kill
    // would, with no summary, no digest and no seal. Just before the throw, and
    // before any close in the run's finally, the file is read from disk: only
    // per-append flushing puts the three finished lines there.
    val dying: AnswerEval.Ask = (text, session, mode) =>
      asked += 1
      if asked == 4 then
        onDiskAtKill = Files
          .readAllLines(
            dir.resolve("out").resolve("run-h").resolve("answers.jsonl"),
            UTF_8
          )
          .asScala
          .toVector
          .filter(_.trim.nonEmpty)
        throw new InterruptedException("killed")
      workflow.answer(text, session, mode)
    // (munit's intercept only catches NonFatal, so the catch is spelled out.)
    val killed =
      try
        AnswerEval.runWith(cfg, wiring, dying)
        false
      catch case _: InterruptedException => true
    assert(killed, "the interrupted run should not have finished")
    assertEquals(
      onDiskAtKill.map(l => AnswerRecord.fromJson(ujson.read(l)).questionId),
      Vector("Q-RL-01", "Q-CA-01", "Q-SP-01"),
      "the finished lines were on disk before the kill, not only at close"
    )
    val runDir = dir.resolve("out").resolve("run-h")
    val lines = Files
      .readAllLines(runDir.resolve("answers.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
    assertEquals(
      lines.map(l => AnswerRecord.fromJson(ujson.read(l)).questionId),
      Vector("Q-RL-01", "Q-CA-01", "Q-SP-01")
    )
    Vector(
      "RAW-SHA256SUMS",
      "SHA256SUMS",
      "run-manifest.json",
      "answer-summary.json"
    ).foreach(n => assert(!Files.exists(runDir.resolve(n)), n))
  }

  test(
    "the raw file is hashed before the summary and holds every record at the end"
  ) {
    val dir = tempDir()
    val report = AnswerEval.run(
      config(dir, repeatCount = 0),
      LaptopPorts.offline(guard())
    )
    val file = report.runDir.resolve("answers.jsonl")
    assertEquals(
      Files.readAllLines(file, UTF_8).asScala.count(_.trim.nonEmpty),
      report.records
    )
    assertEquals(
      Files.readString(report.runDir.resolve("RAW-SHA256SUMS")).trim,
      s"${Digests.sha256File(file)}  answers.jsonl"
    )
  }

  private def ticking(stepSeconds: Long): () => Long =
    var t = 0L
    () =>
      t += stepSeconds * 1000000000L
      t

  test(
    "an absolute deadline stops the run between answers and wins over a longer max-minutes"
  ) {
    val dir = tempDir()
    // nanos advance 1 s per reading; the wall clock reads 1,000,000 ms. The
    // deadline is 3 s after the first reading.
    val cfg = config(dir, repeatCount = 0)
      .copy(
        maxMinutes = Some(60),
        deadlineEpochMillis = Some(1000000L + 3000L)
      )
    val wiring = LaptopPorts.offline(guard())
    val report = AnswerEval.runWith(
      cfg,
      wiring,
      AnswerEval.defaultAsk(wiring),
      ticking(1),
      () => 1000000L
    )
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("wall-clock"))
    assert(report.records >= 1 && report.records < 6, report.records)
    assertEquals(
      Files
        .readAllLines(report.runDir.resolve("answers.jsonl"), UTF_8)
        .asScala
        .count(_.trim.nonEmpty),
      report.records
    )
  }

  test("a deadline already past answers nothing and says so") {
    val dir = tempDir()
    val cfg = config(dir, repeatCount = 0)
      .copy(deadlineEpochMillis = Some(500L))
    val wiring = LaptopPorts.offline(guard())
    val report = AnswerEval.runWith(
      cfg,
      wiring,
      AnswerEval.defaultAsk(wiring),
      ticking(1),
      () => 1000000L
    )
    assertEquals(report.status, "HALTED")
    assertEquals(report.haltReason, Some("wall-clock"))
    assertEquals(report.records, 0)
    assertEquals(wiring.guard.haikuCallCount, 0)
    assert(Files.exists(report.runDir.resolve("RAW-SHA256SUMS")))
  }

  test("the command line takes an absolute deadline and stops at it") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
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
    assert(lines.exists(_.contains("status=HALTED")), lines.toString)
    assert(lines.exists(_.contains("halt=wall-clock")), lines.toString)
  }

  // --- a finished answer is not cost-stopped ---

  private final class Inflated(usageTokens: Int) extends ModelPort:
    private val inner = new scholarchain.rag.DeterministicStubModel
    def complete(r: ModelRequest): ModelResponse =
      inner
        .complete(r)
        .copy(
          promptTokens = usageTokens,
          completionTokens = usageTokens,
          tokensEstimated = false
        )

  test(
    "an answer that completed and pushed spend past the ceiling is not marked cost-stopped"
  ) {
    val dir = tempDir()
    // 200000 tokens in and out is 1.2 USD at 1.0 and 5.0 per million: over the
    // 1.0 ceiling after the first call. The call was admitted (its worst case
    // is about 0.025) and finished, so the answer stands.
    val g = guard(usd = 1.0)
    val wiring =
      LaptopPorts.offline(g, Some(new Inflated(200000)), modelBillable = true)
    val report = AnswerEval.run(
      config(dir, repeatCount = 0).copy(maxQuestions = Some(1)),
      wiring
    )
    assertEquals(g.stopReason, Some("usd-ceiling"))
    assertEquals(g.refusedCount, 0)
    val all = records(report.runDir)
    assertEquals(all.size, 1)
    assert(!all.head.costStopped, "a completed answer is not cost-stopped")
    assertEquals(report.usable, 1)
  }

  test("a call the guard refuses marks its answer cost-stopped") {
    val dir = tempDir()
    val g = guard(haiku = 1)
    val fake = new Broken(None)
    val wiring = LaptopPorts.offline(g, Some(fake), modelBillable = true)
    val report = AnswerEval.run(
      config(dir, repeatCount = 0).copy(maxQuestions = Some(1)),
      wiring
    )
    // The first attempt reached the model, the second was refused unmade.
    assertEquals(fake.calls, 1)
    assert(g.refusedCount >= 1)
    assertEquals(records(report.runDir).map(_.costStopped), Vector(true))
    assertEquals(g.snapshot("refusedCalls").num.toInt, g.refusedCount)
  }

  // --- the command line ---

  test("an offline run through the command line prints counts and no text") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
      Seq(
        "--offline",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("cli").toString,
        "--run-id",
        "cli-h",
        "--repeat-count",
        "2"
      ),
      lines += _
    )
    assertEquals(code, 0)
    assert(
      lines.exists(_.startsWith("ANSWER_RESULT status=COMPLETE mode=full"))
    )
    assert(lines.exists(_.startsWith("ANSWER_COST")))
    lines.foreach { line =>
      assert(!line.contains("Quillfeather"), line)
      assert(!line.contains("capital"), line)
    }
  }

  test(
    "a diagnostic command line clamps the call cap to four and prints the stop reasons"
  ) {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
      Seq(
        "--offline",
        "--diagnostic",
        "--haiku-max-calls",
        "50",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("cli").toString,
        "--run-id",
        "cli-diag"
      ),
      lines += _
    )
    assertEquals(code, 0)
    assert(lines.exists(_.startsWith("DIAGNOSTIC calls=")), lines.toString)
    val calls = lines.find(_.startsWith("ANSWER_COST")).get
    assert(calls.contains("haikuCalls=3"), calls)
  }

  test(
    "a live run needs every rate, the model id and the caps, and refuses another region"
  ) {
    val lines = mutable.ArrayBuffer.empty[String]
    assertEquals(AnswerEval.execute(Seq("--bogus", "1"), lines += _), 2)
    val base = Seq(
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
      "--data-bucket",
      "d",
      "--vector-bucket",
      "v",
      "--model-id",
      "m",
      "--titan-max-calls",
      "10",
      "--haiku-max-calls",
      "10",
      "--usd-ceiling",
      "0.5"
    )
    assertEquals(AnswerEval.execute(base, lines += _), 2)
    assert(lines.exists(_.contains("--haiku-input-usd-per-mtok is required")))
    val rates = Seq(
      "--haiku-input-usd-per-mtok",
      "1",
      "--haiku-output-usd-per-mtok",
      "5",
      "--titan-usd-per-mtok",
      "0.02"
    )
    assertEquals(
      AnswerEval
        .execute(base ++ rates ++ Seq("--region", "eu-west-1"), lines += _),
      2
    )
    assert(lines.exists(_.contains("us-east-2")))
  }

  // --- the rating sample ---

  private def candidate(
      id: String,
      stratum: String,
      abstain: Boolean = false,
      run: String = "primary",
      costStopped: Boolean = false
  ): (AnswerRecord, LabelRec) =
    AuditFixture.answer(id, run = run, costStopped = costStopped) ->
      LabelRec(
        id,
        stratum,
        stratum,
        if abstain then Vector.empty else Vector(("x", 1)),
        Vector.empty,
        abstain,
        None,
        Vector.empty,
        Vector.empty,
        None,
        None,
        Vector.empty,
        None
      )

  private val strata = Vector(
    "researcher-lookup" -> 7,
    "coauthor-lookup" -> 8,
    "ranking" -> 10,
    "shortest-path" -> 6,
    "community-component" -> 6,
    "temporal-comparison" -> 5
  )

  private def population: Vector[(AnswerRecord, LabelRec)] =
    strata.flatMap((s, n) => (1 to n).map(i => candidate(s"$s-$i", s))) ++
      (1 to 8).map(i => candidate(s"un-$i", "unanswerable", abstain = true)) ++
      (1 to 8).map(i => candidate(s"ff-$i", "free-form"))

  test(
    "the rating sample has 3 abstentions, 2 free-form and 2 or 3 per stratum"
  ) {
    val picked = RatingSample.select(population, 20, 7L)
    assertEquals(picked.size, 20)
    assertEquals(
      picked.map(_.sampleId),
      (1 to 20).map(i => f"S$i%02d").toVector
    )
    val byGroup = picked.groupBy { p =>
      if p.label.abstain then "abstention" else p.label.stratum
    }
    assertEquals(byGroup("abstention").size, 3)
    assertEquals(byGroup("free-form").size, 2)
    strata.foreach((s, _) =>
      assert(byGroup(s).size == 2 || byGroup(s).size == 3, s)
    )
    assertEquals(strata.map((s, _) => byGroup(s).size).sum, 15)
    // The three largest strata get the third answer.
    assertEquals(byGroup("ranking").size, 3)
    assertEquals(byGroup("coauthor-lookup").size, 3)
    assertEquals(byGroup("researcher-lookup").size, 3)
    assertEquals(picked.map(_.record.questionId).distinct.size, 20)
  }

  test("the sample is deterministic for a seed and differs for another") {
    val a = RatingSample.select(population, 20, 7L).map(_.record.questionId)
    val b = RatingSample.select(population, 20, 7L).map(_.record.questionId)
    val c = RatingSample.select(population, 20, 8L).map(_.record.questionId)
    assertEquals(a, b)
    assertNotEquals(a, c)
  }

  test(
    "only usable primary answers are drawn, and a missing free-form share moves to the strata"
  ) {
    val noFree = population.filterNot(_._2.stratum == "free-form")
    val mixed = noFree ++ Vector(
      candidate("rep", "ranking", run = "repeat"),
      candidate("stopped", "ranking", costStopped = true)
    )
    val picked = RatingSample.select(mixed, 20, 3L)
    assertEquals(picked.size, 20)
    assert(
      !picked.exists(p => Set("rep", "stopped").contains(p.record.questionId))
    )
    assertEquals(picked.count(_.label.abstain), 3)
    assertEquals(picked.count(p => !p.label.abstain), 17)
  }

  test(
    "the allocation over strata follows the share rule and the available answers"
  ) {
    // Base 3 each (d has only 1), 5 left: a, b, c get one more, then a and b.
    assertEquals(
      RatingSample.shares(Vector("a" -> 10, "b" -> 8, "c" -> 7, "d" -> 1), 15),
      Map("a" -> 5, "b" -> 5, "c" -> 4, "d" -> 1)
    )
    assertEquals(RatingSample.shares(Vector("a" -> 1), 5), Map("a" -> 1))
    assertEquals(RatingSample.shares(Vector.empty, 5), Map.empty[String, Int])
  }

  test("the rating CSV has no questionId, so an id cannot show the stratum") {
    assert(!RatingSample.Columns.contains("questionId"))
    // Stratum-bearing ids as BenchmarkGen and BenchmarkFill write them.
    val picked = Vector("Q-UA-03" -> "unanswerable", "Q-FF-01" -> "free-form")
      .flatMap { (id, stratum) =>
        val (r, l) = candidate(id, stratum, abstain = stratum == "unanswerable")
        RatingSample.select(
          Vector(r.copy(question = "A question?") -> l),
          1,
          1L
        )
      }
    val csv = RatingSample.toCsv(picked)
    assert(!csv.contains("Q-UA-03"), csv)
    assert(!csv.contains("Q-FF-01"), csv)
    assert(!csv.contains("questionId"), csv)
    // The key file still maps the sample row to the question.
    val key = RatingSample.keyJson(picked)
    assertEquals(
      key.arr.map(k => k("questionId").str).toVector,
      Vector("Q-UA-03", "Q-FF-01")
    )
  }

  test("the CSV has the rubric columns, blank rating cells and escaped text") {
    val rec = AuditFixture
      .answer(
        "Q1",
        claims = Vector.empty,
        evidence = Vector(AuditFixture.item("E1", AuditFixture.identityFact())),
        text = "He said \"yes\"\nand left."
      )
      .copy(question = "Who is it?")
    val label = candidate("Q1", "ranking")._2
    val picked = RatingSample.select(Vector(rec -> label), 1, 1L)
    val csv = RatingSample.toCsv(picked)
    val header = csv.linesIterator.next()
    assertEquals(header.split(",").toVector, RatingSample.Columns)
    assert(header.contains("usefulness") && header.contains("clarity"))
    assert(
      header.contains("supported") && header.contains("abstention_appropriate")
    )
    assert(csv.contains("\"He said \"\"yes\"\"\nand left.\""))
    assert(csv.contains("[E1] identity:"))
    assert(
      csv.contains("\"\",\"\",\"\",\"\",\"\"\n"),
      "five blank rating cells"
    )
    assert(!csv.contains("ranking"), "the stratum is not shown to the rater")
    val key = RatingSample.keyJson(picked)
    assertEquals(key(0)("stratum").str, "ranking")
  }
