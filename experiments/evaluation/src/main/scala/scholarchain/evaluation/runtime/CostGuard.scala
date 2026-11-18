package scholarchain.evaluation.runtime

import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.rag.{
  ModelCallException,
  ModelPort,
  ModelRequest,
  ModelResponse,
  Prompts,
  Schemas,
  TokenEstimate
}
import scholarchain.storage.{
  Embedder,
  VectorBucketPort,
  VectorIndexSpec,
  VectorMatch,
  VectorQuery,
  VectorRecord
}

/** Prices in USD per million tokens. They are arguments of the entry points and
  * UNVERIFIED until the human reads the pricing pages; the manifest records the
  * values a run was started with.
  */
final case class Rates(
    haikuInputUsdPerMTok: Double,
    haikuOutputUsdPerMTok: Double,
    titanUsdPerMTok: Double
):
  require(
    Vector(haikuInputUsdPerMTok, haikuOutputUsdPerMTok, titanUsdPerMTok)
      .forall(r => r.isFinite && r >= 0.0),
    "rates must be finite and not negative"
  )

  def toJson: ujson.Value =
    ujson.Obj(
      "haikuInputUsdPerMTok" -> haikuInputUsdPerMTok,
      "haikuOutputUsdPerMTok" -> haikuOutputUsdPerMTok,
      "titanUsdPerMTok" -> titanUsdPerMTok,
      "status" -> "UNVERIFIED: values given on the command line"
    )

object Rates:
  val Zero: Rates = Rates(0.0, 0.0, 0.0)

/** The hard bounds of one run: calls to Titan, calls to the answer model and
  * the USD ceiling that stops the run.
  */
final case class CostLimits(
    titanMaxCalls: Int,
    haikuMaxCalls: Int,
    usdCeiling: Double
):
  require(titanMaxCalls >= 0, "titanMaxCalls must not be negative")
  require(haikuMaxCalls >= 0, "haikuMaxCalls must not be negative")
  require(
    usdCeiling.isFinite && usdCeiling > 0.0,
    "usdCeiling must be a positive number"
  )

/** Raised by the guard before a call it refuses. It is not a
  * `StorageException`, so the semantic-context agent does not turn it into a
  * degraded note: it reaches the entry point, which ends the run.
  */
final class CostStopped(val reason: String)
    extends RuntimeException(s"cost guard stop: $reason")

/** The meter of one run. Every limit is checked BEFORE the call it guards, so a
  * refused call never reaches the service.
  *
  * Titan has no usage field in this adapter, so its cost is always an estimate:
  * input characters / 4 (`TokenEstimate`) at the Titan rate. The answer model's
  * cost is metered from the Converse usage tokens (`ModelResponse.promptTokens`
  * and `completionTokens`, `tokensEstimated = false`); a call that returned no
  * usable usage (a failure, or a response flagged as estimated) is charged the
  * conservative worst case: input = request and system characters / 4, output =
  * the request's `maxOutputTokens`. Before a call the guard projects the worst
  * case of that call and refuses it when spend plus the projection would pass
  * the ceiling.
  */
