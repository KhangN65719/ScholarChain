package scholarchain.evaluation.retrieval

import munit.FunSuite
import scholarchain.evaluation.metrics.RetrievalMetrics

class RetrievalScoringSuite extends FunSuite:
  private def label(
      id: String,
      stratum: String,
      items: Vector[(String, Int)] = Vector.empty,
      abstain: Boolean = false,
      accepted: Vector[String] = Vector.empty,
      codes: Vector[String] = Vector.empty,
      vector: Option[Boolean] = Some(true),
      operation: Option[String] = None
  ): LabelRec =
    LabelRec(
      id,
      stratum,
      operation.getOrElse(stratum),
      items,
      Vector.empty,
      abstain,
      None,
      accepted,
      codes,
      None,
      vector,
      Vector.empty,
      None
    )

  private def hit(index: String, subject: String, distance: Double) =
    VectorHit(index, subject, distance, "researcher-profile")

  private def rec(
      id: String,
      mode: String,
      graph: Vector[String] = Vector.empty,
      deployed: Vector[VectorHit] = Vector.empty,
      direct: Vector[(Int, Vector[VectorHit])] = Vector.empty,
      abstained: Boolean = false,
      reason: Option[String] = None,
      statuses: Vector[String] = Vector.empty,
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
      1L,
      None,
      costStopped = false,
      error = None
    )

  private val Ks = Vector(2, 5, 10)

  test("graph ids are read per stratum from the deterministic facts") {
    def fact(kind: String, rest: (String, ujson.Value)*) =
      ujson.Obj.from(("kind" -> ujson.Str(kind)) +: rest)
    val identity = fact("identity", "researcherId" -> "r1")
    val metric = fact("metric", "researcherId" -> "r1")
    val coauthor =
      fact("coauthor", "researcherId" -> "r1", "coauthorId" -> "r2")
    val ranking = fact(
      "ranking",
      "entries" -> ujson.Arr(
        ujson.Obj("researcherId" -> "r9", "rank" -> 1),
        ujson.Obj("researcherId" -> "r8", "rank" -> 2)
      )
    )
    val path = fact(
      "path",
      "hops" -> ujson.Arr(
        ujson.Obj("edgeId" -> "e1"),
        ujson.Obj("edgeId" -> "e2")
      )
    )
    val community = fact("community", "researcherId" -> "r1")
    import EvidenceIds.ofFact
    assertEquals(ofFact("researcher-lookup", identity), Vector("r1"))
    assertEquals(ofFact("researcher-lookup", metric), Vector("r1"))
    assertEquals(ofFact("researcher-lookup", coauthor), Vector("r2"))
    assertEquals(ofFact("temporal-comparison", metric), Vector("r1"))
    assertEquals(ofFact("coauthor-lookup", coauthor), Vector("r2"))
    assertEquals(ofFact("coauthor-lookup", metric), Vector.empty[String])
    assertEquals(ofFact("ranking", ranking), Vector("r9", "r8"))
    assertEquals(ofFact("ranking", metric), Vector.empty[String])
    assertEquals(ofFact("shortest-path", path), Vector("e1", "e2"))
    assertEquals(ofFact("community-component", community), Vector.empty[String])
    assertEquals(ofFact("free-form", ranking), Vector("r9", "r8"))
    assertEquals(ofFact("free-form", path), Vector("e1", "e2"))
  }

  test("a free-form label is scored by the operation the human named") {
    assertEquals(
      EvidenceIds.effectiveStratum(
        label("F1", "free-form", operation = Some("coauthor-lookup"))
      ),
      "coauthor-lookup"
    )
    assertEquals(
      EvidenceIds.effectiveStratum(
        label("F2", "free-form", operation = Some("something else"))
      ),
      "free-form"
    )
    assertEquals(
      EvidenceIds.effectiveStratum(label("R1", "ranking")),
      "ranking"
    )
  }

  test("K = 2 reads the deployed hits and other K the direct searches") {
    val deployed = Vector(hit("a", "d1", 0.1), hit("a", "d2", 0.2))
    val direct = Vector(
      5 -> Vector(
        hit("a", "x1", 0.1),
        hit("a", "x2", 0.2),
        hit("a", "x3", 0.3)
      ),
      10 -> Vector.empty[VectorHit]
    )
    val v = rec("Q", "vector", deployed = deployed, direct = direct)
    assertEquals(Ranked.list(v, 2), Vector("d1", "d2"))
    assertEquals(Ranked.list(v, 5), Vector("x1", "x2", "x3"))
    assertEquals(Ranked.list(v, 10), Vector.empty[String])
  }

  test("vector hits merge across indexes by distance, distinct subjects") {
    val hits = Vector(
      hit("i1", "s1", 0.5),
      hit("i1", "s2", 0.9),
      hit("i2", "s3", 0.1),
      hit("i2", "s1", 0.6)
    )
    assertEquals(Ranked.vectorIds(hits, 10), Vector("s3", "s1", "s2"))
    assertEquals(Ranked.vectorIds(hits, 2), Vector("s3", "s1"))
  }

  test("graph mode cuts the graph ids; fused lists graph first, then vector") {
    val deployed = Vector(hit("a", "v1", 0.1), hit("a", "g2", 0.2))
    val g = rec("Q", "graph", graph = Vector("g1", "g2", "g3"))
    assertEquals(Ranked.list(g, 2), Vector("g1", "g2"))
    val f = rec(
      "Q",
      "fused",
      graph = Vector("g1", "g2", "g3"),
      deployed = deployed
    )
    assertEquals(Ranked.list(f, 2), Vector("g1", "g2"))
    val short = rec("Q", "fused", graph = Vector("g1"), deployed = deployed)
    assertEquals(Ranked.list(short, 2), Vector("g1", "v1"))
    assertEquals(
      Ranked.list(
        rec("Q", "fused", graph = Vector("g1", "g2"), deployed = deployed),
        10
      ),
      Vector("g1", "g2")
    )
  }

  test("rank metrics of a hand-built list match the metric oracle") {
    val relevant = Vector("a" -> 2, "b" -> 1)
    val l = label("Q", "ranking", items = relevant)
    val r = rec("Q", "graph", graph = Vector("a", "x", "b"))
    val s = Scoring.score(r, l, Ks)
    val list2 = Vector("a", "x")
    assertEquals(s.byK.find(_.k == 2).get.recall, 0.5)
    assertEquals(s.byK.find(_.k == 2).get.precision, 0.5)
    assertEquals(
      s.byK.find(_.k == 2).get.ndcg,
      RetrievalMetrics.ndcgAtK(list2, relevant.toMap, 2)
    )
    assertEquals(s.byK.find(_.k == 5).get.recall, 1.0)
    assertEquals(s.byK.find(_.k == 5).get.precision, 2.0 / 5.0)
    assertEquals(s.mrr, 1.0)
  }

  test("a miss is zero and a label with no graded item has no metric at all") {
    val l = label("Q", "ranking", items = Vector("a" -> 1))
    val miss = Scoring.score(rec("Q", "graph", graph = Vector("z")), l, Ks)
    assertEquals(miss.byK.head.recall, 0.0)
    assertEquals(miss.mrr, 0.0)
    val none = Scoring.score(
      rec("Q", "graph", graph = Vector("z")),
      label("Q", "ranking"),
      Ks
    )
    assert(none.byK.forall(k => k.recall.isNaN && k.precision.isNaN))
    assert(none.mrr.isNaN)
    assertEquals(none.outcome, None)
  }

  test("an unanswerable label scores the abstention and its reason code") {
    val l = label(
      "U",
      "unanswerable",
      abstain = true,
      codes = Vector("identity-unresolved")
    )
    val good = Scoring.score(
      rec(
        "U",
        "fused",
        abstained = true,
        reason = Some("identity-unresolved")
      ),
      l,
      Ks
    )
    assertEquals(good.outcome, Some(true))
    assertEquals(good.codeMatch, Some(true))
    val wrongCode = Scoring.score(
      rec("U", "graph", abstained = true, reason = Some("planner-refused")),
      l,
      Ks
    )
    assertEquals(wrongCode.outcome, Some(true))
    assertEquals(wrongCode.codeMatch, Some(false))
    val answered = Scoring.score(rec("U", "fused"), l, Ks)
    assertEquals(answered.outcome, Some(false))
    assertEquals(answered.codeMatch, None)
    val vector = Scoring.score(
      rec("U", "vector", abstained = true, reason = Some("planner-refused")),
      l,
      Ks
    )
    assertEquals(vector.outcome, Some(true))
    assertEquals(vector.codeMatch, None, "vector mode is outside the mapping")
    assert(good.byK.forall(_.recall.isNaN))
  }

  test(
    "accepted outcomes: a NotPrecomputed status or an abstention is correct"
  ) {
    val l = label(
      "P",
      "shortest-path",
      accepted = Vector("path-status:NotPrecomputed", "abstain")
    )
    def outcome(r: RankedRecord) = Scoring.score(r, l, Ks).outcome
    assertEquals(
      outcome(rec("P", "graph", statuses = Vector("NotPrecomputed"))),
      Some(true)
    )
    assertEquals(
      outcome(rec("P", "fused", abstained = true, reason = Some("x"))),
      Some(true)
    )
    assertEquals(
      outcome(rec("P", "graph", statuses = Vector("DifferentComponent"))),
      Some(false)
    )
    assertEquals(outcome(rec("P", "graph")), Some(false))
  }

  test(
    "an answerable question answered with an abstention is a false abstention"
  ) {
    val l = label("Q", "coauthor-lookup", items = Vector("a" -> 1))
    val s = Scoring.score(
      rec("Q", "fused", abstained = true, reason = Some("storage-error")),
      l,
      Ks
    )
    assert(s.falseAbstention)
    assertEquals(s.byK.head.recall, 0.0)
    val ok = Scoring.score(rec("Q", "fused", graph = Vector("a")), l, Ks)
    assert(!ok.falseAbstention)
    val pathLabel = label("P", "shortest-path", accepted = Vector("abstain"))
    assert(
      !Scoring
        .score(rec("P", "fused", abstained = true), pathLabel, Ks)
        .falseAbstention
    )
  }

  test("the summary is deterministic and keeps pooled and per-stratum means") {
    val labels = Vector(
      label("A1", "ranking", items = Vector("a" -> 1)),
      label("A2", "ranking", items = Vector("b" -> 1)),
      label("B1", "coauthor-lookup", items = Vector("c" -> 1))
    )
    val scored = Vector(
      rec("A1", "graph", graph = Vector("a")),
      rec("A2", "graph", graph = Vector("x")),
      rec("B1", "graph", graph = Vector("c"))
    ).zip(labels).map((r, l) => Scoring.score(r, l, Ks))
    val one = Aggregate.summarize(scored, Ks, 200, 7L)
    val two = Aggregate.summarize(scored, Ks, 200, 7L)
    assertEquals(ujson.write(one), ujson.write(two))
    val all = one("graph")("scopes")("all")
    assertEquals(all("pooled")("questions").num.toInt, 3)
    assertEqualsDouble(
      all("pooled")("metrics")("recall@2")("mean").num,
      2.0 / 3.0,
      1e-9
    )
    assertEqualsDouble(
      all("ranking")("metrics")("recall@2")("mean").num,
      0.5,
      1e-9
    )
    assertEqualsDouble(
      all("coauthor-lookup")("metrics")("recall@2")("mean").num,
      1.0,
      1e-9
    )
    val ci = all("pooled")("metrics")("recall@2")
    assert(ci("low").num <= ci("mean").num && ci("mean").num <= ci("high").num)
    assertEquals(ci("n").num.toInt, 3)
  }

  test("the vectorAvailable scope leaves out questions without a vector") {
    val labels = Vector(
      label("A1", "ranking", items = Vector("a" -> 1), vector = Some(true)),
      label("A2", "ranking", items = Vector("b" -> 1), vector = Some(false)),
      label("A3", "ranking", items = Vector("c" -> 1), vector = None)
    )
    val scored = labels
      .zip(Vector("a", "x", "x"))
      .map((l, id) =>
        Scoring.score(rec(l.questionId, "graph", graph = Vector(id)), l, Ks)
      )
    val s = Aggregate.summarize(scored, Ks, 50, 1L)
    assertEquals(
      s("graph")("scopes")("all")("pooled")("questions").num.toInt,
      3
    )
    assertEquals(
      s("graph")("scopes")("vectorAvailable")("pooled")("questions").num.toInt,
      1
    )
  }

  test(
    "a question's value is its mean over repeats, and repeats are compared"
  ) {
    val l = label("Q", "ranking", items = Vector("a" -> 1))
    val scored = Vector(
      Scoring.score(rec("Q", "graph", graph = Vector("a"), repeat = 1), l, Ks),
      Scoring.score(rec("Q", "graph", graph = Vector("z"), repeat = 2), l, Ks)
    )
    val s = Aggregate.summarize(scored, Ks, 50, 1L)
    val pooled = s("graph")("scopes")("all")("pooled")
    assertEquals(pooled("questions").num.toInt, 1)
    assertEquals(pooled("metrics")("recall@2")("mean").num, 0.5)
    assertEquals(s("graph")("perRepeat").arr.size, 2)
    val stability = s("graph")("repeatStability")
    assertEquals(stability("questionsWithRepeats").num.toInt, 1)
    assertEquals(stability("identicalGraphIds").num, 0.0)
    assertEquals(stability("identicalAbstention").num, 1.0)
  }

  test("outcome rates count abstention recall, codes and false abstentions") {
    val un = label("U", "unanswerable", abstain = true, codes = Vector("c1"))
    val an = label("A", "coauthor-lookup", items = Vector("a" -> 1))
    val scored = Vector(
      Scoring.score(
        rec("U", "fused", abstained = true, reason = Some("c1")),
        un,
        Ks
      ),
      Scoring.score(
        rec("U", "fused", abstained = true, reason = Some("c2")),
        un,
        Ks
      ),
      Scoring.score(rec("U", "fused"), un, Ks),
      Scoring.score(rec("A", "fused", abstained = true), an, Ks),
      Scoring.score(rec("A", "fused", graph = Vector("a")), an, Ks)
    )
    val o = Aggregate.summarize(scored, Ks, 20, 1L)("fused")("outcomes")
    assertEquals(o("unanswerableRecords").num.toInt, 3)
    assertEqualsDouble(o("abstentionRecall").num, 2.0 / 3.0, 1e-9)
    assertEqualsDouble(o("reasonCodeMatchAmongAbstained").num, 0.5, 1e-9)
    assertEqualsDouble(o("falseAbstentionRate").num, 0.5, 1e-9)
  }

  test("a raw record survives its JSON form") {
    val r = rec(
      "Q",
      "fused",
      graph = Vector("g"),
      deployed = Vector(hit("i", "s", 0.25)),
      direct = Vector(5 -> Vector(hit("i", "t", 0.5))),
      abstained = true,
      reason = Some("storage-error"),
      statuses = Vector("NotPrecomputed"),
      repeat = 2
    )
    assertEquals(RankedRecord.fromJson(ujson.read(ujson.write(r.toJson))), r)
  }

  test("a stratified subset keeps every stratum, in file order") {
    val items = Vector("a1", "a2", "a3", "b1", "b2", "c1")
    val picked = Subsets.stratified(items, _.take(1), 4)
    assertEquals(picked, Vector("a1", "a2", "b1", "c1"))
    assertEquals(Subsets.stratified(items, _.take(1), 10), items)
    assertEquals(
      Subsets.stratified(items, _.take(1), 3),
      Vector("a1", "b1", "c1")
    )
  }

  test("questions and labels that do not pair are refused") {
    val q = Vector(QuestionRec("A", "t"), QuestionRec("B", "t"))
    val l = Vector(label("A", "ranking"))
    intercept[IllegalArgumentException](BenchmarkFiles.pair(q, l))
    intercept[IllegalArgumentException](
      BenchmarkFiles.pair(q.take(1), l ++ Vector(label("C", "ranking")))
    )
    intercept[IllegalArgumentException](
      BenchmarkFiles.pair(q.take(1) ++ q.take(1), l)
    )
    assertEquals(BenchmarkFiles.pair(q.take(1), l).size, 1)
  }
