package scholarchain.rag

import scholarchain.core.{EvidenceBundle, EvidenceType}
import scholarchain.storage.WindowLabels

final case class SynthesisOutput(
    analysis: String,
    claims: Vector[ClaimDraft],
    missingEvidence: Vector[String],
    uncertainty: Option[String]
)

enum SynthesisResult:
  case Done(output: SynthesisOutput, attempts: Int, usage: ModelUsage)
  case Failed(reasons: Vector[String], attempts: Int, usage: ModelUsage)

object AnswerSynthesisAgent:
  /** The synthesis reply limit. 2000 cut three of four Haiku replies at
    * `max_tokens` in the Phase 16 H-4 diagnostic (the one that fit used 1990),
    * so it is 4096 (decision P16-32). It bounds the reply only; the input
    * budget is `ModelRetryPolicy.maxInputTokens`, and the planner's own limit
    * is separate.
    */
  val MaxOutputTokens = 4096

  /** The reason of a ranking reply with more claims than the cap. */
  val TooManyRankingClaims = "too many claims for a ranking"

  /** Claims allowed beside a ranking fact of `entries` entries: one `ranking`
    * claim per entry, plus at most one definition and one semantic claim
    * (prompt synthesis 1.2.0, rule 6). The h4-diagnostic-3 replies held 17 and
    * 18 claims for 5 entries and filled the 4096-token limit (decision P16-40).
    * At the measured 238 tokens a claim, the cap is 1.7k tokens for 5 entries
    * (7 claims) and 2.9k for 10 (12 claims). A reply over the cap is rejected
    * and retried with the reason; it is not trimmed, because a trim would
    * choose which claims to drop.
    */
  def rankingClaimCap(entries: Int): Int = entries + 2

/** Writes the analysis and typed claims from the evidence bundle through the
  * model port. The prompt and the output schema are versioned
  * (`Prompts.Synthesis`, `Schemas.SynthesisOutput`). The question and every
  * retrieved snippet are passed under `untrusted` keys, as JSON strings, and
  * never joined into the instructions. The output is validated against the
  * schema before anything reads it; a malformed reply is retried within the
  * policy and then the stage fails closed. The schema has no field for
  * reasoning, so there is none to expose.
  */
final class AnswerSynthesisAgent(
    model: ModelPort,
    retry: ModelRetryPolicy = ModelRetryPolicy()
):
  def input(
      draft: PlanDraft,
      bundle: EvidenceBundle,
      gaps: Vector[String]
  ): ujson.Value =
    ujson.Obj(
      "instructions" -> Prompts.Synthesis.text,
      "schema" -> Schemas.SynthesisOutputId,
      "plan" -> ujson.Obj(
        "operation" -> draft.operation.wire,
        "window" -> WindowLabels.of(draft.window),
        "comparisonWindow" -> Facts.optStr(
          draft.comparisonWindow.map(WindowLabels.of)
        ),
        "metrics" -> Facts.strs(draft.metrics.map(_.wire)),
        "rankMetric" -> Facts.optStr(draft.rankMetric.map(_.wire)),
        "resultLimit" -> draft.resultLimit
      ),
      // Everything that came from a user or from stored data sits under an
      // untrusted key: the question, the known gaps (they quote researcher
      // names), snippets, and the names and titles of each fact.
      "untrusted" -> ujson.Obj(
        "question" -> draft.question,
        "knownGaps" -> Facts.strs(gaps)
      ),
      "evidence" -> ujson.Arr.from(bundle.items.map { item =>
        val semantic = item.evidenceType == EvidenceType.TextChunk
        val (facts, data) =
          if semantic then (ujson.Null: ujson.Value, ujson.Null: ujson.Value)
          else
            item.text
              .flatMap(t => scala.util.Try(ujson.read(t)).toOption)
              .map(Facts.split)
              .getOrElse((ujson.Null, ujson.Null))
        ujson.Obj(
          "evidenceId" -> item.evidenceId,
          "type" -> item.evidenceType.wire,
          "deterministic" -> !semantic,
          "descriptiveOnly" -> semantic,
          "facts" -> facts,
          "untrustedData" -> data,
          "untrustedText" -> (if semantic then Facts.optStr(item.text)
                              else ujson.Null),
          "uncertainty" -> Facts.optStr(item.uncertainty)
        ): ujson.Value
      })
    )

  private def checks(rankingEntries: Option[Int])(
      json: ujson.Value
  ): Vector[String] =
    val badEvidence = Facts
      .list(json, "claims")
      .flatMap(c => Facts.list(c, "evidenceIds"))
      .exists(e => !e.str.startsWith("evidence:v1:"))
    // Reject rather than trim: a trim could drop required ranking claims
    // (P16-41). Cost of a rejection: a completed over-cap reply is retried up
    // to 3 synthesis attempts, about 0.027 USD each at 4096 output tokens,
    // then the answer abstains as synthesis-failed.
    val overCap = rankingEntries.exists(n =>
      Facts.list(json, "claims").size > AnswerSynthesisAgent.rankingClaimCap(n)
    )
    Vector(
      if badEvidence then Some("an evidence ID is malformed") else None,
      if overCap then Some(AnswerSynthesisAgent.TooManyRankingClaims) else None
    ).flatten

  def synthesize(
      draft: PlanDraft,
      bundle: EvidenceBundle,
      gaps: Vector[String]
  ): SynthesisResult =
    val request = ModelRequest(
      Prompts.Synthesis.id,
      Prompts.Synthesis.version,
      Prompts.Synthesis.schemaId,
      input(draft, bundle, gaps),
      AnswerSynthesisAgent.MaxOutputTokens
    )
    val shape = InputShape.of(bundle)
    StructuredCall.run(
      model,
      request,
      retry,
      checks(shape.rankingEntries),
      shape,
      stopOnMaxTokens = true
    ) match
      case StructuredOutcome.Valid(json, attempts, usage) =>
        SynthesisResult.Done(
          SynthesisOutput(
            Facts.s(json, "analysis").getOrElse(""),
            Facts
              .list(json, "claims")
              .zipWithIndex
              .map((c, i) => ClaimDraft.fromJson(c, i + 1)),
            Facts.list(json, "missingEvidence").map(_.str),
            Facts.s(json, "uncertainty")
          ),
          attempts,
          usage
        )
      case StructuredOutcome.Failed(reasons, attempts, usage) =>
        SynthesisResult.Failed(reasons, attempts, usage)
