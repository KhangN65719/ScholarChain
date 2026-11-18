package scholarchain.evaluation.answer

import scala.util.Try
import scholarchain.evaluation.io.{J, Js}
import scholarchain.rag.{Facts, ModelAttempt, WorkflowResult}

/** One evidence item as the frozen answer record keeps it. `text` is the fact
  * JSON of a deterministic item or the snippet of a semantic one.
  */
final case class EvidenceRec(
    evidenceId: String,
    evidenceType: String,
    kind: Option[String],
    workId: Option[String],
    graphPath: Vector[String],
    score: Double,
    text: Option[String],
    uncertainty: Option[String]
):
  def isSemantic: Boolean = evidenceType == "TextChunk"

  /** The parsed fact of a deterministic item. */
  def fact: Option[ujson.Value] =
    if isSemantic then None else text.flatMap(t => Try(ujson.read(t)).toOption)

  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "evidenceId" -> Js.s(evidenceId),
        "evidenceType" -> Js.s(evidenceType),
        "kind" -> Js.opt(kind),
        "workId" -> Js.opt(workId),
        "graphPath" -> Js.arr(graphPath.map(Js.s)),
        "score" -> Js.d(score),
        "text" -> Js.opt(text),
        "uncertainty" -> Js.opt(uncertainty)
      )
    )

object EvidenceRec:
  def fromJson(v: ujson.Value): EvidenceRec =
    EvidenceRec(
      J.str(v, "evidenceId").getOrElse(""),
      J.str(v, "evidenceType").getOrElse(""),
      J.str(v, "kind"),
      J.str(v, "workId"),
      J.strings(v, "graphPath"),
      J.num(v, "score").getOrElse(0.0),
      J.str(v, "text"),
      J.str(v, "uncertainty")
    )

final case class ClaimRec(
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    status: String,
    uncertainty: Option[String]
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "claimId" -> Js.s(claimId),
        "text" -> Js.s(text),
        "evidenceIds" -> Js.arr(evidenceIds.map(Js.s)),
        "status" -> Js.s(status),
        "uncertainty" -> Js.opt(uncertainty)
      )
    )

object ClaimRec:
  def fromJson(v: ujson.Value): ClaimRec =
    ClaimRec(
      J.str(v, "claimId").getOrElse(""),
      J.str(v, "text").getOrElse(""),
      J.strings(v, "evidenceIds"),
      J.str(v, "status").getOrElse(""),
      J.str(v, "uncertainty")
    )

final case class AttemptRec(
    promptId: String,
    attempt: Int,
    accepted: Boolean,
    rejections: Vector[String],
    stopReason: Option[String],
    outputTokens: Int
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "promptId" -> Js.s(promptId),
        "attempt" -> Js.l(attempt.toLong),
        "accepted" -> Js.b(accepted),
        "rejections" -> Js.arr(rejections.map(Js.s)),
        "stopReason" -> Js.opt(stopReason),
        "outputTokens" -> Js.l(outputTokens.toLong)
      )
    )

object AttemptRec:
  def of(a: ModelAttempt): AttemptRec =
    AttemptRec(
      a.promptId,
      a.attempt,
      a.accepted,
      a.rejections,
      a.stopReason,
      a.outputTokens
    )

  def fromJson(v: ujson.Value): AttemptRec =
    AttemptRec(
      J.str(v, "promptId").getOrElse(""),
      J.int(v, "attempt").getOrElse(0),
      J.field(v, "accepted").flatMap(_.boolOpt).getOrElse(false),
      J.strings(v, "rejections"),
      J.str(v, "stopReason"),
      J.int(v, "outputTokens").getOrElse(0)
    )

/** The frozen record of one answer: everything the audit and the rating sample
  * need, written once before any score. It is the only file (with the rating
  * sample) that holds question and answer text. `verification` is the system
  * verifier's own report, kept as self-reported. `costStopped` marks an answer
  * made while the guard stopped, so it is left out of every score.
  */
