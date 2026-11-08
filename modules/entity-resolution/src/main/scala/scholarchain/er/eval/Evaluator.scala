package scholarchain.er.eval

import scholarchain.core.MatchOutcome
import scholarchain.er.matching.{
  EntityKind,
  Matcher,
  MatchPath,
  Policy,
  ResolutionRecord
}
import upickle.default.{ReadWriter, write}

/** Counts of how the system answered for pairs with one gold verdict. */
final case class OutcomeRow(
    accepted: Int,
    rejected: Int,
    unresolved: Int,
    conflict: Int,
    missing: Int
) derives ReadWriter:
  def total: Int = accepted + rejected + unresolved + conflict + missing

/** Rows are the human verdict; columns are what the matcher did. `missing`
  * counts labeled pairs the matcher produced no record for.
  */
final case class Confusion(goldMatch: OutcomeRow, goldNonMatch: OutcomeRow)
    derives ReadWriter

/** Precision is over accepted pairs. Recall is over all gold matches, so a
  * match left unresolved or in conflict counts against it; `decidedRecall`
  * excludes those and shows how much of the recall loss is abstention.
  */
final case class Metrics(
    labeled: Int,
    truePositive: Int,
    falsePositive: Int,
    falseNegative: Int,
    trueNegative: Int,
    precision: Option[Double],
    recall: Option[Double],
    f1: Option[Double],
    decidedRecall: Option[Double],
    coverage: Option[Double],
    unresolvedRate: Option[Double],
    conflictRate: Option[Double]
) derives ReadWriter

final case class PathMetrics(
    path: String,
    accepted: Int,
    truePositive: Int,
    falsePositive: Int,
    precision: Option[Double],
    rejected: Int,
    trueNegative: Int,
    falseNegative: Int,
    negativePredictiveValue: Option[Double],
    recallContribution: Option[Double]
) derives ReadWriter

final case class ErrorExample(
    itemId: String,
    leftId: String,
    rightId: String,
    leftSourceRecordId: String,
    rightSourceRecordId: String,
    outcome: String,
    path: Option[String],
    score: Option[Double],
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    goldVerdict: String,
    notes: Option[String]
) derives ReadWriter

final case class ErrorAnalysis(
    falsePositives: Vector[ErrorExample],
    falseNegatives: Vector[ErrorExample],
    abstainedPositives: Vector[ErrorExample],
    availableFalsePositives: Int,
    availableFalseNegatives: Int,
    availableAbstainedPositives: Int
) derives ReadWriter

final case class KindReport(
    kind: String,
    labeledPairs: Int,
    unsureExcluded: Int,
    confusion: Confusion,
    metrics: Metrics,
    byPath: Vector[PathMetrics],
    errors: ErrorAnalysis
) derives ReadWriter

final case class SensitivityPoint(
    kind: String,
    accept: Double,
    reject: Double,
    isPolicyThreshold: Boolean,
    accepted: Int,
    rejected: Int,
    precision: Option[Double],
    recall: Option[Double],
    f1: Option[Double],
    coverage: Option[Double],
    unresolvedRate: Option[Double]
) derives ReadWriter

final case class EvaluationReport(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    totalLabeled: Int,
    minimumRequired: Int,
    meetsMinimum: Boolean,
    kinds: Vector[KindReport],
    sensitivity: Vector[SensitivityPoint],
    warnings: Vector[String]
) derives ReadWriter:
  def toJson: String = write(this, indent = 2)

final case class EvaluationConfig(
    minimumLabeledPairs: Int = 200,
    errorSampleSize: Int = 10,
    acceptSweep: Vector[Double] = Vector(0.80, 0.85, 0.90, 0.95, 0.97, 0.99),
    rejectSweep: Vector[Double] = Vector(0.10, 0.20, 0.35, 0.50)
)

/** Scores matcher output against human labels. This package depends on the
  * matcher's output types; nothing in the matching package depends on it. The
  * labels are read here and nowhere upstream of a decision.
  *
  * Positive class is "same entity". A pair is predicted positive only when
  * ACCEPTED and predicted negative only when REJECTED; UNRESOLVED and CONFLICT
  * are abstentions and appear as their own confusion columns. Researcher and
  * work pairs are always reported separately, and precision and recall are
  * always separate numbers.
  */
