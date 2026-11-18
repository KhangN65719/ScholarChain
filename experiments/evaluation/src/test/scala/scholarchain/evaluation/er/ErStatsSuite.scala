package scholarchain.evaluation.er

import munit.FunSuite
import scholarchain.evaluation.metrics.Bootstrap

class ErStatsSuite extends FunSuite:
  import ErFixtures.items

  private val tol = 1e-12

  test(
    "tally follows GoldEvaluation: unresolved, conflict and missing count in the recall denominator"
  ):
    val t = Tally.of(items)
    assertEquals(
      (t.truePositive, t.falsePositive, t.same, t.different, t.unsure),
      (5L, 1L, 11L, 4L, 1L)
    )
    assertEquals(t.n, 16L)
    assertEqualsDouble(t.precision.get, 5.0 / 6.0, tol)
    assertEqualsDouble(t.recall.get, 5.0 / 11.0, tol)
    val (p, r) = (5.0 / 6.0, 5.0 / 11.0)
    assertEqualsDouble(t.f1.get, 2 * p * r / (p + r), tol)

  test("a tally with nothing accepted has undefined precision and F1"):
    val none = items.map(_.copy(decision = None))
    val t = Tally.of(none)
    assertEquals(t.precision, None)
    assertEquals(t.recall, Some(0.0))
    assertEquals(t.f1, None)

  test("per-path recall counts gold-same pairs by the path that decided them"):
    val pp = PathRecall.compute(items)
    val byPath = pp.rows.map(r => r.path -> r).toMap
    assertEquals(pp.rows.map(_.path), PathRecall.KnownPaths)
    val exact = byPath("exact-id")
    assertEquals(
      (exact.same, exact.sameAccepted, exact.sameConflict, exact.different),
      (4L, 3L, 1L, 1L)
    )
    assertEqualsDouble(exact.recall.get, 0.75, tol)
    assertEqualsDouble(exact.precision.get, 1.0, tol)
    val det = byPath("deterministic-bibliographic")
    assertEquals(
      (det.same, det.sameAccepted, det.differentAccepted),
      (1L, 1L, 1L)
    )
    assertEqualsDouble(det.recall.get, 1.0, tol)
    assertEqualsDouble(det.precision.get, 0.5, tol)
    val prob = byPath("probabilistic")
    assertEquals(
      (
        prob.same,
        prob.sameAccepted,
        prob.sameRejected,
        prob.sameUnresolved,
        prob.sameConflict,
        prob.different,
        prob.unsure
      ),
      (5L, 1L, 2L, 1L, 1L, 2L, 1L)
    )
    assertEqualsDouble(prob.recall.get, 0.2, tol)
    assertEquals(prob.precision, Some(1.0))

  test("a path that decided nothing reports recall null, not zero"):
    val llm =
      PathRecall.compute(items).rows.find(_.path == "llm-adjudicated").get
    assertEquals((llm.pairs, llm.same), (0L, 0L))
    assertEquals(llm.recall, None)
    assertEquals(llm.recallInterval, None)
    assertEquals(llm.contribution, Some(0.0))

  test(
    "gold-same pairs with no decision are counted apart and path contributions sum to pooled recall"
  ):
    val pp = PathRecall.compute(items)
    assertEquals(pp.sameWithoutDecision, 1L)
    assertEquals(pp.totalSame, 11L)
    val decided = pp.rows.map(_.same).sum
    assertEquals(decided + pp.sameWithoutDecision, pp.totalSame)
    val summed = pp.rows.flatMap(_.contribution).sum
    assertEqualsDouble(summed, Tally.of(items).recall.get, tol)

  test("a path outside the four known paths is listed after them"):
    val odd = items.map(i =>
      if i.pairId == "c1" then
        i.copy(decision = i.decision.map(_.copy(path = "zzz-new")))
      else i
    )
    val rows = PathRecall.compute(odd).rows
    assertEquals(rows.map(_.path), PathRecall.KnownPaths :+ "zzz-new")

  test("Wilson interval matches the closed form"):
    val (lo, hi) = Wilson.interval(3, 4).get
    // z = 1.96, p = 0.75, n = 4
    val z = 1.96
    val denom = 1 + z * z / 4
    val centre = (0.75 + z * z / 8) / denom
    val half = z * math.sqrt(0.75 * 0.25 / 4 + z * z / 64.0) / denom
    assertEqualsDouble(lo, centre - half, tol)
    assertEqualsDouble(hi, math.min(1.0, centre + half), tol)
    assertEquals(Wilson.interval(0, 0), None)

  test("stratified draws keep every stratum at its own size"):
    val strata = Map(
      "a" -> Vector.fill(3)("a"),
      "b" -> Vector.fill(5)("b"),
      "empty" -> Vector.empty[String]
    )
    val draws = StratifiedBootstrap.draws(strata, 40, 7L)(sample =>
      Vector(
        sample.count(_ == "a").toDouble,
        sample.count(_ == "b").toDouble
      )
    )
    assertEquals(draws.size, 40)
    assert(draws.forall(_ == Vector(3.0, 5.0)))

  test("stratified draws replay the documented algorithm"):
    val strata = Map(
      "b" -> Vector(1.0, 2.0, 3.0, 4.0),
      "a" -> Vector(10.0, 20.0, 30.0)
    )
    val got = StratifiedBootstrap.draws(strata, 25, 99L)(s => Vector(s.sum))
    val rng = new scala.util.Random(99L)
    val expected = Vector.fill(25) {
      var sum = 0.0
      for group <- Vector(strata("a"), strata("b")) do
        for _ <- group.indices do sum += group(rng.nextInt(group.size))
      Vector(sum)
    }
    assertEquals(got, expected)

  test("with one stratum and the mean it equals Bootstrap.meanCi"):
    val values = Vector(0.0, 1.0, 1.0, 0.0, 1.0, 0.5, 0.25, 0.75, 1.0, 0.0)
    val draws = StratifiedBootstrap.draws(Map("s" -> values), 200, 5L)(v =>
      Vector(v.sum / v.size)
    )
    val point = values.sum / values.size
    val mine = StratifiedBootstrap.interval(point, draws.map(_.head), 0.95)
    val (mean, low, high) = Bootstrap.meanCi(values, 200, 5L, 0.95)
    assertEqualsDouble(mine.point, mean, tol)
    assertEqualsDouble(mine.low, low, tol)
    assertEqualsDouble(mine.high, high, tol)

  test(
    "interval drops NaN, uses the Bootstrap index rule and counts the dropped"
  ):
    val values = (1 to 10).map(_.toDouble) :+ Double.NaN
    val i = StratifiedBootstrap.interval(5.5, values, 0.8)
    // m = 10, alpha = 0.2: low index floor(1.0) = 1, high index ceil(9.0) - 1 = 8
    assertEquals((i.low, i.high, i.used, i.undefined), (2.0, 9.0, 10, 1))
    val allNan = StratifiedBootstrap.interval(0.0, Seq(Double.NaN), 0.95)
    assertEquals((allNan.used, allNan.undefined), (0, 1))
    assert(allNan.low.isNaN && allNan.high.isNaN)

  test(
    "pooled bootstrap is deterministic for a seed and changes with the seed"
  ):
    val one = ErBootstrap.pooled(items, 300, 16L, 0.95)
    val again = ErBootstrap.pooled(items, 300, 16L, 0.95)
    val other = ErBootstrap.pooled(items, 300, 17L, 0.95)
    assertEquals(
      one.draws.map(_.map(_.toString)),
      again.draws.map(_.map(_.toString))
    )
    assertEquals(one.intervals, again.intervals)
    assertNotEquals(
      one.draws.map(_.map(_.toString)),
      other.draws.map(_.map(_.toString))
    )

  test("pooled bootstrap does not depend on the order of the input rows"):
    val a = ErBootstrap.pooled(items, 100, 3L, 0.95)
    val b = ErBootstrap.pooled(items.reverse, 100, 3L, 0.95)
    assertEquals(a.intervals, b.intervals)

  test(
    "pooled bootstrap point values equal the pooled tally and resample inside the three strata"
  ):
    val r = ErBootstrap.pooled(items, 500, 16L, 0.95)
    val t = Tally.of(items)
    assertEqualsDouble(r.intervals(0).point, t.precision.get, tol)
    assertEqualsDouble(r.intervals(1).point, t.recall.get, tol)
    assertEqualsDouble(r.intervals(2).point, t.f1.get, tol)
    assertEquals(
      r.strataSizes,
      Map("exact-id" -> 5, "hard-common-name" -> 6, "clear-non-match" -> 5)
    )
    r.intervals.foreach { i =>
      assert(i.low <= i.high, i.toString)
      assert(i.low >= 0.0 && i.high <= 1.0, i.toString)
    }
    // recall resamples are over 16 pairs with 0 to 11 gold-same: used = all
    assertEquals(r.intervals(1).used, 500)

  test("pooled bootstrap reports resamples where precision is undefined"):
    val onlyFp = Vector(
      ErFixtures.item("a", "s1", GoldVerdict.Same, None),
      ErFixtures.item("b", "s1", GoldVerdict.Same, None)
    )
    val r = ErBootstrap.pooled(onlyFp, 50, 1L, 0.95)
    assertEquals((r.intervals(0).used, r.intervals(0).undefined), (0, 50))
    assertEquals(r.intervals(1).point, 0.0)

  test("sensitivity excludes or relabels the suspect pairs"):
    val ids = Sensitivity.proxyPairIds(items)
    assertEquals(ids.sorted, Vector("c3", "c5"))
    val set = Sensitivity.suspectSet("proxy", "test", ids, items, 50, 16L, 0.95)
    val by = set.scenarios.map(s => s.name -> s).toMap
    val excl = by("exclude-suspects").tally
    assertEquals((excl.n, excl.same, excl.truePositive), (14L, 9L, 5L))
    assertEqualsDouble(excl.recall.get, 5.0 / 9.0, tol)
    assertEqualsDouble(excl.precision.get, 5.0 / 6.0, tol)
    val rel = by("relabel-suspects-different").tally
    assertEquals((rel.n, rel.same, rel.different), (16L, 9L, 6L))
    assertEqualsDouble(rel.recall.get, 5.0 / 9.0, tol)
    assertEqualsDouble(rel.precision.get, 5.0 / 6.0, tol)
    assertEquals(set.notInGold, Vector.empty[String])

  test("sensitivity lists suspect ids that are not in the gold file"):
    val set = Sensitivity.suspectSet(
      "file",
      "test",
      Vector("c3", "nope"),
      items,
      20,
      16L,
      0.95
    )
    assertEquals(set.pairIds, Vector("c3"))
    assertEquals(set.notInGold, Vector("nope"))
