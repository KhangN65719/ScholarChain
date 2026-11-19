package scholarchain.rag

import scala.collection.mutable

/** Phase 16 WO-3 part 2 (decision P16-32): the synthesis reply limit is 4096
  * tokens. The planner's limit and the input budget are separate and stay as
  * they were. Offline stub only; no live call.
  */
class SynthesisOutputLimitSuite extends munit.FunSuite:
  import RuntimeTestSupport.ask

  private final class Recording(inner: ModelPort, invalidSynthesis: Boolean)
      extends ModelPort:
    val requests = mutable.ArrayBuffer.empty[ModelRequest]
    def complete(r: ModelRequest): ModelResponse =
      requests += r
      if invalidSynthesis && r.promptId == Prompts.Synthesis.id then
        ModelResponse("{", "stub/1", 1, 1, tokensEstimated = true)
      else inner.complete(r)

  private val question =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"

  test("the synthesis limit is 4096 and the input budget is unchanged") {
    assertEquals(AnswerSynthesisAgent.MaxOutputTokens, 4096)
    assertEquals(ModelRetryPolicy().maxInputTokens, 12000)
  }

  test("a synthesis call carries 4096 as its output limit") {
    val rec = new Recording(new DeterministicStubModel, false)
    val r = ask(DemoWorld.build(model = rec), question)
    assert(!r.abstained, r.response.answer)
    val synth = rec.requests.filter(_.promptId == Prompts.Synthesis.id)
    assertEquals(synth.map(_.maxOutputTokens).toVector, Vector(4096))
  }

  test("every synthesis retry keeps the 4096 limit") {
    val rec = new Recording(new DeterministicStubModel, true)
    val r = ask(DemoWorld.build(model = rec), question)
    assertEquals(r.abstentionReason, Some("synthesis-failed"))
    val synth = rec.requests.filter(_.promptId == Prompts.Synthesis.id)
    assertEquals(synth.size, ModelRetryPolicy().maxAttempts)
    assert(synth.forall(_.maxOutputTokens == 4096), synth.toString)
  }

  test("the planner classifier keeps its own limit of 200") {
    val rec = new Recording(new DeterministicStubModel, false)
    new QueryPlannerAgent(rec).plan("tell me something interesting", "q")
    val planner = rec.requests.filter(_.promptId == Prompts.Planner.id)
    assert(planner.nonEmpty, "the question did not reach the classifier")
    assert(planner.forall(_.maxOutputTokens == 200), planner.toString)
  }

  test("the limit leaves the schema version unchanged") {
    // the prompt went 1.1.0 to 1.2.0 for the ranking claim cap (WO-3 part 4)
    assertEquals(Prompts.Synthesis.version, "1.2.0")
    assertEquals(
      Schemas.SynthesisOutputId,
      "scholarchain.rag.synthesis-output/3"
    )
  }
