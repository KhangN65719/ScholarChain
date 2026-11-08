package scholarchain.er.eval.assess

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.MatchOutcome
import scholarchain.er.eval.EvaluationConfig
import scholarchain.er.eval.EvaluationReport
import scholarchain.er.eval.Evaluator
import scholarchain.er.eval.GoldFile
import scholarchain.er.eval.GoldLabel
import scholarchain.er.eval.OutcomeRow
import scholarchain.er.eval.Verdict
import scholarchain.er.matching.MatchPath
import scholarchain.er.matching.Policy
import scholarchain.er.matching.RecordIndex
import scholarchain.er.matching.ResolutionRecord
import scholarchain.er.matching.RunInfo
import scholarchain.er.pairs.AtomicFile
import scholarchain.er.pairs.Cli
import scholarchain.er.pairs.Hashing
import scholarchain.er.pairs.InputFiles
import scholarchain.er.pairs.Locations
import scholarchain.er.pairs.PairItem
import scholarchain.er.pairs.PairsFile
import scholarchain.er.sampling.PairsExport
import upickle.default.ReadWriter

/** Rows are the human verdict (including unsure); columns are the matcher. */
final case class ThreeWay(
    same: OutcomeRow,
    different: OutcomeRow,
    unsure: OutcomeRow
) derives ReadWriter

final case class Interval(low: Double, high: Double) derives ReadWriter

final case class Rates(
    pairs: Int,
    table: ThreeWay,
    precision: Option[Double],
    precisionInterval: Option[Interval],
    recall: Option[Double],
    recallInterval: Option[Interval],
    f1: Option[Double]
) derives ReadWriter

final case class StratumResult(stratum: String, rates: Rates) derives ReadWriter

/** Rates for the rows of one labeling pass of a two-pass gold file. Pass 2 is
  * the blind re-review; pass 1 rows are carried from the first labeling pass
  * and were not reviewed again.
  */
final case class PassResult(pass: Int, rates: Rates) derives ReadWriter

final case class ErrorRow(
    pairId: String,
    category: String,
    kind: String,
    stratum: String,
    gold: String,
    outcome: String,
    path: Option[String],
    score: Option[Double],
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    leftName: String,
    rightName: String,
    leftSourceRecordId: String,
    rightSourceRecordId: String,
    note: Option[String]
) derives ReadWriter

final case class BandResult(
    low: Double,
    high: Double,
    same: Int,
    different: Int,
    unsure: Int,
    eligibleForAdjudication: Int,
    abstainedByStub: Int,
    modelCalls: Int,
    model: String
) derives ReadWriter

final case class FullReport(
    policyVersion: String,
    accept: Double,
    reject: Double,
    inputs: Map[String, String],
    pooled: Rates,
    strata: Vector[StratumResult],
    band: BandResult,
    errors: Vector[ErrorRow],
    base: EvaluationReport,
    warnings: Vector[String],
    uncertainty: Vector[String],
    passes: Vector[PassResult] = Vector.empty
) derives ReadWriter:
  def toJson: String = upickle.default.write(this, indent = 2)

/** Scores a decisions file against the label file and the private strata key.
  * This is the only place where labels and strata meet decisions, and they meet
  * after every decision is made.
  */
