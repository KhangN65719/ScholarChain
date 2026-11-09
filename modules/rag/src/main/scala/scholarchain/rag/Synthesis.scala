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

  private def checks(json: ujson.Value): Vector[String] =
    val ids = Facts.list(json, "claims").flatMap(c => Facts.s(c, "claimId"))
    val badEvidence = Facts
      .list(json, "claims")
      .flatMap(c => Facts.list(c, "evidenceIds"))
      .exists(e => !e.str.startsWith("evidence:v1:"))
    Vector(
      if ids.distinct.size != ids.size then Some("claimId values repeat")
      else None,
      if badEvidence then Some("an evidence ID is malformed") else None
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
      2000
    )
    StructuredCall.run(model, request, retry, checks) match
      case StructuredOutcome.Valid(json, attempts, usage) =>
        SynthesisResult.Done(
          SynthesisOutput(
            Facts.s(json, "analysis").getOrElse(""),
            Facts.list(json, "claims").map(ClaimDraft.fromJson),
            Facts.list(json, "missingEvidence").map(_.str),
            Facts.s(json, "uncertainty")
          ),
          attempts,
          usage
        )
      case StructuredOutcome.Failed(reasons, attempts, usage) =>
        SynthesisResult.Failed(reasons, attempts, usage)
