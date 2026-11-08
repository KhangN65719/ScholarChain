package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, MatchOutcome}
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.assess.*
import scholarchain.er.eval.{GoldLabel, Verdict}
import scholarchain.er.matching.{
  EntityKind,
  MatchPath,
  Policy,
  RecordIndex,
  SideRecord
}
import scholarchain.er.pairs.{PairItem, PairSide, PairsFile}
import upickle.default.{read, write}

class GoldEvaluationSuite extends munit.FunSuite:
  private val policy = Policy.v1
  private val titleA = "Quantum lattice chromodynamics methods"
  private val titleB = "Medieval pottery trade networks"

  private def sides(
      id: String,
      left: (String, Map[String, String], Vector[String]),
      right: (String, Map[String, String], Vector[String])
  ): (SideRecord, SideRecord) =
    val (nameA, idsA, coA) = left
    val (nameB, idsB, coB) = right
    (
      mention(s"${id}a", nameA, coA, Vector.empty, idsA, titleA),
      mention(s"${id}b", nameB, coB, Vector.empty, idsB, titleB)
    )

  private val jane = "Jane Doe"
  private val none = Map.empty[String, String]
  private val orcidA = Map("orcid" -> validOrcidA)
  private val orcidB = Map("orcid" -> validOrcidB)
  private val shared = Vector("Alice Wong")

  // exact ORCID agreement, accepted by rule
  private val e1 =
    sides("e1", (jane, orcidA, Vector.empty), (jane, orcidA, Vector.empty))
  // different family names, rejected by rule
  private val r2 =
    sides("r2", (jane, none, Vector.empty), ("Zed Quux", none, Vector.empty))
  // same ORCID but the human says different: a false accept
  private val f3 = sides(
    "f3",
    ("John Roe", orcidB, Vector.empty),
    ("John Roe", orcidB, Vector.empty)
  )
  // two valid ORCIDs that disagree: a conflict
  private val c4 =
    sides("c4", (jane, orcidA, Vector.empty), (jane, orcidB, Vector.empty))
  // same name and one shared coauthor: scores inside the adjudication band
  private val b5 = sides("b5", (jane, none, shared), (jane, none, shared))
  private val b6 = sides("b6", (jane, none, shared), (jane, none, shared))
  // a real match the matcher rejects: a false reject
  private val x7 =
    sides("x7", (jane, none, Vector.empty), ("Zed Quux", none, Vector.empty))

  private val all = Vector(e1, r2, f3, c4, b5, b6, x7)

  private val index = RecordIndex(
    all.flatMap((a, b) => Vector(a.id -> a, b.id -> b)).toMap
  )

  private def pairOf(s: (SideRecord, SideRecord)): PairItem =
    PairItem(
      ContractSchema.currentVersion,
      PairItem.idOf(EntityKind.Researcher, s._1.id, s._2.id),
      EntityKind.Researcher,
      runId,
      PairItem.exportVersion,
      PairSide.of(s._1),
      PairSide.of(s._2)
    )

  private val pairs = all.map(pairOf)

  private def goldFor(p: PairItem, v: Verdict): GoldLabel =
    GoldLabel(
      ContractSchema.currentVersion,
      p.pairId,
      p.kind,
      p.left.recordId,
      p.right.recordId,
      p.left.sourceRecordId,
      p.right.sourceRecordId,
      "snap-1",
      v,
      "human",
      "tester",
      "2026-11-07T10:00:00Z",
      None
    )

  private val verdicts = Vector(
    Verdict.Match,
    Verdict.NonMatch,
    Verdict.NonMatch,
    Verdict.Match,
    Verdict.Unsure,
    Verdict.Match,
    Verdict.Match
  )

  private val labels = pairs.zip(verdicts).map((p, v) => goldFor(p, v))

  private val strata = Map(
    pairs(0).pairId -> "exact-id",
    pairs(1).pairId -> "clear-non-match",
    pairs(2).pairId -> "exact-id",
    pairs(3).pairId -> "exact-id",
    pairs(4).pairId -> "hard-common-name",
    pairs(5).pairId -> "hard-common-name",
    pairs(6).pairId -> "clear-non-match"
  )

  private val decisions = PairDecisions.decide(pairs, index, policy, runInfo)

  private val report = GoldEvaluation.evaluate(
    decisions,
    pairs,
    labels,
    strata,
    policy,
    Map("pairs" -> "sha-p"),
    2
  )

  private def outcomeOf(p: PairItem) =
    decisions.decided.find(_.pair.pairId == p.pairId).get.record

  test("the fixture produces the outcomes the other tests rely on") {
    import MatchOutcome.*
    val got = pairs.map(p => outcomeOf(p).decision.decision)
    assertEquals(
      got,
      Vector(
        Accepted,
        Rejected,
        Accepted,
        Conflict,
        Unresolved,
        Unresolved,
        Rejected
      )
    )
    assert(
      policy.inLlmBand(outcomeOf(pairs(4)).score),
      outcomeOf(pairs(4)).score.toString
    )
    assertEquals(outcomeOf(pairs(0)).path, MatchPath.ExactId)
  }

  test("every pair is decided and none is dropped") {
    assertEquals(decisions.decided.size, 7)
    assertEquals(decisions.unmatched, Vector.empty[String])
  }

  test("a pair whose records are not indexed is listed and gets no decision") {
    val partial = RecordIndex(Map(e1._1.id -> e1._1, e1._2.id -> e1._2))
    val some = PairDecisions.decide(pairs, partial, policy, runInfo)
    assertEquals(some.decided.size, 1)
    assertEquals(some.unmatched.size, 6)
    val scored =
      GoldEvaluation.evaluate(some, pairs, labels, strata, policy, Map.empty, 0)
    assert(scored.warnings.exists(_.contains("6 pairs have no records")))
    assertEquals(scored.pooled.table.same.missing, 3)
  }

  test("deciding takes no label, verdict or stratum argument") {
    val decide =
      PairDecisions.getClass.getMethods.find(_.getName == "decide").get
    val types = decide.getParameterTypes.map(_.getSimpleName).toVector
    assertEquals(types, Vector("Vector", "RecordIndex", "Policy", "RunInfo"))
  }

  test(
    "the decision stage source never mentions labels, suggestions or strata"
  ) {
    val dir = Vector(
      Paths.get(
        "modules/entity-resolution/src/main/scala/scholarchain/er/eval/assess"
      ),
      Paths.get("src/main/scala/scholarchain/er/eval/assess")
    ).find(Files.isDirectory(_)).getOrElse(fail("cannot locate the sources"))
    val text =
      Files.readString(dir.resolve("PairDecisions.scala"), UTF_8).toLowerCase
    val words = Vector("gold", "verdict", "suggest", "strat", "label")
    assertEquals(words.filter(text.contains), Vector.empty[String])
  }

  test(
    "a decisions line holds the matcher's answer and nothing from the labels"
  ) {
    val json = ujson.read(PairDecisions.line(decisions.decided.head))
    val keys = json.obj.keySet.toSet
    assertEquals(
      keys,
      Set(
        "pair_id",
        "kind",
        "outcome",
        "path",
        "score",
        "rulesFired",
        "reasonCodes",
        "policyVersion",
        "modelVersion"
      )
    )
  }

  test("deciding the same pairs twice gives the same decisions") {
    val again = PairDecisions.decide(pairs, index, policy, runInfo)
    assertEquals(again.records, decisions.records)
  }

  test("the run ID has the shape the contracts require and is repeatable") {
    val id = PairDecisions.runIdFor("abc")
    assert(
      id.matches(
        "run:v1:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
      )
    )
    assertEquals(PairDecisions.runIdFor("abc"), id)
    assertNotEquals(PairDecisions.runIdFor("abd"), id)
  }

  test(
    "the confusion table has a row for unsure and a column for every outcome"
  ) {
    val t = report.pooled.table
    assertEquals((t.same.accepted, t.same.rejected), (1, 1))
    assertEquals(
      (t.same.unresolved, t.same.conflict, t.same.missing),
      (1, 1, 0)
    )
    assertEquals((t.different.accepted, t.different.rejected), (1, 1))
    assertEquals(t.different.unresolved + t.different.conflict, 0)
    assertEquals((t.unsure.unresolved, t.unsure.total), (1, 1))
  }

  test("precision and recall are separate numbers with exact values") {
    val r = report.pooled
    assertEquals(r.precision, Some(0.5))
    assertEquals(r.recall, Some(0.25))
    assertEqualsDouble(r.f1.get, 1.0 / 3, 1e-12)
    val low = r.recallInterval.get
    assert(low.low < 0.25 && 0.25 < low.high)
    assert(low.low >= 0.0 && low.high <= 1.0)
  }

  test("the per-kind report from the evaluator agrees with the pooled one") {
    assertEquals(report.base.kinds.map(_.kind), Vector("researcher"))
    assertEquals(report.base.kinds.head.metrics.truePositive, 1)
    assertEquals(report.base.kinds.head.unsureExcluded, 1)
    assert(
      report.warnings.forall(!_.contains("cross-check")),
      report.warnings.toString
    )
  }

  test("strata are reported separately and use the key only for grouping") {
    val by = report.strata.map(s => s.stratum -> s.rates).toMap
    assertEquals(
      by.keySet,
      Set("exact-id", "hard-common-name", "clear-non-match")
    )
    assertEquals(by("exact-id").pairs, 3)
    assertEquals(by("exact-id").precision, Some(0.5))
    assertEquals(by("hard-common-name").precision, None)
    assertEquals(by("hard-common-name").table.unsure.unresolved, 1)
    assertEquals(by("clear-non-match").recall, Some(0.0))
  }

  test("a label with no stratum is reported as unknown with a warning") {
    val thin = strata - pairs(0).pairId
    val r = GoldEvaluation.evaluate(
      decisions,
      pairs,
      labels,
      thin,
      policy,
      Map.empty,
      0
    )
    assert(r.strata.exists(_.stratum == "unknown"))
    assert(r.warnings.exists(_.contains("no stratum")))
  }

  test(
    "band pairs and unsure pairs stay UNRESOLVED and are never auto-labeled"
  ) {
    val band = report.band
    assertEquals((band.same, band.different, band.unsure), (1, 0, 1))
    assertEquals(band.eligibleForAdjudication, 2)
    assertEquals(band.abstainedByStub, 2)
    assertEquals(band.modelCalls, 2)
    assertEquals(band.model, PairDecisions.StubModel)
    Vector(pairs(4), pairs(5)).foreach { p =>
      val record = outcomeOf(p)
      assertEquals(record.decision.decision, MatchOutcome.Unresolved)
      assertEquals(record.path, MatchPath.Probabilistic)
      assert(record.decision.reasonCodes.contains("llm.abstained"))
      assertEquals(record.decision.modelVersion, Some(PairDecisions.StubModel))
    }
    assertEquals(band.low, policy.llmBandLow)
    assertEquals(band.high, policy.accept)
  }

  test("errors are listed by pair_id with false accepts first") {
    val cats = report.errors.map(_.category)
    assertEquals(
      cats,
      Vector(
        GoldEvaluation.FalseAccept,
        GoldEvaluation.FalseReject,
        GoldEvaluation.MissedConflict,
        GoldEvaluation.MissedUnresolved,
        GoldEvaluation.GoldUnsure
      )
    )
    assertEquals(report.errors.head.pairId, pairs(2).pairId)
    assertEquals(report.errors.head.outcome, "ACCEPTED")
    assertEquals(report.errors.head.gold, "non-match")
    assertEquals(report.errors.map(_.pairId).distinct.size, report.errors.size)
    assertEquals(
      report.errors.head.leftSourceRecordId,
      pairs(2).left.sourceRecordId
    )
  }

  test(
    "the sweep covers accept 0.90-0.98 and reject 0.20-0.50 once at the policy"
  ) {
    val points = report.base.sensitivity.filter(_.kind == "researcher")
    assertEquals(
      points.map(_.accept).distinct.sorted,
      GoldEvaluation.AcceptSweep
    )
    assertEquals(
      points.map(_.reject).distinct.sorted,
      GoldEvaluation.RejectSweep
    )
    assertEquals(GoldEvaluation.AcceptSweep.head, 0.90)
    assertEquals(GoldEvaluation.AcceptSweep.last, 0.98)
    assertEquals(GoldEvaluation.RejectSweep.head, 0.20)
    assertEquals(GoldEvaluation.RejectSweep.last, 0.50)
    assertEquals(points.count(_.isPolicyThreshold), 1)
    val at = points.find(_.isPolicyThreshold).get
    assertEquals((at.accept, at.reject), (policy.accept, policy.reject))
  }

  test("the policy thresholds in the report are the shipped ones, unchanged") {
    assertEquals(report.policyVersion, Policy.v1Version)
    assertEquals((report.accept, report.reject), (0.95, 0.35))
    assertEquals(report.base.acceptThreshold, 0.95)
    assertEquals(report.base.rejectThreshold, 0.35)
  }

  test(
    "the uncertainty statement gives n, unsure, stratification and caveats"
  ) {
    val text = report.uncertainty.mkString("\n")
    assert(text.contains("n = 7"))
    assert(text.contains("1 labeled unsure"))
    assert(text.contains("stratified"))
    assert(text.contains("not population rates"))
    assert(text.contains("2 labels differed"))
    assert(text.contains("Recall is measured over candidate pairs only"))
    assert(text.contains("provisional"))
  }

  test("the text report names every section and lists the errors") {
    val text = ReportText.render(report)
    Vector(
      "WARNINGS",
      "== pooled",
      "== separate by kind",
      "== by stratum",
      "== adjudication band, never auto-labeled",
      "== errors by pair_id, false accepts first",
      "== threshold sensitivity",
      "== uncertainty",
      "thresholds are the shipped provisional values, not tuned on this set"
    ).foreach(section => assert(text.contains(section), section))
    assert(text.contains(pairs(2).pairId))
    val first = text.indexOf(GoldEvaluation.FalseAccept)
    assert(first >= 0 && first < text.indexOf(GoldEvaluation.FalseReject))
  }

  test("the report round-trips through JSON") {
    val back = read[FullReport](report.toJson)
    assertEquals(back.pooled, report.pooled)
    assertEquals(back.errors, report.errors)
    assertEquals(back.policyVersion, report.policyVersion)
  }

  test("Wilson intervals are proper: inside [0, 1], around the estimate") {
    assertEquals(GoldEvaluation.wilson(0, 0), None)
    val none = GoldEvaluation.wilson(0, 10).get
    assertEquals(none.low, 0.0)
    assert(none.high > 0.0 && none.high < 0.5)
    val all = GoldEvaluation.wilson(10, 10).get
    assertEquals(all.high, 1.0)
    val mid = GoldEvaluation.wilson(5, 10).get
    assert(mid.low < 0.5 && mid.high > 0.5)
  }

  test("the changed count comes from the suggestion the reviewer saw") {
    val line = (v: String, s: String) =>
      write(goldFor(pairs.head, Verdict.Match)).dropRight(1) +
        s""","suggested_label":"$s","changed_from_suggestion":false,""" +
        s""""suggestion_source_file":"f.csv","suggestion_source_sha256":"$v"}"""
    val lines = Vector(line("a", "same"), line("a", "different"), "")
    assertEquals(EvaluationRun.changedCount(lines), 1)
  }

  test("the strata key is read as pair_id to stratum") {
    val lines =
      Vector("""{"pair_id":"p1","stratum":"exact-id","kind":"x"}""", " ")
    assertEquals(EvaluationRun.readStrata(lines), Map("p1" -> "exact-id"))
  }

  test("arguments are required and unknown options are refused") {
    val full = Seq(
      "--pairs",
      "p",
      "--gold",
      "g",
      "--records",
      "r",
      "--strata-key",
      "s",
      "--out",
      "o"
    )
    assert(EvaluationRun.parseArgs(full).isRight)
    assert(EvaluationRun.parseArgs(full.take(8)).isLeft)
    assert(EvaluationRun.parseArgs(full ++ Seq("--accept", "0.9")).isLeft)
  }

  test("files in, report out: the full run on real-format files") {
    val dir = Files.createTempDirectory("er-evaluation")
    val works = Vector(
      coreWork(
        "dblp",
        "k/a",
        "Graph methods for coauthor networks",
        Vector("Jane Doe"),
        None
      ),
      coreWork(
        "dblp",
        "k/b",
        "Pottery trade networks in the Aegean",
        Vector("Jane Doe"),
        None
      )
    )
    val index = RecordIndex.fromCore(works)
    val ids = works.map(w => w.authors.head.mentionId).sorted
    val left = index.get(ids(0)).get
    val right = index.get(ids(1)).get
    val pair = PairItem(
      ContractSchema.currentVersion,
      PairItem.idOf(EntityKind.Researcher, left.id, right.id),
      EntityKind.Researcher,
      runId,
      PairItem.exportVersion,
      PairSide.of(left),
      PairSide.of(right)
    )
    val recordsDir = dir.resolve("records")
    Files.createDirectories(recordsDir)
    Files.write(
      recordsDir.resolve("part-r-00000"),
      works.map(w => write(w)).asJava,
      UTF_8
    )
    PairsFile.write(dir.resolve("pairs.jsonl"), Vector(pair))
    val label = goldFor(pair, Verdict.NonMatch)
    Files.write(dir.resolve("gold.jsonl"), Vector(write(label)).asJava, UTF_8)
    val key = s"""{"pair_id":"${pair.pairId}","stratum":"hard-common-name"}"""
    Files.write(dir.resolve("strata.jsonl"), Vector(key).asJava, UTF_8)
    val out = Files.createTempDirectory("er-evaluation-out")
    EvaluationRun.main(
      Array(
        "--pairs",
        dir.resolve("pairs.jsonl").toString,
        "--gold",
        dir.resolve("gold.jsonl").toString,
        "--records",
        recordsDir.toString,
        "--strata-key",
        dir.resolve("strata.jsonl").toString,
        "--out",
        out.toString
      )
    )
    val decided = Files.readAllLines(out.resolve("decisions.jsonl"), UTF_8)
    assertEquals(decided.size, 1)
    assert(!decided.get(0).contains("hard-common-name"))
    val text = Files.readString(out.resolve("evaluation-report.txt"), UTF_8)
    assert(text.contains("hard-common-name"))
    assert(text.contains("input records (directory digest)"))
    val back = read[FullReport](
      Files.readString(out.resolve("evaluation-report.json"), UTF_8)
    )
    assertEquals(back.pooled.pairs, 1)
    assertEquals(back.strata.map(_.stratum), Vector("hard-common-name"))
  }