final class CostGuard(val limits: CostLimits, val rates: Rates):
  private var titanCalls = 0
  private var haikuCalls = 0
  private var haikuFailed = 0
  private var haikuEstimated = 0
  private var spent = 0.0
  private var meteredIn = 0L
  private var meteredOut = 0L
  private var titanTokens = 0L
  private var stop: Option[String] = None
  private var refused = 0

  private val callLog = mutable.ArrayBuffer.empty[CallRecord]

  def recordCall(call: CallRecord): Unit = synchronized { callLog += call }
  def calls: Vector[CallRecord] = synchronized(callLog.toVector)

  def stopReason: Option[String] = synchronized(stop)
  def usd: Double = synchronized(spent)
  def haikuCallCount: Int = synchronized(haikuCalls)
  def titanCallCount: Int = synchronized(titanCalls)

  /** Calls the guard refused (each one reached no service). An answer is
    * excluded as `costStopped` only when this count rose during it: a call that
    * completed and pushed spend past the ceiling is a finished answer.
    */
  def refusedCount: Int = synchronized(refused)

  private def usdOf(tokens: Long, perMTok: Double): Double =
    tokens.toDouble * perMTok / 1.0e6

  private def refuse(reason: String): Nothing =
    refused += 1
    if stop.isEmpty then stop = Some(reason)
    throw new CostStopped(stop.get)

  private def failIfStopped(): Unit =
    stop.foreach { r =>
      refused += 1
      throw new CostStopped(r)
    }

  /** The characters the Bedrock adapter sends besides `input`: the prompt text,
    * the schema and a fixed framing paragraph (about 400 characters).
    */
  private def systemChars(request: ModelRequest): Int =
    Prompts.all.find(_.id == request.promptId).map(_.text.length).getOrElse(0) +
      Schemas.byId
        .get(request.schemaId)
        .map(s => ujson.write(s).length)
        .getOrElse(0) + CostGuard.SystemFramingChars

  /** Worst-case input and output tokens of one call. */
  def worstCase(request: ModelRequest): (Long, Long) =
    val chars = ujson.write(request.input).length + systemChars(request)
    ((chars + 3L) / 4L, request.maxOutputTokens.toLong)

  def admitTitan(text: String, billable: Boolean): Unit = synchronized {
    failIfStopped()
    if titanCalls >= limits.titanMaxCalls then refuse("titan-call-cap")
    val tokens = TokenEstimate.of(text).toLong
    val cost = if billable then usdOf(tokens, rates.titanUsdPerMTok) else 0.0
    if spent + cost > limits.usdCeiling then refuse("usd-ceiling")
    titanCalls += 1
    titanTokens += tokens
    spent += cost
  }

  def admitHaiku(request: ModelRequest, billable: Boolean): Unit =
    synchronized {
      failIfStopped()
      if haikuCalls >= limits.haikuMaxCalls then refuse("haiku-call-cap")
      if billable then
        val (in, out) = worstCase(request)
        val worst = usdOf(in, rates.haikuInputUsdPerMTok) +
          usdOf(out, rates.haikuOutputUsdPerMTok)
        if spent + worst > limits.usdCeiling then refuse("usd-ceiling")
      haikuCalls += 1
    }

  def settleHaiku(
      request: ModelRequest,
      billable: Boolean,
      reply: Option[ModelResponse]
  ): Unit = synchronized {
    if billable then
      val (in, out) = reply match
        case Some(r) if !r.tokensEstimated =>
          meteredIn += r.promptTokens.toLong
          meteredOut += r.completionTokens.toLong
          (r.promptTokens.toLong, r.completionTokens.toLong)
        case other =>
          if other.isEmpty then haikuFailed += 1
          haikuEstimated += 1
          worstCase(request)
      spent += usdOf(in, rates.haikuInputUsdPerMTok) +
        usdOf(out, rates.haikuOutputUsdPerMTok)
      if spent >= limits.usdCeiling && stop.isEmpty then
        stop = Some("usd-ceiling")
  }

  def snapshot: ujson.Value = synchronized {
    ujson.Obj(
      "limits" -> ujson.Obj(
        "titanMaxCalls" -> limits.titanMaxCalls,
        "haikuMaxCalls" -> limits.haikuMaxCalls,
        "usdCeiling" -> limits.usdCeiling
      ),
      "rates" -> rates.toJson,
      "titanCalls" -> titanCalls,
      "titanEstimatedTokens" -> titanTokens.toDouble,
      "haikuCalls" -> haikuCalls,
      "haikuFailedCalls" -> haikuFailed,
      "refusedCalls" -> refused,
      "haikuCallsChargedAtWorstCase" -> haikuEstimated,
      "haikuMeteredInputTokens" -> meteredIn.toDouble,
      "haikuMeteredOutputTokens" -> meteredOut.toDouble,
      "spentUsd" -> BigDecimal(spent)
        .setScale(6, BigDecimal.RoundingMode.HALF_UP)
        .toDouble,
      "stopReason" -> stop.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "basis" -> CostGuard.Basis
    )
  }

object CostGuard:
  val SystemFramingChars = 400

  val Basis: String =
    "Every USD figure is an ESTIMATE at the given, UNVERIFIED rates, not a " +
      "bound. Haiku: metered from Converse usage tokens (input and output); a " +
      "failed call, or a reply flagged as estimated, is charged the worst " +
      "case (input = request and system characters / 4, output = " +
      "maxOutputTokens). Titan: estimate, input characters / 4 at the given " +
      "rate (the adapter has no usage field); the estimate can be off, so " +
      "spend can pass the ceiling by the error of one call. Stop: checked " +
      "before each call, spend plus the worst case of the call must stay " +
      "within the ceiling. The evaluation Titan client does not retry, so one " +
      "admitted Titan call is one request; the Converse client does not retry " +
      "either. S3 Vectors queries and S3 reads are not metered (fractions of " +
      "a cent)."

