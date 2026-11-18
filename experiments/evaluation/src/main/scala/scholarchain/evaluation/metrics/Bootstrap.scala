package scholarchain.evaluation.metrics

/** Percentile bootstrap of a mean over items (questions).
  *
  * Algorithm, fixed so a test can replay it: drop NaN values; `rng =
  * scala.util.Random(seed)`; for each of `resamples` resamples draw n indices
  * in order with `rng.nextInt(n)` and take the mean of the drawn values; sort
  * the resample means ascending; with `alpha = 1 - level` the lower bound is
  * index `floor(alpha / 2 * resamples)` and the upper bound index
  * `ceil((1 - alpha / 2) * resamples) - 1` (a 1e-9 tolerance guards
  * floating-point error), both clamped to the array. The first value is the
  * mean of the cleaned sample itself. No values gives (NaN, NaN, NaN).
  */
object Bootstrap:
  private val eps = 1e-9

  def meanCi(
      values: IndexedSeq[Double],
      resamples: Int,
      seed: Long,
      level: Double
  ): (Double, Double, Double) =
    require(resamples >= 1, s"resamples must be at least 1, not $resamples")
    require(level > 0.0 && level < 1.0, s"level must be in (0, 1): $level")
    val clean = values.filterNot(_.isNaN).toVector
    if clean.isEmpty then (Double.NaN, Double.NaN, Double.NaN)
    else
      val n = clean.size
      val mean = clean.sum / n.toDouble
      val rng = new scala.util.Random(seed)
      val means = new Array[Double](resamples)
      var r = 0
      while r < resamples do
        var sum = 0.0
        var j = 0
        while j < n do
          sum += clean(rng.nextInt(n))
          j += 1
        means(r) = sum / n.toDouble
        r += 1
      java.util.Arrays.sort(means)
      val alpha = 1.0 - level
      val lowIndex = math.floor(alpha / 2.0 * resamples + eps).toInt
      val highIndex = math.ceil((1.0 - alpha / 2.0) * resamples - eps).toInt - 1
      val low = math.min(resamples - 1, math.max(0, lowIndex))
      val high = math.min(resamples - 1, math.max(0, highIndex))
      (mean, means(low), means(high))
