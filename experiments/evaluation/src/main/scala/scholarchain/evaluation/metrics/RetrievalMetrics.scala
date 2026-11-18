package scholarchain.evaluation.metrics

import scala.collection.mutable

/** Rank metrics over one ranked list of ids and graded relevance labels.
  *
  * `relevant` maps id to grade; only grades of 1 or more count as relevant. A
  * repeated id in `ranked` keeps its rank positions, but gains credit only at
  * its first occurrence. A metric with no relevant id is `Double.NaN` except
  * `reciprocalRank`, which is 0.0.
  */
object RetrievalMetrics:
  private def isRelevant(relevant: Map[String, Int], id: String): Boolean =
    relevant.get(id).exists(_ >= 1)

  private def relevantCount(relevant: Map[String, Int]): Int =
    relevant.count((_, grade) => grade >= 1)

  private def hits(ranked: Seq[String], relevant: Map[String, Int], k: Int) =
    require(k >= 1, s"k must be at least 1, not $k")
    ranked.take(k).distinct.count(id => isRelevant(relevant, id))

  def recallAtK(
      ranked: Seq[String],
      relevant: Map[String, Int],
      k: Int
  ): Double =
    val total = relevantCount(relevant)
    if total == 0 then Double.NaN
    else hits(ranked, relevant, k).toDouble / total.toDouble

  def precisionAtK(
      ranked: Seq[String],
      relevant: Map[String, Int],
      k: Int
  ): Double =
    hits(ranked, relevant, k).toDouble / k.toDouble

  def reciprocalRank(ranked: Seq[String], relevant: Map[String, Int]): Double =
    val index = ranked.indexWhere(id => isRelevant(relevant, id))
    if index < 0 then 0.0 else 1.0 / (index + 1).toDouble

  private def gain(grade: Int): Double =
    if grade >= 1 then math.pow(2.0, grade.toDouble) - 1.0 else 0.0

  private def discount(index: Int): Double =
    math.log((index + 2).toDouble) / math.log(2.0)

  def ndcgAtK(
      ranked: Seq[String],
      relevant: Map[String, Int],
      k: Int
  ): Double =
    require(k >= 1, s"k must be at least 1, not $k")
    val grades =
      relevant.values
        .filter(_ >= 1)
        .toVector
        .sorted(using Ordering[Int].reverse)
    if grades.isEmpty then Double.NaN
    else
      val seen = mutable.HashSet.empty[String]
      val dcg = ranked
        .take(k)
        .zipWithIndex
        .map { (id, i) =>
          val grade = if seen.add(id) then relevant.getOrElse(id, 0) else 0
          gain(grade) / discount(i)
        }
        .sum
      val ideal = grades
        .take(k)
        .zipWithIndex
        .map((grade, i) => gain(grade) / discount(i))
        .sum
      dcg / ideal