object Evaluator:
  private enum Predicted:
    case Accepted, Rejected, Unresolved, Conflict, Missing

  private final case class Joined(
      label: GoldLabel,
      record: Option[ResolutionRecord],
      predicted: Predicted
  )

  private def predictedOf(record: Option[ResolutionRecord]): Predicted =
    record.map(_.decision.decision) match
      case None                          => Predicted.Missing
      case Some(MatchOutcome.Accepted)   => Predicted.Accepted
      case Some(MatchOutcome.Rejected)   => Predicted.Rejected
      case Some(MatchOutcome.Unresolved) => Predicted.Unresolved
      case Some(MatchOutcome.Conflict)   => Predicted.Conflict

  private def ratio(numerator: Int, denominator: Int): Option[Double] =
    Option.when(denominator > 0)(numerator.toDouble / denominator)

  private def f1Of(precision: Option[Double], recall: Option[Double]) =
    for
      p <- precision
      r <- recall
      if p + r > 0
    yield 2 * p * r / (p + r)

  private def row(
      joined: Vector[Joined],
      predictions: Joined => Predicted
  ): Confusion =
    def count(verdict: Verdict, predicted: Predicted): Int =
      joined.count(j =>
        j.label.verdict == verdict && predictions(j) == predicted
      )
    def of(verdict: Verdict) = OutcomeRow(
      count(verdict, Predicted.Accepted),
      count(verdict, Predicted.Rejected),
      count(verdict, Predicted.Unresolved),
      count(verdict, Predicted.Conflict),
      count(verdict, Predicted.Missing)
    )
    Confusion(of(Verdict.Match), of(Verdict.NonMatch))

  private def metricsOf(confusion: Confusion): Metrics =
    val m = confusion.goldMatch
    val n = confusion.goldNonMatch
    val labeled = m.total + n.total
    val precision = ratio(m.accepted, m.accepted + n.accepted)
    val recall = ratio(m.accepted, m.total)
    Metrics(
      labeled = labeled,
      truePositive = m.accepted,
      falsePositive = n.accepted,
      falseNegative = m.rejected,
      trueNegative = n.rejected,
      precision = precision,
      recall = recall,
      f1 = f1Of(precision, recall),
      decidedRecall = ratio(m.accepted, m.accepted + m.rejected),
      coverage = ratio(
        m.accepted + m.rejected + n.accepted + n.rejected,
        labeled
      ),
      unresolvedRate = ratio(m.unresolved + n.unresolved, labeled),
      conflictRate = ratio(m.conflict + n.conflict, labeled)
    )

  private def pathMetrics(
      joined: Vector[Joined],
      positives: Int
  ): Vector[PathMetrics] =
    MatchPath.values.toVector.map { path =>
      val onPath = joined.filter(_.record.exists(_.path == path))
      def count(verdict: Verdict, predicted: Predicted) =
        onPath.count(j =>
          j.label.verdict == verdict && j.predicted == predicted
        )
      val tp = count(Verdict.Match, Predicted.Accepted)
      val fp = count(Verdict.NonMatch, Predicted.Accepted)
      val tn = count(Verdict.NonMatch, Predicted.Rejected)
      val fn = count(Verdict.Match, Predicted.Rejected)
      PathMetrics(
        path = path.wire,
        accepted = tp + fp,
        truePositive = tp,
        falsePositive = fp,
        precision = ratio(tp, tp + fp),
        rejected = tn + fn,
        trueNegative = tn,
        falseNegative = fn,
        negativePredictiveValue = ratio(tn, tn + fn),
        recallContribution = ratio(tp, positives)
      )
    }

  private def example(joined: Joined): ErrorExample =
    val label = joined.label
    ErrorExample(
      itemId = label.itemId,
      leftId = label.leftId,
      rightId = label.rightId,
      leftSourceRecordId = label.leftSourceRecordId,
      rightSourceRecordId = label.rightSourceRecordId,
      outcome = joined.record.fold("MISSING")(_.decision.decision.wire),
      path = joined.record.map(_.path.wire),
      score = joined.record.map(_.score),
      rulesFired =
        joined.record.fold(Vector.empty[String])(_.decision.rulesFired),
      reasonCodes =
        joined.record.fold(Vector.empty[String])(_.decision.reasonCodes),
      goldVerdict = label.verdict.wire,
      notes = label.notes
    )

  private def errors(joined: Vector[Joined], sample: Int): ErrorAnalysis =
    def score(j: Joined) = j.record.fold(0.0)(_.score)
    val fp = joined
      .filter(j =>
        j.label.verdict == Verdict.NonMatch && j.predicted == Predicted.Accepted
      )
      .sortBy(j => (-score(j), j.label.itemId))
    val fn = joined
      .filter(j =>
        j.label.verdict == Verdict.Match && j.predicted == Predicted.Rejected
      )
      .sortBy(j => (score(j), j.label.itemId))
    val abstained = joined
      .filter(j =>
        j.label.verdict == Verdict.Match &&
          Set(Predicted.Unresolved, Predicted.Conflict, Predicted.Missing)
            .contains(j.predicted)
      )
      .sortBy(j => (-score(j), j.label.itemId))
    ErrorAnalysis(
      fp.take(sample).map(example),
      fn.take(sample).map(example),
      abstained.take(sample).map(example),
      fp.size,
      fn.size,
      abstained.size
    )

  /** Whether a record's outcome follows from its score and the thresholds. Rule
    * and identifier outcomes, conflicts, and pairs the matcher never saw do
    * not, so they are held fixed in the sensitivity sweep.
    */
  private def followsThresholds(record: ResolutionRecord): Boolean =
    record.path == MatchPath.Probabilistic &&
      !record.decision.reasonCodes.contains(Matcher.SideMissing) &&
      !record.decision.reasonCodes.contains(Matcher.InvalidCandidate)

  private def predictAt(accept: Double, reject: Double)(
      joined: Joined
  ): Predicted =
    joined.record match
      case Some(record) if followsThresholds(record) =>
        val capped = record.decision.reasonCodes.exists(code =>
          code == Matcher.CapCorroboration || code == Matcher.CapNameGate
        )
        if record.score >= accept && !capped then Predicted.Accepted
        else if record.score < reject then Predicted.Rejected
        else Predicted.Unresolved
      case _ => joined.predicted

  def evaluate(
      records: Vector[ResolutionRecord],
      labels: Vector[GoldLabel],
      policy: Policy,
      config: EvaluationConfig = EvaluationConfig()
  ): EvaluationReport =
    val byPair = records.map(r => (r.kind, r.leftId, r.rightId) -> r).toMap
    val joined = labels.map { label =>
      val record = byPair.get(label.pairKey)
      Joined(label, record, predictedOf(record))
    }
    val scored = joined.filter(_.label.verdict != Verdict.Unsure)
    val unsure = joined.size - scored.size
    val reports = EntityKind.values.toVector.flatMap { kind =>
      val ofKind = scored.filter(_.label.kind == kind)
      Option.when(ofKind.nonEmpty) {
        val confusion = row(ofKind, _.predicted)
        KindReport(
          kind = kind.wire,
          labeledPairs = ofKind.size,
          unsureExcluded = joined.count(j =>
            j.label.kind == kind && j.label.verdict == Verdict.Unsure
          ),
          confusion = confusion,
          metrics = metricsOf(confusion),
          byPath = pathMetrics(ofKind, confusion.goldMatch.total),
          errors = errors(ofKind, config.errorSampleSize)
        )
      }
    }
    val acceptGrid =
      (config.acceptSweep :+ policy.accept).distinct.sorted
    val rejectGrid =
      (config.rejectSweep :+ policy.reject).distinct.sorted
    val sensitivity = for
      kind <- EntityKind.values.toVector
      ofKind = scored.filter(_.label.kind == kind)
      if ofKind.nonEmpty
      accept <- acceptGrid
      reject <- rejectGrid
      if reject < accept
    yield
      val metrics = metricsOf(row(ofKind, predictAt(accept, reject)))
      SensitivityPoint(
        kind = kind.wire,
        accept = accept,
        reject = reject,
        isPolicyThreshold = accept == policy.accept && reject == policy.reject,
        accepted = metrics.truePositive + metrics.falsePositive,
        rejected = metrics.trueNegative + metrics.falseNegative,
        precision = metrics.precision,
        recall = metrics.recall,
        f1 = metrics.f1,
        coverage = metrics.coverage,
        unresolvedRate = metrics.unresolvedRate
      )
    EvaluationReport(
      policyVersion = policy.version,
      acceptThreshold = policy.accept,
      rejectThreshold = policy.reject,
      totalLabeled = scored.size,
      minimumRequired = config.minimumLabeledPairs,
      meetsMinimum = scored.size >= config.minimumLabeledPairs,
      kinds = reports,
      sensitivity = sensitivity,
      warnings = warnings(scored, unsure, reports, config)
    )

  private def warnings(
      scored: Vector[Joined],
      unsure: Int,
      reports: Vector[KindReport],
      config: EvaluationConfig
  ): Vector[String] =
    Vector(
      Option.when(scored.size < config.minimumLabeledPairs)(
        s"Only ${scored.size} labeled pairs; at least ${config.minimumLabeledPairs} are required"
      ),
      Option.when(unsure > 0)(
        s"$unsure pairs labeled unsure were excluded from precision and recall"
      ),
      Option.when(scored.exists(_.record.isEmpty))(
        s"${scored.count(_.record.isEmpty)} labeled pairs have no matcher record"
      )
    ).flatten ++ reports.flatMap { report =>
      Vector(
        Option.when(
          report.errors.availableFalsePositives < config.errorSampleSize
        )(
          s"${report.kind}: only ${report.errors.availableFalsePositives} false positives available, fewer than ${config.errorSampleSize}"
        ),
        Option.when(
          report.errors.availableFalseNegatives < config.errorSampleSize
        )(
          s"${report.kind}: only ${report.errors.availableFalseNegatives} false negatives available, fewer than ${config.errorSampleSize}"
        )
      ).flatten
    }
