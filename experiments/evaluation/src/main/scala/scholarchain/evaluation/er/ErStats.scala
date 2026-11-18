package scholarchain.evaluation.er

object Ratio:
  def of(n: Long, d: Long): Option[Double] =
    Option.when(d > 0L)(n.toDouble / d.toDouble)

/** Counts of a set of gold pairs, defined as `GoldEvaluation.rates` defines
  * them: a true positive is a gold-same pair the matcher ACCEPTED, a false
  * positive a gold-different pair it ACCEPTED, and recall divides by every
  * gold-same pair, including those left unresolved, in conflict or with no
  * decision. Gold-unsure pairs count in `n` only.
  */
final case class Tally(
    truePositive: Long,
    falsePositive: Long,
    same: Long,
    different: Long,
    unsure: Long
):
  def n: Long = same + different + unsure
  def precision: Option[Double] =
    Ratio.of(truePositive, truePositive + falsePositive)
  def recall: Option[Double] = Ratio.of(truePositive, same)
  def f1: Option[Double] =
    for
      p <- precision
      r <- recall
      if p + r > 0.0
    yield 2.0 * p * r / (p + r)

object Tally:
  def of(items: Iterable[Scored]): Tally =
    var tp, fp, same, different, unsure = 0L
    items.foreach { item =>
      item.gold match
        case GoldVerdict.Same =>
          same += 1L
          if item.accepted then tp += 1L
        case GoldVerdict.Different =>
          different += 1L
          if item.accepted then fp += 1L
        case GoldVerdict.Unsure => unsure += 1L
    }
    Tally(tp, fp, same, different, unsure)

object Wilson:
  /** Wilson 95% interval for k successes in n trials (the interval
    * `GoldEvaluation.wilson` reports).
    */
  def interval(k: Long, n: Long): Option[(Double, Double)] =
    Option.when(n > 0L) {
      val z = 1.96
      val p = k.toDouble / n
      val denom = 1.0 + z * z / n
      val centre = (p + z * z / (2.0 * n)) / denom
      val half =
        z * math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denom
      (math.max(0.0, centre - half), math.min(1.0, centre + half))
    }

/** One decision path. Rows hold the pairs whose decision came from that path,
  * split by gold verdict and outcome. `recall` is path-conditional: of the
  * gold-same pairs the path decided, the share it ACCEPTED. It is not the
  * pooled recall, which divides by every gold-same pair; `contribution` is that
  * share (accepted by this path over all gold-same pairs), and the
  * contributions of all paths sum to the pooled recall.
  */
final case class PathRow(
    path: String,
    same: Long,
    sameAccepted: Long,
    sameRejected: Long,
    sameUnresolved: Long,
    sameConflict: Long,
    different: Long,
    differentAccepted: Long,
    unsure: Long,
    totalSame: Long
):
  def pairs: Long = same + different + unsure
  def recall: Option[Double] = Ratio.of(sameAccepted, same)
  def recallInterval: Option[(Double, Double)] =
    Wilson.interval(sameAccepted, same)
  def precision: Option[Double] =
    Ratio.of(sameAccepted, sameAccepted + differentAccepted)
  def contribution: Option[Double] = Ratio.of(sameAccepted, totalSame)

final case class PerPath(
    rows: Vector[PathRow],
    sameWithoutDecision: Long,
    totalSame: Long
)

object PathRecall:
  /** The four paths of `MatchPath`, always listed (zeros when unused); any
    * other path found in the decisions follows, sorted.
    */
  val KnownPaths: Vector[String] = Vector(
    "exact-id",
    "deterministic-bibliographic",
    "probabilistic",
    "llm-adjudicated"
  )

  def compute(items: Vector[Scored]): PerPath =
    val totalSame = items.count(_.gold == GoldVerdict.Same).toLong
    val extra = items
      .flatMap(_.decision.map(_.path))
      .distinct
      .filterNot(KnownPaths.contains)
      .sorted
    val rows = (KnownPaths ++ extra).map { path =>
      val here = items.filter(_.decision.exists(_.path == path))
      def count(gold: GoldVerdict, outcome: Option[Outcome]): Long =
        here
          .count(i =>
            i.gold == gold &&
              outcome.forall(o => i.decision.exists(_.outcome == o))
          )
          .toLong
      PathRow(
        path,
        count(GoldVerdict.Same, None),
        count(GoldVerdict.Same, Some(Outcome.Accepted)),
        count(GoldVerdict.Same, Some(Outcome.Rejected)),
        count(GoldVerdict.Same, Some(Outcome.Unresolved)),
        count(GoldVerdict.Same, Some(Outcome.Conflict)),
        count(GoldVerdict.Different, None),
        count(GoldVerdict.Different, Some(Outcome.Accepted)),
        count(GoldVerdict.Unsure, None),
        totalSame
      )
    }
    val missing = items
      .count(i => i.gold == GoldVerdict.Same && i.decision.isEmpty)
      .toLong
    PerPath(rows, missing, totalSame)

/** Percentile bootstrap with resampling inside strata.
  *
  * Algorithm, fixed so a test can replay it: strata are taken in ascending name
  * order and empty strata are skipped; `rng = scala.util.Random(seed)`; for
  * each resample and each stratum, draw n indices in order with
  * `rng.nextInt(n)` (n = the stratum's size, so every stratum keeps its size);
  * the statistic is computed on the concatenated resample. With a single
  * stratum and the mean as statistic this draws exactly what `Bootstrap.meanCi`
  * draws. The interval drops NaN statistics (for example a precision with
  * nothing accepted), sorts the rest and uses the index rule of `Bootstrap`:
  * with `alpha = 1 - level` and m values, the lower bound is index
  * `floor(alpha / 2 * m)` and the upper `ceil((1 - alpha / 2) * m) - 1`, with a
  * 1e-9 tolerance, clamped. The number of dropped resamples is reported.
  */
