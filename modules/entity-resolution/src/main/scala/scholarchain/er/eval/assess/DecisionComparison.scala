package scholarchain.er.eval.assess

import scholarchain.er.eval.GoldLabel
import scholarchain.er.eval.labeling.LabelNames

/** The matcher's answer for one pair, as written to `decisions.jsonl`. */
final case class DecisionRow(
    pairId: String,
    kind: String,
    outcome: String,
    path: String,
    score: Double,
    reasons: Vector[String]
)

/** Sets the matcher decisions of an earlier run beside this run's, pair by
  * pair, with the gold label of each changed pair. Descriptive only: it runs
  * after the report numbers are final, is appended to the text report, and
  * nothing reads it back. The policy version, the rules fired and the model
  * version are not compared, so a version bump alone is not a change.
  */
object DecisionComparison:
  private val scoreTolerance = 1e-9

  def rowOf(item: Decided): DecisionRow =
    val d = item.record.decision
    DecisionRow(
      item.pair.pairId,
      item.record.kind.wire,
      d.decision.wire,
      item.record.path.wire,
      item.record.score,
      d.reasonCodes
    )

  /** Parses a decisions file. A line that is not a decision, or a repeated pair
    * ID, refuses the whole file.
    */
  def parse(lines: Iterable[String]): Either[String, Map[String, DecisionRow]] =
    val rows =
      try
        Right(
          lines.iterator.zipWithIndex
            .filter((line, _) => line.trim.nonEmpty)
            .map { (line, number) =>
              try
                val json = ujson.read(line).obj
                DecisionRow(
                  json("pair_id").str,
                  json("kind").str,
                  json("outcome").str,
                  json("path").str,
                  json("score").num,
                  json("reasonCodes").arr.map(_.str).toVector
                )
              catch
                case e: Exception =>
                  throw new IllegalArgumentException(
                    s"line ${number + 1} is not a decision: ${e.getMessage}"
                  )
            }
            .toVector
        )
      catch case e: IllegalArgumentException => Left(e.getMessage)
    rows.flatMap { found =>
      val repeated = found.groupBy(_.pairId).filter(_._2.size > 1).keys.toVector
      if repeated.nonEmpty then
        Left(s"repeated pair_id: ${repeated.sorted.mkString(", ")}")
      else Right(found.map(r => r.pairId -> r).toMap)
    }

  def changed(before: DecisionRow, after: DecisionRow): Boolean =
    before.outcome != after.outcome || before.path != after.path ||
      math.abs(before.score - after.score) > scoreTolerance ||
      before.reasons != after.reasons

  def lines(
      file: String,
      sha256: String,
      earlier: Map[String, DecisionRow],
      now: Vector[DecisionRow],
      labels: Vector[GoldLabel],
      strata: Map[String, String]
  ): Vector[String] =
    val current = now.map(r => r.pairId -> r).toMap
    val both = current.keySet.intersect(earlier.keySet).toVector.sorted
    val moved = both.filter(id => changed(earlier(id), current(id)))
    val gold = labels.map(l => l.itemId -> l.verdict).toMap
    def goldWord(id: String): String =
      gold.get(id).fold("none")(LabelNames.name)
    val transitions = moved
      .groupBy(id => s"${earlier(id).outcome}->${current(id).outcome}")
      .toVector
      .sortBy(_._1)
      .map((t, ids) => s"$t ${ids.size}")
    val byGold = moved
      .groupBy(goldWord)
      .toVector
      .sortBy(_._1)
      .map((g, ids) => s"$g ${ids.size}")
    def rowLine(id: String): String =
      val (a, b) = (earlier(id), current(id))
      val stratum = strata.getOrElse(id, "unknown")
      f"  decision-change $id ${b.kind} $stratum " +
        s"gold=${goldWord(id)} ${a.outcome}->${b.outcome} " +
        s"path ${a.path}->${b.path} " +
        f"score ${a.score}%.3f->${b.score}%.3f " +
        s"reasons [${a.reasons.mkString(",")}]->[${b.reasons.mkString(",")}]"
    Vector(
      s"== matcher decisions compared with $file (sha256 $sha256)",
      "descriptive only: never used to alter a threshold, a rule or a label",
      s"pairs earlier ${earlier.size}, this run ${current.size}, in both " +
        s"${both.size}, only earlier ${earlier.keySet.diff(current.keySet).size}, " +
        s"only this run ${current.keySet.diff(earlier.keySet).size}",
      s"decisions changed (outcome, path, score or reasons): ${moved.size} " +
        s"of ${both.size}",
      "by outcome: " +
        (if transitions.isEmpty then "none" else transitions.mkString(", ")),
      "by gold label: " +
        (if byGold.isEmpty then "none" else byGold.mkString(", "))
    ) ++ moved.map(rowLine)
