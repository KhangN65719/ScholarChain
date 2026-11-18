package scholarchain.tests.adversarial

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal
import munit.FunSuite
import scholarchain.evaluation.answer.AnswerEval
import scholarchain.evaluation.runtime.{CostGuard, CostLimits, CostStopped}
import scholarchain.rag.{
  CollaborationWorkflow,
  ModelCallException,
  ModelRequest,
  RetrievalMode,
  Session
}
import P16W2Support.*

/** Cost guards gate every AWS run of Phase 16 (decisions P16-3, P16-28,
  * P16-29): no call to the answer model or to the embedder after the call cap
  * or the USD stop, the stop is checked before each call and stays set, a
  * refused call is not retried, H's cap is 460 and the USD stops are G 0.25 and
  * H 3.00 (P16-33), and the diagnostic makes at most four Haiku calls with a
  * ceiling of at most 0.15.
  *
  * Every count below is taken at the service behind the guard (a spy), never
  * from the guard's own counters alone.
  */
class P16W2CostGuardSuite extends FunSuite:

  private val request =
    ModelRequest("synthesis", "1", "t", ujson.Obj("q" -> "x"), 500)

  private def tryCall(m: scholarchain.rag.ModelPort): Boolean =
    try
      m.complete(request)
      true
    catch case _: ModelCallException => false

  // ---- the call cap ------------------------------------------------------------

  List(0, 1, 3, 7).foreach { cap =>
    test(s"a Haiku cap of $cap lets exactly $cap calls reach the model") {
      val g = guard(haiku = cap)
      val spy = new SpyModel(_ => metered(1))
      val model = guardedModel(spy, g)
      val outcomes = (1 to cap + 6).map(_ => tryCall(model))
      assertEquals(spy.calls, cap)
      assertEquals(outcomes.count(identity), cap)
      assertEquals(g.haikuCallCount, cap)
      assertEquals(g.stopReason, Some("haiku-call-cap"))
    }
  }

  test("a refused model call is a non-retryable ModelCallException") {
    val g = guard(haiku = 0)
    val model = guardedModel(new SpyModel(), g)
    val e = intercept[ModelCallException](model.complete(request))
    assert(!e.retryable, "a retry of a refused call would be refused again")
    assert(e.kind.startsWith("cost-stop"), e.kind)
  }

  test("a Titan cap of N lets exactly N embeds reach the embedder") {
    val g = guard(titan = 4)
    val spy = new SpyEmbedder
    val emb = guardedEmbedder(spy, g)
    val outcomes = (1 to 10).map { i =>
      try
        emb.embed(s"text $i")
        true
      catch case _: CostStopped => false
    }
    assertEquals(spy.calls, 4)
    assertEquals(outcomes.count(identity), 4)
    assertEquals(g.titanCallCount, 4)
    assertEquals(g.stopReason, Some("titan-call-cap"))
  }

  test("sixteen threads cannot get more than the cap through to the model") {
    val g = guard(haiku = 25)
    val spy = new SpyModel(_ => metered(1))
    val model = guardedModel(spy, g)
    val pool = Executors.newFixedThreadPool(16)
    val start = new CountDownLatch(1)
    val done = new AtomicInteger(0)
    try
      (1 to 16).foreach { _ =>
        pool.submit(
          new Runnable:
            def run(): Unit =
              start.await()
              (1 to 20).foreach(_ => tryCall(model))
              done.incrementAndGet()
        )
      }
      start.countDown()
      pool.shutdown()
      assert(pool.awaitTermination(60, TimeUnit.SECONDS))
    finally pool.shutdownNow()
    assertEquals(done.get, 16)
    assertEquals(spy.calls, 25)
    assertEquals(g.haikuCallCount, 25)
  }

  // ---- the USD stop ---------------------------------------------------------------

  test("the USD stop is checked before the call: no call after it, ever") {
    // Metered 20000 in and 20000 out is 0.12 USD at 1.0 and 5.0 per million.
    val g = guard(usd = 0.2)
    val spy = new SpyModel(_ => metered(20000))
    val model = guardedModel(spy, g)
    (1 to 25).foreach(_ => tryCall(model))
    val atStop = spy.calls
    assert(atStop >= 1 && atStop <= 2, s"calls before the stop: $atStop")
    assertEquals(g.stopReason, Some("usd-ceiling"))
    (1 to 10).foreach(_ => assert(!tryCall(model)))
    assertEquals(spy.calls, atStop, "a call reached the model after the stop")
  }

  test("a ceiling under the worst case of one call refuses even the first") {
    val g = guard(usd = 0.0005)
    val spy = new SpyModel(_ => metered(1))
    assert(!tryCall(guardedModel(spy, g)))
    assertEquals(spy.calls, 0)
    assertEquals(g.stopReason, Some("usd-ceiling"))
  }

  test("a failed call is charged: failures cannot run past the ceiling") {
    val g = guard(usd = 0.05)
    val spy = new SpyModel(_ => throw new ModelCallException("timeout", "x"))
    val model = guardedModel(spy, g)
    (1 to 200).foreach(_ => tryCall(model))
    assert(g.usd > 0.0, "a failed call is charged at the worst case")
    assert(spy.calls < 200, s"calls made: ${spy.calls}")
    assertEquals(g.stopReason, Some("usd-ceiling"))
    val before = spy.calls
    (1 to 5).foreach(_ => tryCall(model))
    assertEquals(spy.calls, before)
  }

  test("a stop of either kind is sticky for the model and for the embedder") {
    val ceilingStop = guard(usd = 0.001)
    val spy = new SpyModel(_ => metered(1))
    val emb = new SpyEmbedder
    assert(!tryCall(guardedModel(spy, ceilingStop)))
    intercept[CostStopped](
      guardedEmbedder(emb, ceilingStop).embed("hello")
    )
    assertEquals(spy.calls, 0)
    assertEquals(emb.calls, 0)

    val capStop = guard(haiku = 1)
    val spy2 = new SpyModel(_ => metered(1))
    val model2 = guardedModel(spy2, capStop)
    assert(tryCall(model2))
    assert(!tryCall(model2))
    val emb2 = new SpyEmbedder
    intercept[CostStopped](guardedEmbedder(emb2, capStop).embed("hello"))
    assertEquals(emb2.calls, 0, "the embedder ran after a model stop")
    assertEquals(spy2.calls, 1)
  }

  test("an unbillable stub is capped but never stopped by money") {
    val g = guard(haiku = 3, usd = 0.0001)
    val spy = new SpyModel(_ => metered(1000000))
    val model = guardedModel(spy, g, billable = false)
    (1 to 8).foreach(_ => tryCall(model))
    assertEquals(spy.calls, 3)
    assertEquals(g.usd, 0.0)
  }

  // ---- the workflow does not retry a refused call --------------------------------

  private val planned = "What is the capital of France?"
  private val lookup = "Who is Ada Quillfeather between 2000 and 2009?"

  private def ask(
      w: scholarchain.evaluation.runtime.LaptopPorts.Wiring,
      text: String,
      ordinal: Int = 1
  ) =
    new CollaborationWorkflow(w.ports, w.config)
      .answer(text, Session("p16w2", ordinal), RetrievalMode.Fused)

  /** After a stop the semantic stage may raise the cost stop to the entry point
    * (as a run's loop expects); either way nothing may reach a service.
    */
  private def askAfterStop(
      w: scholarchain.evaluation.runtime.LaptopPorts.Wiring,
      text: String,
      ordinal: Int
  ): Unit =
    try ask(w, text, ordinal)
    catch case _: CostStopped => ()

  test("planner path: a refused classifier call reaches no model, no retry") {
    val g = guard(haiku = 0)
    val spy = new SpyModel()
    val w = wiring(g, spy, new SpyEmbedder)
    val result = ask(w, planned)
    assertEquals(spy.calls, 0)
    assertEquals(g.refusedCount, 1, "the workflow asked again after a refusal")
    assert(result.abstained)
  }

  test("synthesis path: a refused synthesis call reaches no model, no retry") {
    val g = guard(haiku = 0)
    val spy = new SpyModel()
    val w = wiring(g, spy, new SpyEmbedder)
    val result = ask(w, lookup)
    assertEquals(spy.calls, 0)
    assertEquals(g.refusedCount, 1, "the workflow asked again after a refusal")
    assert(result.abstained)
  }

  test("synthesis path: one call goes out, the refused second is not retried") {
    val g = guard(haiku = 1)
    // An unusable reply makes the workflow want a second attempt.
    val spy = new SpyModel(_ => metered(10, "not json"))
    val w = wiring(g, spy, new SpyEmbedder)
    val result = ask(w, lookup)
    assertEquals(spy.calls, 1)
    assertEquals(g.refusedCount, 1)
    assert(result.abstained)
  }

  test(
    "planner path then synthesis path: nothing after the cap, any question"
  ) {
    val g = guard(haiku = 2)
    val spy = new SpyModel(_ => metered(10, "not json"))
    val emb = new SpyEmbedder
    val w = wiring(g, spy, emb)
    ask(w, planned, 1)
    val atCap = spy.calls
    assertEquals(atCap, 2)
    val embedsAtCap = emb.calls
    (2 to 6).foreach { i =>
      askAfterStop(w, if i % 2 == 0 then lookup else planned, i)
    }
    assertEquals(spy.calls, atCap, "a model call went out after the cap")
    assertEquals(g.haikuCallCount, atCap)
    assertEquals(emb.calls, embedsAtCap, "an embed went out after the stop")
  }

  test("the USD stop holds across workflow questions") {
    val g = guard(usd = 0.2)
    val spy = new SpyModel(_ => metered(20000, "not json"))
    val emb = new SpyEmbedder
    val w = wiring(g, spy, emb)
    ask(w, lookup, 1)
    val after = spy.calls
    val embedsAfter = emb.calls
    assertEquals(g.stopReason, Some("usd-ceiling"))
    (2 to 6).foreach(i => askAfterStop(w, lookup, i))
    assertEquals(spy.calls, after, "a model call went out after the USD stop")
    assertEquals(emb.calls, embedsAfter, "an embed went out after the stop")
  }

  test("a Titan cap of zero: no embed reaches the embedder, a stop surfaces") {
    val g = guard(titan = 0)
    val emb = new SpyEmbedder
    val w = wiring(g, new SpyModel(), emb)
    val outcome =
      try
        new CollaborationWorkflow(w.ports, w.config)
          .answer(lookup, Session("p16w2-t", 1), RetrievalMode.Vector)
        "answered"
      catch
        case _: CostStopped => "cost-stopped"
        case NonFatal(e)    => s"other: ${e.getClass.getSimpleName}"
    assertEquals(emb.calls, 0)
    assertEquals(g.stopReason, Some("titan-call-cap"))
    assertEquals(
      outcome,
      "cost-stopped",
      "a cost stop must reach the entry point, not become a degraded note"
    )
  }

  // ---- the committed limits ---------------------------------------------------------

  private def manifestValue(name: String, key: String): String =
    val line = lines(repoRoot.resolve(s"experiments/manifests/$name.manifest"))
      .find(_.startsWith(s"$key="))
      .getOrElse(fail(s"$name has no $key"))
    line.drop(key.length + 1).trim

  private def jsonValue(name: String, key: String): String =
    val json = ujson.read(
      readAll(repoRoot.resolve(s"experiments/manifests/json/$name.json"))
    )
    json("values")(key).str

  private def scriptValue(path: String, key: String): String =
    val line = lines(repoRoot.resolve(path))
      .find(_.startsWith(s"$key="))
      .getOrElse(fail(s"$path has no $key"))
    line.drop(key.length + 1).trim

  test(
    "H: the call cap is 460 and the USD stop is 3.00, everywhere it is set"
  ) {
    assertEquals(manifestValue("experiment-h-answers", "MAX_TASKS"), "460")
    assertEquals(jsonValue("experiment-h-answers", "MAX_TASKS"), "460")
    assertEquals(
      manifestValue("experiment-h-answers", "SPEND_CEILING_USD").toDouble,
      3.0
    )
    assertEquals(
      jsonValue("experiment-h-answers", "SPEND_CEILING_USD").toDouble,
      3.0
    )
    val run = "experiments/h-answers/run.sh"
    assertEquals(scriptValue(run, "HAIKU_MAX_CALLS"), "460")
    assertEquals(scriptValue(run, "USD_CEILING").toDouble, 3.0)
    assert(
      readAll(repoRoot.resolve(run))
        .contains("--haiku-max-calls $HAIKU_MAX_CALLS"),
      "run.sh must pass the committed cap to the entry point"
    )
    assert(
      readAll(repoRoot.resolve(run)).contains("--usd-ceiling $USD_CEILING"),
      "run.sh must pass the committed USD stop to the entry point"
    )
    assert(
      manifestValue("experiment-h-answers", "MAX_TASKS").toInt >=
        scriptValue(run, "HAIKU_MAX_CALLS").toInt,
      "the manifest cap must not be below the entry point cap"
    )
  }

  test("G: the USD stop is 0.25 and the Titan cap is committed in run.sh") {
    assertEquals(
      manifestValue("experiment-g-retrieval", "SPEND_CEILING_USD").toDouble,
      0.25
    )
    assertEquals(
      jsonValue("experiment-g-retrieval", "SPEND_CEILING_USD").toDouble,
      0.25
    )
    val run = "experiments/g-retrieval/run.sh"
    assertEquals(scriptValue(run, "USD_CEILING").toDouble, 0.25)
    assert(scriptValue(run, "TITAN_MAX_CALLS").toInt > 0)
    val text = readAll(repoRoot.resolve(run))
    assert(text.contains("--titan-max-calls $TITAN_MAX_CALLS"))
    assert(text.contains("--usd-ceiling $USD_CEILING"))
    assert(!text.contains("--haiku-max-calls"), "G makes no Haiku call")
  }

  test("G and H stay in us-east-2 tier S in manifest and JSON copy") {
    List("experiment-g-retrieval", "experiment-h-answers").foreach { n =>
      assertEquals(manifestValue(n, "REGION"), "us-east-2")
      assertEquals(jsonValue(n, "REGION"), "us-east-2")
      assertEquals(manifestValue(n, "SIZE_TIER"), "S")
    }
  }

  // ---- the diagnostic (decision P16-28) ----------------------------------------------

  private def diagArgs(
      dir: java.nio.file.Path,
      runId: String,
      extra: Seq[String]
  ): Seq[String] =
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    Seq(
      "--offline",
      "--diagnostic",
      "--questions",
      q.toString,
      "--labels",
      l.toString,
      "--out-dir",
      dir.resolve("out").toString,
      "--run-id",
      runId
    ) ++ extra

  private def limitsOf(dir: java.nio.file.Path, runId: String) =
    ujson
      .read(readAll(dir.resolve(s"out/$runId/run-manifest.json")))("cost")(
        "limits"
      )

  test("the diagnostic command line clamps a larger call cap to four") {
    val dir = tempDir()
    val code = AnswerEval.execute(
      diagArgs(dir, "clamp-calls", Seq("--haiku-max-calls", "999")),
      _ => ()
    )
    assertEquals(code, 0)
    assertEquals(limitsOf(dir, "clamp-calls")("haikuMaxCalls").num.toInt, 4)
  }

  test("the diagnostic command line clamps a USD ceiling above 0.15") {
    val dir = tempDir()
    AnswerEval.execute(
      diagArgs(dir, "clamp-usd", Seq("--usd-ceiling", "9")),
      _ => ()
    )
    assertEqualsDouble(
      limitsOf(dir, "clamp-usd")("usdCeiling").num,
      0.15,
      1e-12
    )
  }

  test("the diagnostic keeps a lower cap and a lower ceiling as given") {
    val dir = tempDir()
    AnswerEval.execute(
      diagArgs(
        dir,
        "lower",
        Seq("--haiku-max-calls", "2", "--usd-ceiling", "0.05")
      ),
      _ => ()
    )
    assertEquals(limitsOf(dir, "lower")("haikuMaxCalls").num.toInt, 2)
    assertEqualsDouble(limitsOf(dir, "lower")("usdCeiling").num, 0.05, 1e-12)
  }

  test("the diagnostic with no cap given still defaults to four calls") {
    val dir = tempDir()
    AnswerEval.execute(diagArgs(dir, "default", Seq.empty), _ => ())
    assertEquals(limitsOf(dir, "default")("haikuMaxCalls").num.toInt, 4)
    assert(limitsOf(dir, "default")("usdCeiling").num <= 0.15)
  }

  private def diagConfig(dir: java.nio.file.Path, runId: String) =
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    AnswerEval.Config(
      q,
      l,
      dir.resolve("out"),
      runId,
      diagnostic = true,
      repeatCount = 20
    )

  test("a diagnostic over a guard of five calls is refused before any write") {
    val dir = tempDir()
    val e = intercept[IllegalArgumentException](
      AnswerEval.run(
        diagConfig(dir, "five"),
        wiring(guard(haiku = 5, usd = 0.1), new SpyModel(), new SpyEmbedder)
      )
    )
    assert(e.getMessage.contains("4"), e.getMessage)
    assert(!Files.exists(dir.resolve("out/five")))
  }

  test(
    "a diagnostic over a ceiling just above 0.15 is refused before a write"
  ) {
    val dir = tempDir()
    intercept[IllegalArgumentException](
      AnswerEval.run(
        diagConfig(dir, "usd"),
        wiring(guard(haiku = 4, usd = 0.1501), new SpyModel(), new SpyEmbedder)
      )
    )
    assert(!Files.exists(dir.resolve("out/usd")))
  }

  test("a diagnostic at exactly four calls and 0.15 is accepted") {
    val dir = tempDir()
    val report = AnswerEval.run(
      diagConfig(dir, "edge"),
      wiring(guard(haiku = 4, usd = 0.15), new SpyModel(), new SpyEmbedder)
    )
    assert(report.status == "COMPLETE" || report.status == "HALTED")
  }

  test("the diagnostic makes at most four model calls whatever fails") {
    // A model that fails with a retryable error invites every retry the
    // workflow owns; only four calls may reach it in the whole diagnostic.
    val dir = tempDir()
    val spy = new SpyModel(_ =>
      throw new ModelCallException("throttled", "x", retryable = true)
    )
    val g = guard(haiku = 4, usd = 0.15)
    val report =
      AnswerEval.run(diagConfig(dir, "fail"), wiring(g, spy, new SpyEmbedder))
    assert(spy.calls >= 1, s"nothing reached the model: ${spy.calls}")
    assert(spy.calls <= 4, s"calls: ${spy.calls}")
    assert(g.haikuCallCount >= 1, "the guard counted no call")
    assert(g.haikuCallCount <= 4)
    assert(report.records >= 1, "the diagnostic recorded nothing")
    assert(report.records <= 4)
    val raw = lines(dir.resolve("out/fail/diagnostic.jsonl"))
    assert(raw.nonEmpty, "the diagnostic wrote no raw line")
    assert(raw.size <= 4)
    assert(
      raw.forall(l => ujson.read(l)("questionId").str.nonEmpty),
      "every line names a question"
    )
  }

  test("the diagnostic makes at most four calls when every reply is unusable") {
    val dir = tempDir()
    val spy = new SpyModel(_ => metered(50, "not json"))
    val g = guard(haiku = 4, usd = 0.15)
    AnswerEval.run(diagConfig(dir, "junk"), wiring(g, spy, new SpyEmbedder))
    assertEquals(spy.calls, 4)
    assertEquals(g.haikuCallCount, 4)
  }

  test("the diagnostic has no repeat pass and writes no question or answer") {
    val dir = tempDir()
    val g = guard(haiku = 4, usd = 0.15)
    val report = AnswerEval.run(
      diagConfig(dir, "text"),
      wiring(g, new SpyModel(), new SpyEmbedder)
    )
    val out = report.runDir
    assert(!Files.exists(out.resolve("answers.jsonl")))
    assert(!Files.exists(out.resolve("rating-sample.csv")))
    val text = allText(out)
    Questions.foreach { (id, q) =>
      assert(!text.contains(q), s"the diagnostic wrote the text of $id")
    }
    Vector("Quillfeather", "capital of France", "Okonkwo").foreach { w =>
      assert(!text.contains(w), s"the diagnostic wrote $w")
    }
    val rows = lines(out.resolve("diagnostic.jsonl")).map(ujson.read(_))
    assert(rows.size <= 4)
    assert(rows.forall(r => !r.obj.contains("answer")))
    assert(rows.forall(r => !r.obj.contains("question")))
  }

  test(
    "a diagnostic on the command line with live arguments missing is usage"
  ) {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val code = AnswerEval.execute(
      Seq(
        "--diagnostic",
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("out").toString,
        "--run-id",
        "live"
      ),
      _ => ()
    )
    assertEquals(code, 2)
    assert(!Files.exists(dir.resolve("out/live")))
  }

  test("a live run refuses a region other than us-east-2 before any call") {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val printed = scala.collection.mutable.ArrayBuffer.empty[String]
    val code = AnswerEval.execute(
      Seq(
        "--questions",
        q.toString,
        "--labels",
        l.toString,
        "--out-dir",
        dir.resolve("out").toString,
        "--run-id",
        "region",
        "--export-dir",
        dir.toString,
        "--region",
        "eu-west-1",
        "--data-bucket",
        "d",
        "--vector-bucket",
        "v",
        "--model-id",
        "m",
        "--titan-max-calls",
        "5",
        "--haiku-max-calls",
        "5",
        "--usd-ceiling",
        "0.1",
        "--haiku-input-usd-per-mtok",
        "1",
        "--haiku-output-usd-per-mtok",
        "5",
        "--titan-usd-per-mtok",
        "0.02"
      ),
      printed += _
    )
    assertEquals(code, 2)
    assert(printed.exists(_.contains("us-east-2")), printed.toString)
  }

  test("limits refuse impossible values at construction") {
    intercept[IllegalArgumentException](CostLimits(1, 1, 0.0))
    intercept[IllegalArgumentException](CostLimits(1, 1, Double.NaN))
    intercept[IllegalArgumentException](CostLimits(-1, 1, 1.0))
    intercept[IllegalArgumentException](CostLimits(1, -1, 1.0))
    assertEquals(new CostGuard(CostLimits(1, 1, 1.0), rates).usd, 0.0)
  }
