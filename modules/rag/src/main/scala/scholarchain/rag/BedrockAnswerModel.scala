package scholarchain.rag

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  InferenceConfiguration,
  Message,
  SystemContentBlock
}
import scholarchain.storage.AwsClients

/** One Converse turn, free of SDK types so a test can fake it. */
final case class ConverseTurn(
    modelId: String,
    system: String,
    user: String,
    maxTokens: Int,
    temperature: Double
)

/** `usageReported` is false when the service sent no usage or only one of the
  * two counts. The counts then hold what was reported and 0 for what was not:
  * an explicit placeholder, never a measurement. `BedrockAnswerModel` turns the
  * flag into `tokensEstimated = true`, so the cost guard charges the worst
  * case.
  */
final case class ConverseReply(
    text: String,
    inputTokens: Int,
    outputTokens: Int,
    stopReason: String,
    usageReported: Boolean = true
)

/** The Bedrock Runtime Converse call, behind an interface. */
trait ConverseClient:
  def converse(turn: ConverseTurn): ConverseReply

/** Converse over the AWS SDK. It makes exactly one SDK call per turn and
  * translates nothing else: error mapping lives in `BedrockAnswerModel`.
  */
final class SdkConverseClient(client: BedrockRuntimeClient)
    extends ConverseClient:
  def converse(turn: ConverseTurn): ConverseReply =
    val request = ConverseRequest
      .builder()
      .modelId(turn.modelId)
      .system(SystemContentBlock.builder().text(turn.system).build())
      .messages(
        Message
          .builder()
          .role(ConversationRole.USER)
          .content(ContentBlock.fromText(turn.user))
          .build()
      )
      .inferenceConfig(
        InferenceConfiguration
          .builder()
          .maxTokens(Int.box(turn.maxTokens))
          .temperature(Float.box(turn.temperature.toFloat))
          .build()
      )
      .build()
    val response = client.converse(request)
    val text = Option(response.output())
      .flatMap(o => Option(o.message()))
      .map(_.content().asScala.toVector.flatMap(b => Option(b.text())))
      .getOrElse(Vector.empty)
      .mkString
    val usage = Option(response.usage())
    val in = usage.flatMap(u => Option(u.inputTokens())).map(_.intValue)
    val out = usage.flatMap(u => Option(u.outputTokens())).map(_.intValue)
    ConverseReply(
      text,
      in.getOrElse(0),
      out.getOrElse(0),
      Option(response.stopReasonAsString()).getOrElse(""),
      usageReported = in.isDefined && out.isDefined
    )

/** The synthesis and planner model on Amazon Bedrock (Converse).
  *
  *   - The model ID is an argument, never a constant here.
  *   - At most `maxCalls` calls per instance, that is per process when one
  *     instance is shared. The call after that is refused with
  *     `ModelCallException("cap-exceeded")` and the client is not called. A
  *     call that reaches the client counts, whether or not it succeeds.
  *   - One client call per `complete`; no retry here. Only a reply that is not
  *     valid JSON for the schema is retried, by `StructuredCall`, and each
  *     retry counts against the cap. Every client failure is a non-retryable
  *     `ModelCallException`, so a timeout or throttle is never repeated.
  *   - Logs carry the prompt ID, call number, model ID, sizes, latency and
  *     outcome. Never prompt text, answer text or an SDK message.
  *   - The reply is untrusted: `StructuredCall` validates it against its schema
  *     and `ClaimVerificationAgent` checks every claim, as for any model.
  */
