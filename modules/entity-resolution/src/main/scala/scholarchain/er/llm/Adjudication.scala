package scholarchain.er.llm

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.er.matching.{
  EntityKind,
  Identifiers,
  MatchPath,
  Policy,
  ResolutionRecord,
  RunInfo,
  SideRecord
}
import scholarchain.core.MatchOutcome
import upickle.default.write

/** The three answers a model may give. Abstaining is a first-class answer: an
  * abstention leaves the pair unresolved and is cached like any other.
  */
enum LlmDecision(val wire: String):
  case Accept extends LlmDecision("accept")
  case Reject extends LlmDecision("reject")
  case Abstain extends LlmDecision("abstain")

object LlmDecision:
  def fromWire(wire: String): Option[LlmDecision] =
    values.find(_.wire == wire)

/** A validated model answer. The only identifiers it may mention are ones that
  * were already in the request; it carries no field in which a new identifier
  * could be introduced.
  */
final case class AdjudicationResponse(
    decision: LlmDecision,
    confidence: Double,
    reasonCodes: Vector[String],
    evidenceIds: Vector[String]
)

object AdjudicationResponse:
  val schemaVersion = "scholarchain.er.adjudication/1"
  val maxTextLength = 8192
  private val allowedKeys =
    Set("decision", "confidence", "reasonCodes", "evidenceIds")
  private val reasonCode = "[a-z0-9._-]{1,64}".r

  /** The JSON Schema the prompt asks the model to follow. Parsing below
    * enforces the same constraints, because a model may ignore its prompt.
    */
  val jsonSchema: String =
    """{
      |  "$schema": "https://json-schema.org/draft/2020-12/schema",
      |  "type": "object",
      |  "additionalProperties": false,
      |  "required": ["decision", "confidence", "reasonCodes"],
      |  "properties": {
      |    "decision": {"enum": ["accept", "reject", "abstain"]},
      |    "confidence": {"type": "number", "minimum": 0, "maximum": 1},
      |    "reasonCodes": {
      |      "type": "array", "minItems": 1, "maxItems": 8,
      |      "items": {"type": "string", "pattern": "^[a-z0-9._-]{1,64}$"}
      |    },
      |    "evidenceIds": {
      |      "type": "array", "maxItems": 16, "items": {"type": "string"}
      |    }
      |  }
      |}""".stripMargin

  /** Strict parse. Any deviation is an error message, never an exception and
    * never a partially trusted result.
    */
  def parse(
      text: String,
      allowedIds: Set[String]
  ): Either[String, AdjudicationResponse] =
    if text == null || text.length > maxTextLength then
      Left("response missing or too long")
    else
      try
        ujson.read(text) match
          case ujson.Obj(fields) =>
            val unknown = fields.keySet.diff(allowedKeys)
            if unknown.nonEmpty then
              Left(s"unknown fields: ${unknown.toVector.sorted.mkString(",")}")
            else fromFields(fields.toMap, allowedIds)
          case _ => Left("response is not a JSON object")
      catch
        case NonFatal(error) =>
          Left(s"unparseable response: ${error.getClass.getSimpleName}")

  private def strings(value: ujson.Value): Option[Vector[String]] =
    value match
      case ujson.Arr(items) =>
        val texts = items.toVector.collect { case ujson.Str(text) => text }
        Option.when(texts.size == items.size)(texts)
      case _ => None

  private def fromFields(
      fields: Map[String, ujson.Value],
      allowedIds: Set[String]
  ): Either[String, AdjudicationResponse] =
    for
      decision <- fields
        .get("decision")
        .collect { case ujson.Str(wire) => wire }
        .flatMap(LlmDecision.fromWire)
        .toRight("decision must be accept, reject or abstain")
      confidence <- fields
        .get("confidence")
        .collect { case ujson.Num(number) => number }
        .filter(number => number.isFinite && number >= 0.0 && number <= 1.0)
        .toRight("confidence must be a number in [0, 1]")
      reasons <- fields
        .get("reasonCodes")
        .flatMap(strings)
        .filter(codes => codes.nonEmpty && codes.size <= 8)
        .filter(_.forall(reasonCode.matches))
        .toRight("reasonCodes must be 1 to 8 codes of [a-z0-9._-]")
      evidence <- fields
        .get("evidenceIds")
        .fold(Option(Vector.empty[String]))(strings)
        .filter(_.size <= 16)
        .toRight("evidenceIds must be an array of at most 16 strings")
      _ <- Either.cond(
        evidence.forall(allowedIds.contains),
        (),
        "evidenceIds names an identifier not present in the candidates"
      )
    yield AdjudicationResponse(decision, confidence, reasons, evidence)

