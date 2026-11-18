package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.evaluation.metrics.RetrievalMetrics

/** Oracle tests for the Phase 16 retrieval metrics. Every expected value is
  * worked out by hand in the comment above its test, from the definitions in
  * plan.md (relevant ids have grade >= 1, gain 2^grade - 1, discount log2(i +
  * 2) for 0-based rank i). They are a specification the evaluation code must
  * pass, not a copy of it.
  */
class P16RetrievalMetricsSuite extends FunSuite:
  private val eps = 1e-9
  private def log2(x: Double): Double = math.log(x) / math.log(2.0)

  // ranked a b c d; relevant a(1) c(2) e(1): three relevant, e never retrieved
  private val ranked = Seq("a", "b", "c", "d")
  private val relevant = Map("a" -> 1, "c" -> 2, "e" -> 1)

  // Recall@K = hits in the top K / number of relevant ids ----------------------

  test("recall@1 is 1/3: one of three relevant ids is in the top 1"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 1),
      1.0 / 3.0,
      eps
    )

  test("recall@2 is 1/3: only a is among the first two"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 2),
      1.0 / 3.0,
      eps
    )

  test("recall@4 is 2/3: a and c, and e is never retrieved"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 4),
      2.0 / 3.0,
      eps
    )

  test("recall with k larger than the list equals recall over the list"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 10),
      2.0 / 3.0,
      eps
    )

  test("recall with no relevant id is NaN, not 0 and not an exception"):
    assert(RetrievalMetrics.recallAtK(ranked, Map.empty, 3).isNaN)

  test("recall of an empty ranking with relevant ids is 0"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(Seq.empty, relevant, 5),
      0.0,
      eps
    )

  test("recall ignores ids outside the relevant map"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(Seq("x", "y", "z"), relevant, 3),
      0.0,
      eps
    )

  // Precision@K = hits in the top K / K ----------------------------------------

  test("precision@2 is 1/2"):
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(ranked, relevant, 2),
      0.5,
      eps
    )

  test("precision@4 is 2/4"):
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(ranked, relevant, 4),
      0.5,
      eps
    )

  test("precision divides by k even when the list is shorter: 2 hits / 10"):
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(ranked, relevant, 10),
      0.2,
      eps
    )

  test("precision@1 of a perfect first hit is 1"):
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(Seq("a"), relevant, 1),
      1.0,
      eps
    )

  // Reciprocal rank = 1 / (1-based rank of the first relevant id), else 0 ------

  test("reciprocal rank is 1 when the first item is relevant"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(ranked, relevant),
      1.0,
      eps
    )

  test("reciprocal rank is 1/3 when the third item is the first relevant"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("x", "y", "c", "a"), relevant),
      1.0 / 3.0,
      eps
    )

  test("reciprocal rank uses only the first relevant id, not later ones"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("x", "a", "c"), relevant),
      0.5,
      eps
    )

  test("reciprocal rank is 0.0 when no ranked id is relevant"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("x", "y"), relevant),
      0.0,
      eps
    )

  test("reciprocal rank is 0.0 when the relevant map is empty"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("x", "y"), Map.empty),
      0.0,
      eps
    )

  // nDCG@K with graded labels ---------------------------------------------------
  // ranked b a x; relevant a=2 (gain 3), b=1 (gain 1).
  // DCG = 1/log2(2) + 3/log2(3) + 0 = 1 + 3/log2(3) = 2.8927893
  // ideal order a b: 3/log2(2) + 1/log2(3) = 3 + 1/log2(3) = 3.6309298
  // nDCG = 2.8927893 / 3.6309298 = 0.796707

  private val graded = Map("a" -> 2, "b" -> 1)

  test("nDCG@3 of b a x with grades a=2, b=1 is 0.7967"):
    val expected = (1.0 + 3.0 / log2(3.0)) / (3.0 + 1.0 / log2(3.0))
    val got = RetrievalMetrics.ndcgAtK(Seq("b", "a", "x"), graded, 3)
    assertEqualsDouble(got, expected, eps)
    assertEqualsDouble(got, 0.7967, 1e-4)

  test("nDCG of the ideal order is exactly 1"):
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("a", "b", "x"), graded, 3),
      1.0,
      eps
    )

  test("nDCG@1 of b first is 1/3: gain 1 against an ideal gain of 3"):
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("b", "a"), graded, 1),
      1.0 / 3.0,
      eps
    )

  test("nDCG ignores anything ranked beyond k"):
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("x", "a", "b"), graded, 1),
      0.0,
      eps
    )

  test("nDCG with k larger than the list: a alone, ideal a then b"):
    // DCG 3; ideal 3 + 1/log2(3) = 3.6309298; ratio 0.826
    val expected = 3.0 / (3.0 + 1.0 / log2(3.0))
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("a"), graded, 10),
      expected,
      eps
    )

  test("nDCG separates grades: the grade 2 item first beats the grade 1 item"):
    val high = RetrievalMetrics.ndcgAtK(Seq("a", "b"), graded, 2)
    val low = RetrievalMetrics.ndcgAtK(Seq("b", "a"), graded, 2)
    assert(high > low, s"$high vs $low")

  test(
    "nDCG is tie-neutral: swapping two items of equal grade changes nothing"
  ):
    val ties = Map("a" -> 2, "b" -> 2, "c" -> 1)
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("a", "b", "c"), ties, 3),
      RetrievalMetrics.ndcgAtK(Seq("b", "a", "c"), ties, 3),
      eps
    )

  test("nDCG gain is exponential: grade 3 is worth 7, not 3"):
    // ranked only the grade 1 item b (gain 1) against relevant c=3 and b=1:
    // DCG 1; ideal 7/log2(2) + 1/log2(3) = 7.6309298; nDCG 0.131
    val rel = Map("c" -> 3, "b" -> 1)
    val expected = 1.0 / (7.0 + 1.0 / log2(3.0))
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("b"), rel, 2),
      expected,
      eps
    )

  test("nDCG with no relevant id is NaN"):
    assert(RetrievalMetrics.ndcgAtK(Seq("a", "b"), Map.empty, 2).isNaN)

  test("nDCG with nothing retrieved is 0 when something is relevant"):
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq.empty, graded, 3),
      0.0,
      eps
    )

  test("every metric stays within [0, 1] when defined"):
    val lists = Seq(
      Seq.empty[String],
      Seq("a"),
      Seq("x", "a", "b"),
      Seq("b", "a", "x", "y", "z")
    )
    for
      list <- lists
      k <- Seq(1, 2, 3, 5, 10)
    do
      val values = Seq(
        RetrievalMetrics.recallAtK(list, graded, k),
        RetrievalMetrics.precisionAtK(list, graded, k),
        RetrievalMetrics.reciprocalRank(list, graded),
        RetrievalMetrics.ndcgAtK(list, graded, k)
      )
      values.foreach(v => assert(v >= 0.0 && v <= 1.0 + eps, s"$list $k $v"))
