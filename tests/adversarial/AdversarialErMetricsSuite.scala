package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.{ContractSchema, MatchOutcome}
import scholarchain.er.eval.*
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** Evaluation arithmetic on a gold set small enough to count by hand. */
class AdversarialErMetricsSuite extends FunSuite:

  import MatchOutcome.*
  import MatchPath.*

  private def label(
      id: String,
      kind: EntityKind,
      verdict: Verdict,
      left: String = "",
      right: String = ""
  ): GoldLabel =
    val l = if left.isEmpty then s"$id-l" else left
    val r = if right.isEmpty then s"$id-r" else right
    GoldLabel(
      ContractSchema.currentVersion,
      id,
      kind,
      l,
      r,
      s"rec-$l",
      s"rec-$r",
      "snap-1",
      verdict,
      "human",
      "reviewer-1",
      "2026-11-01T00:00:00Z",
      None
    )

  private def rec(
      id: String,
      kind: EntityKind,
      o: MatchOutcome,
      p: MatchPath,
      score: Double = 0.9,
      reasons: Vector[String] = Vector.empty
  ) = record(kind, s"$id-l", s"$id-r", o, p, score, reasons)

  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  /** (id, gold, outcome, path). Expected counts are in the comments. */
  private val rows
      : Vector[(String, Verdict, Option[(MatchOutcome, MatchPath)])] =
    import Verdict.*
    Vector(
      ("e1", Match, Some((Accepted, ExactId))),
      ("e2", Match, Some((Accepted, ExactId))),
      ("e3", Match, Some((Accepted, ExactId))),
      ("e4", NonMatch, Some((Accepted, ExactId))),
      ("p1", Match, Some((Accepted, Probabilistic))),
      ("p2", Match, Some((Accepted, Probabilistic))),
      ("p3", NonMatch, Some((Rejected, Probabilistic))),
      ("p4", NonMatch, Some((Rejected, Probabilistic))),
      ("p5", Match, Some((Rejected, Probabilistic))),
      ("p6", Match, Some((Unresolved, Probabilistic))),
      ("p7", NonMatch, Some((Unresolved, Probabilistic))),
      ("d1", NonMatch, Some((Rejected, DeterministicBibliographic))),
      ("l1", Match, Some((Accepted, LlmAdjudicated))),
      ("l2", NonMatch, Some((Accepted, LlmAdjudicated))),
      ("c1", NonMatch, Some((Conflict, ExactId))),
      ("x1", Match, None),
      ("u1", Unsure, Some((Accepted, Probabilistic)))
    )

  private val labels = rows.map((id, v, _) => label(id, R, v))
  private val records = rows.collect { case (id, _, Some((o, p))) =>
    rec(id, R, o, p)
  }
  private val report = Evaluator.evaluate(records, labels, policy)
  private val researchers = report.kinds.find(_.kind == "researcher").get

  test("totals exclude the unsure label") {
    assertEquals(report.totalLabeled, 16)
    assertEquals(researchers.labeledPairs, 16)
    assertEquals(researchers.unsureExcluded, 1)
    assertEquals(report.kinds.size, 1)
  }

  test("confusion matrix counts match the hand tally") {
    val c = researchers.confusion
    assertEquals(c.goldMatch, OutcomeRow(6, 1, 1, 0, 1))
    assertEquals(c.goldNonMatch, OutcomeRow(2, 3, 1, 1, 0))
  }

  test("precision, recall, F1, coverage and rates are exact") {
    val m = researchers.metrics
    assertEquals((m.truePositive, m.falsePositive), (6, 2))
    assertEquals((m.falseNegative, m.trueNegative), (1, 3))
    assertEqualsDouble(m.precision.get, 6.0 / 8, 1e-12)
    assertEqualsDouble(m.recall.get, 6.0 / 9, 1e-12)
    val p = 6.0 / 8
    val r = 6.0 / 9
    assertEqualsDouble(m.f1.get, 2 * p * r / (p + r), 1e-12)
    assertEqualsDouble(m.decidedRecall.get, 6.0 / 7, 1e-12)
    assertEqualsDouble(m.coverage.get, 12.0 / 16, 1e-12)
    assertEqualsDouble(m.unresolvedRate.get, 2.0 / 16, 1e-12)
    assertEqualsDouble(m.conflictRate.get, 1.0 / 16, 1e-12)
  }

  test("precision and recall are separate numbers, not one aggregate") {
    val m = researchers.metrics
    assertNotEquals(m.precision, m.recall)
  }

  test("metrics are reported per match path") {
    def path(w: String) = researchers.byPath.find(_.path == w).get
    assertEquals(
      researchers.byPath.map(_.path).toSet,
      MatchPath.values.map(_.wire).toSet
    )
    val exact = path("exact-id")
    assertEquals((exact.truePositive, exact.falsePositive), (3, 1))
    assertEqualsDouble(exact.precision.get, 0.75, 1e-12)
    assertEqualsDouble(exact.recallContribution.get, 3.0 / 9, 1e-12)
    val prob = path("probabilistic")
    assertEquals((prob.truePositive, prob.falsePositive), (2, 0))
    assertEquals((prob.trueNegative, prob.falseNegative), (2, 1))
    assertEqualsDouble(prob.precision.get, 1.0, 1e-12)
    assertEqualsDouble(prob.negativePredictiveValue.get, 2.0 / 3, 1e-12)
    val llm = path("llm-adjudicated")
    assertEquals((llm.truePositive, llm.falsePositive), (1, 1))
    assertEqualsDouble(llm.precision.get, 0.5, 1e-12)
    val det = path("deterministic-bibliographic")
    assertEquals(det.accepted, 0)
    assertEquals(det.precision, None)
    assertEquals(det.trueNegative, 1)
    assertEqualsDouble(det.negativePredictiveValue.get, 1.0, 1e-12)
  }

  test("the per-path accepted counts sum to the overall accepted count") {
    val accepted = researchers.byPath.map(_.accepted).sum
    assertEquals(
      accepted,
      researchers.metrics.truePositive + researchers.metrics.falsePositive
    )
  }

  test("a conflict is an abstention, not a rejection or an acceptance") {
    assertEquals(researchers.confusion.goldNonMatch.conflict, 1)
    val exact = researchers.byPath.find(_.path == "exact-id").get
    assertEquals(exact.trueNegative, 0)
  }

  test("fewer than 200 labels is reported, not hidden") {
    assert(!report.meetsMinimum)
    assertEquals(report.minimumRequired, 200)
    assert(report.warnings.exists(_.contains("at least 200")))
    assert(report.warnings.exists(_.contains("unsure")))
    assert(report.warnings.exists(_.contains("no matcher record")))
  }

  test(
    "error analysis lists the false positives and negatives with their sources"
  ) {
    val e = researchers.errors
    assertEquals(e.falsePositives.map(_.itemId).toSet, Set("e4", "l2"))
    assertEquals(e.falseNegatives.map(_.itemId), Vector("p5"))
    assertEquals(e.abstainedPositives.map(_.itemId).toSet, Set("p6", "x1"))
    assert(
      e.falsePositives.forall(x =>
        x.leftSourceRecordId.nonEmpty && x.rightSourceRecordId.nonEmpty
      )
    )
    assertEquals(e.falsePositives.head.goldVerdict, "non-match")
  }

  test("the error sample is capped but the available count is not") {
    val twelve = (1 to 12).toVector.map(i => f"f$i%02d")
    val ls = twelve.map(label(_, R, Verdict.NonMatch))
    val rs = twelve.zipWithIndex.map((id, i) =>
      rec(id, R, Accepted, Probabilistic, 0.95 + i * 0.001)
    )
    val e = Evaluator.evaluate(rs, ls, policy).kinds.head.errors
    assertEquals(e.falsePositives.size, 10)
    assertEquals(e.availableFalsePositives, 12)
    assertEquals(e.falsePositives.head.itemId, "f12")
  }

  test("researchers and works are reported separately") {
    val workLabels = Vector(
      label("wa", W, Verdict.Match),
      label("wb", W, Verdict.NonMatch)
    )
    val workRecords = Vector(
      rec("wa", W, Accepted, ExactId),
      rec("wb", W, Accepted, ExactId)
    )
    val both =
      Evaluator.evaluate(records ++ workRecords, labels ++ workLabels, policy)
    assertEquals(both.kinds.map(_.kind).toSet, Set("researcher", "work"))
    val works = both.kinds.find(_.kind == "work").get
    assertEqualsDouble(works.metrics.precision.get, 0.5, 1e-12)
    assertEqualsDouble(works.metrics.recall.get, 1.0, 1e-12)
    val rs = both.kinds.find(_.kind == "researcher").get
    assertEquals(rs.metrics, researchers.metrics)
  }

  test("degenerate inputs give None, never NaN or an exception") {
    val none = Evaluator.evaluate(Vector.empty, Vector.empty, policy)
    assertEquals(none.kinds, Vector.empty)
    assert(!none.meetsMinimum)
    val onlyUnresolved = Evaluator.evaluate(
      Vector(rec("a", R, Unresolved, Probabilistic, 0.8)),
      Vector(label("a", R, Verdict.Match)),
      policy
    )
    val m = onlyUnresolved.kinds.head.metrics
    assertEquals(m.precision, None)
    assertEqualsDouble(m.recall.get, 0.0, 1e-12)
    assertEquals(m.f1, None)
    val allUnsure = Evaluator.evaluate(
      Vector.empty,
      Vector(label("u", R, Verdict.Unsure)),
      policy
    )
    assertEquals(allUnsure.totalLabeled, 0)
    assertEquals(allUnsure.kinds, Vector.empty)
  }

  test("the sensitivity sweep follows scores, fixes rules and honors caps") {
    val ids = Vector("s1", "s2", "s3", "s4")
    val gold = Vector(
      label("s1", R, Verdict.Match),
      label("s2", R, Verdict.Match),
      label("s3", R, Verdict.NonMatch),
      label("s4", R, Verdict.NonMatch)
    )
    val rs = Vector(
      rec("s1", R, Accepted, Probabilistic, 0.96),
      rec("s2", R, Unresolved, Probabilistic, 0.90),
      rec("s3", R, Unresolved, Probabilistic, 0.85),
      rec("s4", R, Unresolved, Probabilistic, 0.99, Vector(Matcher.CapNameGate))
    )
    val rep = Evaluator.evaluate(rs, gold, policy)
    def point(a: Double, r: Double) =
      rep.sensitivity
        .find(p => p.kind == "researcher" && p.accept == a && p.reject == r)
        .get
    val strict = point(0.95, 0.35)
    assertEquals(strict.accepted, 1)
    assertEqualsDouble(strict.precision.get, 1.0, 1e-12)
    val loose = point(0.80, 0.35)
    assertEquals(loose.accepted, 3)
    assertEqualsDouble(loose.precision.get, 2.0 / 3, 1e-12)
    assertEquals(point(0.99, 0.35).accepted, 0)
    assert(rep.sensitivity.filter(_.isPolicyThreshold).size == 1)
    assert(rep.sensitivity.forall(p => p.reject < p.accept))
    assertEquals(ids.size, 4)
  }

  test("a threshold exactly at a score accepts it") {
    val rs = Vector(rec("t1", R, Unresolved, Probabilistic, 0.80))
    val rep =
      Evaluator.evaluate(rs, Vector(label("t1", R, Verdict.Match)), policy)
    val p = rep.sensitivity.find(p => p.accept == 0.80 && p.reject == 0.35).get
    assertEquals(p.accepted, 1)
  }

  test("a label that is not human-reviewed or is malformed is refused") {
    val ok = label("g", R, Verdict.Match)
    intercept[IllegalArgumentException](
      ok.copy(reviewMethod = "model-suggestion")
    )
    intercept[IllegalArgumentException](ok.copy(reviewer = " "))
    intercept[IllegalArgumentException](ok.copy(leftSourceRecordId = ""))
    intercept[IllegalArgumentException](ok.copy(leftId = "z", rightId = "a"))
    intercept[Exception](ok.copy(reviewedAt = "yesterday"))
  }

  test("a label file with contradictory verdicts for one pair is refused") {
    import upickle.default.write
    val a = write(label("g", R, Verdict.Match, "a", "b"))
    val b = write(label("h", R, Verdict.NonMatch, "a", "b"))
    intercept[IllegalArgumentException](GoldFile.parse(Vector(a, "", b)))
    assertEquals(GoldFile.parse(Vector(a, a)).size, 1)
    intercept[IllegalArgumentException](GoldFile.parse(Vector("{not json")))
  }