/** Everything a model is shown, and everything its answer is checked against.
  */
final case class AdjudicationRequest(
    record: ResolutionRecord,
    left: SideRecord,
    right: SideRecord
):
  require(
    record.leftId == left.id && record.rightId == right.id,
    "Request records must match the decision being adjudicated"
  )

  /** Identifiers present in the candidates: record IDs, source record IDs and
    * normalized authoritative identifier values.
    */
  def allowedIds: Set[String] =
    def idsOf(side: SideRecord): Set[String] =
      Set(side.id, side.sourceRecordId, side.workId) ++
        side.identifiers.values.values.flatten
    idsOf(left) ++ idsOf(right)

  /** SHA-256 over a canonical rendering: object keys sorted, set-like fields
    * ordered, so equal inputs hash equally whatever order they were built in.
    */
  def inputHash: String =
    val body = ujson.Obj(
      "kind" -> record.kind.wire,
      "left" -> Canonical.sorted(ujson.read(write(left.canonical))),
      "right" -> Canonical.sorted(ujson.read(write(right.canonical))),
      "features" -> Canonical.sorted(
        ujson.read(write(record.decision.features))
      ),
      "confidence" -> record.decision.confidence
    )
    Digests.sha256Hex(Vector(Canonical.render(body)))

object Canonical:
  def sorted(value: ujson.Value): ujson.Value =
    value match
      case ujson.Obj(fields) =>
        val ordered = fields.toVector.sortBy(_._1)
        ujson.Obj.from(ordered.map((key, item) => key -> sorted(item)))
      case ujson.Arr(items) => ujson.Arr.from(items.map(sorted))
      case other            => other

  def render(value: ujson.Value): String = ujson.write(sorted(value))

