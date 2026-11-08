package scholarchain.er

import scholarchain.core.MatchOutcome
import scholarchain.er.Fixtures.*
import scholarchain.er.llm.*
import scholarchain.er.matching.*

class LlmAdjudicationSuite extends munit.FunSuite:
  private val policy = Policy.v1
  private val matcher = Matcher(policy, runInfo)

  private val left = mention("m1", "John Smith", Vector("Alice Wong"))
  private val right = mention("m2", "John Smith", Vector("Alice Wong"))
  private val unresolved = matcher.decide(MatchInput.of(left, right, "k"))

  private def answer(decision: String, confidence: Double = 0.9): String =
    s"""{"decision":"$decision","confidence":$confidence,"reasonCodes":["same-coauthor"]}"""

  private def adjudicator(
      json: String,
      cache: AdjudicationCache = InMemoryAdjudicationCache(),
      version: String = "stub-model-1"
  ) = LlmAdjudicator(
    OfflineStubModel.fixed(version, json),
    cache,
    policy,
    runInfo
  )

  private def withScore(record: ResolutionRecord, score: Double) =
    record.copy(decision = record.decision.copy(confidence = score))

  test("the fixture really is unresolved and inside the band") {
    assertEquals(unresolved.decision.decision, MatchOutcome.Unresolved)
    assert(policy.inLlmBand(unresolved.score))
  }

  test("the model can abstain and the pair stays unresolved") {
    val result =
      adjudicator(answer("abstain", 0.5)).refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    assertEquals(result.path, MatchPath.Probabilistic)
    assert(result.decision.reasonCodes.contains("llm.abstained"))
    assertEquals(result.decision.modelVersion, Some("stub-model-1"))
    assertEquals(
      result.decision.promptVersion,
      Some(AdjudicationPrompt.version)
    )
  }

  test("a malformed or off-schema answer is an abstention, not a decision") {
    val bad = Vector(
      "not json",
      "[]",
      """{"decision":"accept"}""",
      """{"decision":"maybe","confidence":0.9,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":1.5,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":[]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["Bad Code"]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["x"],"newId":"orcid:1"}""",
      """{"decision":"accept","confidence":"high","reasonCodes":["x"]}"""
    )
    bad.foreach { json =>
      val result = adjudicator(json).refine(unresolved, left, right)
      assertEquals(result.decision.decision, MatchOutcome.Unresolved, json)
      assert(
        result.decision.reasonCodes.contains("llm.invalid-response"),
        json
      )
    }
  }

  test("an identifier that is not in the candidates is refused") {
    val invented =
      """{"decision":"accept","confidence":0.95,"reasonCodes":["x"],"evidenceIds":["orcid-9999"]}"""
    val result = adjudicator(invented).refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    val known =
      s"""{"decision":"accept","confidence":0.95,"reasonCodes":["x"],"evidenceIds":["${left.id}"]}"""
    val accepted = adjudicator(known).refine(unresolved, left, right)
    assertEquals(accepted.decision.decision, MatchOutcome.Accepted)
  }

  test("a confident accept becomes an LLM-adjudicated acceptance") {
    val result =
      adjudicator(answer("accept", 0.92)).refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Accepted)
    assertEquals(result.path, MatchPath.LlmAdjudicated)
    assertEquals(result.decision.modelVersion, Some("stub-model-1"))
    assertEquals(result.decision.confidence, 0.92)
    assert(result.decision.rulesFired.contains("llm.accept"))
  }

  test("a confident reject becomes an LLM-adjudicated rejection") {
    val result =
      adjudicator(answer("reject", 0.9)).refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Rejected)
    assertEquals(result.path, MatchPath.LlmAdjudicated)
  }

  test("an answer below the confidence floor is treated as abstention") {
    val result =
      adjudicator(answer("accept", 0.6)).refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    assert(result.decision.reasonCodes.contains("llm.below-confidence-floor"))
    assertEquals(result.path, MatchPath.Probabilistic)
  }

  test("the model is never asked about a pair outside the unresolved band") {
    val tool = adjudicator(answer("accept"))
    val accepted = matcher.decide(
      MatchInput.of(
        mention(
          "m1",
          "John Smith",
          Vector("Alice Wong", "Bob Chen"),
          Vector("MIT")
        ),
        mention(
          "m2",
          "John Smith",
          Vector("Alice Wong", "Bob Chen"),
          Vector("MIT")
        ),
        "k"
      )
    )
    val rejected = matcher.decide(
      MatchInput.of(
        mention("m1", "John Smith"),
        mention("m2", "John Nguyen"),
        "k"
      )
    )
    val conflict = matcher.decide(
      MatchInput.of(
        mention("m1", "Jane Doe", externalIds = Map("orcid" -> validOrcidA)),
        mention("m2", "Jane Doe", externalIds = Map("orcid" -> validOrcidB)),
        "k"
      )
    )
    val belowBand = withScore(unresolved, policy.llmBandLow - 0.01)
    val atAccept = withScore(unresolved, policy.accept)
    val outcomes = Vector(
      MatchOutcome.Accepted -> accepted,
      MatchOutcome.Rejected -> rejected,
      MatchOutcome.Conflict -> conflict,
      MatchOutcome.Unresolved -> belowBand,
      MatchOutcome.Unresolved -> atAccept
    )
    outcomes.foreach { (expected, record) =>
      assertEquals(record.decision.decision, expected)
      assertEquals(tool.refine(record, left, right), record)
    }
    assertEquals(tool.modelCalls, 0)
    tool.refine(unresolved, left, right)
    assertEquals(tool.modelCalls, 1)
  }

  test("a conflict cannot be overturned by the model") {
    val conflict = matcher.decide(
      MatchInput.of(
        mention("m1", "Jane Doe", externalIds = Map("orcid" -> validOrcidA)),
        mention("m2", "Jane Doe", externalIds = Map("orcid" -> validOrcidB)),
        "k"
      )
    )
    val tool = adjudicator(answer("accept", 1.0))
    assertEquals(
      tool.refine(conflict, left, right).decision.decision,
      MatchOutcome.Conflict
    )
    assertEquals(tool.modelCalls, 0)
  }

  test("adjudication changes neither endpoint IDs nor source records") {
    val before = (left, right)
    val result =
      adjudicator(answer("accept", 0.95)).refine(unresolved, left, right)
    assertEquals(result.decision.sourceId, unresolved.decision.sourceId)
    assertEquals(result.decision.targetId, unresolved.decision.targetId)
    assertEquals((left, right), before)
  }

  test("cache hits skip the model for the same input, prompt, model, policy") {
    val cache = InMemoryAdjudicationCache()
    val first = adjudicator(answer("accept", 0.9), cache)
    val second = adjudicator(answer("reject", 0.9), cache)
    assertEquals(
      first.refine(unresolved, left, right).decision.decision,
      MatchOutcome.Accepted
    )
    // The second model would answer differently, so a cache hit is visible.
    assertEquals(
      second.refine(unresolved, left, right).decision.decision,
      MatchOutcome.Accepted
    )
    assertEquals(second.modelCalls, 0)
    assertEquals(cache.size, 1)
  }

  test("a different model version does not reuse a cached answer") {
    val cache = InMemoryAdjudicationCache()
    adjudicator(answer("accept"), cache, "model-a").refine(
      unresolved,
      left,
      right
    )
    val other = adjudicator(answer("accept"), cache, "model-b")
    other.refine(unresolved, left, right)
    assertEquals(other.modelCalls, 1)
    assertEquals(cache.size, 2)
  }

  test("the cache key changes with each of its four parts") {
    val base = CacheKey("input", "prompt", "model", "policy")
    val variants = Vector(
      base.copy(inputHash = "input2"),
      base.copy(promptVersion = "prompt2"),
      base.copy(modelVersion = "model2"),
      base.copy(policyVersion = "policy2")
    )
    assertEquals((base +: variants).map(_.digest).distinct.size, 5)
    // Length framing: moving a character across a boundary changes the key.
    assertNotEquals(
      CacheKey("ab", "c", "m", "p").digest,
      CacheKey("a", "bc", "m", "p").digest
    )
    intercept[IllegalArgumentException](CacheKey("", "p", "m", "v"))
  }

  test("the input hash follows the input and is stable across rebuilds") {
    val request = AdjudicationRequest(unresolved, left, right)
    val rebuilt = AdjudicationRequest(
      matcher.decide(MatchInput.of(left, right, "k")),
      left,
      right
    )
    assertEquals(request.inputHash, rebuilt.inputHash)
    val changed = right.copy(affiliations = Vector("Somewhere Else"))
    val other = AdjudicationRequest(
      matcher.decide(MatchInput.of(left, changed, "k")),
      left,
      changed
    )
    assertNotEquals(request.inputHash, other.inputHash)
  }

  test("a policy version change invalidates cached answers") {
    val cache = InMemoryAdjudicationCache()
    adjudicator(answer("accept"), cache).refine(unresolved, left, right)
    val newer = policy.copy(version = "scholarchain.er.policy/1.0.1")
    val tool = LlmAdjudicator(
      OfflineStubModel.fixed("stub-model-1", answer("accept")),
      cache,
      newer,
      runInfo
    )
    tool.refine(unresolved, left, right)
    assertEquals(tool.modelCalls, 1)
  }

  test("a model that throws is an abstention") {
    val tool = LlmAdjudicator(
      OfflineStubModel("stub", _ => throw new IllegalStateException("down")),
      InMemoryAdjudicationCache(),
      policy,
      runInfo
    )
    val result = tool.refine(unresolved, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    assert(result.decision.reasonCodes.contains("llm.model-error"))
  }

  test("the prompt carries the schema and only the pair's own facts") {
    val prompt =
      AdjudicationPrompt.render(AdjudicationRequest(unresolved, left, right))
    assert(prompt.contains("\"abstain\""))
    assert(prompt.contains("additionalProperties"))
    assert(prompt.contains(left.id) && prompt.contains(right.id))
  }
