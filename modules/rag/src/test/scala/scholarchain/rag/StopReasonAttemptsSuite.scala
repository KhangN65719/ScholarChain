package scholarchain.rag

import scala.collection.mutable

/** Phase 16 WO-3 part 1: the Converse stop reason is read on every reply and
  * kept, and each rejected attempt's reason reaches the audit as a fixed code.
  * Fake clients only; no live call.
  */
class StopReasonAttemptsSuite extends munit.FunSuite:
  import RuntimeTestSupport.ask

  private val Canary = "CANARY-MODEL-TEXT-5521"
  private val ModelId = "test-model"

  private final class FakeClient(stop: String, text: String)
      extends ConverseClient:
    var turns = 0
    def converse(turn: ConverseTurn): ConverseReply =
      turns += 1
      ConverseReply(text, 7, 9, stop)

  private def request(
      promptId: Prompts.type => PromptSpec = _.Synthesis
  ): ModelRequest =
    val spec = promptId(Prompts)
    ModelRequest(
      spec.id,
      spec.version,
      spec.schemaId,
      ujson.Obj("q" -> "x"),
      500
    )

  private def run(
      client: ConverseClient,
      lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  ): ModelResponse =
    new BedrockAnswerModel(client, ModelId, 10, l => lines += l)
      .complete(request())

  private val question =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"

  // --- BedrockAnswerModel: one test per stopReason path ---

  test("end_turn is logged and exposed, outcome ok") {
    val lines = mutable.ArrayBuffer.empty[String]
    val r = run(new FakeClient("end_turn", """{"a":1}"""), lines)
    assertEquals(r.stopReason, Some("end_turn"))
    assert(lines.head.contains("outcome=ok"), lines.head)
    assert(lines.head.contains("stopReason=end_turn"), lines.head)
  }

  test("max_tokens is detected, logged and returned, not thrown") {
    val lines = mutable.ArrayBuffer.empty[String]
    val r = run(new FakeClient("MAX_TOKENS", s"""{"a":"$Canary"""), lines)
    assertEquals(r.stopReason, Some("max_tokens"))
    assertEquals(lines.size, 1)
    assert(lines.head.contains("outcome=max-tokens"), lines.head)
    assert(lines.head.contains("stopReason=max_tokens"), lines.head)
    assert(!lines.head.contains(Canary), lines.head)
  }

  test("guardrail_intervened and content_filtered log the reason and throw") {
    List("guardrail_intervened", "content_filtered").foreach { stop =>
      val lines = mutable.ArrayBuffer.empty[String]
      val e = intercept[ModelCallException](
        run(new FakeClient(stop, ""), lines)
      )
      assertEquals(e.kind, "blocked")
      assert(lines.head.contains("outcome=blocked"), lines.head)
      assert(lines.head.contains(s"stopReason=$stop"), lines.head)
    }
  }

  test("other service reasons (tool_use, stop_sequence) are kept as is") {
    List("tool_use", "stop_sequence").foreach { stop =>
      val lines = mutable.ArrayBuffer.empty[String]
      val r = run(new FakeClient(stop, "{}"), lines)
      assertEquals(r.stopReason, Some(stop))
      assert(lines.head.contains(s"stopReason=$stop"), lines.head)
    }
  }

  test("an absent stop reason is none; a free-text one becomes other") {
    val lines = mutable.ArrayBuffer.empty[String]
    assertEquals(run(new FakeClient("", "{}"), lines).stopReason, None)
    assert(lines.head.contains("stopReason=none"), lines.head)
    lines.clear()
    val r = run(new FakeClient(s"Some $Canary text!", "{}"), lines)
    assertEquals(r.stopReason, Some("other"))
    assert(!lines.head.contains(Canary), lines.head)
    assert(lines.head.contains("stopReason=other"), lines.head)
  }

  test("a client failure logs stopReason=none and exposes nothing") {
    val lines = mutable.ArrayBuffer.empty[String]
    val failing = new ConverseClient:
      def converse(turn: ConverseTurn): ConverseReply =
        throw new IllegalStateException(Canary)
    intercept[ModelCallException](run(failing, lines))
    assert(lines.head.contains("stopReason=none"), lines.head)
    assert(!lines.head.contains(Canary), lines.head)
  }

  // --- StructuredCall: attempts and rejection codes ---

  private final class Replies(
      replies: Vector[(String, Option[String])]
  ) extends ModelPort:
    var n = 0
    def complete(r: ModelRequest): ModelResponse =
      val (raw, stop) = replies(math.min(n, replies.size - 1))
      n += 1
      ModelResponse(raw, "m/1", 3, 4, tokensEstimated = false, stop)

  private val planSchema = Schemas.PlanOutputId
  private val goodPlan =
    s"""{"schema":"$planSchema","operation":null,"confidence":0.5}"""

  test("each attempt keeps its own rejection code, in order") {
    val model = new Replies(
      Vector(
        ("not json", Some("stop_sequence")),
        (
          s"""{"schema":"$planSchema","operation":null,"confidence":0.5,"$Canary":1}""",
          Some("end_turn")
        ),
        (
          s"""{"schema":"$planSchema","operation":"nope","confidence":2}""",
          None
        )
      )
    )
    val outcome =
      StructuredCall.run(model, request(_.Planner), ModelRetryPolicy())
    outcome match
      case StructuredOutcome.Failed(_, attempts, usage) =>
        assertEquals(attempts, 3)
        val a = usage.attempts
        assertEquals(a.map(_.attempt), Vector(1, 2, 3))
        assertEquals(a(0).rejections, Vector("output is not valid JSON"))
        assertEquals(a(0).stopReason, Some("stop_sequence"))
        assertEquals(a(0).outputTokens, 4)
        assertEquals(a(1).rejections, Vector("$: unexpected key"))
        assertEquals(a(1).stopReason, Some("end_turn"))
        assert(a(2).rejections.contains("$.confidence: above the maximum"))
        assert(
          a(2).rejections.contains(
            "$.operation: value is not one of the allowed values"
          )
        )
        assertEquals(a(2).stopReason, None)
        assert(a.forall(!_.accepted))
        assert(!a.map(_.toJson).mkString.contains(Canary))
      case other => fail(s"expected Failed, got $other")
  }

  test(
    "an accepted retry keeps the earlier rejection and its own stop reason"
  ) {
    val model = new Replies(
      Vector(("{", Some("stop_sequence")), (goodPlan, Some("end_turn")))
    )
    StructuredCall.run(model, request(_.Planner), ModelRetryPolicy()) match
      case StructuredOutcome.Valid(_, attempts, usage) =>
        assertEquals(attempts, 2)
        assertEquals(usage.attempts.map(_.accepted), Vector(false, true))
        assertEquals(usage.attempts(1).rejections, Vector.empty[String])
        assertEquals(usage.attempts(1).stopReason, Some("end_turn"))
      case other => fail(s"expected Valid, got $other")
  }

  test("an accepted first attempt with no stop reason leaves no record") {
    val model = new Replies(Vector((goodPlan, None)))
    StructuredCall.run(model, request(_.Planner), ModelRetryPolicy()) match
      case StructuredOutcome.Valid(_, _, usage) =>
        assertEquals(usage.attempts, Vector.empty[ModelAttempt])
      case other => fail(s"expected Valid, got $other")
  }

  test("a failed model call is recorded as a code, with no exception text") {
    val failing = new ModelPort:
      def complete(r: ModelRequest): ModelResponse =
        throw new ModelCallException("timeout", Canary)
    StructuredCall.run(failing, request(), ModelRetryPolicy()) match
      case StructuredOutcome.Failed(_, 1, usage) =>
        assertEquals(
          usage.attempts.map(_.rejections),
          Vector(Vector("model call failed: timeout"))
        )
        assertEquals(usage.attempts.head.stopReason, None)
      case other => fail(s"expected Failed after 1, got $other")
  }

  test("RejectionReason.code never returns model text") {
    assertEquals(RejectionReason.code(s"model says $Canary"), "other")
    assertEquals(
      RejectionReason.code(s"$$.claims[2]: unexpected key $Canary"),
      "$.claims[2]: unexpected key"
    )
    assertEquals(
      RejectionReason.code("$.analysis: too long"),
      "$.analysis: too long"
    )
    assertEquals(
      RejectionReason.code("model call failed: throttled"),
      "model call failed: throttled"
    )
    assertEquals(
      RejectionReason.code(StructuredCall.OverBudget),
      StructuredCall.OverBudget
    )
  }

  // --- Workflow: the audit and the result ---

  test("synthesis rejected at max_tokens reaches the audit and the result") {
    val client = new FakeClient(
      "max_tokens",
      s"""{"schema":"${Schemas.SynthesisOutputId}","analysis":"$Canary"""
    )
    val bedrock = new BedrockAnswerModel(client, ModelId, 10, _ => ())
    val r = ask(DemoWorld.build(model = bedrock), question)
    assert(r.abstained)
    assertEquals(r.abstentionReason, Some("synthesis-failed"))
    // A reply cut at max_tokens is not retried (WO-3 part 3): one attempt.
    assertEquals(r.modelAttempts.size, 1)
    assert(r.modelAttempts.forall(_.promptId == Prompts.Synthesis.id))
    assert(r.modelAttempts.forall(_.stopReason.contains("max_tokens")))
    assert(
      r.modelAttempts.forall(_.rejections == Vector("output is not valid JSON"))
    )
    val audited = r.audit.payload("modelAttempts").arr.toVector
    assertEquals(audited.size, 1)
    assertEquals(audited.head("stopReason").str, "max_tokens")
    assertEquals(
      audited.head("rejections").arr.toVector.map(_.str),
      Vector("output is not valid JSON")
    )
    assertEquals(audited.map(_("attempt").num.toInt), Vector(1))
    // What was stored carries the same, and none of the model's text.
    val stored = ujson.write(r.audit.payload)
    assert(stored.contains("modelAttempts"))
    assert(!stored.contains(Canary))
    val lines = r.attemptLogLines
    assertEquals(lines.size, 1)
    assert(lines.forall(_.contains("stopReason=max_tokens")), lines.toString)
    assert(
      lines.forall(_.contains("rejections=output is not valid JSON")),
      lines.toString
    )
    assert(lines.forall(!_.contains(Canary)))
  }

  test("a schema rejection reaches the audit as a code, not as model text") {
    val client = new FakeClient(
      "end_turn",
      s"""{"schema":"${Schemas.SynthesisOutputId}","analysis":"x","claims":[],"missingEvidence":[],"uncertainty":null,"$Canary":1}"""
    )
    val bedrock = new BedrockAnswerModel(client, ModelId, 10, _ => ())
    val r = ask(DemoWorld.build(model = bedrock), question)
    assertEquals(r.abstentionReason, Some("synthesis-failed"))
    assert(
      r.modelAttempts.forall(_.rejections == Vector("$: unexpected key")),
      r.modelAttempts.toString
    )
    assert(!ujson.write(r.audit.payload).contains(Canary))
    assert(!r.attemptLogLines.mkString.contains(Canary))
  }

  test("a complete reply at end_turn records its stop reason as accepted") {
    final class Honest extends ConverseClient:
      private val stub = new DeterministicStubModel
      def converse(turn: ConverseTurn): ConverseReply =
        val spec = Prompts.all.find(p => turn.system.startsWith(p.text)).get
        val raw = stub
          .complete(
            ModelRequest(
              spec.id,
              spec.version,
              spec.schemaId,
              ujson.read(turn.user),
              turn.maxTokens
            )
          )
          .rawJson
        ConverseReply(raw, 5, 5, "end_turn")
    val bedrock = new BedrockAnswerModel(new Honest, ModelId, 10, _ => ())
    val r = ask(DemoWorld.build(model = bedrock), question)
    assert(!r.abstained, r.response.answer)
    assertEquals(r.modelAttempts.map(_.accepted), Vector(true))
    assertEquals(r.modelAttempts.head.stopReason, Some("end_turn"))
    assertEquals(r.modelAttempts.head.rejections, Vector.empty[String])
    assertEquals(
      r.audit.payload("modelAttempts").arr.head("stopReason").str,
      "end_turn"
    )
  }

  test("the offline stub leaves the audit without modelAttempts") {
    val r = ask(DemoWorld.build(), question)
    assertEquals(r.modelAttempts, Vector.empty[ModelAttempt])
    assert(!r.audit.payload.obj.contains("modelAttempts"))
    assertEquals(r.attemptLogLines, Vector.empty[String])
  }
