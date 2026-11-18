package scholarchain.evaluation

import munit.FunSuite
import scholarchain.evaluation.metrics.{Bootstrap, RetrievalMetrics}

class MetricsSuite extends FunSuite:
  private val log2 = math.log(2.0)
  private def lg(x: Double): Double = math.log(x) / log2

  private val relevant = Map("a" -> 1, "c" -> 1, "x" -> 1, "z" -> 0)

  test("recall and precision at k count relevant ids in the top k"):
    val ranked = Seq("a", "b", "c", "d")
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 2),
      1.0 / 3.0,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(ranked, relevant, 4),
      2.0 / 3.0,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(ranked, relevant, 2),
      0.5,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(ranked, relevant, 4),
      0.5,
      1e-12
    )

  test("precision divides by k even when fewer than k ids are returned"):
    assertEqualsDouble(
      RetrievalMetrics.precisionAtK(Seq("a"), relevant, 5),
      0.2,
      1e-12
    )

  test("grade 0 is not relevant, so it is not in the recall denominator"):
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(Seq("z"), relevant, 3),
      0.0,
      1e-12
    )

  test("no relevant id gives NaN for recall and nDCG, 0 for reciprocal rank"):
    val none = Map("q" -> 0)
    assert(RetrievalMetrics.recallAtK(Seq("a"), none, 3).isNaN)
    assert(RetrievalMetrics.ndcgAtK(Seq("a"), none, 3).isNaN)
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("a"), none),
      0.0,
      0.0
    )

  test("reciprocal rank is 1 over the rank of the first relevant id"):
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("a", "b"), relevant),
      1.0,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("b", "c"), relevant),
      0.5,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.reciprocalRank(Seq("b", "d"), relevant),
      0.0,
      1e-12
    )

  test("nDCG uses gain 2^g - 1 and discount log2(i + 2)"):
    val graded = Map("a" -> 3, "b" -> 1)
    val got = RetrievalMetrics.ndcgAtK(Seq("b", "a"), graded, 2)
    val dcg = 1.0 / lg(2.0) + 7.0 / lg(3.0)
    val ideal = 7.0 / lg(2.0) + 1.0 / lg(3.0)
    assertEqualsDouble(got, dcg / ideal, 1e-12)

  test("a perfect ranking has nDCG 1"):
    val graded = Map("a" -> 3, "b" -> 1)
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("a", "b", "c"), graded, 5),
      1.0,
      1e-12
    )

  test("a repeated id earns its gain once"):
    val graded = Map("a" -> 1)
    assertEqualsDouble(
      RetrievalMetrics.ndcgAtK(Seq("a", "a"), graded, 2),
      1.0,
      1e-12
    )
    assertEqualsDouble(
      RetrievalMetrics.recallAtK(Seq("a", "a"), graded, 2),
      1.0,
      1e-12
    )

  test("k below 1 is refused"):
    intercept[IllegalArgumentException](
      RetrievalMetrics.precisionAtK(Seq("a"), relevant, 0)
    )

  test("bootstrap of a constant sample is that constant"):
    val (m, lo, hi) = Bootstrap.meanCi(Vector.fill(8)(0.25), 200, 1L, 0.95)
    assertEquals((m, lo, hi), (0.25, 0.25, 0.25))

  test("bootstrap drops NaN and an empty sample gives NaN"):
    val (m, _, _) = Bootstrap.meanCi(Vector(1.0, Double.NaN, 3.0), 50, 1L, 0.9)
    assertEqualsDouble(m, 2.0, 1e-12)
    val (a, b, c) = Bootstrap.meanCi(Vector(Double.NaN), 50, 1L, 0.9)
    assert(a.isNaN && b.isNaN && c.isNaN)

  test("bootstrap is reproducible per seed and brackets the mean"):
    val xs = Vector(0.0, 1.0, 0.5, 0.25, 0.75, 1.0, 0.0, 0.5)
    val one = Bootstrap.meanCi(xs, 500, 42L, 0.95)
    assertEquals(one, Bootstrap.meanCi(xs, 500, 42L, 0.95))
    assert(one._2 <= one._1 && one._1 <= one._3, one.toString)

  test("bootstrap follows its documented algorithm"):
    val xs = Vector(1.0, 2.0, 3.0, 4.0)
    val resamples = 10
    val rng = new scala.util.Random(7L)
    val means = (0 until resamples).map { _ =>
      (0 until xs.size).map(_ => xs(rng.nextInt(xs.size))).sum / xs.size
    }.sorted
    // alpha = 0.2: lower index floor(0.1 * 10) = 1, upper ceil(0.9 * 10) - 1 = 8
    val (m, lo, hi) = Bootstrap.meanCi(xs, resamples, 7L, 0.8)
    assertEqualsDouble(m, 2.5, 1e-12)
    assertEqualsDouble(lo, means(1), 1e-12)
    assertEqualsDouble(hi, means(8), 1e-12)
