package scholarchain.evaluation.er

import java.util.Locale

/** The pooled numbers of the recorded replay of the entity-resolution gold
  * evaluation, `docs/agent-runs/phase-05/gold/eval-v3-r3/evaluation-report.txt`
  * ("pooled over researcher and work", gold rows include unsure): same 96
  * (accepted 77, rejected 14, unresolved 4, conflict 1), different 131
  * (accepted 0, rejected 109, unresolved 14, conflict 8), unsure 3; precision
  * 1.000, recall 0.802, F1 0.890; TP 77, FP 0.
  *
  * `ErExtras` re-implements the counting rules instead of calling
  * `GoldEvaluation` (the entity-resolution module is not a dependency of this
  * project), so on those inputs its pooled numbers must equal these. The check
  * is printed and recorded in the report; a difference means the
  * re-implementation, or the inputs, are not those of the replay.
  */
object ErReference:
  val Source = "docs/agent-runs/phase-05/gold/eval-v3-r3/evaluation-report.txt"
  val TruePositive = 77L
  val FalsePositive = 0L
  val Same = 96L
  val Different = 131L
  val Unsure = 3L
  val Precision = "1.000"
  val Recall = "0.802"
  val F1 = "0.890"

  final case class Check(
      matches: Boolean,
      expected: String,
      actual: String
  ):
    def status: String = if matches then "MATCH" else "DIFFERS"

    def line: String =
      s"ER_REFERENCE_CHECK status=$status source=$Source expected[$expected] " +
        s"actual[$actual]"

  private def f(d: Option[Double]): String =
    d.fold("n/a")(x => "%.3f".formatLocal(Locale.ROOT, x))

  def check(t: Tally): Check =
    val expected =
      s"TP=$TruePositive FP=$FalsePositive same=$Same different=$Different " +
        s"unsure=$Unsure precision=$Precision recall=$Recall F1=$F1"
    val actual =
      s"TP=${t.truePositive} FP=${t.falsePositive} same=${t.same} " +
        s"different=${t.different} unsure=${t.unsure} " +
        s"precision=${f(t.precision)} recall=${f(t.recall)} F1=${f(t.f1)}"
    Check(expected == actual, expected, actual)
