package scholarchain.er.eval.validation

import java.util.Locale
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.LabelNames

/** A descriptive comparison of the same pairs labeled in two passes. It feeds
  * no threshold and no pass or fail decision.
  */
object PassComparison:
  final case class Pair(id: String, first: Verdict, second: Verdict):
    def agree: Boolean = first == second

  private def fixed(x: Double): String = String.format(Locale.ROOT, "%.3f", x)

  private def name(v: Verdict): String = LabelNames.name(v)

  /** Cohen's kappa over same and different. Pairs either pass called unsure are
    * left out. None when chance agreement is total, so kappa has no meaning.
    */
  def kappaLine(pairs: Vector[Pair]): String =
    val decided =
      pairs.filter(p => p.first != Verdict.Unsure && p.second != Verdict.Unsure)
    val left = pairs.size - decided.size
    val n = decided.size
    if n == 0 then
      s"kappa undefined: no pair is decided in both passes (excluded for unsure: $left)"
    else
      def same(v: Verdict) = v == Verdict.Match
      val agree = decided.count(_.agree).toDouble / n
      val firstSame = decided.count(p => same(p.first)).toDouble / n
      val secondSame = decided.count(p => same(p.second)).toDouble / n
      val chance =
        firstSame * secondSame + (1 - firstSame) * (1 - secondSame)
      val head =
        s"Cohen kappa over same/different: n $n, excluded for unsure: $left"
      if chance >= 1.0 then
        s"kappa undefined: both passes use one label only ($head)"
      else
        val kappa = (agree - chance) / (1 - chance)
        s"$head, observed ${fixed(agree)}, by chance ${fixed(chance)}, " +
          s"kappa ${fixed(kappa)}"

  private def strataLines(
      pairs: Vector[Pair],
      strata: Map[String, String]
  ): Vector[String] =
    pairs
      .groupBy(p => strata.getOrElse(p.id, "unknown"))
      .toVector
      .sortBy(_._1)
      .map { (s, group) =>
        val agree = group.count(_.agree)
        s"stratum $s: ${group.size} pairs, $agree agree, " +
          s"${group.size - agree} disagree"
      }

  def lines(
      pairs: Vector[Pair],
      strata: Option[Map[String, String]],
      notReviewed: Int
  ): Vector[String] =
    linesFor(
      pairs,
      strata,
      Vector(
        "descriptive only: not a threshold input and not a quality score; " +
          "pass 1 was made with suggestions, pass 2 without",
        s"only ${pairs.size} subset pairs were re-reviewed blind; " +
          s"$notReviewed pairs were NOT re-reviewed and carry their pass-1 labels"
      ),
      "pass 1",
      "pass 2"
    )

  /** The comparison of pass 1 with pass 3: the same pairs, the first time with
    * suggestions, the second time blind. `carriedPass2` counts the pass-2
    * labels, which are not part of the comparison.
    */
  def linesPass3(
      pairs: Vector[Pair],
      strata: Option[Map[String, String]],
      carriedPass2: Int
  ): Vector[String] =
    linesFor(
      pairs,
      strata,
      Vector(
        "descriptive only, not a threshold input and not a quality score; " +
          "pass 1 was made with suggestions, pass 3 is a blind re-review of " +
          "the same pairs",
        s"all ${pairs.size} pass-1 pairs were re-reviewed blind in pass 3; " +
          s"the $carriedPass2 pass-2 labels are carried unchanged and are " +
          "not in this comparison",
        "the reviewer had seen every one of these pairs before, with " +
          "suggestions, so agreement may partly reflect memory"
      ),
      "pass 1",
      "pass 3"
    )

  def linesFor(
      pairs: Vector[Pair],
      strata: Option[Map[String, String]],
      intro: Vector[String],
      from: String,
      to: String
  ): Vector[String] =
    val agree = pairs.count(_.agree)
    val pct = if pairs.isEmpty then 0.0 else 100.0 * agree / pairs.size
    val moves = pairs
      .filterNot(_.agree)
      .groupBy(p => s"${name(p.first)}->${name(p.second)}")
      .toVector
      .sortBy(_._1)
      .map((m, v) => s"$m ${v.size}")
    val wrong = pairs.filterNot(_.agree).sortBy(_.id)
    intro ++ Vector(
      s"exact agreement $agree of ${pairs.size} (${fixed(pct)}%)",
      kappaLine(pairs),
      s"changes $from->$to: ${
          if moves.isEmpty then "none" else moves.mkString(", ")
        }"
    ) ++ strata.toVector.flatMap(strataLines(pairs, _)) ++
      Vector(s"disagreements: ${wrong.size}") ++
      wrong.map(p =>
        s"disagree ${p.id} first=${name(p.first)} second=${name(p.second)}"
      )
