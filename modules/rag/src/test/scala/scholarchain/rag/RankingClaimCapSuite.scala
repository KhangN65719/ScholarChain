package scholarchain.rag

import scholarchain.core.{CollaborationMetric, EvidenceBundle}

/** Phase 16 WO-3 part 4 (decisions P16-36, P16-38, P16-40): a ranking answer
  * holds one claim per listed entry plus at most two more, so it stays well
  * under the 4096-token reply limit. In `h4-diagnostic-3` two 5-entry rankings
  * produced 18 and 17 claims and filled the limit. Offline fakes only.
  */
class RankingClaimCapSuite extends munit.FunSuite:
  import RuntimeTestSupport.{ask, planned}

  private val question =
    "Which researcher has the most distinct coauthors from 2000-2009?"

  private val world = DemoWorld.build()
  private val rich = new RichEvidence(world)

  private def rankingBundle(entries: Int): EvidenceBundle =
    val fact = ujson.Obj(
      "kind" -> "ranking",
      "metric" -> CollaborationMetric.DistinctCoauthorCount.wire,
      "rankedTotal" -> 300,
      "tieAtCutoff" -> false,
      "entries" -> ujson.Arr.from(
        (1 to entries).map(i =>
          ujson.Obj(
            "rank" -> i,
            "researcherId" -> s"r$i",
            "value" -> (100 - i)
          ): ujson.Value
        )
      )
    )
    val other = rich.bundle.items.filterNot(_.evidenceId == rich.ranking)
    rich.bundle.copy(items =
      other :+ rich.item(rich.ranking).copy(text = Some(ujson.write(fact)))
    )

  private def noRankingBundle: EvidenceBundle =
    rich.bundle.copy(items =
      rich.bundle.items.filterNot(_.evidenceId == rich.ranking)
    )

  private def rankingClaim(rank: Int): ujson.Value =
    ujson.Obj(
      "text" -> "Rank.",
      "kind" -> "ranking",
      "evidenceIds" -> ujson.Arr("evidence:v1:abc"),
      "assertion" -> ujson.Obj(
        "kind" -> "ranking",
        "metric" -> CollaborationMetric.DistinctCoauthorCount.wire,
        "rank" -> rank,
        "researcherId" -> s"r$rank"
      ),
      "uncertainty" -> ujson.Null
    )

  private val definitionClaim: ujson.Value = ujson.Obj(
    "text" -> "Definition.",
    "kind" -> "definition",
    "evidenceIds" -> ujson.Arr(),
    "assertion" -> ujson.Obj("kind" -> "definition", "term" -> "breadth"),
    "uncertainty" -> ujson.Null
  )

  private val semanticClaim: ujson.Value = ujson.Obj(
    "text" -> "Context.",
    "kind" -> "semantic",
    "evidenceIds" -> ujson.Arr("evidence:v1:abc"),
    "assertion" -> ujson.Obj("kind" -> "semantic"),
    "uncertainty" -> ujson.Null
  )

  private def reply(claims: Vector[ujson.Value]): String =
    ujson.write(
      ujson.Obj(
        "schema" -> Schemas.SynthesisOutputId,
        "analysis" -> "A.",
        "claims" -> ujson.Arr.from(claims),
        "missingEvidence" -> ujson.Arr(),
        "uncertainty" -> ujson.Null
      )
    )

  /** `n` claims: ranking claims for ranks 1 to `entries`, then a definition, a
    * semantic claim, and further ranking claims for the rest.
    */
  private def claims(n: Int, entries: Int): Vector[ujson.Value] =
    val base = (1 to entries).map(rankingClaim).toVector ++
      Vector(definitionClaim, semanticClaim)
    if n <= base.size then base.take(n)
    else base ++ (1 to n - base.size).map(i => rankingClaim(entries + i))

  private def synthesize(
      bundle: EvidenceBundle,
      model: ModelPort
  ): SynthesisResult =
    new AnswerSynthesisAgent(model).synthesize(
      planned(question),
      bundle,
      Vector.empty
    )

  private def scripted(raw: String) =
    new ScriptedModel((r, _) =>
      if r.promptId == Prompts.Synthesis.id then Some(raw) else None
    )

  // --- the cap and the arithmetic ---

  test("the cap is the entry count plus two") {
    assertEquals(AnswerSynthesisAgent.rankingClaimCap(5), 7)
    assertEquals(AnswerSynthesisAgent.rankingClaimCap(10), 12)
  }

  test("capped replies fit the 4096 limit at the measured claim size") {
    // h4-diagnostic-3: 4096 / 18 and 4039 / 17 are about 227 and 238 tokens a
    // claim. The larger figure is used; the frame (analysis, gaps) is about 150.
    val perClaim = 238
    val frame = 150
    val five = AnswerSynthesisAgent.rankingClaimCap(5) * perClaim + frame
    val ten = AnswerSynthesisAgent.rankingClaimCap(10) * perClaim + frame
    assertEquals(five, 1816)
    assertEquals(ten, 3006)
    assert(five < AnswerSynthesisAgent.MaxOutputTokens / 2, five.toString)
    assert(ten < AnswerSynthesisAgent.MaxOutputTokens * 3 / 4, ten.toString)
    // the replies of h4-diagnostic-3 would have been over the cap
    assert(18 > AnswerSynthesisAgent.rankingClaimCap(5))
    assert(17 > AnswerSynthesisAgent.rankingClaimCap(5))
  }

  test("the cap at the planner's largest top-N fits the 4096 limit") {
    // P16-41: maxLimit entries plus two claims at 238 tokens each and the
    // 150-token frame. Derived from the config, so a larger maxLimit fails here.
    val maxEntries = PlannerConfig().maxLimit
    val claimCount = AnswerSynthesisAgent.rankingClaimCap(maxEntries)
    val tokens = claimCount * 238 + 150
    assertEquals(maxEntries, 12)
    assertEquals(claimCount, 14)
    assertEquals(tokens, 3482)
    assert(tokens < AnswerSynthesisAgent.MaxOutputTokens, tokens.toString)
    // one more entry than the planner allows would approach the limit
    assert(
      (AnswerSynthesisAgent.rankingClaimCap(20) * 238 + 150) >
        AnswerSynthesisAgent.MaxOutputTokens,
      "a top-20 cap would not fit"
    )
  }

  // --- the prompt ---

  test("the synthesis prompt is 1.2.0 and states the ranking rule") {
    assertEquals(Prompts.Synthesis.version, "1.2.0")
    val text = Prompts.Synthesis.text
    assert(text.contains("\"kind\":\"ranking\""), text)
    assert(text.contains("one claim of kind \"ranking\" for each entry"), text)
    assert(text.contains("the number of entries plus 2"), text)
    assert(text.contains("at most one definition claim"), text)
    // the short-sentence instruction is scoped to rankings (review 10, finding 1)
    assert(
      text.contains(
        "For a ranking, keep each claim \"text\" and the \"analysis\" to one short sentence."
      ),
      text
    )
    assert(!text.contains("sentence. Keep each"), text)
    // the verifier's rules stay in the prompt
    assert(text.contains("must cite its evidenceId"), text)
    assertEquals(
      Schemas.SynthesisOutputId,
      "scholarchain.rag.synthesis-output/3"
    )
  }

  // --- the check ---

  test("5 entries: 7 claims are accepted, 8 are rejected") {
    val bundle = rankingBundle(5)
    synthesize(bundle, scripted(reply(claims(7, 5)))) match
      case SynthesisResult.Done(out, attempts, _) =>
        assertEquals(out.claims.size, 7)
        assertEquals(attempts, 1)
      case other => fail(s"expected Done, got $other")
    val over = scripted(reply(claims(8, 5)))
    synthesize(bundle, over) match
      case SynthesisResult.Failed(reasons, attempts, usage) =>
        assert(reasons.contains(AnswerSynthesisAgent.TooManyRankingClaims))
        assertEquals(attempts, ModelRetryPolicy().maxAttempts)
        assert(
          usage.attempts.forall(
            _.rejections == Vector(AnswerSynthesisAgent.TooManyRankingClaims)
          ),
          usage.attempts.toString
        )
      case other => fail(s"expected Failed, got $other")
  }

  test("10 entries: 12 claims are accepted, 13 are rejected") {
    val bundle = rankingBundle(10)
    synthesize(bundle, scripted(reply(claims(12, 10)))) match
      case SynthesisResult.Done(out, _, _) => assertEquals(out.claims.size, 12)
      case other                           => fail(s"expected Done, got $other")
    synthesize(bundle, scripted(reply(claims(13, 10)))) match
      case SynthesisResult.Failed(reasons, _, _) =>
        assert(reasons.contains(AnswerSynthesisAgent.TooManyRankingClaims))
      case other => fail(s"expected Failed, got $other")
  }

  test("the reply of h4-diagnostic-3 (17 claims, 5 entries) is rejected") {
    synthesize(rankingBundle(5), scripted(reply(claims(17, 5)))) match
      case SynthesisResult.Failed(reasons, _, _) =>
        assert(reasons.contains(AnswerSynthesisAgent.TooManyRankingClaims))
      case other => fail(s"expected Failed, got $other")
  }

  test("an over-cap reply is retried with the problem, then accepted") {
    val model = new ScriptedModel((r, n) =>
      if r.promptId != Prompts.Synthesis.id then None
      else if n == 0 then Some(reply(claims(17, 5)))
      else Some(reply(claims(6, 5)))
    )
    synthesize(rankingBundle(5), model) match
      case SynthesisResult.Done(out, attempts, usage) =>
        assertEquals(attempts, 2)
        assertEquals(out.claims.size, 6)
        // an accepted attempt with no stop reason leaves no record
        assertEquals(
          usage.attempts.map(_.rejections),
          Vector(Vector(AnswerSynthesisAgent.TooManyRankingClaims))
        )
        assert(
          ujson
            .write(model.requests(1).input)
            .contains(AnswerSynthesisAgent.TooManyRankingClaims)
        )
      case other => fail(s"expected Done, got $other")
  }

  test("an over-cap reply cut at max_tokens is not retried") {
    var calls = 0
    val model = new ModelPort:
      def complete(r: ModelRequest): ModelResponse =
        calls += 1
        ModelResponse(
          reply(claims(17, 5)),
          "m/1",
          3,
          4,
          tokensEstimated = false,
          Some("max_tokens")
        )
    synthesize(rankingBundle(5), model) match
      case SynthesisResult.Failed(reasons, attempts, usage) =>
        assertEquals(attempts, 1)
        assertEquals(calls, 1)
        assert(reasons.contains(AnswerSynthesisAgent.TooManyRankingClaims))
        assertEquals(usage.attempts.size, 1)
        assertEquals(usage.attempts.head.stopReason, Some("max_tokens"))
      case other => fail(s"expected Failed, got $other")
  }

  test("the rejection reason is a fixed code") {
    assertEquals(
      RejectionReason.code(AnswerSynthesisAgent.TooManyRankingClaims),
      AnswerSynthesisAgent.TooManyRankingClaims
    )
  }

  test("a bundle without a ranking keeps the schema limit only") {
    val bundle = noRankingBundle
    synthesize(bundle, scripted(reply(claims(20, 1)))) match
      case SynthesisResult.Done(out, attempts, _) =>
        assertEquals(out.claims.size, 20)
        assertEquals(attempts, 1)
      case other => fail(s"expected Done, got $other")
    synthesize(bundle, scripted(reply(claims(25, 1)))) match
      case SynthesisResult.Failed(reasons, _, _) =>
        assert(!reasons.contains(AnswerSynthesisAgent.TooManyRankingClaims))
        assert(reasons.exists(_.contains("too many items")), reasons.toString)
      case other => fail(s"expected Failed, got $other")
  }

  // --- the workflow ---

  test("the stub's ranking answer stays within the cap and still verifies") {
    val r = ask(DemoWorld.build(), question)
    assert(!r.abstained, r.response.answer)
    val entries = InputShape.of(r.response.evidence).rankingEntries.get
    assert(entries >= 1)
    val claims = r.response.claims
    assert(
      claims.size <= AnswerSynthesisAgent.rankingClaimCap(entries),
      s"${claims.size} claims for $entries entries"
    )
    assert(
      claims.size >= entries,
      s"${claims.size} claims for $entries entries"
    )
    assert(r.verification.exists(_.materialClaims >= entries))
    assert(r.verdict.contains(VerificationVerdict.Verified), r.verdict.toString)
  }

  test("the stub's bridge and multi-metric rankings stay within the cap") {
    // BridgeAnalysis defaults to 3 metrics; a multi-metric question names two.
    val questions = Vector(
      "Which researchers are the top bridges in 2000-2009?",
      "Which researcher is the top bridge between groups in 2000-2009?",
      "Which researcher has the most distinct coauthors and the highest weighted degree in 2000-2009?"
    )
    questions.foreach { q =>
      val r = ask(DemoWorld.build(), q)
      val entries = InputShape.of(r.response.evidence).rankingEntries
      assert(entries.isDefined, s"no ranking for: $q")
      val n = entries.get
      assert(!r.abstained, s"$q: ${r.abstentionReason}")
      assert(
        r.response.claims.size <= AnswerSynthesisAgent.rankingClaimCap(n),
        s"$q: ${r.response.claims.size} claims for $n entries"
      )
      assert(
        r.response.claims.count(c => Glossary.terms.values.toSet(c.text)) <= 1,
        s"$q: definitions"
      )
    }
  }

  test("a non-ranking answer is unaffected") {
    val r = ask(
      DemoWorld.build(),
      "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"
    )
    assert(!r.abstained, r.response.answer)
    assertEquals(InputShape.of(r.response.evidence).rankingEntries, None)
    assert(r.response.claims.nonEmpty)
  }