object StratifiedBootstrap:
  private val eps = 1e-9

  final case class Interval(
      point: Double,
      low: Double,
      high: Double,
      used: Int,
      undefined: Int
  )

  def draws[A](
      strata: Map[String, Vector[A]],
      resamples: Int,
      seed: Long
  )(stat: Vector[A] => Vector[Double]): Vector[Vector[Double]] =
    require(resamples >= 1, s"resamples must be at least 1, not $resamples")
    val ordered = strata.toVector.sortBy(_._1).map(_._2).filter(_.nonEmpty)
    val rng = new scala.util.Random(seed)
    Vector.tabulate(resamples) { _ =>
      val sample = Vector.newBuilder[A]
      ordered.foreach { group =>
        val n = group.size
        var j = 0
        while j < n do
          sample += group(rng.nextInt(n))
          j += 1
      }
      stat(sample.result())
    }

  def interval(point: Double, values: Seq[Double], level: Double): Interval =
    require(level > 0.0 && level < 1.0, s"level must be in (0, 1): $level")
    val clean = values.filterNot(_.isNaN).sorted.toVector
    val undefined = values.size - clean.size
    if clean.isEmpty then Interval(point, Double.NaN, Double.NaN, 0, undefined)
    else
      val m = clean.size
      val alpha = 1.0 - level
      val lowIndex = math.floor(alpha / 2.0 * m + eps).toInt
      val highIndex = math.ceil((1.0 - alpha / 2.0) * m - eps).toInt - 1
      val low = math.min(m - 1, math.max(0, lowIndex))
      val high = math.min(m - 1, math.max(0, highIndex))
      Interval(point, clean(low), clean(high), m, undefined)

/** The stratified bootstrap of pooled precision, recall and F1. The unit is the
  * gold pair; the strata are those of the private strata key. Gold-unsure pairs
  * stay in the resampled strata, as they are in the sample the report
  * describes, and add nothing to any count.
  */
object ErBootstrap:
  val Names: Vector[String] = Vector("precision", "recall", "f1")

  def stat(items: Vector[Scored]): Vector[Double] =
    val t = Tally.of(items)
    Vector(t.precision, t.recall, t.f1).map(_.getOrElse(Double.NaN))

  final case class Result(
      intervals: Vector[StratifiedBootstrap.Interval],
      draws: Vector[Vector[Double]],
      strataSizes: Map[String, Int]
  )

  def pooled(
      items: Vector[Scored],
      resamples: Int,
      seed: Long,
      level: Double
  ): Result =
    val sorted = items.sortBy(_.pairId)
    val strata = sorted.groupBy(_.stratum)
    val all = StratifiedBootstrap.draws(strata, resamples, seed)(stat)
    val point = stat(sorted)
    val intervals = Names.indices.toVector.map(k =>
      StratifiedBootstrap.interval(point(k), all.map(_(k)), level)
    )
    Result(intervals, all, strata.view.mapValues(_.size).toMap)

final case class ScenarioResult(
    name: String,
    description: String,
    tally: Tally,
    bootstrap: ErBootstrap.Result
)

/** A candidate set of mislabelled pairs and the scenarios built on it. */
final case class SuspectSet(
    name: String,
    basis: String,
    pairIds: Vector[String],
    notInGold: Vector[String],
    scenarios: Vector[ScenarioResult]
)

object Sensitivity:
  val ProxyStratum = "clear-non-match"

  /** The gold-same pairs of the stratum that holds clear non-matches. The
    * manifest claims about this many pairs "labeled same" in that stratum, but
    * names none, so this set is a proxy for them, never an identification.
    */
  def proxyPairIds(items: Vector[Scored]): Vector[String] =
    items
      .filter(i => i.stratum == ProxyStratum && i.gold == GoldVerdict.Same)
      .map(_.pairId)

  def scenario(
      name: String,
      description: String,
      items: Vector[Scored],
      resamples: Int,
      seed: Long,
      level: Double
  ): ScenarioResult =
    ScenarioResult(
      name,
      description,
      Tally.of(items),
      ErBootstrap.pooled(items, resamples, seed, level)
    )

  def baseline(
      items: Vector[Scored],
      resamples: Int,
      seed: Long,
      level: Double
  ): ScenarioResult =
    scenario(
      "all-labels",
      "every gold label as written",
      items,
      resamples,
      seed,
      level
    )

  def suspectSet(
      name: String,
      basis: String,
      ids: Vector[String],
      items: Vector[Scored],
      resamples: Int,
      seed: Long,
      level: Double
  ): SuspectSet =
    val set = ids.toSet
    val present = items.map(_.pairId).toSet
    val excluded = items.filterNot(i => set.contains(i.pairId))
    val relabelled = items.map(i =>
      if set.contains(i.pairId) && i.gold == GoldVerdict.Same then
        i.copy(gold = GoldVerdict.Different)
      else i
    )
    SuspectSet(
      name,
      basis,
      ids.filter(present.contains).sorted,
      ids.filterNot(present.contains).sorted,
      Vector(
        scenario(
          "exclude-suspects",
          "suspect pairs removed from every count and from the resampled strata",
          excluded,
          resamples,
          seed,
          level
        ),
        scenario(
          "relabel-suspects-different",
          "suspect gold-same pairs counted as gold-different",
          relabelled,
          resamples,
          seed,
          level
        )
      )
    )