final class BedrockAnswerModel(
    client: ConverseClient,
    modelId: String,
    maxCalls: Int = BedrockAnswerModel.DefaultMaxCalls,
    log: String => Unit = line => System.err.println(line),
    nanos: () => Long = () => System.nanoTime()
) extends ModelPort:
  require(modelId.trim.nonEmpty, "A Bedrock model ID is required")
  require(maxCalls >= 1, "maxCalls must be at least 1")

  private val used = new AtomicInteger(0)

  val modelVersion: String = s"bedrock:$modelId"

  /** Calls that reached the client so far. */
  def callsMade: Int = used.get()

  private def claim(promptId: String): Int =
    var n = 0
    var taken = false
    while !taken do
      val current = used.get()
      if current >= maxCalls then
        log(
          s"bedrock-call promptId=$promptId modelId=$modelId outcome=cap-exceeded maxCalls=$maxCalls"
        )
        throw new ModelCallException(
          "cap-exceeded",
          s"The Bedrock call cap of $maxCalls per process is reached; no call was made."
        )
      taken = used.compareAndSet(current, current + 1)
      n = current + 1
    n

  private def systemText(spec: PromptSpec, schema: ujson.Value): String =
    spec.text + "\n\nReturn exactly one JSON object and nothing else: no " +
      "markdown fence and no commentary. If the input has a \"retry\" key, " +
      "fix the listed problems and answer the \"original\" input. The JSON " +
      s"must satisfy this schema (schema ID ${spec.schemaId}):\n" +
      ujson.write(schema)

  /** The first `{` to the last `}`, so a fenced reply still parses. */
  private def jsonOf(text: String): String =
    val from = text.indexOf('{')
    val to = text.lastIndexOf('}')
    if from >= 0 && to > from then text.substring(from, to + 1) else text.trim

  def complete(request: ModelRequest): ModelResponse =
    val spec = Prompts.all
      .find(_.id == request.promptId)
      .getOrElse(
        throw new ModelCallException(
          "unknown-prompt",
          s"No prompt is registered under ${request.promptId}."
        )
      )
    val schema = Schemas.byId.getOrElse(
      request.schemaId,
      throw new ModelCallException(
        "unknown-schema",
        s"No schema is registered under ${request.schemaId}."
      )
    )
    val turn = ConverseTurn(
      modelId,
      systemText(spec, schema),
      ujson.write(request.input),
      request.maxOutputTokens,
      0.0
    )
    val callNumber = claim(request.promptId)
    val started = nanos()
    def line(
        outcome: String,
        outChars: Int,
        stop: Option[String] = None
    ): String =
      s"bedrock-call promptId=${request.promptId} callNo=$callNumber " +
        s"modelId=$modelId inputChars=${turn.user.length + turn.system.length} " +
        s"outputChars=$outChars millis=${math.max(0L, nanos() - started) / 1000000L} " +
        s"outcome=$outcome stopReason=${stop.getOrElse("none")}"
    val reply =
      try client.converse(turn)
      catch
        case NonFatal(error) =>
          val mapped = BedrockAnswerModel.mapError(error)
          log(line(mapped.kind, 0))
          throw mapped
    val stop = BedrockAnswerModel.normalizeStopReason(reply.stopReason)
    if stop.exists(BedrockAnswerModel.BlockedStopReasons.contains) then
      log(line("blocked", reply.text.length, stop))
      throw new ModelCallException(
        "blocked",
        "The model reply was blocked by the service."
      )
    // A reply cut at the token limit is returned, not thrown: whether the
    // truncated text still satisfies the schema is `StructuredCall`'s call, and
    // the stop reason travels with the response so the audit can name it.
    val outcome =
      if stop.contains(BedrockAnswerModel.MaxTokens) then "max-tokens" else "ok"
    log(line(outcome, reply.text.length, stop))
    ModelResponse(
      jsonOf(reply.text),
      modelVersion,
      reply.inputTokens,
      reply.outputTokens,
      tokensEstimated = !reply.usageReported,
      stopReason = stop,
      replyShape = Some(ReplyMeasures.of(reply.text))
    )

object BedrockAnswerModel:
  val MaxTokens = "max_tokens"
  val BlockedStopReasons: Set[String] =
    Set("guardrail_intervened", "content_filtered")

  /** The service's stop reason, lower case, or `None` when the reply carries
    * none. Anything that is not a short snake_case word becomes `other`, so a
    * log line or audit record never holds free text.
    */
  def normalizeStopReason(raw: String): Option[String] =
    Option(raw).map(_.trim.toLowerCase).filter(_.nonEmpty).map { r =>
      if r.matches("[a-z_]{1,40}") then r else "other"
    }

  /** The per-process call cap when the caller names none. */
  val DefaultMaxCalls = 10

  /** One model call is bounded to these deadlines: the attempt deadline per
    * HTTP attempt, the call deadline for the whole call.
    */
  val DefaultAttemptTimeout: Duration = Duration.ofSeconds(30)
  val DefaultCallTimeout: Duration = Duration.ofSeconds(40)

  /** The SDK client: an explicit Region (the caller's, us-east-2 in this
    * project), the default credential chain, bounded deadlines and no SDK
    * retry.
    */
  def sdkClient(
      region: String,
      attemptTimeout: Duration = DefaultAttemptTimeout,
      callTimeout: Duration = DefaultCallTimeout
  ): BedrockRuntimeClient =
    require(region.trim.nonEmpty, "An explicit AWS region is required")
    val clients = new AwsClients(Region.of(region.trim))
    BedrockRuntimeClient
      .builder()
      .region(clients.region)
      .credentialsProvider(clients.credentials)
      .httpClient(clients.httpClient)
      .overrideConfiguration(
        ClientOverrideConfiguration
          .builder()
          .apiCallAttemptTimeout(attemptTimeout)
          .apiCallTimeout(callTimeout)
          .retryPolicy(RetryPolicy.none())
          .build()
      )
      .build()

  /** Maps a client failure to a non-retryable `ModelCallException`. The message
    * holds the kind, HTTP status and error code only: never the SDK message,
    * which could echo request content.
    */
  def mapError(error: Throwable): ModelCallException = error match
    case e: ModelCallException                                          => e
    case _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException =>
      new ModelCallException(
        "timeout",
        "The Bedrock call timed out.",
        cause = error
      )
    case e: AwsServiceException =>
      val status = e.statusCode
      val code = Option(e.awsErrorDetails)
        .flatMap(d => Option(d.errorCode))
        .getOrElse("")
      val kind =
        if e.isThrottlingException || status == 429 then "throttled"
        else if status == 401 || status == 403 then "access-denied"
        else if status >= 400 && status < 500 then "rejected"
        else "service-error"
      new ModelCallException(
        kind,
        s"Bedrock refused the call: $kind (HTTP $status $code).",
        cause = error
      )
    case _: SdkClientException =>
      new ModelCallException(
        "client-error",
        "The Bedrock client failed before a reply arrived.",
        cause = error
      )
    case _ =>
      new ModelCallException(
        "client-error",
        s"The Bedrock call failed (${error.getClass.getSimpleName}).",
        cause = error
      )
