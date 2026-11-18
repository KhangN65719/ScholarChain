package scholarchain.evaluation.runtime

import munit.FunSuite
import scholarchain.rag.{
  ModelCallException,
  ModelPort,
  ModelRequest,
  ModelResponse,
  Prompts,
  Schemas
}
import scholarchain.storage.{Embedder, StorageException}

class CostGuardSuite extends FunSuite:
  private val rates = Rates(1.0, 5.0, 0.02)

  private val request =
    ModelRequest("test-prompt", "1", "test-schema", ujson.Obj("q" -> "x"), 500)

  /** A model that counts the calls that reach it. */
  private final class Counting(reply: => ModelResponse) extends ModelPort:
    var calls = 0
    def complete(r: ModelRequest): ModelResponse =
      calls += 1
      reply

  private def metered(in: Int, out: Int): ModelResponse =
    ModelResponse(
      "{}",
      "fake",
      in,
      out,
      tokensEstimated = false,
      Some("end_turn")
    )

  private final class CountingEmbedder extends Embedder:
    val modelVersion = "fake"
    val dimension = 2
    var calls = 0
    def embed(text: String): Vector[Float] =
      calls += 1
      Vector(1.0f, 0.0f)

  test("the Haiku call cap refuses the call before it reaches the model") {
    val guard = new CostGuard(CostLimits(10, 2, 10.0), rates)
    val inner = new Counting(metered(10, 10))
    val model = new GuardedModel(inner, guard, billable = true)
    model.complete(request)
    model.complete(request)
    val refused = intercept[ModelCallException](model.complete(request))
    assertEquals(refused.kind, "cost-stop-haiku-call-cap")
    assert(!refused.retryable)
    assertEquals(inner.calls, 2)
    assertEquals(guard.stopReason, Some("haiku-call-cap"))
  }

  test("the USD stop fires before the call that could pass the ceiling") {
    // Each call is metered at 1000 in and 1000 out: 0.001 + 0.005 = 0.006 USD.
    // The worst case of a call is about 0.0026, so the fourth call (0.018 spent)
    // would reach 0.0206, over the 0.02 ceiling, and is refused unmade.
    val guard = new CostGuard(CostLimits(10, 100, 0.02), rates)
    val inner = new Counting(metered(1000, 1000))
    val model = new GuardedModel(inner, guard, billable = true)
    (1 to 3).foreach(_ => model.complete(request))
    assertEqualsDouble(guard.usd, 0.018, 1e-9)
    val refused = intercept[ModelCallException](model.complete(request))
    assertEquals(refused.kind, "cost-stop-usd-ceiling")
    assertEquals(inner.calls, 3)
    assertEquals(guard.stopReason, Some("usd-ceiling"))
  }

  test("a ceiling below the worst case of one call refuses the first call") {
    val guard = new CostGuard(CostLimits(10, 100, 0.001), rates)
    val inner = new Counting(metered(1, 1))
    val model = new GuardedModel(inner, guard, billable = true)
    intercept[ModelCallException](model.complete(request))
    assertEquals(inner.calls, 0)
  }

  test("a stop is sticky: every later call is refused unmade") {
    val guard = new CostGuard(CostLimits(10, 1, 10.0), rates)
    val inner = new Counting(metered(1, 1))
    val model = new GuardedModel(inner, guard, billable = true)
    model.complete(request)
    intercept[ModelCallException](model.complete(request))
    intercept[ModelCallException](model.complete(request))
    assertEquals(inner.calls, 1)
    intercept[CostStopped](guard.admitTitan("text", billable = true))
  }

  test("a failed call is charged at the worst case") {
    val guard = new CostGuard(CostLimits(10, 10, 10.0), rates)
    val inner = new Counting(throw new ModelCallException("timeout", "x"))
    val model = new GuardedModel(inner, guard, billable = true)
    intercept[ModelCallException](model.complete(request))
    val (in, out) = guard.worstCase(request)
    val expected = in * 1.0 / 1e6 + out * 5.0 / 1e6
    assertEqualsDouble(guard.usd, expected, 1e-12)
    val snap = guard.snapshot
    assertEquals(snap("haikuFailedCalls").num.toInt, 1)
  }

  test("a reply flagged as estimated is charged at the worst case") {
    val guard = new CostGuard(CostLimits(10, 10, 10.0), rates)
    val estimated =
      ModelResponse("{}", "fake", 1, 1, tokensEstimated = true, None)
    val model =
      new GuardedModel(new Counting(estimated), guard, billable = true)
    model.complete(request)
    val (in, out) = guard.worstCase(request)
    assertEqualsDouble(guard.usd, in * 1.0 / 1e6 + out * 5.0 / 1e6, 1e-12)
    assertEquals(guard.snapshot("haikuCallsChargedAtWorstCase").num.toInt, 1)
  }

  test(
    "four worst-case synthesis calls at 4096 output tokens fit the 0.15 diagnostic ceiling"
  ) {
    // The estimate follows the request's maxOutputTokens, not a fixed 2000:
    // 4096 x 5.00 / 1e6 = 0.02048 USD of output per call.
    def synthesis(inputChars: Int) =
      ModelRequest(
        "test-prompt",
        "1",
        "test-schema",
        ujson.Str("x" * inputChars),
        4096
      )
    // About 7k input tokens (a full synthesis call, about 0.027 USD) and the
    // 12000-token input budget (about 0.033 USD): four calls, each charged at
    // the worst case because the model fails, stay under 0.15.
    Vector(28000 -> 0.12, 48000 -> 0.14).foreach { (chars, bound) =>
      val req = synthesis(chars)
      val guard = new CostGuard(CostLimits(10, 4, 0.15), rates)
      val inner = new Counting(throw new ModelCallException("timeout", "x"))
      val model = new GuardedModel(inner, guard, billable = true)
      val (in, out) = guard.worstCase(req)
      assertEquals(out, 4096L)
      (1 to 4).foreach(_ => intercept[ModelCallException](model.complete(req)))
      assertEquals(inner.calls, 4, "no call was refused")
      assertEquals(guard.refusedCount, 0)
      assertEqualsDouble(
        guard.usd,
        4 * (in * 1.0 / 1e6 + out * 5.0 / 1e6),
        1e-12
      )
      assert(guard.usd < bound && guard.usd <= 0.15, guard.usd.toString)
    }
  }

  test(
    "four worst-case calls with the real synthesis prompt and schema and a full 12000-token input fit 0.15"
  ) {
    val promptChars = Prompts.Synthesis.text.length
    val schemaChars =
      ujson.write(Schemas.byId(Schemas.SynthesisOutputId)).length
    val req = ModelRequest(
      Prompts.Synthesis.id,
      Prompts.Synthesis.version,
      Schemas.SynthesisOutputId,
      ujson.Str("x" * 48000),
      4096
    )
    val guard = new CostGuard(CostLimits(10, 4, 0.15), rates)
    val inner = new Counting(throw new ModelCallException("timeout", "x"))
    val model = new GuardedModel(inner, guard, billable = true)
    val (in, out) = guard.worstCase(req)
    val inputChars = ujson.write(req.input).length
    val totalChars: Long =
      inputChars.toLong + promptChars + schemaChars +
        CostGuard.SystemFramingChars
    assertEquals(in, (totalChars + 3L) / 4L)
    (1 to 4).foreach(_ => intercept[ModelCallException](model.complete(req)))
    assertEquals(inner.calls, 4, "no call was refused")
    assertEquals(guard.refusedCount, 0)
    assert(guard.usd <= 0.15, guard.usd.toString)
  }

  test("a metered reply is charged from its usage tokens") {
    val guard = new CostGuard(CostLimits(10, 10, 10.0), rates)
    val model =
      new GuardedModel(new Counting(metered(2000, 400)), guard, billable = true)
    model.complete(request)
    assertEqualsDouble(guard.usd, 2000 * 1.0 / 1e6 + 400 * 5.0 / 1e6, 1e-12)
    val snap = guard.snapshot
    assertEquals(snap("haikuMeteredInputTokens").num.toLong, 2000L)
    assertEquals(snap("haikuMeteredOutputTokens").num.toLong, 400L)
  }

  test("the offline stub is counted and capped but costs nothing") {
    val guard = new CostGuard(CostLimits(10, 2, 0.01), rates)
    val inner = new Counting(metered(100000, 100000))
    val model = new GuardedModel(inner, guard, billable = false)
    model.complete(request)
    model.complete(request)
    assertEquals(guard.usd, 0.0)
    intercept[ModelCallException](model.complete(request))
    assertEquals(inner.calls, 2)
  }

  test("the Titan call cap refuses the embed before it reaches Titan") {
    val guard = new CostGuard(CostLimits(2, 10, 10.0), rates)
    val inner = new CountingEmbedder
    val embedder = new GuardedEmbedder(inner, guard, billable = true)
    embedder.embed("one")
    embedder.embed("two")
    val stop = intercept[CostStopped](embedder.embed("three"))
    assertEquals(stop.reason, "titan-call-cap")
    assertEquals(inner.calls, 2)
  }

  test("a cost stop is not a StorageException, so the agent cannot absorb it") {
    val guard = new CostGuard(CostLimits(0, 10, 10.0), rates)
    val embedder =
      new GuardedEmbedder(new CountingEmbedder, guard, billable = true)
    val stop = intercept[Throwable](embedder.embed("x"))
    assert(stop.isInstanceOf[CostStopped])
    assert(!stop.isInstanceOf[StorageException])
  }

  test("Titan cost is an estimate of characters over four at the Titan rate") {
    val guard = new CostGuard(CostLimits(10, 10, 10.0), Rates(1.0, 5.0, 2.0))
    val embedder =
      new GuardedEmbedder(new CountingEmbedder, guard, billable = true)
    embedder.embed("a" * 400)
    assertEqualsDouble(guard.usd, 100.0 * 2.0 / 1e6, 1e-12)
    assertEquals(guard.snapshot("titanEstimatedTokens").num.toLong, 100L)
  }

  test("the Titan USD stop fires before the call") {
    val guard =
      new CostGuard(CostLimits(100, 10, 0.0001), Rates(1.0, 5.0, 10.0))
    val inner = new CountingEmbedder
    val embedder = new GuardedEmbedder(inner, guard, billable = true)
    // 4,000 characters = 1,000 tokens = 0.01 USD, over the 0.0001 ceiling.
    val stop = intercept[CostStopped](embedder.embed("a" * 4000))
    assertEquals(stop.reason, "usd-ceiling")
    assertEquals(inner.calls, 0)
  }

  test("a memoizing embedder calls Titan once per text") {
    val inner = new CountingEmbedder
    val memo = new MemoEmbedder(inner)
    memo.embed("same")
    memo.embed("same")
    memo.embed("other")
    assertEquals(inner.calls, 2)
  }

  test("the snapshot records the limits, the rates, the stop and the basis") {
    val guard = new CostGuard(CostLimits(3, 4, 0.5), rates)
    val snap = guard.snapshot
    assertEquals(snap("limits")("haikuMaxCalls").num.toInt, 4)
    assertEquals(snap("limits")("titanMaxCalls").num.toInt, 3)
    assertEquals(snap("rates")("haikuOutputUsdPerMTok").num, 5.0)
    assert(snap("rates")("status").str.contains("UNVERIFIED"))
    assert(snap("basis").str.contains("worst case"))
    assertEquals(snap("stopReason"), ujson.Null)
  }

  test("a completed call that passes the ceiling is not a refusal") {
    // 100000 tokens in and out is 0.6 USD at 1.0 and 5.0 per million.
    val guard = new CostGuard(CostLimits(10, 10, 0.5), rates)
    val inner = new Counting(metered(100000, 100000))
    val model = new GuardedModel(inner, guard, billable = true)
    model.complete(request)
    assertEquals(guard.stopReason, Some("usd-ceiling"))
    assertEquals(guard.refusedCount, 0)
    intercept[ModelCallException](model.complete(request))
    intercept[CostStopped](guard.admitTitan("x", billable = true))
    assertEquals(guard.refusedCount, 2)
    assertEquals(guard.snapshot("refusedCalls").num.toInt, 2)
    assertEquals(inner.calls, 1)
  }

  test(
    "the basis calls every figure an estimate and states the no-retry rule"
  ) {
    val basis =
      new CostGuard(CostLimits(1, 1, 1.0), rates).snapshot("basis").str
    assert(basis.contains("ESTIMATE"), basis)
    assert(basis.contains("not a bound"), basis)
    assert(basis.contains("does not retry"), basis)
    assert(basis.contains("worst case"), basis)
  }

  test("the deadline is the earlier of the relative and the absolute bound") {
    var t = 0L
    val nanos = () =>
      t += 1000000000L
      t
    // first reading 1 s; relative 2 min -> 121 s; absolute +30 s -> 31 s
    assertEquals(
      RunDeadline.nanosAt(Some(2), Some(1030000L), nanos, () => 1000000L),
      Some(31000000000L)
    )
    t = 0L
    assertEquals(
      RunDeadline.nanosAt(Some(2), Some(9999999L), nanos, () => 1000000L),
      Some(121000000000L)
    )
    t = 0L
    assertEquals(
      RunDeadline.nanosAt(None, Some(1030000L), nanos, () => 1000000L),
      Some(31000000000L)
    )
    t = 0L
    assertEquals(RunDeadline.nanosAt(None, None, nanos, () => 1000000L), None)
    // an absolute deadline already behind the wall clock is already past
    t = 0L
    assert(
      RunDeadline
        .nanosAt(None, Some(500L), nanos, () => 1000000L)
        .exists(_ < 1000000000L)
    )
  }

  test("rates and limits refuse impossible values") {
    intercept[IllegalArgumentException](Rates(-1.0, 1.0, 1.0))
    intercept[IllegalArgumentException](Rates(Double.NaN, 1.0, 1.0))
    intercept[IllegalArgumentException](CostLimits(1, 1, 0.0))
    intercept[IllegalArgumentException](CostLimits(-1, 1, 1.0))
  }

  test(
    "arguments: an unknown option, a missing value and a repeat are refused"
  ) {
    val opts = Set("a", "b")
    val sw = Set("on")
    val ok = EvalArgs.parse(Seq("--a", "1", "--on"), opts, sw)
    assertEquals(ok.required("a"), "1")
    assert(ok.flag("on"))
    assert(!ok.flag("b"))
    intercept[IllegalArgumentException](
      EvalArgs.parse(Seq("--c", "1"), opts, sw)
    )
    intercept[IllegalArgumentException](EvalArgs.parse(Seq("--a"), opts, sw))
    intercept[IllegalArgumentException](
      EvalArgs.parse(Seq("--a", "--b"), opts, sw)
    )
    intercept[IllegalArgumentException](
      EvalArgs.parse(Seq("--a", "1", "--a", "2"), opts, sw)
    )
    intercept[IllegalArgumentException](EvalArgs.parse(Seq("a"), opts, sw))
    intercept[IllegalArgumentException](
      EvalArgs.parse(Seq("--a", "x"), opts, sw).int("a", 0)
    )
  }
