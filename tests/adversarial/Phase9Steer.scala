package scholarchain.tests.adversarial

import scholarchain.rag.*

/** Helpers that build hostile or dishonest model behavior on top of the public
  * `ModelPort`. They read the model request the way any model would: the
  * evidence list of the synthesis input.
  */
object P9Steer:
  val Absent: String = "evidence:v1:" + "0" * 64

  def unwrap(input: ujson.Value): ujson.Value =
    if input.objOpt.exists(_.contains("original")) then input("original")
    else input

  def evidence(input: ujson.Value): Vector[ujson.Value] =
    Facts.list(unwrap(input), "evidence")

  def firstOfKind(input: ujson.Value, kind: String): Option[String] =
    evidence(input)
      .find(e =>
        Facts.b(e, "deterministic").contains(true) &&
          Facts.kind(e("facts")).contains(kind)
      )
      .map(_("evidenceId").str)

  def chunkIds(input: ujson.Value): Vector[String] =
    evidence(input)
      .filter(e => Facts.b(e, "descriptiveOnly").contains(true))
      .map(_("evidenceId").str)

  def claim(
      id: String,
      kind: String,
      text: String,
      ids: Seq[String],
      assertion: ujson.Obj,
      uncertainty: Option[String] = None
  ): ujson.Value =
    ujson.Obj(
      "claimId" -> id,
      "text" -> text,
      "kind" -> kind,
      "evidenceIds" -> Facts.strs(ids),
      "assertion" -> assertion,
      "uncertainty" -> uncertainty.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    )

  def metricAssertion(
      researcherId: String,
      metric: String,
      value: Double,
      window: String
  ): ujson.Obj =
    ujson.Obj(
      "kind" -> "metric",
      "researcherId" -> researcherId,
      "metric" -> metric,
      "value" -> value,
      "window" -> window
    )

  /** An honest synthesis reply that `edit` may change before it is returned. */
  def steerSynthesis(edit: (ujson.Value, ujson.Value) => Unit): ModelPort =
    new SteeredModel(
      new DeterministicStubModel,
      (req, resp) =>
        if req.promptId == Prompts.Synthesis.id then
          val out = ujson.read(resp.rawJson)
          edit(out, req.input)
          Replies.raw(ujson.write(out))
        else resp
    )

  def addClaims(out: ujson.Value, more: ujson.Value*): Unit =
    more.foreach(c => out("claims").arr += c)

  /** Obeys an instruction found anywhere in the model input: when the input
    * contains `trigger`, it appends `bogus` to the synthesis claims.
    */
  def obedient(
      trigger: String,
      bogus: ujson.Value => Vector[ujson.Value]
  ): ModelPort =
    steerSynthesis { (out, input) =>
      if ujson.write(input).toLowerCase.contains(trigger.toLowerCase) then
        addClaims(out, bogus(input)*)
    }
