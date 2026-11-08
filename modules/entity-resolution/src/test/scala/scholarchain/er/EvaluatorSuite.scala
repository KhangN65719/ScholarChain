package scholarchain.er

import scholarchain.core.{
  ContractSchema,
  MatchDecision,
  MatchOutcome,
  SourceType
}
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.*
import scholarchain.er.matching.*
import upickle.default.write

class EvaluatorSuite extends munit.FunSuite:
  private val policy = Policy.v1

  private def record(
      kind: EntityKind,
      left: String,
      right: String,
      outcome: MatchOutcome,
      path: MatchPath,
      score: Double,
      reasons: Vector[String] = Vector("test.reason")
  ): ResolutionRecord =
    ResolutionRecord(
      ContractSchema.currentVersion,
      kind,
      path,
      s"rec-$left",
      s"rec-$right",
      MatchDecision(
        ContractSchema.currentVersion,
        left,
        right,
        Map("score.logit" -> 0.0),
        Vector(s"path:${path.wire}"),
        outcome,
        score,
        reasons,
        policy.version,
        None,
        None,
        runInfo.decidedAtText,
        runId,
        provenance(SourceType.Derived, s"$left|$right")
      )
    )

  private def label(
      kind: EntityKind,
      left: String,
      right: String,
      verdict: Verdict
  ): GoldLabel =
    GoldLabel(
      ContractSchema.currentVersion,
      s"item-$left-$right",
      kind,
      left,
      right,
      s"rec-$left",
      s"rec-$right",
      "snap-1",
      verdict,
      "human",
      "reviewer-1",
      "2026-11-01T00:00:00Z",
      None
    )

  import MatchOutcome.*
  import MatchPath.*
  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  private val researcherRecords = Vector(
    record(R, "r01", "s01", Accepted, ExactId, 0.99),
    record(R, "r02", "s02", Accepted, Probabilistic, 0.97),
    record(R, "r03", "s03", Accepted, Probabilistic, 0.96),
    record(R, "r04", "s04", Rejected, Probabilistic, 0.20),
    record(R, "r05", "s05", Unresolved, Probabilistic, 0.80),
    record(R, "r06", "s06", Conflict, ExactId, 0.90),
    record(R, "r07", "s07", Accepted, Probabilistic, 0.98),
    record(R, "r08", "s08", Rejected, Probabilistic, 0.10),
    record(R, "r09", "s09", Rejected, DeterministicBibliographic, 0.02)
  )
  private val workRecords = Vector(
    record(W, "w1", "x1", Accepted, DeterministicBibliographic, 0.97),
    record(W, "w2", "x2", Rejected, Probabilistic, 0.05)
  )
  private val labels = Vector(
    label(R, "r01", "s01", Verdict.Match),
    label(R, "r02", "s02", Verdict.Match),
    label(R, "r03", "s03", Verdict.Match),
    label(R, "r04", "s04", Verdict.Match),
    label(R, "r05", "s05", Verdict.Match),
    label(R, "r06", "s06", Verdict.Match),
    label(R, "r07", "s07", Verdict.NonMatch),
    label(R, "r08", "s08", Verdict.NonMatch),
    label(R, "r09", "s09", Verdict.NonMatch),
    label(R, "r10", "s10", Verdict.NonMatch),
    label(R, "r11", "s11", Verdict.Unsure),
    label(W, "w1", "x1", Verdict.Match),
    label(W, "w2", "x2", Verdict.NonMatch)
  )
  private val report =
    Evaluator.evaluate(researcherRecords ++ workRecords, labels, policy)

  private def kind(name: String) = report.kinds.find(_.kind == name).get

  test("researcher and work resolution are reported separately") {
    assertEquals(report.kinds.map(_.kind), Vector("researcher", "work"))
    assertEquals(kind("researcher").labeledPairs, 10)
    assertEquals(kind("work").labeledPairs, 2)
    assertEquals(report.totalLabeled, 12)
    assertEquals(kind("researcher").unsureExcluded, 1)
  }

  test("the confusion matrix has a column for every outcome") {
    val confusion = kind("researcher").confusion
    assertEquals(confusion.goldMatch, OutcomeRow(3, 1, 1, 1, 0))
    assertEquals(confusion.goldNonMatch, OutcomeRow(1, 2, 0, 0, 1))
  }

  test("precision, recall and F1 are separate numbers with exact values") {
    val metrics = kind("researcher").metrics
    assertEquals(metrics.precision, Some(0.75))
    assertEquals(metrics.recall, Some(0.5))
    assertEqualsDouble(metrics.f1.get, 0.6, 1e-12)
    assertEquals(metrics.decidedRecall, Some(0.75))
    assertEquals(metrics.coverage, Some(0.7))
    assertEquals(metrics.unresolvedRate, Some(0.1))
    assertEquals(metrics.conflictRate, Some(0.1))
    val work = kind("work").metrics
    assertEquals(
      (work.precision, work.recall, work.f1),
      (Some(1.0), Some(1.0), Some(1.0))
    )
  }

  test("abstentions lower recall but not precision") {
    val metrics = kind("researcher").metrics
    assert(metrics.recall.get < metrics.decidedRecall.get)
    assertEquals(metrics.truePositive, 3)
    assertEquals(metrics.falsePositive, 1)
  }

  test("metrics are reported for each match path") {
    val paths = kind("researcher").byPath.map(p => p.path -> p).toMap
    assertEquals(paths.keySet, MatchPath.values.map(_.wire).toSet)
    assertEquals(paths("exact-id").precision, Some(1.0))
    assertEquals(paths("exact-id").accepted, 1)
    assertEquals(paths("probabilistic").accepted, 3)
    assertEqualsDouble(paths("probabilistic").precision.get, 2.0 / 3, 1e-12)
    assertEquals(paths("probabilistic").negativePredictiveValue, Some(0.5))
    assertEquals(paths("deterministic-bibliographic").precision, None)
    assertEquals(
      paths("deterministic-bibliographic").negativePredictiveValue,
      Some(1.0)
    )
    assertEquals(paths("llm-adjudicated").accepted, 0)
  }

  test("error analysis lists errors with their source records") {
    val errors = kind("researcher").errors
    assertEquals(errors.falsePositives.map(_.leftId), Vector("r07"))
    assertEquals(errors.falsePositives.head.leftSourceRecordId, "rec-r07")
    assertEquals(errors.falseNegatives.map(_.leftId), Vector("r04"))
    assertEquals(
      errors.abstainedPositives.map(_.leftId).toSet,
      Set("r05", "r06")
    )
    assertEquals(errors.availableFalsePositives, 1)
  }

  test("a report with fewer than ten errors says so") {
    assert(report.warnings.exists(_.contains("only 1 false positives")))
    assert(report.warnings.exists(_.contains("at least 200")))
    assert(!report.meetsMinimum)
  }

  test("two hundred reviewed pairs meet the minimum") {
    val many = (1 to 200).toVector.map(i => f"p$i%03d")
    val records =
      many.map(id => record(R, id, s"q$id", Rejected, Probabilistic, 0.1))
    val golds = many.map(id => label(R, id, s"q$id", Verdict.NonMatch))
    val big = Evaluator.evaluate(records, golds, policy)
    assert(big.meetsMinimum)
    assertEquals(big.kinds.map(_.kind), Vector("researcher"))
  }

  test("threshold sensitivity re-applies thresholds to scored pairs only") {
    def at(accept: Double, reject: Double) =
      report.sensitivity
        .find(p =>
          p.kind == "researcher" && p.accept == accept && p.reject == reject
        )
        .get
    val current = at(0.95, 0.35)
    assert(current.isPolicyThreshold)
    assertEquals(current.precision, Some(0.75))
    val strict = at(0.99, 0.35)
    assertEquals(strict.accepted, 1)
    assertEquals(strict.precision, Some(1.0))
    assertEqualsDouble(strict.recall.get, 1.0 / 6, 1e-12)
    val loose = at(0.80, 0.35)
    assertEquals(loose.accepted, 5)
    assertEquals(loose.precision, Some(0.8))
    assert(report.sensitivity.forall(point => point.reject < point.accept))
  }

  test("capped and rule outcomes are held fixed in the sweep") {
    val capped = record(
      R,
      "c1",
      "d1",
      Unresolved,
      Probabilistic,
      0.99,
      Vector(Matcher.CapCorroboration)
    )
    val only = Evaluator.evaluate(
      Vector(capped),
      Vector(label(R, "c1", "d1", Verdict.Match)),
      policy
    )
    assert(only.sensitivity.forall(_.accepted == 0))
  }

  test("the report serializes to JSON") {
    val json = ujson.read(report.toJson)
    assertEquals(json("policyVersion").str, policy.version)
    assert(json("kinds").arr.size == 2)
  }

  test("a label must be human reviewed and traceable to source records") {
    def make(
        method: String = "human",
        reviewer: String = "alice",
        at: String = "2026-11-01T00:00:00Z",
        leftRecord: String = "rec-a",
        snapshot: String = "snap",
        left: String = "a",
        right: String = "b"
    ) = GoldLabel(
      ContractSchema.currentVersion,
      "item",
      R,
      left,
      right,
      leftRecord,
      "rec-b",
      snapshot,
      Verdict.Match,
      method,
      reviewer,
      at,
      None
    )
    make()
    intercept[IllegalArgumentException](make(method = "model"))
    intercept[IllegalArgumentException](make(method = "llm-suggestion"))
    intercept[IllegalArgumentException](make(reviewer = " "))
    intercept[IllegalArgumentException](make(leftRecord = ""))
    intercept[IllegalArgumentException](make(snapshot = ""))
    intercept[IllegalArgumentException](make(left = "b", right = "a"))
    intercept[java.time.format.DateTimeParseException](make(at = "yesterday"))
  }

  test("a label file parses, rejects bad lines and inconsistent duplicates") {
    val good = labels.take(3).map(write(_))
    assertEquals(GoldFile.parse(good :+ "  ").size, 3)
    val bad = intercept[IllegalArgumentException](
      GoldFile.parse(good :+ """{"itemId":"x"}""")
    )
    assert(bad.getMessage.contains("line 4"))
    val flipped = write(label(R, "r01", "s01", Verdict.NonMatch))
    intercept[IllegalArgumentException](GoldFile.parse(good :+ flipped))
    assertEquals(GoldFile.parse(good ++ good).size, 3)
  }