final case class AnswerRecord(
    questionId: String,
    run: String,
    mode: String,
    sessionId: String,
    ordinal: Int,
    question: String,
    answer: String,
    abstained: Boolean,
    abstentionReason: Option[String],
    claims: Vector[ClaimRec],
    evidence: Vector[EvidenceRec],
    verification: Option[ujson.Value],
    attempts: Vector[AttemptRec],
    modelCalls: Int,
    promptTokens: Int,
    completionTokens: Int,
    tokensEstimated: Boolean,
    latencyMillis: Long,
    flags: Vector[String],
    answerHash: String,
    operation: Option[String],
    costStopped: Boolean,
    error: Option[String]
):
  def usable: Boolean = !costStopped && error.isEmpty

  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "run" -> Js.s(run),
        "mode" -> Js.s(mode),
        "sessionId" -> Js.s(sessionId),
        "ordinal" -> Js.l(ordinal.toLong),
        "question" -> Js.s(question),
        "answer" -> Js.s(answer),
        "abstained" -> Js.b(abstained),
        "abstentionReason" -> Js.opt(abstentionReason),
        "claims" -> Js.arr(claims.map(_.toJson)),
        "evidence" -> Js.arr(evidence.map(_.toJson)),
        "verification" -> verification.getOrElse(ujson.Null),
        "attempts" -> Js.arr(attempts.map(_.toJson)),
        "modelCalls" -> Js.l(modelCalls.toLong),
        "promptTokens" -> Js.l(promptTokens.toLong),
        "completionTokens" -> Js.l(completionTokens.toLong),
        "tokensEstimated" -> Js.b(tokensEstimated),
        "latencyMillis" -> Js.l(latencyMillis),
        "flags" -> Js.arr(flags.map(Js.s)),
        "answerHash" -> Js.s(answerHash),
        "operation" -> Js.opt(operation),
        "costStopped" -> Js.b(costStopped),
        "error" -> Js.opt(error)
      )
    )

object AnswerRecord:
  private def array(v: ujson.Value, key: String): Vector[ujson.Value] =
    J.field(v, key).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  def fromJson(v: ujson.Value): AnswerRecord =
    AnswerRecord(
      J.str(v, "questionId").getOrElse(""),
      J.str(v, "run").getOrElse("primary"),
      J.str(v, "mode").getOrElse("fused"),
      J.str(v, "sessionId").getOrElse(""),
      J.int(v, "ordinal").getOrElse(0),
      J.str(v, "question").getOrElse(""),
      J.str(v, "answer").getOrElse(""),
      J.field(v, "abstained").flatMap(_.boolOpt).getOrElse(false),
      J.str(v, "abstentionReason"),
      array(v, "claims").map(ClaimRec.fromJson),
      array(v, "evidence").map(EvidenceRec.fromJson),
      J.field(v, "verification"),
      array(v, "attempts").map(AttemptRec.fromJson),
      J.int(v, "modelCalls").getOrElse(0),
      J.int(v, "promptTokens").getOrElse(0),
      J.int(v, "completionTokens").getOrElse(0),
      J.field(v, "tokensEstimated").flatMap(_.boolOpt).getOrElse(true),
      J.long(v, "latencyMillis").getOrElse(0L),
      J.strings(v, "flags"),
      J.str(v, "answerHash").getOrElse(""),
      J.str(v, "operation"),
      J.field(v, "costStopped").flatMap(_.boolOpt).getOrElse(false),
      J.str(v, "error")
    )

  /** The record of a finished workflow call. */
  def of(
      questionId: String,
      run: String,
      sessionId: String,
      ordinal: Int,
      question: String,
      result: WorkflowResult,
      latencyMillis: Long,
      costStopped: Boolean
  ): AnswerRecord =
    val payload = result.audit.payload
    val tokens = payload("tokens")
    AnswerRecord(
      questionId,
      run,
      result.mode.wire,
      sessionId,
      ordinal,
      question,
      result.response.answer,
      result.abstained,
      result.abstentionReason,
      result.response.claims.map(c =>
        ClaimRec(
          c.claimId,
          c.text,
          c.evidenceIds,
          c.verificationStatus.toString,
          c.uncertainty
        )
      ),
      result.response.evidence.items.map { i =>
        val fact = i.text.flatMap(t => Try(ujson.read(t)).toOption)
        EvidenceRec(
          i.evidenceId,
          i.evidenceType.toString,
          if i.evidenceType.toString == "TextChunk" then None
          else fact.flatMap(Facts.kind),
          i.workId,
          i.graphPath.getOrElse(Vector.empty),
          i.score,
          i.text,
          i.uncertainty
        )
      },
      result.verification.map(_.toJson),
      result.modelAttempts.map(AttemptRec.of),
      payload("modelCalls").num.toInt,
      tokens("promptTokens").num.toInt,
      tokens("completionTokens").num.toInt,
      tokens("estimated").bool,
      latencyMillis,
      result.flags,
      result.audit.answerHash,
      result.planDraft.map(_.operation.wire),
      costStopped,
      None
    )

  /** The record of a question that raised instead of answering. */
  def failed(
      questionId: String,
      run: String,
      sessionId: String,
      ordinal: Int,
      question: String,
      costStopped: Boolean,
      error: Option[String]
  ): AnswerRecord =
    AnswerRecord(
      questionId,
      run,
      "fused",
      sessionId,
      ordinal,
      question,
      "",
      abstained = false,
      None,
      Vector.empty,
      Vector.empty,
      None,
      Vector.empty,
      0,
      0,
      0,
      tokensEstimated = true,
      0L,
      Vector.empty,
      "",
      None,
      costStopped,
      error
    )