object GoldEvaluation:
  val AcceptSweep = Vector(0.90, 0.92, 0.94, 0.95, 0.96, 0.98)
  val RejectSweep = Vector(0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50)
  val FalseAccept = "1-false-accept"
  val FalseReject = "2-false-reject"
  val MissedUnresolved = "3-same-left-unresolved"
  val MissedConflict = "3-same-left-conflict"
  val MissedNoRecord = "3-same-no-record"
  val ConflictOnDifferent = "4-different-flagged-conflict"
  val GoldUnsure = "5-gold-unsure"

  private final case class Joined(
      label: GoldLabel,
      record: Option[ResolutionRecord],
      pair: Option[PairItem],
      stratum: String,
      pass: Option[Int] = None
  ):
    def outcome: Option[MatchOutcome] = record.map(_.decision.decision)

  private def count(rows: Vector[Joined], o: MatchOutcome): Int =
    rows.count(_.outcome.contains(o))

  private def outcomeRow(rows: Vector[Joined], v: Verdict): OutcomeRow =
    val of = rows.filter(_.label.verdict == v)
    OutcomeRow(
      count(of, MatchOutcome.Accepted),
      count(of, MatchOutcome.Rejected),
      count(of, MatchOutcome.Unresolved),
      count(of, MatchOutcome.Conflict),
      of.count(_.record.isEmpty)
    )

  private def threeWay(rows: Vector[Joined]): ThreeWay =
    ThreeWay(
      outcomeRow(rows, Verdict.Match),
      outcomeRow(rows, Verdict.NonMatch),
      outcomeRow(rows, Verdict.Unsure)
    )

  private def ratio(n: Int, d: Int): Option[Double] =
    Option.when(d > 0)(n.toDouble / d)

  private def f1Of(p: Option[Double], r: Option[Double]): Option[Double] =
    p.zip(r).filter((a, b) => a + b > 0).map((a, b) => 2 * a * b / (a + b))

  /** Wilson 95% interval for k successes in n trials. */
  def wilson(k: Int, n: Int): Option[Interval] =
    Option.when(n > 0) {
      val z = 1.96
      val p = k.toDouble / n
      val denom = 1 + z * z / n
      val centre = (p + z * z / (2 * n)) / denom
      val half = z * math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom
      Interval(math.max(0.0, centre - half), math.min(1.0, centre + half))
    }

  private def rates(rows: Vector[Joined]): Rates =
    val t = threeWay(rows)
    val tp = t.same.accepted
    val accepted = tp + t.different.accepted
    val positives = t.same.total
    val p = ratio(tp, accepted)
    val r = ratio(tp, positives)
    Rates(
      rows.size,
      t,
      p,
      wilson(tp, accepted),
      r,
      wilson(tp, positives),
      f1Of(p, r)
    )

  private def category(j: Joined): Option[String] =
    import MatchOutcome.*
    import Verdict.*
    (j.label.verdict, j.outcome) match
      case (NonMatch, Some(Accepted)) => Some(FalseAccept)
      case (Match, Some(Rejected))    => Some(FalseReject)
      case (Match, Some(Unresolved))  => Some(MissedUnresolved)
      case (Match, Some(Conflict))    => Some(MissedConflict)
      case (Match, None)              => Some(MissedNoRecord)
      case (NonMatch, Some(Conflict)) => Some(ConflictOnDifferent)
      case (Unsure, _)                => Some(GoldUnsure)
      case _                          => None

  private def errorRow(j: Joined, cat: String): ErrorRow =
    val rec = j.record
    ErrorRow(
      j.label.itemId,
      cat,
      j.label.kind.wire,
      j.stratum,
      j.label.verdict.wire,
      rec.fold("MISSING")(_.decision.decision.wire),
      rec.map(_.path.wire),
      rec.map(_.score),
      rec.fold(Vector.empty[String])(_.decision.rulesFired),
      rec.fold(Vector.empty[String])(_.decision.reasonCodes),
      j.pair.fold("")(_.left.name),
      j.pair.fold("")(_.right.name),
      j.label.leftSourceRecordId,
      j.label.rightSourceRecordId,
      j.label.notes
    )

  private def errorsOf(rows: Vector[Joined]): Vector[ErrorRow] =
    val found = rows.flatMap(j => category(j).map(c => errorRow(j, c)))
    found.sortBy(e => (e.category, -e.score.getOrElse(0.0), e.pairId))

  private def inBand(policy: Policy)(j: Joined): Boolean =
    j.record.exists { r =>
      r.decision.decision == MatchOutcome.Unresolved &&
      r.path == MatchPath.Probabilistic && policy.inLlmBand(r.score)
    }

  private def bandOf(
      rows: Vector[Joined],
      policy: Policy,
      decisions: Decisions
  ): BandResult =
    val band = rows.filter(inBand(policy))
    val abstained = band.count { j =>
      j.record.exists(_.decision.reasonCodes.contains("llm.abstained"))
    }
    def of(v: Verdict) = band.count(_.label.verdict == v)
    BandResult(
      policy.llmBandLow,
      policy.accept,
      of(Verdict.Match),
      of(Verdict.NonMatch),
      of(Verdict.Unsure),
      decisions.inBand,
      abstained,
      decisions.modelCalls,
      PairDecisions.StubModel
    )

  private def join(
      decisions: Decisions,
      pairs: Vector[PairItem],
      labels: Vector[GoldLabel],
      strata: Map[String, String],
      passes: Map[String, Int]
  ): Vector[Joined] =
    val byKey = decisions.records.map(r => key(r) -> r).toMap
    val byId = pairs.map(p => p.pairId -> p).toMap
    labels.map { l =>
      val stratum = strata.getOrElse(l.itemId, "unknown")
      Joined(
        l,
        byKey.get(l.pairKey),
        byId.get(l.itemId),
        stratum,
        passes.get(l.itemId)
      )
    }

  private def key(r: ResolutionRecord) = (r.kind, r.leftId, r.rightId)

  private def warningsOf(
      rows: Vector[Joined],
      decisions: Decisions,
      base: EvaluationReport
  ): Vector[String] =
    val pooled = threeWay(rows).same.accepted
    val summed = base.kinds.map(_.metrics.truePositive).sum
    Vector(
      Option.when(decisions.unmatched.nonEmpty)(
        s"${decisions.unmatched.size} pairs have no records in the index"
      ),
      Option.when(rows.exists(_.pair.isEmpty))(
        "some labels are not in the pairs file"
      ),
      Option.when(rows.exists(_.stratum == "unknown"))(
        "some labels have no stratum in the key"
      ),
      Option.when(pooled != summed)(
        s"cross-check failed: pooled TP $pooled, per-kind TP $summed"
      )
    ).flatten ++ base.warnings

  def uncertainty(
      n: Int,
      unsure: Int,
      changed: Int,
      noSuggestion: Boolean = false
  ): Vector[String] =
    val reviewer =
      if noSuggestion then
        "Gold labels come from one reviewer. No label in this file was made " +
          "with a suggestion visible, so there is no suggestion to agree with."
      else
        "Gold labels come from one reviewer who saw an external suggestion; " +
          s"$changed labels differed from it. Agreement with a suggestion the " +
          "reviewer saw may overstate independence."
    Vector(
      s"n = $n labeled pairs, $unsure labeled unsure and excluded from " +
        "precision and recall; the metrics rest on that small sample.",
      "The sample is stratified by design (exact-id, hard-common-name, " +
        "clear-non-match, about a third each), not random, so these are not " +
        "population rates. Pooled precision and recall depend on the mix.",
      "Pooled precision does not measure the probabilistic path when the " +
        "export left out pairs whose context agrees (includeAgreeingContext " +
        "false), and the exact-id stratum is selected by the same shared " +
        "identifier rule the exact-id path accepts on, so its accepts agree " +
        "with it by construction. A high pooled precision therefore says " +
        "nothing about the probabilistic or deterministic-bibliographic " +
        "accept paths.",
      "Intervals are Wilson 95% intervals for a simple binomial, which " +
        "ignores the stratification and the clustering of pairs by author.",
      "Recall is measured over candidate pairs only. Matches that blocking " +
        "never proposed are not in the sample, so true recall is lower or " +
        "equal.",
      reviewer,
      "DBLP names carry numeric homonym suffixes, so many DBLP-DBLP labels " +
        "are decided by DBLP's own disambiguation. Scores against those " +
        "labels measure agreement with DBLP, not independent truth.",
      "Thresholds are provisional and hand-set. The sweep reads them off " +
        "this sample only; a change chosen from it would be tuned to the " +
        "evaluation set and needs a separate sample."
    )

  /** What the export manifest says about excluded agreeing-context pairs, or
    * None when the manifest does not carry the fields.
    */
  def exportNote(manifestJson: String): Option[String] =
    try
      val json = ujson.read(manifestJson).obj
      json.value.get("includeAgreeingContext").collect {
        case ujson.Bool(included) =>
          val excluded = json.value
            .get("excluded")
            .collect { case o: ujson.Obj => o.value }
            .flatMap(_.get("same-name-agreeing-context"))
            .collect { case ujson.Num(n) => n.toInt }
          s"Export manifest: includeAgreeingContext $included; " +
            excluded.fold("no excluded count recorded")(n =>
              s"$n same-name agreeing-context pairs were excluded " +
                "from the sample"
            ) + "."
      }
    catch case _: Exception => None

  def evaluate(
      decisions: Decisions,
      pairs: Vector[PairItem],
      labels: Vector[GoldLabel],
      strata: Map[String, String],
      policy: Policy,
      inputs: Map[String, String],
      changed: Int,
      passes: Map[String, Int] = Map.empty,
      exportNote: Option[String] = None
  ): FullReport =
    val config = EvaluationConfig(
      acceptSweep = AcceptSweep,
      rejectSweep = RejectSweep
    )
    val base = Evaluator.evaluate(decisions.records, labels, policy, config)
    val rows = join(decisions, pairs, labels, strata, passes)
    val unsure = rows.count(_.label.verdict == Verdict.Unsure)
    val byPass = rows.flatMap(_.pass).distinct.sorted.map { p =>
      PassResult(p, rates(rows.filter(_.pass.contains(p))))
    }
    val carried = rows.count(_.pass.contains(1))
    val blind = rows.count(_.pass.contains(2))
    val reviewedAgain = rows.count(_.pass.contains(3))
    val noSuggestion = reviewedAgain > 0 && carried == 0
    val unmarked = passes.nonEmpty && rows.exists(_.pass.isEmpty)
    val names = rows.map(_.stratum).distinct.sorted
    val byStratum = names.map { s =>
      StratumResult(s, rates(rows.filter(_.stratum == s)))
    }
    FullReport(
      policy.version,
      policy.accept,
      policy.reject,
      inputs,
      rates(rows),
      byStratum,
      bandOf(rows, policy, decisions),
      errorsOf(rows),
      base,
      warningsOf(rows, decisions, base) ++
        Option
          .when(unmarked)("some labels have no pass in the gold file")
          .toVector,
      uncertainty(rows.size, unsure, changed, noSuggestion) ++
        exportNote.toVector ++
        Option
          .when(noSuggestion)(
            s"This gold file has no with-suggestion label: $blind labels " +
              s"were reviewed blind in pass 2 and $reviewedAgain in pass 3, " +
              "a blind re-review of the pass-1 pairs. The pooled and " +
              "per-stratum numbers mix the two passes; read the by-pass " +
              "section. The reviewer had seen every pair before, with " +
              "suggestions, so memory may still anchor the labels."
          )
          .toVector ++
        Option
          .when(byPass.nonEmpty && !noSuggestion)(
            s"This gold file has two passes. $carried labels are carried " +
              s"from the first pass, made with suggestions and not reviewed " +
              s"again; only $blind were reviewed blind. The pooled and " +
              "per-stratum numbers mix the two. Read the by-pass section, " +
              "and do not describe the carried labels as freshly reviewed."
          )
          .toVector,
      byPass
    )
end GoldEvaluation
