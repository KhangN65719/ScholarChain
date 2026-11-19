package scholarchain.rag

import scala.collection.mutable

/** Phase 16 WO-3 part 3 (decision P16-36): text-free structural measures of
  * each synthesis attempt, and no retry of a reply that stopped at
  * `max_tokens`. Offline fakes only; no live call.
  */
class ReplyMeasuresSuite extends munit.FunSuite:
  import RuntimeTestSupport.ask

  private val Canary = "CANARY-MODEL-TEXT-7731"

  private val question =
    "Which researcher has the most distinct coauthors from 2000-2009?"

  // --- reply length and claims started ---

  test("reply length is the character count of the reply") {
    assertEquals(ReplyMeasures.of("").chars, 0)
    assertEquals(ReplyMeasures.of("""{"a":1}""").chars, 7)
  }

  test("claims started counts every claim object, complete or cut") {
    val three =
      """{"schema":"s","claims":[{"a":1},{"b":{"c":[1,2]}},{"d":"x"}],"missingEvidence":[]}"""
    assertEquals(ReplyMeasures.claimsStarted(three), 3)
    val cut = """{"schema":"s","claims":[{"a":1},{"b":{"c":2}},{"d":"""
    assertEquals(ReplyMeasures.claimsStarted(cut), 3)
    assertEquals(ReplyMeasures.claimsStarted("""{"claims":[{"""), 1)
    assertEquals(ReplyMeasures.claimsStarted("""{"claims":["""), 0)
    assertEquals(ReplyMeasures.claimsStarted("""{"claims":[]}"""), 0)
  }

  test("claims started ignores braces in strings and other claims keys") {
    val tricky =
      """{"analysis":"a { b \" { claims [ {","other":{"claims":[{"x":1}]},"claims":[{"t":"}{ \\ {"},{"u":1}]}"""
    assertEquals(ReplyMeasures.claimsStarted(tricky), 2)
    assertEquals(ReplyMeasures.claimsStarted("not json at all"), 0)
    assertEquals(ReplyMeasures.claimsStarted("""{"note":"claims"}"""), 0)
    // objects after the claims array closed are not claims
    assertEquals(
      ReplyMeasures.claimsStarted("""{"claims":[{"a":1}],"later":[{"b":2}]}"""),
      1
    )
  }

  // --- repeated-substring ratio ---

  test("a looping reply has a repeat ratio near 1") {
    val loop =
      """{"claims":[""" + ("""{"text":"The same sentence again."},""" * 150)
    val ratio = ReplyMeasures.repeatRatio(loop)
    assert(ratio > 0.9, ratio.toString)
    assert(ratio <= 1.0, ratio.toString)
  }

  test("a long reply that never repeats a 32-character window is 0") {
    val long = (1 to 800).map(i => s"x$i").mkString("{", ",", "}")
    assert(long.length > 2000)
    assertEquals(ReplyMeasures.repeatRatio(long), 0.0)
  }

  test("a short reply is 0 and the ratio is deterministic") {
    assertEquals(ReplyMeasures.repeatRatio("abc"), 0.0)
    assertEquals(ReplyMeasures.repeatRatio("a" * 31), 0.0)
    val text = "ab" * 40
    assertEquals(
      ReplyMeasures.repeatRatio(text),
      ReplyMeasures.repeatRatio(text)
    )
    // 80 chars of "ab": windows 0 and 1 are new, windows 2..48 repeat and cover
    // characters 2..79, so 78 of 80
    assertEquals(ReplyMeasures.repeatRatio(text), 0.975)
  }

  test("the measures of a reply hold no reply text") {
    val shape = ReplyMeasures.of(s"""{"claims":[{"t":"$Canary"}]}""")
    assertEquals(shape.claimsStarted, 1)
    assert(!shape.toString.contains(Canary))
  }

  // --- the bundle sent to the model ---

  private val world = DemoWorld.build()
  private val rich = new RichEvidence(world)

  private def withRanking(entries: Vector[Double], tie: Boolean) =
    val fact = ujson.Obj(
      "kind" -> "ranking",
      "rankedTotal" -> 300,
      "tieAtCutoff" -> tie,
      "entries" -> ujson.Arr.from(
        entries.zipWithIndex.map((v, i) =>
          ujson.Obj("rank" -> (i + 1), "value" -> v): ujson.Value
        )
      )
    )
    val other = rich.bundle.items.filterNot(_.evidenceId == rich.ranking)
    rich.bundle.copy(items =
      other :+ rich.item(rich.ranking).copy(text = Some(ujson.write(fact)))
    )

  test("a bundle without a ranking reports its size and no ranking fields") {
    val bundle = rich.bundle.copy(items =
      rich.bundle.items.filterNot(_.evidenceId == rich.ranking)
    )
    val shape = InputShape.of(bundle)
    assertEquals(shape.evidenceItems, Some(bundle.items.size))
    assertEquals(shape.rankingEntries, None)
    assertEquals(shape.cutoffTies, None)
  }

  test("a ranking reports entries passed in, ranked total and cut-off ties") {
    val allTied = InputShape.of(
      withRanking(Vector(2, 2, 2, 2, 2), tie = true)
    )
    assertEquals(allTied.rankingEntries, Some(5))
    assertEquals(allTied.rankedTotal, Some(300))
    assertEquals(allTied.cutoffTies, Some(5))
    assertEquals(allTied.tieAtCutoff, Some(true))
    val three = InputShape.of(withRanking(Vector(5, 4, 2, 2, 2), tie = true))
    assertEquals(three.cutoffTies, Some(3))
    val none = InputShape.of(
      withRanking(Vector(30, 27, 26, 22, 21), tie = false)
    )
    assertEquals(none.rankingEntries, Some(5))
    assertEquals(none.cutoffTies, Some(1))
    assertEquals(none.tieAtCutoff, Some(false))
    assertEquals(
      none.evidenceItems,
      Some(withRanking(Vector(1), tie = false).items.size)
    )
  }

  test("the real ranking evidence is measured from its own fact") {
    val fact = rich.fact(rich.ranking)
    val values = Facts.list(fact, "entries").flatMap(e => Facts.n(e, "value"))
    val shape = InputShape.of(rich.bundle)
    assertEquals(shape.rankingEntries, Some(values.size))
    assertEquals(shape.cutoffTies, Some(values.count(_ == values.last)))
    assertEquals(shape.evidenceItems, Some(rich.bundle.items.size))
  }

  // --- StructuredCall: stop retrying a max_tokens reply ---

  private final class Replies(replies: Vector[(String, Option[String])])
      extends ModelPort:
    var n = 0
    def complete(r: ModelRequest): ModelResponse =
      val (raw, stop) = replies(math.min(n, replies.size - 1))
      n += 1
      ModelResponse(raw, "m/1", 3, 4, tokensEstimated = false, stop)

  private def synthRequest = ModelRequest(
    Prompts.Synthesis.id,
    Prompts.Synthesis.version,
    Prompts.Synthesis.schemaId,
    ujson.Obj("q" -> "x"),
    4096
  )

  test("a max_tokens reply makes exactly one call and records why") {
    val model = new Replies(
      Vector((s"""{"claims":[{"t":"$Canary"""", Some("max_tokens")))
    )
    StructuredCall.run(
      model,
      synthRequest,
      ModelRetryPolicy(),
      stopOnMaxTokens = true
    ) match
      case StructuredOutcome.Failed(_, attempts, usage) =>
        assertEquals(model.n, 1)
        assertEquals(attempts, 1)
        assertEquals(usage.calls, 1)
        val a = usage.attempts.head
        assertEquals(a.retryDecision, Some("max_tokens-no-retry"))
        assertEquals(a.rejections, Vector("output is not valid JSON"))
        assertEquals(a.reply.map(_.claimsStarted), Some(1))
        assert(!a.toJson.toString.contains(Canary))
        assertEquals(a.toJson("retryDecision").str, "max_tokens-no-retry")
        assert(a.logLine("q").contains("retryDecision=max_tokens-no-retry"))
      case other => fail(s"expected Failed, got $other")
  }

  test("a schema-invalid max_tokens reply is not retried either") {
    val model = new Replies(Vector(("{}", Some("max_tokens"))))
    StructuredCall.run(
      model,
      synthRequest,
      ModelRetryPolicy(),
      stopOnMaxTokens = true
    )
    assertEquals(model.n, 1)
  }

  test("a planner max_tokens rejection still retries up to the bound") {
    val model = new Replies(Vector(("""{"schema":""", Some("max_tokens"))))
    val plan = ModelRequest(
      Prompts.Planner.id,
      Prompts.Planner.version,
      Prompts.Planner.schemaId,
      ujson.Obj("q" -> "x"),
      200
    )
    StructuredCall.run(model, plan, ModelRetryPolicy()) match
      case StructuredOutcome.Failed(_, attempts, usage) =>
        assertEquals(model.n, ModelRetryPolicy().maxAttempts)
        assertEquals(attempts, 3)
        assert(usage.attempts.forall(_.retryDecision.isEmpty))
      case other => fail(s"expected Failed, got $other")
  }

  test("the planner agent retries a max_tokens reply with the hint") {
    val seen = mutable.ArrayBuffer.empty[ModelRequest]
    val model = new ModelPort:
      def complete(r: ModelRequest): ModelResponse =
        seen += r
        ModelResponse(
          """{"schema":""",
          "m/1",
          3,
          200,
          false,
          Some("max_tokens")
        )
    new QueryPlannerAgent(model).plan("tell me something interesting", "q")
    val planner = seen.filter(_.promptId == Prompts.Planner.id)
    assertEquals(planner.size, ModelRetryPolicy().maxAttempts)
    assert(planner.drop(1).forall(_.input.obj.contains("retry")))
  }

  test("Bedrock: measures come from the full reply, past the last }") {
    val runaway = "The same sentence again. " * 200
    val full =
      """Sure: {"schema":"s","claims":[{"a":1},{"b":2},{"c":"""" + runaway
    val client = new ConverseClient:
      def converse(turn: ConverseTurn): ConverseReply =
        ConverseReply(full, 10, 4096, "max_tokens")
    val bedrock =
      new BedrockAnswerModel(client, "model/x", log = _ => ())
    StructuredCall.run(
      bedrock,
      synthRequest,
      ModelRetryPolicy(),
      stopOnMaxTokens = true
    ) match
      case StructuredOutcome.Failed(_, _, usage) =>
        assertEquals(bedrock.callsMade, 1)
        val shape = usage.attempts.head.reply.get
        assertEquals(shape.chars, full.length)
        assertEquals(shape.claimsStarted, 3)
        assertEquals(shape.repeatRatio, ReplyMeasures.repeatRatio(full))
        assert(shape.repeatRatio > 0.9, shape.repeatRatio.toString)
        // the trimmed text ends at the last } and would measure lower
        val trimmed =
          full.substring(full.indexOf('{'), full.lastIndexOf('}') + 1)
        assert(trimmed.length < full.length)
        assertEquals(ReplyMeasures.claimsStarted(trimmed), 2)
        assert(shape.repeatRatio > ReplyMeasures.repeatRatio(trimmed))
      case other => fail(s"expected Failed, got $other")
  }

  test("other invalid replies still retry up to the bound") {
    List[Option[String]](None, Some("end_turn"), Some("stop_sequence"))
      .foreach { stop =>
        val model = new Replies(Vector(("{", stop)))
        StructuredCall.run(model, synthRequest, ModelRetryPolicy()) match
          case StructuredOutcome.Failed(_, attempts, usage) =>
            assertEquals(model.n, ModelRetryPolicy().maxAttempts)
            assertEquals(attempts, 3)
            assert(usage.attempts.forall(_.retryDecision.isEmpty))
          case other => fail(s"expected Failed, got $other")
      }
  }

  test("a retry bound of 0 and a valid reply at max_tokens are unchanged") {
    val valid = new Replies(
      Vector(
        (
          s"""{"schema":"${Schemas.PlanOutputId}","operation":null,"confidence":0.5}""",
          Some("max_tokens")
        )
      )
    )
    val plan = ModelRequest(
      Prompts.Planner.id,
      Prompts.Planner.version,
      Prompts.Planner.schemaId,
      ujson.Obj("q" -> "x"),
      200
    )
    StructuredCall.run(valid, plan, ModelRetryPolicy()) match
      case StructuredOutcome.Valid(_, 1, usage) =>
        assertEquals(valid.n, 1)
        assertEquals(usage.attempts.head.retryDecision, None)
        assert(usage.attempts.head.accepted)
      case other => fail(s"expected Valid, got $other")
  }

  // --- the workflow: result, audit and log carry the measures ---

  private final class Truncating(raw: String) extends ModelPort:
    private val inner = new DeterministicStubModel
    val synthesisCalls = mutable.ArrayBuffer.empty[ModelRequest]
    def complete(r: ModelRequest): ModelResponse =
      if r.promptId == Prompts.Synthesis.id then
        synthesisCalls += r
        ModelResponse(raw, "m/1", 5, 4096, false, Some("max_tokens"))
      else inner.complete(r)

  test("a truncated ranking answer: one call, measures in result, audit, log") {
    val raw =
      s"""{"schema":"${Schemas.SynthesisOutputId}","analysis":"$Canary","claims":[{"text":"$Canary","kind":"ranking"},{"text":"$Canary","ki"""
    val model = new Truncating(raw)
    val r = ask(DemoWorld.build(model = model), question)
    assertEquals(model.synthesisCalls.size, 1)
    assertEquals(r.abstentionReason, Some("synthesis-failed"))
    val a = r.modelAttempts.head
    assertEquals(a.retryDecision, Some("max_tokens-no-retry"))
    assertEquals(a.reply.map(_.chars), Some(raw.length))
    assertEquals(a.reply.map(_.claimsStarted), Some(2))
    assertEquals(a.input.evidenceItems, Some(r.response.evidence.items.size))
    val fact = ujson.read(
      RuntimeTestSupport
        .evidenceOfKind(r.response.evidence, "ranking")
        .get
        .text
        .get
    )
    val values = Facts.list(fact, "entries").flatMap(e => Facts.n(e, "value"))
    assertEquals(a.input.rankingEntries, Some(values.size))
    assertEquals(a.input.cutoffTies, Some(values.count(_ == values.last)))
    val audited = r.audit.payload("modelAttempts").arr.head
    assertEquals(audited("replyChars").num.toInt, raw.length)
    assertEquals(audited("claimsStarted").num.toInt, 2)
    assertEquals(
      audited("evidenceItems").num.toInt,
      r.response.evidence.items.size
    )
    assertEquals(audited("rankingEntries").num.toInt, values.size)
    assertEquals(audited("retryDecision").str, "max_tokens-no-retry")
    assert(audited.obj.contains("repeatRatio"))
    val line = r.attemptLogLines.head
    assert(line.contains(s"replyChars=${raw.length}"), line)
    assert(line.contains("claimsStarted=2"), line)
    assert(line.contains("rankingEntries="), line)
    assert(line.contains("retryDecision=max_tokens-no-retry"), line)
    assert(!ujson.write(r.audit.payload).contains(Canary))
    assert(!r.attemptLogLines.mkString.contains(Canary))
  }
