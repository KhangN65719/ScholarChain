package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.evaluation.metrics.Bootstrap

/** Oracle tests for the seeded percentile bootstrap of the mean. */
class P16BootstrapSuite extends FunSuite:
  private val eps = 1e-9
  private val values = (1 to 20).map(_.toDouble).toIndexedSeq

  test("the point estimate is the plain mean whatever the seed"):
    // mean of 1..20 is 10.5
    val (m1, _, _) = Bootstrap.meanCi(values, 500, 1L, 0.95)
    val (m2, _, _) = Bootstrap.meanCi(values, 500, 99L, 0.95)
    assertEqualsDouble(m1, 10.5, eps)
    assertEqualsDouble(m2, 10.5, eps)

  test("the same inputs and seed give identical results"):
    assertEquals(
      Bootstrap.meanCi(values, 1000, 16L, 0.95),
      Bootstrap.meanCi(values, 1000, 16L, 0.95)
    )

  test("a different seed gives a different interval"):
    val a = Bootstrap.meanCi(values, 1000, 1L, 0.95)
    val b = Bootstrap.meanCi(values, 1000, 2L, 0.95)
    assertNotEquals((a._2, a._3), (b._2, b._3))

  test("lower <= mean <= upper and both bounds lie in [min, max]"):
    val (m, lo, hi) = Bootstrap.meanCi(values, 1000, 7L, 0.95)
    assert(lo <= m && m <= hi, s"$lo $m $hi")
    assert(lo >= 1.0 && hi <= 20.0, s"$lo $hi")

  test("a constant sample has a zero-width interval at that constant"):
    val (m, lo, hi) =
      Bootstrap.meanCi(Vector.fill(8)(4.25), 200, 3L, 0.95)
    assertEqualsDouble(m, 4.25, eps)
    assertEqualsDouble(lo, 4.25, eps)
    assertEqualsDouble(hi, 4.25, eps)

  test("a single value gives that value three times"):
    val (m, lo, hi) = Bootstrap.meanCi(Vector(2.0), 200, 3L, 0.95)
    assertEquals((m, lo, hi), (2.0, 2.0, 2.0))

  test("NaN values are dropped before everything else"):
    val withNaN = IndexedSeq(1.0, Double.NaN, 2.0, 3.0, Double.NaN)
    val without = IndexedSeq(1.0, 2.0, 3.0)
    val got = Bootstrap.meanCi(withNaN, 500, 5L, 0.95)
    assertEqualsDouble(got._1, 2.0, eps)
    assertEquals(got, Bootstrap.meanCi(without, 500, 5L, 0.95))

  test("a higher level gives an interval that contains the lower level's"):
    // same seed and resample count draw the same resamples, so the
    // percentile bounds are monotone in the level
    val (_, lo80, hi80) = Bootstrap.meanCi(values, 1000, 11L, 0.80)
    val (_, lo99, hi99) = Bootstrap.meanCi(values, 1000, 11L, 0.99)
    assert(lo99 <= lo80 && hi99 >= hi80, s"$lo99 $lo80 $hi80 $hi99")

  test("the interval narrows as the sample grows"):
    val small = values.take(5)
    val big = (1 to 400).map(i => ((i - 1) % 20 + 1).toDouble).toIndexedSeq
    val (_, sLo, sHi) = Bootstrap.meanCi(small, 1000, 4L, 0.95)
    val (_, bLo, bHi) = Bootstrap.meanCi(big, 1000, 4L, 0.95)
    assert(bHi - bLo < sHi - sLo, s"${bHi - bLo} vs ${sHi - sLo}")

  test("the interval has the right order of width for a known sample"):
    // standard error of the mean of 1..20 is sd / sqrt(20) = 5.766 / 4.472
    // = 1.289, so a 95% interval is about 2 * 1.96 * 1.289 = 5.05 wide
    val (_, lo, hi) = Bootstrap.meanCi(values, 2000, 21L, 0.95)
    val width = hi - lo
    assert(width > 4.0 && width < 6.1, s"width $width")

  test("the bounds do not depend on the order of the input"):
    val shuffled = values.reverse
    val (_, a, b) = Bootstrap.meanCi(values, 1000, 8L, 0.95)
    val (_, c, d) = Bootstrap.meanCi(shuffled, 1000, 8L, 0.95)
    // order changes which element each draw picks, so exact equality is not
    // promised; the width must still agree to a tolerance
    assertEqualsDouble(b - a, d - c, 0.6)
