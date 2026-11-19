package scholarchain.rag

import java.io.IOException
import scala.collection.mutable
import software.amazon.awssdk.awscore.exception.{
  AwsErrorDetails,
  AwsServiceException
}
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseOutput,
  ConverseRequest,
  ConverseResponse,
  Message,
  StopReason,
  TokenUsage
}

/** WO-5: the Bedrock Converse adapter, with fake clients only. No live call and
  * no network.
  */
class BedrockAnswerModelSuite extends munit.FunSuite:
  import RuntimeTestSupport.ask

  private val QuestionCanary = "CANARY-QUESTION-7731"
  private val AnswerCanary = "CANARY-ANSWER-9954"
  private val ModelId = "test-model"

  private final class FakeClient(reply: ConverseTurn => ConverseReply)
      extends ConverseClient:
    val turns = mutable.ArrayBuffer.empty[ConverseTurn]
    def converse(turn: ConverseTurn): ConverseReply =
      turns += turn
      reply(turn)

  private def okReply(
      text: String = """{"ok":true}"""
  ): ConverseTurn => ConverseReply =
    _ => ConverseReply(text, 11, 22, "end_turn")

  private def synthesisRequest(input: ujson.Value = ujson.Obj("q" -> "x")) =
    ModelRequest(
      Prompts.Synthesis.id,
      Prompts.Synthesis.version,
      Prompts.Synthesis.schemaId,
      input,
      777
    )

  private def model(
      client: ConverseClient,
      cap: Int = BedrockAnswerModel.DefaultMaxCalls,
      lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  ) = new BedrockAnswerModel(client, ModelId, cap, line => lines += line)

  test("request shape: model ID, system prompt, schema, input, limits") {
    val client = new FakeClient(okReply("```json\n{\"a\":1}\n```"))
    val m = model(client)
    val input = ujson.Obj("untrusted" -> ujson.Obj("question" -> "hello"))
    val response = m.complete(synthesisRequest(input))
    val turn = client.turns.head
    assertEquals(turn.modelId, ModelId)
    assert(turn.system.startsWith(Prompts.Synthesis.text))
    assert(turn.system.contains(Schemas.SynthesisOutputId))
    assert(turn.system.contains("\"additionalProperties\""))
    assertEquals(turn.user, ujson.write(input))
    assertEquals(turn.maxTokens, 777)
    assertEquals(turn.temperature, 0.0)
    assertEquals(response.rawJson, "{\"a\":1}")
    assertEquals(response.modelVersion, s"bedrock:$ModelId")
    assertEquals(response.promptTokens, 11)
    assertEquals(response.completionTokens, 22)
    assert(!response.tokensEstimated)
  }

  test("a blank model ID or a cap below 1 is refused at construction") {
    val client = new FakeClient(okReply())
    intercept[IllegalArgumentException](new BedrockAnswerModel(client, " "))
    intercept[IllegalArgumentException](
      new BedrockAnswerModel(client, ModelId, 0)
    )
  }

  test("an unknown prompt is refused before any call") {
    val client = new FakeClient(okReply())
    val e = intercept[ModelCallException](
      model(client).complete(synthesisRequest().copy(promptId = "other"))
    )
    assertEquals(e.kind, "unknown-prompt")
    assertEquals(client.turns.size, 0)
  }

  test("cap: the default is 10 and call 11 is refused with no client call") {
    assertEquals(BedrockAnswerModel.DefaultMaxCalls, 10)
    val client = new FakeClient(okReply())
    val m = new BedrockAnswerModel(client, ModelId, log = _ => ())
    (1 to 10).foreach(_ => m.complete(synthesisRequest()))
    assertEquals(client.turns.size, 10)
    val e = intercept[ModelCallException](m.complete(synthesisRequest()))
    assertEquals(e.kind, "cap-exceeded")
    assert(!e.retryable)
    assert(e.getMessage.contains("10"), e.getMessage)
    assertEquals(client.turns.size, 10)
    assertEquals(m.callsMade, 10)
  }

  test("cap: a failed call still counts against the cap") {
    val client = new FakeClient(_ => throw new RuntimeException("boom"))
    val m = model(client, cap = 2)
    intercept[ModelCallException](m.complete(synthesisRequest()))
    intercept[ModelCallException](m.complete(synthesisRequest()))
    val e = intercept[ModelCallException](m.complete(synthesisRequest()))
    assertEquals(e.kind, "cap-exceeded")
    assertEquals(client.turns.size, 2)
  }

  private def awsError(status: Int, code: String): AwsServiceException =
    AwsServiceException
      .builder()
      .statusCode(status)
      .message(s"$QuestionCanary $AnswerCanary")
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
      .build()
      .asInstanceOf[AwsServiceException]

  private val mappings: List[(String, () => Throwable, String)] = List(
    ("call timeout", () => ApiCallTimeoutException.create(1000L), "timeout"),
    (
      "attempt timeout",
      () => ApiCallAttemptTimeoutException.create(1000L),
      "timeout"
    ),
    (
      "throttled",
      () => awsError(429, "ThrottlingException"),
      "throttled"
    ),
    (
      "access denied",
      () => awsError(403, "AccessDeniedException"),
      "access-denied"
    ),
    ("validation", () => awsError(400, "ValidationException"), "rejected"),
    (
      "service error",
      () => awsError(503, "ServiceUnavailable"),
      "service-error"
    ),
    (
      "client error",
      () => SdkClientException.create(QuestionCanary, new IOException("io")),
      "client-error"
    ),
    (
      "unexpected",
      () => new IllegalStateException(QuestionCanary),
      "client-error"
    )
  )

  mappings.foreach { (label, failure, kind) =>
    test(s"error mapping: $label becomes $kind, once, with no text echoed") {
      val client = new FakeClient(_ => throw failure())
      val lines = mutable.ArrayBuffer.empty[String]
      val m = model(client, lines = lines)
      val e = intercept[ModelCallException](m.complete(synthesisRequest()))
      assertEquals(e.kind, kind)
      assert(!e.retryable)
      assert(!e.getMessage.contains(QuestionCanary), e.getMessage)
      assert(!e.getMessage.contains(AnswerCanary), e.getMessage)
      assertEquals(client.turns.size, 1)
      assert(lines.forall(l => !l.contains(QuestionCanary)))
      assert(lines.exists(_.contains(s"outcome=$kind")), lines.toString)
    }
  }

  test("a non-retryable failure ends StructuredCall after one attempt") {
    val client = new FakeClient(_ => throw ApiCallTimeoutException.create(5L))
    val outcome = StructuredCall.run(
      model(client),
      synthesisRequest(),
      ModelRetryPolicy()
    )
    outcome match
      case StructuredOutcome.Failed(reasons, attempts, _) =>
        assertEquals(attempts, 1)
        assert(reasons.exists(_.contains("timeout")), reasons.toString)
      case other => fail(s"expected Failed, got $other")
    assertEquals(client.turns.size, 1)
  }

  test("any other exception is still retried, as before") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val flaky = new ModelPort:
      def complete(request: ModelRequest): ModelResponse =
        calls.incrementAndGet()
        throw new IllegalStateException("flaky")
    val outcome =
      StructuredCall.run(flaky, synthesisRequest(), ModelRetryPolicy())
    assert(outcome.isInstanceOf[StructuredOutcome.Failed])
    assertEquals(calls.get, 3)
  }

  test("an invalid reply is retried by StructuredCall within the cap") {
    val client = new FakeClient(okReply("not json at all"))
    val outcome = StructuredCall.run(
      model(client, cap = 2),
      synthesisRequest(),
      ModelRetryPolicy()
    )
    outcome match
      case StructuredOutcome.Failed(reasons, attempts, _) =>
        assertEquals(attempts, 3)
        assert(reasons.exists(_.contains("cap-exceeded")), reasons.toString)
      case other => fail(s"expected Failed, got $other")
    assertEquals(client.turns.size, 2)
  }

  test("logs hold IDs and sizes, never prompt or answer text") {
    val client = new FakeClient(okReply(s"""{"x":"$AnswerCanary"}"""))
    val lines = mutable.ArrayBuffer.empty[String]
    val m = model(client, lines = lines)
    m.complete(synthesisRequest(ujson.Obj("untrusted" -> QuestionCanary)))
    assertEquals(lines.size, 1)
    val line = lines.head
    assert(!line.contains(QuestionCanary), line)
    assert(!line.contains(AnswerCanary), line)
    assert(!line.contains(Prompts.Synthesis.text.take(30)), line)
    assert(line.contains("promptId=synthesis"), line)
    assert(line.contains("callNo=1"), line)
    assert(line.contains(s"modelId=$ModelId"), line)
    assert(line.contains("outputChars="), line)
    assert(line.contains("outcome=ok"), line)
  }

  test("a blocked reply is a non-retryable error") {
    val client =
      new FakeClient(_ => ConverseReply("", 1, 0, "guardrail_intervened"))
    val e = intercept[ModelCallException](
      model(client).complete(synthesisRequest())
    )
    assertEquals(e.kind, "blocked")
    assert(!e.retryable)
  }

  test("SdkConverseClient builds one Converse request and reads the reply") {
    final class StubSdk(response: ConverseResponse)
        extends BedrockRuntimeClient:
      var last: Option[ConverseRequest] = None
      var calls = 0
      def serviceName(): String = "bedrock-runtime"
      def close(): Unit = ()
      override def converse(request: ConverseRequest): ConverseResponse =
        calls += 1
        last = Some(request)
        response
    val response = ConverseResponse
      .builder()
      .output(
        ConverseOutput
          .builder()
          .message(
            Message
              .builder()
              .role(ConversationRole.ASSISTANT)
              .content(
                ContentBlock.fromText("""{"a":"""),
                ContentBlock.fromText("1}")
              )
              .build()
          )
          .build()
      )
      .usage(
        TokenUsage
          .builder()
          .inputTokens(Int.box(5))
          .outputTokens(Int.box(7))
          .totalTokens(Int.box(12))
          .build()
      )
      .stopReason(StopReason.END_TURN)
      .build()
    val sdk = new StubSdk(response)
    val reply = new SdkConverseClient(sdk).converse(
      ConverseTurn(ModelId, "system text", "user text", 321, 0.0)
    )
    assertEquals(sdk.calls, 1)
    val request = sdk.last.get
    assertEquals(request.modelId, ModelId)
    assertEquals(request.system.get(0).text, "system text")
    assertEquals(request.messages.size, 1)
    assertEquals(request.messages.get(0).role, ConversationRole.USER)
    assertEquals(request.messages.get(0).content.get(0).text, "user text")
    assertEquals(request.inferenceConfig.maxTokens.intValue, 321)
    assertEquals(request.inferenceConfig.temperature.floatValue, 0.0f)
    assertEquals(reply.text, """{"a":1}""")
    assertEquals(reply.inputTokens, 5)
    assertEquals(reply.outputTokens, 7)
    assertEquals(reply.stopReason, "end_turn")
  }

  private final class UsageStub(usage: Option[TokenUsage])
      extends BedrockRuntimeClient:
    def serviceName(): String = "bedrock-runtime"
    def close(): Unit = ()
    override def converse(request: ConverseRequest): ConverseResponse =
      val b = ConverseResponse
        .builder()
        .output(
          ConverseOutput
            .builder()
            .message(
              Message
                .builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText("""{"ok":true}"""))
                .build()
            )
            .build()
        )
        .stopReason(StopReason.END_TURN)
      usage.foreach(b.usage(_))
      b.build()

  private def sdkModel(usage: Option[TokenUsage]) =
    model(new SdkConverseClient(new UsageStub(usage)))

  test("a reply with no usage is flagged as estimated, counts 0") {
    val response = sdkModel(None).complete(synthesisRequest())
    assert(response.tokensEstimated)
    assertEquals(response.promptTokens, 0)
    assertEquals(response.completionTokens, 0)
  }

  test("a reply with only one usage count is flagged as estimated") {
    val onlyIn = TokenUsage.builder().inputTokens(Int.box(9)).build()
    val onlyOut = TokenUsage.builder().outputTokens(Int.box(4)).build()
    val a = sdkModel(Some(onlyIn)).complete(synthesisRequest())
    assert(a.tokensEstimated)
    assertEquals(a.promptTokens, 9)
    assertEquals(a.completionTokens, 0)
    val b = sdkModel(Some(onlyOut)).complete(synthesisRequest())
    assert(b.tokensEstimated)
    assertEquals(b.promptTokens, 0)
    assertEquals(b.completionTokens, 4)
  }

  test("a reply with both usage counts is metered, not estimated") {
    val full = TokenUsage
      .builder()
      .inputTokens(Int.box(5))
      .outputTokens(Int.box(7))
      .build()
    val response = sdkModel(Some(full)).complete(synthesisRequest())
    assert(!response.tokensEstimated)
    assertEquals(response.promptTokens, 5)
    assertEquals(response.completionTokens, 7)
  }

  test("the model carries a client's usageReported = false as estimated") {
    val client = new FakeClient(_ =>
      ConverseReply("""{"ok":true}""", 0, 0, "end_turn", usageReported = false)
    )
    assert(model(client).complete(synthesisRequest()).tokensEstimated)
  }

  /** A client that answers like the stub model, so the workflow can run end to
    * end, and can tamper with the synthesis reply the way a steered model
    * might.
    */
  private final class StubBackedClient(tamper: Boolean) extends ConverseClient:
    private val stub = new DeterministicStubModel
    var turns = 0
    def converse(turn: ConverseTurn): ConverseReply =
      turns += 1
      val spec = Prompts.all.find(p => turn.system.startsWith(p.text)).get
      val request = ModelRequest(
        spec.id,
        spec.version,
        spec.schemaId,
        ujson.read(turn.user),
        turn.maxTokens
      )
      val reply = ujson.read(stub.complete(request).rawJson)
      if tamper && spec.id == Prompts.Synthesis.id then
        reply("claims").arr.foreach { claim =>
          if claim("kind").str == "metric" then
            claim("assertion")("value") = ujson.Num(999)
            claim("text") = ujson.Str("Ada Quillfeather has 999 coauthors.")
        }
      ConverseReply(ujson.write(reply), 5, 5, "end_turn")

  private val question =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"

  test("the verifier still drops an unsupported claim from the model") {
    val client = new StubBackedClient(tamper = true)
    val bedrock = model(client)
    val world = DemoWorld.build(model = bedrock)
    val r = ask(world, question)
    assert(client.turns >= 1)
    assert(
      r.response.claims.forall(c => !c.text.contains("999")),
      r.response.claims.map(_.text).toString
    )
    assert(!r.response.answer.contains("999"), r.response.answer)
    assert(r.response.answer.contains("withheld"), r.response.answer)
    assert(
      r.verification.exists(_.changes.exists(_.action == "removed")),
      r.verification.toString
    )
    assertEquals(
      r.audit.payload("models").arr.toVector.map(_.str),
      Vector(s"bedrock:$ModelId")
    )
  }

  test("an honest reply through the same path is verified") {
    val client = new StubBackedClient(tamper = false)
    val world = DemoWorld.build(model = model(client))
    val r = ask(world, question)
    assert(!r.abstained, r.response.answer)
    assertEquals(r.verdict, Some(VerificationVerdict.Verified))
    assert(!r.response.answer.contains("withheld"))
  }

  test("the cap holds through the workflow: the next answer abstains") {
    val client = new StubBackedClient(tamper = false)
    val world = DemoWorld.build(model = model(client, cap = 1))
    ask(world, question)
    val second = ask(world, question, ordinal = 1)
    assert(second.abstained)
    assertEquals(second.abstentionReason, Some("synthesis-failed"))
    assertEquals(client.turns, 1)
  }
