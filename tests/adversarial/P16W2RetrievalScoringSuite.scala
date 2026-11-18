package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.evaluation.retrieval.{
  Aggregate,
  EvidenceIds,
  LabelRec,
  Ranked,
  RankedRecord,
  RetrievalEval,
  Scoring,
  VectorHit
}

/** Oracle tests of the RetrievalEval scoring on hand-built retrieved lists,
  * abstention-aware, from plan.md and experiments/benchmark/README.md:
  * Recall@K, Precision@K, MRR and nDCG@K with gain 2^g - 1, tiered ranking
  * grades (P16-10), `acceptedOutcomes`, `expectedCodes`, `NotPrecomputed`, and
  * a free-form label whose `operation` names a stratum.
  *
  * Every expected number is worked out below from the definitions, with log2
  * written out, not copied from the scorer.
  */
class P16W2RetrievalScoringSuite extends FunSuite:
  private val eps = 1e-9
  private def log2(x: Double): Double = math.log(x) / math.log(2.0)
  private val ks = Vector(2, 5)

  private def label(
      id: String = "Q",
      stratum: String = "ranking",
      operation: String = "ranking",
      items: Vector[(String, Int)] = Vector.empty,
      abstain: Boolean = false,
      accepted: Vector[String] = Vector.empty,
      codes: Vector[String] = Vector.empty,
      vectorAvailable: Option[Boolean] = Some(true)
  ): LabelRec =
    LabelRec(
      id,
      stratum,
      operation,
      items,
      Vector.empty,
      abstain,
      None,
      accepted,
      codes,
      None,
      vectorAvailable,
      Vector.empty,
      None
    )

  private def hit(index: String, id: String, d: Double): VectorHit =
    VectorHit(index, id, d, "summary")

  private def rec(
      mode: String = "graph",
      graph: Vector[String] = Vector.empty,
      deployed: Vector[VectorHit] = Vector.empty,
      direct: Vector[(Int, Vector[VectorHit])] = Vector.empty,
      abstained: Boolean = false,
      reason: Option[String] = None,
      statuses: Vector[String] = Vector.empty,
      id: String = "Q",
      repeat: Int = 1
  ): RankedRecord =
    RankedRecord(
      id,
      mode,
      repeat,
      abstained,
      reason,
      graph,
      statuses,
      deployed,
      direct,
      graph.size,
      None,
      None,
      0,
      0L,
      None,
      costStopped = false,
      error = None
    )

  // A ranking label of six: ranks 1-3 grade 3 (gain 7), ranks 4-6 grade 2 (gain 3).
  private val tiered = Vector(
    "r1" -> 3,
    "r2" -> 3,
    "r3" -> 3,
    "r4" -> 2,
    "r5" -> 2,
    "r6" -> 2
  )
  private val rankingLabel = label(items = tiered)
  private val ideal = Vector(7.0, 7.0, 7.0, 3.0, 3.0, 3.0)

  private def dcg(gains: Seq[Double]): Double =
    gains.zipWithIndex.map((g, i) => g / log2(i + 2.0)).sum

  // ---- graph mode: tiered grades -------------------------------------------------

  private val graphList = Vector("r2", "x", "r1", "r6", "y", "r4")
  private val graphRec = rec(graph = graphList)

  test("graph mode ranks by the evidence order and cuts at K") {
    assertEquals(Ranked.list(graphRec, 2), Vector("r2", "x"))
    assertEquals(Ranked.list(graphRec, 5), Vector("r2", "x", "r1", "r6", "y"))
    assertEquals(Ranked.list(graphRec, 99), graphList)
  }

  test("Recall@K and Precision@K at K = 2 and 5 on the tiered ranking label") {
    val s = Scoring.score(graphRec, rankingLabel, ks)
    // K=2: only r2 is relevant: 1 of 6; 1 of 2.
    assertEqualsDouble(s.byK(0).recall, 1.0 / 6.0, eps)
    assertEqualsDouble(s.byK(0).precision, 1.0 / 2.0, eps)
    // K=5: r2, r1, r6 relevant: 3 of 6; 3 of 5.
    assertEqualsDouble(s.byK(1).recall, 3.0 / 6.0, eps)
    assertEqualsDouble(s.byK(1).precision, 3.0 / 5.0, eps)
  }

  test("nDCG@K uses gain 2^grade - 1 with the tiered grades") {
    val s = Scoring.score(graphRec, rankingLabel, ks)
    // K=2: gains [7, 0]; ideal [7, 7].
    assertEqualsDouble(
      s.byK(0).ndcg,
      dcg(Seq(7.0, 0.0)) / dcg(ideal.take(2)),
      eps
    )
    // K=5: gains [7, 0, 7, 3, 0]; ideal [7, 7, 7, 3, 3].
    assertEqualsDouble(
      s.byK(1).ndcg,
      dcg(Seq(7.0, 0.0, 7.0, 3.0, 0.0)) / dcg(ideal.take(5)),
      eps
    )
  }

  test("tiering matters: a grade 2 hit scores below a grade 3 hit at rank 1") {
    val high = Scoring.score(rec(graph = Vector("r1", "x")), rankingLabel, ks)
    val low = Scoring.score(rec(graph = Vector("r5", "x")), rankingLabel, ks)
    assertEqualsDouble(
      high.byK(0).ndcg,
      7.0 / dcg(ideal.take(2)),
      eps
    )
    assertEqualsDouble(
      low.byK(0).ndcg,
      3.0 / dcg(ideal.take(2)),
      eps
    )
    assert(high.byK(0).ndcg > low.byK(0).ndcg)
    // Recall and precision do not see the tiers.
    assertEqualsDouble(high.byK(0).recall, low.byK(0).recall, eps)
  }

  test(
    "MRR is the reciprocal rank of the first relevant id up to the largest K"
  ) {
    assertEqualsDouble(
      Scoring
        .score(rec(graph = Vector("x", "y", "r3", "r1")), rankingLabel, ks)
        .mrr,
      1.0 / 3.0,
      eps
    )
    assertEqualsDouble(Scoring.score(graphRec, rankingLabel, ks).mrr, 1.0, eps)
    // Relevant only at rank 6, beyond the largest K of 5: no credit.
    val late = rec(graph = Vector("x", "y", "z", "w", "v", "r1"))
    assertEqualsDouble(Scoring.score(late, rankingLabel, ks).mrr, 0.0, eps)
  }

  test("a perfect retrieval scores 1 on every rank metric at K = 5") {
    val perfect = rec(graph = Vector("r1", "r2", "r3", "r4", "r5", "r6"))
    val s = Scoring.score(perfect, rankingLabel, ks)
    assertEqualsDouble(s.byK(1).ndcg, 1.0, eps)
    assertEqualsDouble(s.byK(1).precision, 1.0, eps)
    assertEqualsDouble(s.mrr, 1.0, eps)
  }

  test("a repeated label id keeps its highest grade") {
    val l = label(items = Vector("a" -> 1, "a" -> 3, "b" -> 1))
    assertEquals(l.relevant, Map("a" -> 3, "b" -> 1))
  }

  // ---- vector and fused lists ------------------------------------------------------

  private val deployed = Vector(
    hit("profile", "s1", 0.10),
    hit("profile", "s2", 0.40),
    hit("path", "s3", 0.20),
    hit("path", "s1", 0.30),
    hit("path", "", 0.05)
  )

  test("vector mode merges the indexes by distance, dedupes, drops empty ids") {
    // distance order: "" (0.05, dropped), s1 .10, s3 .20, s1 .30 (dup), s2 .40
    assertEquals(Ranked.vectorIds(deployed, 10), Vector("s1", "s3", "s2"))
    assertEquals(Ranked.vectorIds(deployed, 2), Vector("s1", "s3"))
  }

  test("equal distances keep the order the hits arrived in") {
    val tied =
      Vector(hit("a", "x", 0.5), hit("b", "y", 0.5), hit("a", "z", 0.5))
    assertEquals(Ranked.vectorIds(tied, 3), Vector("x", "y", "z"))
  }

  test(
    "K = 2 reads the deployed hits, another K reads the direct search at K"
  ) {
    val direct = Vector(
      5 -> Vector(
        hit("p", "d1", 0.1),
        hit("p", "d2", 0.2),
        hit("p", "d3", 0.3)
      ),
      10 -> Vector(hit("p", "e1", 0.1))
    )
    val r = rec(mode = "vector", deployed = deployed, direct = direct)
    assertEquals(RetrievalEval.DeployedK, 2)
    assertEquals(Ranked.list(r, 2), Vector("s1", "s3"))
    assertEquals(Ranked.list(r, 5), Vector("d1", "d2", "d3"))
    assertEquals(Ranked.list(r, 10), Vector("e1"))
    assertEquals(
      Ranked.list(r, 7),
      Vector.empty[String],
      "no direct search at 7"
    )
  }

  test("vector mode scores against the labelled ids") {
    val l =
      label(items = Vector("s1" -> 3, "s9" -> 1), stratum = "researcher-lookup")
    val r = rec(mode = "vector", deployed = deployed)
    val s = Scoring.score(r, l, Vector(2))
    // list [s1, s3]: one of two relevant, precision 1/2, reciprocal rank 1.
    assertEqualsDouble(s.byK(0).recall, 0.5, eps)
    assertEqualsDouble(s.byK(0).precision, 0.5, eps)
    assertEqualsDouble(s.mrr, 1.0, eps)
    assertEqualsDouble(s.byK(0).ndcg, 7.0 / (7.0 + 1.0 / log2(3.0)), eps)
  }

  test("fused mode lists the graph ids first, then the vector ids, deduped") {
    val vector =
      Vector(hit("a", "g2", 0.1), hit("a", "v1", 0.2), hit("a", "v2", 0.3))
    val r = rec(
      mode = "fused",
      graph = Vector("g1", "g2"),
      deployed = vector,
      direct = Vector(3 -> vector, 10 -> vector)
    )
    assertEquals(Ranked.list(r, 2), Vector("g1", "g2"))
    assertEquals(Ranked.list(r, 3), Vector("g1", "g2", "v1"))
    assertEquals(Ranked.list(r, 10), Vector("g1", "g2", "v1", "v2"))
  }

  // ---- abstention-aware scoring ------------------------------------------------------

  private val unanswerable =
    label(
      stratum = "unanswerable",
      abstain = true,
      codes = Vector("identity-unresolved", "no-deterministic-evidence"),
      vectorAvailable = None
    )

  test("an unanswerable label has no rank metric: every value is NaN") {
    val s = Scoring.score(
      rec(abstained = true, reason = Some("planner-refused")),
      unanswerable,
      ks
    )
    assert(s.mrr.isNaN)
    assert(
      s.byK.forall(k => k.recall.isNaN && k.precision.isNaN && k.ndcg.isNaN)
    )
  }

  test(
    "an unanswerable question is correct exactly when the answer abstained"
  ) {
    val abstained =
      Scoring.score(rec(abstained = true, reason = Some("x")), unanswerable, ks)
    val answered = Scoring.score(rec(abstained = false), unanswerable, ks)
    assertEquals(abstained.outcome, Some(true))
    assertEquals(answered.outcome, Some(false))
    assert(!abstained.falseAbstention && !answered.falseAbstention)
  }

  test(
    "the reason code is matched against expectedCodes in graph and fused mode"
  ) {
    List("graph", "fused").foreach { mode =>
      val good = Scoring.score(
        rec(
          mode = mode,
          abstained = true,
          reason = Some("identity-unresolved")
        ),
        unanswerable,
        ks
      )
      val other = Scoring.score(
        rec(mode = mode, abstained = true, reason = Some("planner-refused")),
        unanswerable,
        ks
      )
      val none = Scoring.score(
        rec(mode = mode, abstained = true, reason = None),
        unanswerable,
        ks
      )
      assertEquals(good.codeMatch, Some(true), mode)
      assertEquals(other.codeMatch, Some(false), mode)
      assertEquals(none.codeMatch, Some(false), mode)
    }
  }

  test("an answer that did not abstain has no code match to score") {
    val s = Scoring.score(rec(abstained = false), unanswerable, ks)
    assertEquals(s.codeMatch, None)
  }

  test(
    "vector mode is outside the code mapping: no code match even if it abstained"
  ) {
    val s = Scoring.score(
      rec(
        mode = "vector",
        abstained = true,
        reason = Some("identity-unresolved")
      ),
      unanswerable,
      ks
    )
    assertEquals(s.outcome, Some(true))
    assertEquals(s.codeMatch, None)
  }

  private val notPrecomputed = label(
    stratum = "shortest-path",
    operation = "shortest-path",
    accepted = Vector("path-status:NotPrecomputed", "abstain"),
    vectorAvailable = Some(true)
  )

  test("NotPrecomputed: a path-status item of that kind is a correct outcome") {
    val s = Scoring.score(
      rec(statuses = Vector("NotPrecomputed")),
      notPrecomputed,
      ks
    )
    assertEquals(s.outcome, Some(true))
    assert(s.mrr.isNaN && s.byK.forall(_.recall.isNaN))
  }

  test(
    "NotPrecomputed: an abstention is also correct, and is not a false abstention"
  ) {
    val s = Scoring.score(
      rec(abstained = true, reason = Some("no-deterministic-evidence")),
      notPrecomputed,
      ks
    )
    assertEquals(s.outcome, Some(true))
    assert(!s.falseAbstention)
    assertEquals(s.codeMatch, None)
  }

  test("NotPrecomputed: neither a status nor an abstention is wrong") {
    assertEquals(
      Scoring.score(rec(), notPrecomputed, ks).outcome,
      Some(false)
    )
    // Some other status does not satisfy the accepted outcomes.
    assertEquals(
      Scoring
        .score(rec(statuses = Vector("Reachable")), notPrecomputed, ks)
        .outcome,
      Some(false)
    )
  }

  test(
    "a path claimed with hops where the label accepts only a status is wrong"
  ) {
    val s = Scoring.score(rec(graph = Vector("e1", "e2")), notPrecomputed, ks)
    assertEquals(s.outcome, Some(false))
  }

  test(
    "an answerable question has no outcome; an abstention is a false abstention"
  ) {
    val answerable = label(items = Vector("r1" -> 3))
    val ok = Scoring.score(rec(graph = Vector("r1")), answerable, ks)
    val refused =
      Scoring.score(rec(abstained = true, reason = Some("x")), answerable, ks)
    assertEquals(ok.outcome, None)
    assert(!ok.falseAbstention)
    assertEquals(refused.outcome, None)
    assert(refused.falseAbstention)
  }

  test(
    "an answerable label keeps its rank metrics even when the answer abstained"
  ) {
    val answerable = label(items = Vector("r1" -> 3))
    val s =
      Scoring.score(rec(abstained = true, reason = Some("x")), answerable, ks)
    assertEqualsDouble(s.byK(0).recall, 0.0, eps)
    assertEqualsDouble(s.mrr, 0.0, eps)
  }

  // ---- free-form labels: the operation is a stratum name -------------------------

  private def freeForm(op: String) =
    label(stratum = "free-form", operation = op, items = Vector("r1" -> 3))

  test("a free-form label is scored as the stratum its operation names") {
    List(
      "researcher-lookup",
      "coauthor-lookup",
      "ranking",
      "shortest-path",
      "community-component",
      "temporal-comparison"
    ).foreach { op =>
      assertEquals(EvidenceIds.effectiveStratum(freeForm(op)), op)
    }
  }

  test("a free-form operation that is no stratum name stays free-form") {
    List("community", "component", "collaboration-metrics", "", "whatever")
      .foreach { op =>
        assertEquals(
          EvidenceIds.effectiveStratum(freeForm(op)),
          "free-form",
          op
        )
      }
  }

  test("a generated label is scored by its stratum, never by its operation") {
    val generated =
      label(stratum = "ranking", operation = "community-component")
    assertEquals(EvidenceIds.effectiveStratum(generated), "ranking")
    val odd = label(stratum = "unanswerable", operation = "ranking")
    assertEquals(EvidenceIds.effectiveStratum(odd), "unanswerable")
  }

  private def rankFact =
    ujson.Obj(
      "kind" -> "ranking",
      "entries" -> ujson.Arr(
        ujson.Obj("rank" -> 1, "researcherId" -> "r1"),
        ujson.Obj("rank" -> 2, "researcherId" -> "r2")
      )
    )
  private def pathFact =
    ujson.Obj(
      "kind" -> "path",
      "hops" -> ujson.Arr(
        ujson.Obj("edgeId" -> "e1"),
        ujson.Obj("edgeId" -> "e2")
      )
    )
  private def coFact =
    ujson.Obj("kind" -> "coauthor", "researcherId" -> "a", "coauthorId" -> "b")
  private def metricFact = ujson.Obj("kind" -> "metric", "researcherId" -> "a")
  private def identityFact =
    ujson.Obj("kind" -> "identity", "researcherId" -> "a")
  private def groupFact =
    ujson.Obj("kind" -> "community", "researcherId" -> "a")

  test("the ids a fact asserts depend on the stratum it is scored as") {
    assertEquals(EvidenceIds.ofFact("ranking", rankFact), Vector("r1", "r2"))
    assertEquals(EvidenceIds.ofFact("ranking", coFact), Vector.empty[String])
    assertEquals(
      EvidenceIds.ofFact("shortest-path", pathFact),
      Vector("e1", "e2")
    )
    assertEquals(
      EvidenceIds.ofFact("shortest-path", rankFact),
      Vector.empty[String]
    )
    assertEquals(EvidenceIds.ofFact("coauthor-lookup", coFact), Vector("b"))
    assertEquals(
      EvidenceIds.ofFact("coauthor-lookup", metricFact),
      Vector.empty[String]
    )
    assertEquals(
      EvidenceIds.ofFact("researcher-lookup", metricFact),
      Vector("a")
    )
    assertEquals(
      EvidenceIds.ofFact("researcher-lookup", identityFact),
      Vector("a")
    )
    assertEquals(
      EvidenceIds.ofFact("temporal-comparison", metricFact),
      Vector("a")
    )
  }

  test("community-component retrieves no member ids in graph mode") {
    assertEquals(
      EvidenceIds.ofFact("community-component", groupFact),
      Vector.empty[String]
    )
    assertEquals(
      EvidenceIds.ofFact("community-component", metricFact),
      Vector.empty[String]
    )
  }

  test(
    "a free-form label with an unknown operation takes every id-bearing kind"
  ) {
    List(
      rankFact -> Vector("r1", "r2"),
      pathFact -> Vector("e1", "e2"),
      coFact -> Vector("b"),
      metricFact -> Vector("a")
    )
      .foreach { (f, ids) =>
        assertEquals(EvidenceIds.ofFact("free-form", f), ids)
      }
  }

  // ---- aggregation: pooled means over questions, outcomes ---------------------------

  private def scored(r: RankedRecord, l: LabelRec) = Scoring.score(r, l, ks)

  private val lA = label(id = "A", items = Vector("a1" -> 3, "a2" -> 3))
  private val lB =
    label(id = "B", items = Vector("b1" -> 2, "b2" -> 2, "b3" -> 2, "b4" -> 2))
  private val lU = unanswerable.copy(questionId = "U")
  private val lP = notPrecomputed.copy(questionId = "P")

  private val records = Vector(
    // A, repeat 1: both relevant in the top 2: recall@2 = 1. Repeat 2: one: 0.5.
    scored(rec(id = "A", graph = Vector("a1", "a2"), repeat = 1), lA),
    scored(rec(id = "A", graph = Vector("a1", "z"), repeat = 2), lA),
    // B: one of four in the top 2: recall@2 = 0.25.
    scored(rec(id = "B", graph = Vector("b1", "z"), repeat = 1), lB),
    scored(
      rec(id = "U", abstained = true, reason = Some("identity-unresolved")),
      lU
    ),
    scored(rec(id = "P", statuses = Vector("NotPrecomputed")), lP)
  )

  private lazy val summary =
    Aggregate.summarize(records, ks, resamples = 200, seed = 7L)("graph")

  test(
    "the pooled mean is over questions, each question the mean of its repeats"
  ) {
    val m = summary("scopes")("all")("pooled")("metrics")("recall@2")
    // A: (1 + 0.5) / 2 = 0.75; B: 0.25; pooled (0.75 + 0.25) / 2 = 0.5.
    assertEqualsDouble(m("mean").num, 0.5, eps)
    assertEquals(
      m("n").num.toInt,
      2,
      "unanswerable and accepted-outcome labels are not counted"
    )
    assertEquals(summary("scopes")("all")("pooled")("questions").num.toInt, 4)
  }

  test("a stratum with its own questions gets its own mean") {
    val m = summary("scopes")("all")("ranking")("metrics")("recall@2")
    assertEqualsDouble(m("mean").num, 0.5, eps)
  }

  test("the interval brackets the mean and the summary is reproducible") {
    val m = summary("scopes")("all")("pooled")("metrics")("recall@2")
    assert(m("low").num <= m("mean").num + eps)
    assert(m("high").num >= m("mean").num - eps)
    val again =
      Aggregate.summarize(records, ks, resamples = 200, seed = 7L)("graph")
    assertEquals(ujson.write(again), ujson.write(summary))
  }

  test(
    "the outcome block reports abstention recall, code match and false abstention"
  ) {
    val o = summary("outcomes")
    assertEquals(o("unanswerableRecords").num.toInt, 1)
    assertEqualsDouble(o("abstentionRecall").num, 1.0, eps)
    assertEqualsDouble(o("reasonCodeMatchAmongAbstained").num, 1.0, eps)
    assertEquals(o("acceptedOutcomeRecords").num.toInt, 1)
    assertEqualsDouble(o("acceptedOutcomeCorrect").num, 1.0, eps)
    assertEquals(o("answerableRecords").num.toInt, 3)
    assertEqualsDouble(o("falseAbstentionRate").num, 0.0, eps)
  }

  test("the vectorAvailable scope keeps only labels marked available") {
    val noVector = lB.copy(vectorAvailable = Some(false))
    val rs = Vector(
      scored(rec(id = "A", graph = Vector("a1", "a2")), lA),
      scored(rec(id = "B", graph = Vector("z", "z2")), noVector)
    )
    val s = Aggregate.summarize(rs, ks, 50, 1L)("graph")("scopes")
    assertEquals(s("all")("pooled")("questions").num.toInt, 2)
    assertEquals(s("vectorAvailable")("pooled")("questions").num.toInt, 1)
    assertEqualsDouble(
      s("vectorAvailable")("pooled")("metrics")("recall@2")("mean").num,
      1.0,
      eps
    )
  }

  test("a false abstention over answerable labels is counted") {
    val rs = Vector(
      scored(rec(id = "A", abstained = true, reason = Some("x")), lA),
      scored(rec(id = "B", graph = Vector("b1")), lB)
    )
    val o = Aggregate.summarize(rs, ks, 20, 1L)("graph")("outcomes")
    assertEquals(o("answerableRecords").num.toInt, 2)
    assertEqualsDouble(o("falseAbstentionRate").num, 0.5, eps)
  }

  test("the confidence interval is deterministic for a seed and key") {
    val v = Vector(0.0, 0.25, 0.5, 1.0, 1.0, 0.75)
    val a = Aggregate.ci(v, 300, 11L, "k")
    val b = Aggregate.ci(v, 300, 11L, "k")
    assertEquals((a.mean, a.low, a.high, a.n), (b.mean, b.low, b.high, b.n))
    assertEqualsDouble(a.mean, v.sum / v.size, eps)
    assertEquals(a.n, 6)
  }

  test("NaN values are left out of a mean and out of n") {
    val c = Aggregate.ci(Vector(1.0, Double.NaN, 0.0), 50, 1L, "k")
    assertEquals(c.n, 2)
    assertEqualsDouble(c.mean, 0.5, eps)
  }
