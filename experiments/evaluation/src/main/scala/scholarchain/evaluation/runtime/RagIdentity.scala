package scholarchain.evaluation.runtime

import scholarchain.evaluation.io.Js
import scholarchain.rag.{
  AnswerSynthesisAgent,
  PlannerConfig,
  PromptSpec,
  Prompts,
  Schemas
}

/** The prompt and schema identity a run actually used (review 10 finding 6),
  * read from the rag module at run time, never typed in here: for each prompt
  * its id, version, schema id, the sha256 of its text and the sha256 of its
  * schema, plus the synthesis reply limit and the planner's top-N bound. Ids,
  * versions, hashes and numbers only; no prompt text. Every parameter defaults
  * to the live value, so a test can pass a changed one and see the record move.
  */
object RagIdentity:
  def json(
      prompts: Vector[PromptSpec] = Prompts.all,
      schemaHashes: Map[String, String] = Schemas.hashes,
      synthesisMaxOutputTokens: Int = AnswerSynthesisAgent.MaxOutputTokens,
      plannerMaxLimit: Int = PlannerConfig().maxLimit
  ): ujson.Value =
    Js.obj(
      Vector(
        "prompts" -> Js.arr(
          prompts.map(p =>
            Js.obj(
              Vector(
                "id" -> Js.s(p.id),
                "version" -> Js.s(p.version),
                "sha256" -> Js.s(p.hash),
                "schemaId" -> Js.s(p.schemaId),
                "schemaSha256" -> Js.opt(schemaHashes.get(p.schemaId))
              )
            )
          )
        ),
        "schemas" -> Js.obj(
          schemaHashes.toVector.sortBy(_._1).map((k, v) => k -> Js.s(v))
        ),
        "synthesisMaxOutputTokens" -> Js.l(synthesisMaxOutputTokens.toLong),
        "plannerMaxLimit" -> Js.l(plannerMaxLimit.toLong)
      )
    )
