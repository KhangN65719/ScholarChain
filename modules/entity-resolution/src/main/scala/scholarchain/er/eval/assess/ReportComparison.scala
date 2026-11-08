package scholarchain.er.eval.assess

import scholarchain.er.eval.GoldLabel
import scholarchain.er.eval.OutcomeRow
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.LabelNames

/** A pass-3 pair whose label differs from the pass-1 label it had in the
  * earlier gold file, with what the matcher decided about it in this run.
  */
final case class LabelChange(
    pairId: String,
    kind: String,
    stratum: String,
    before: Verdict,
    after: Verdict,
    outcome: String,
    path: Option[String],
    score: Option[Double],
    reasonCodes: Vector[String]
)

/** Sets an earlier report beside this one and lists the labels that changed. It
  * is descriptive: it runs after every number of this report is final, it is
  * appended to the text report only, and nothing reads it back.
  *
  * The earlier gold file carried the pass-1 labels of the pass-3 pairs
  * unchanged, so the label a pass-3 pair had there is its stored `pass1_label`.
  * The earlier gold file itself is not read.
  */
object ReportComparison:
  private def num(v: Option[Double]): String = v.fold("n/a")(x => f"$x%.3f")

  private def word(v: Verdict): String = LabelNames.name(v)

  def changes(
      decisions: Decisions,
      labels: Vector[GoldLabel],
      strata: Map[String, String],
      before: Map[String, Verdict],
      passes: Map[String, Int]
  ): Vector[LabelChange] =
    val byPair = decisions.decided.map(d => d.pair.pairId -> d.record).toMap
    labels
      .flatMap { label =>
        for
          _ <- passes.get(label.itemId).filter(_ == 3)
          earlier <- before.get(label.itemId).filter(_ != label.verdict)
        yield
          val record = byPair.get(label.itemId)
          LabelChange(
            label.itemId,
            label.kind.wire,
            strata.getOrElse(label.itemId, "unknown"),
            earlier,
            label.verdict,
            record.fold("MISSING")(_.decision.decision.wire),
            record.map(_.path.wire),
            record.map(_.score),
            record.fold(Vector.empty[String])(_.decision.reasonCodes)
          )
      }
      .sortBy(_.pairId)

  private def outcomes(r: OutcomeRow): String =
    s"accepted ${r.accepted}, rejected ${r.rejected}, " +
      s"unresolved ${r.unresolved}, conflict ${r.conflict}"

  private def pooledLines(previous: Rates, current: Rates): Vector[String] =
    Vector(
      s"  pairs earlier ${previous.pairs}  this ${current.pairs}",
      s"  precision earlier ${num(previous.precision)}  this ${num(current.precision)}",
      s"  recall earlier ${num(previous.recall)}  this ${num(current.recall)}",
      s"  F1 earlier ${num(previous.f1)}  this ${num(current.f1)}"
    ) ++ Vector(
      ("same", previous.table.same, current.table.same),
      ("different", previous.table.different, current.table.different),
      ("unsure", previous.table.unsure, current.table.unsure)
    ).flatMap { (name, p, c) =>
      Vector(
        s"  $name earlier ${outcomes(p)}",
        s"  $name this    ${outcomes(c)}"
      )
    }

  private def changeLine(c: LabelChange, listed: Boolean): String =
    val score = c.score.fold("-")(s => f"$s%.3f")
    s"  change ${c.pairId} ${c.kind} ${c.stratum} " +
      s"${word(c.before)}->${word(c.after)} matcher=${c.outcome} " +
      s"${c.path.getOrElse("-")} score=$score " +
      s"listed in the earlier report: ${if listed then "yes" else "no"} " +
      c.reasonCodes.mkString(",")

  def lines(
      file: String,
      sha256: String,
      previous: FullReport,
      current: FullReport,
      reviewedAgain: Vector[String],
      changes: Vector[LabelChange]
  ): Vector[String] =
    val flagged = previous.errors.map(_.pairId).toSet
    val seen = reviewedAgain.count(flagged)
    val moved = changes.count(c => flagged(c.pairId))
    val directions = changes
      .groupBy(c => s"${word(c.before)}->${word(c.after)}")
      .toVector
      .sortBy(_._1)
      .map((d, v) => s"$d ${v.size}")
    val differences = Vector(
      Option.when(previous.inputs.get("pairs") != current.inputs.get("pairs"))(
        "WARNING: the earlier report was made on a different pairs file " +
          s"(sha256 ${previous.inputs.getOrElse("pairs", "unknown")} against " +
          s"${current.inputs.getOrElse("pairs", "unknown")}); matcher " +
          "decisions are not comparable"
      ),
      Option.when(previous.policyVersion != current.policyVersion)(
        "WARNING: the policy version differs: " +
          s"${previous.policyVersion} against ${current.policyVersion}"
      )
    ).flatten
    Vector(
      s"== comparison with $file (sha256 $sha256)",
      "descriptive only: never used to alter a threshold, a rule or a label",
      "note: the v2 label of a pass-3 pair is its pass-1 label (gold-v2 " +
        "carried the pass-1 labels unchanged); the earlier gold file itself " +
        "is not read here"
    ) ++ differences ++ Vector("-- pooled, earlier report against this one") ++
      pooledLines(previous.pooled, current.pooled) ++
      Vector(
        "-- labels that changed",
        s"gold labels changed between the files: ${changes.size} of " +
          s"${reviewedAgain.size} pass-3 pairs" +
          (if directions.isEmpty then ""
           else s" (${directions.mkString(", ")})"),
        s"pass-3 pairs listed in the earlier report's error section: $seen " +
          s"of ${reviewedAgain.size}; labels changed among them: $moved",
        s"pass-3 pairs not listed there: ${reviewedAgain.size - seen}; " +
          s"labels changed among them: ${changes.size - moved}"
      ) ++ changes.map(c => changeLine(c, flagged(c.pairId)))