object Digests:
  /** Length framing keeps ("ab", "c") and ("a", "bc") apart. */
  def sha256Hex(parts: Vector[String]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    parts.foreach { part =>
      val bytes = part.getBytes(UTF_8)
      digest.update(
        java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()
      )
      digest.update(bytes)
    }
    HexFormat.of().formatHex(digest.digest())

/** A cached answer is valid only for the same input, prompt, model and policy.
  */
final case class CacheKey(
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String
):
  require(
    Vector(inputHash, promptVersion, modelVersion, policyVersion)
      .forall(_.trim.nonEmpty),
    "Every cache key part is required"
  )
  def digest: String =
    Digests.sha256Hex(
      Vector(inputHash, promptVersion, modelVersion, policyVersion)
    )

trait AdjudicationCache:
  def get(key: CacheKey): Option[AdjudicationResponse]
  def put(key: CacheKey, response: AdjudicationResponse): Unit

final class InMemoryAdjudicationCache extends AdjudicationCache:
  private val entries = mutable.HashMap.empty[String, AdjudicationResponse]
  def get(key: CacheKey): Option[AdjudicationResponse] =
    synchronized(entries.get(key.digest))
  def put(key: CacheKey, response: AdjudicationResponse): Unit =
    synchronized { entries(key.digest) = response }
  def size: Int = synchronized(entries.size)

/** The seam where a hosted model would plug in. This module ships no
  * implementation that reaches a network; the only implementations are offline
  * stubs. `complete` returns the model's raw text, which is untrusted until
  * [[AdjudicationResponse.parse]] accepts it.
  */
trait AdjudicationModel:
  def modelVersion: String
  def complete(prompt: String): String

/** Offline stand-in that answers from a function. */
final class OfflineStubModel(val modelVersion: String, answer: String => String)
    extends AdjudicationModel:
  def complete(prompt: String): String = answer(prompt)

object OfflineStubModel:
  def fixed(modelVersion: String, json: String): OfflineStubModel =
    OfflineStubModel(modelVersion, _ => json)

  /** A model that always abstains. */
  def abstaining(modelVersion: String): OfflineStubModel =
    fixed(
      modelVersion,
      """{"decision":"abstain","confidence":0.5,"reasonCodes":["insufficient-evidence"]}"""
    )

object AdjudicationPrompt:
  val version = "scholarchain.er.prompt/1"

  def render(request: AdjudicationRequest): String =
    def side(label: String, record: SideRecord): String =
      s"""$label: id=${record.id} source=${record.source.wire}
         |  name=${record.name} title=${record.title} year=${record.year.fold(
          "unknown"
        )(_.toString)}
         |  venue=${record.venue.getOrElse("unknown")}
         |  affiliations=${record.affiliations.mkString("; ")}
         |  authors=${record.authorNames.mkString("; ")}""".stripMargin
    s"""Decide whether two ${request.record.kind.wire} records describe the same ${request.record.kind.wire}.
       |Answer with one JSON object matching this schema and nothing else:
       |${AdjudicationResponse.jsonSchema}
       |Abstain when the evidence does not decide the question. Cite only identifiers shown below.
       |${side("LEFT", request.left)}
       |${side("RIGHT", request.right)}
       |""".stripMargin

/** Adjudicates only pairs the matcher left unresolved inside the policy's
  * adjudication band. It never edits a source record and never emits an
  * identifier of its own: the returned record keeps the matcher's endpoint IDs.
  */
final class LlmAdjudicator(
    model: AdjudicationModel,
    cache: AdjudicationCache,
    policy: Policy,
    run: RunInfo
):
  private val calls = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Number of times the model was actually invoked (cache hits excluded). */
  def modelCalls: Int = calls.get()

  /** Unresolved, probabilistic, and inside `[llmBandLow, accept)`. Conflicts
    * and every rule or identifier outcome are outside the band, as are records
    * the matcher could not evaluate.
    */
  def eligible(record: ResolutionRecord): Boolean =
    record.decision.decision == MatchOutcome.Unresolved &&
      record.path == MatchPath.Probabilistic &&
      record.decision.modelVersion.isEmpty &&
      !record.decision.reasonCodes.contains("input.side-missing") &&
      !record.decision.reasonCodes.contains("input.invalid-candidate") &&
      policy.inLlmBand(record.decision.confidence)

  def refine(
      record: ResolutionRecord,
      left: SideRecord,
      right: SideRecord
  ): ResolutionRecord =
    if !eligible(record) then record
    else
      val request = AdjudicationRequest(record, left, right)
      val key = CacheKey(
        request.inputHash,
        AdjudicationPrompt.version,
        model.modelVersion,
        policy.version
      )
      val response = cache.get(key).getOrElse {
        val fresh = ask(request)
        cache.put(key, fresh)
        fresh
      }
      applyResponse(record, response)

  private def ask(request: AdjudicationRequest): AdjudicationResponse =
    calls.incrementAndGet()
    val raw =
      try model.complete(AdjudicationPrompt.render(request))
      catch case NonFatal(_) => null
    if raw == null then abstention("model-error")
    else
      AdjudicationResponse.parse(raw, request.allowedIds) match
        case Right(response) => response
        case Left(_)         => abstention("invalid-response")

  private def abstention(code: String): AdjudicationResponse =
    AdjudicationResponse(LlmDecision.Abstain, 0.0, Vector(code), Vector.empty)

  private def applyResponse(
      record: ResolutionRecord,
      response: AdjudicationResponse
  ): ResolutionRecord =
    val decided = response.decision != LlmDecision.Abstain &&
      response.confidence >= policy.llmMinConfidence
    val outcome = response.decision match
      case LlmDecision.Accept if decided => MatchOutcome.Accepted
      case LlmDecision.Reject if decided => MatchOutcome.Rejected
      case _                             => MatchOutcome.Unresolved
    val note =
      if response.decision == LlmDecision.Abstain then "llm.abstained"
      else if !decided then "llm.below-confidence-floor"
      else s"llm.${response.decision.wire}"
    val original = record.decision
    val updated = original.copy(
      decision = outcome,
      confidence = if decided then response.confidence else original.confidence,
      rulesFired = original.rulesFired :+ note,
      reasonCodes = (original.reasonCodes :+ note) ++
        response.reasonCodes.map(code => s"llm.$code"),
      modelVersion = Some(model.modelVersion),
      promptVersion = Some(AdjudicationPrompt.version),
      decidedAt = run.decidedAtText
    )
    record.copy(
      path = if decided then MatchPath.LlmAdjudicated else record.path,
      decision = updated
    )