/** One call that reached the answer model: its latency (laptop to Bedrock, not
  * in-VPC), usage tokens, stop reason and outcome (`ok` or a failure kind). No
  * prompt or answer text.
  */
final case class CallRecord(
    promptId: String,
    millis: Long,
    promptTokens: Int,
    completionTokens: Int,
    tokensEstimated: Boolean,
    stopReason: Option[String],
    outcome: String
):
  def toJson: ujson.Value =
    ujson.Obj(
      "promptId" -> promptId,
      "millis" -> millis.toDouble,
      "promptTokens" -> promptTokens,
      "completionTokens" -> completionTokens,
      "tokensEstimated" -> tokensEstimated,
      "stopReason" -> stopReason.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "outcome" -> outcome
    )

/** The answer model behind the guard. `billable` is false for the offline stub,
  * which costs nothing; its calls are still counted and capped.
  */
final class GuardedModel(
    inner: ModelPort,
    guard: CostGuard,
    billable: Boolean
) extends ModelPort:
  def complete(request: ModelRequest): ModelResponse =
    try guard.admitHaiku(request, billable)
    catch
      case stop: CostStopped =>
        throw new ModelCallException(
          s"cost-stop-${stop.reason}",
          "The cost guard refused the call; no call was made."
        )
    val began = System.nanoTime()
    def millis: Long = (System.nanoTime() - began) / 1000000L
    val reply =
      try inner.complete(request)
      catch
        case NonFatal(error) =>
          guard.settleHaiku(request, billable, None)
          guard.recordCall(
            CallRecord(
              request.promptId,
              millis,
              0,
              0,
              tokensEstimated = true,
              None,
              error match
                case m: ModelCallException => m.kind
                case other                 => other.getClass.getSimpleName
            )
          )
          throw error
    guard.settleHaiku(request, billable, Some(reply))
    guard.recordCall(
      CallRecord(
        request.promptId,
        millis,
        reply.promptTokens,
        reply.completionTokens,
        reply.tokensEstimated,
        reply.stopReason,
        "ok"
      )
    )
    reply

/** The embedder behind the guard. */
final class GuardedEmbedder(
    inner: Embedder,
    guard: CostGuard,
    billable: Boolean
) extends Embedder:
  val modelVersion: String = inner.modelVersion
  val dimension: Int = inner.dimension

  def embed(text: String): Vector[Float] =
    guard.admitTitan(text, billable)
    inner.embed(text)

/** Embeds a text once per instance. A direct search of one question reuses it
  * for every index and every K, so Titan is called once for the question.
  */
final class MemoEmbedder(inner: Embedder) extends Embedder:
  val modelVersion: String = inner.modelVersion
  val dimension: Int = inner.dimension
  private val cache = mutable.HashMap.empty[String, Vector[Float]]

  def embed(text: String): Vector[Float] =
    cache.getOrElseUpdate(text, inner.embed(text))

/** One recorded vector query: the index, the topK asked and the hits. */
final case class RecordedQuery(
    index: String,
    topK: Int,
    hits: Vector[VectorMatch]
)

/** A vector bucket that remembers what each query returned, so the evaluation
  * reads the ids the deployed workflow retrieved without a second call.
  */
final class RecordingVectorBucket(inner: VectorBucketPort)
    extends VectorBucketPort:
  private val buffer = mutable.ArrayBuffer.empty[RecordedQuery]
  private var queries = 0L

  def queryCount: Long = synchronized(queries)

  def drain(): Vector[RecordedQuery] = synchronized {
    val out = buffer.toVector
    buffer.clear()
    out
  }

  def ensureIndex(spec: VectorIndexSpec): Unit = inner.ensureIndex(spec)

  def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
    inner.putVectors(index, batch)

  def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
    inner.getVectors(index, keys)

  def count(index: String): Int = inner.count(index)

  def query(request: VectorQuery): Vector[VectorMatch] =
    synchronized { queries += 1L }
    val hits = inner.query(request)
    synchronized {
      buffer += RecordedQuery(request.index, request.topK, hits)
    }
    hits
