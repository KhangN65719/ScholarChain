package scholarchain.er

import java.nio.file.Paths
import scholarchain.core.ContractSchema
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.assess.*
import scholarchain.er.eval.{GoldLabel, Verdict}
import scholarchain.er.matching.{EntityKind, Policy, RecordIndex, SideRecord}
import scholarchain.er.pairs.{PairItem, PairSide}

/** Gate item 4: the evaluation accepts a gold-v3 file (pass 2 and pass 3 rows),
  * reports overall and by pass and by stratum, and can set an earlier report
  * beside this one without ever feeding it back.
  */
class Pass3EvaluationSuite extends munit.FunSuite:
  private val policy = Policy.v1
  private val titleA = "Quantum lattice chromodynamics methods"
  private val titleB = "Medieval pottery trade networks"
  private val jane = "Jane Doe"
  private val none = Map.empty[String, String]
  private val orcidA = Map("orcid" -> validOrcidA)
  private val orcidB = Map("orcid" -> validOrcidB)

  private def sides(
      id: String,
      left: (String, Map[String, String]),
      right: (String, Map[String, String])
  ): (SideRecord, SideRecord) =
    (
      mention(s"${id}a", left._1, Vector.empty, Vector.empty, left._2, titleA),
      mention(s"${id}b", right._1, Vector.empty, Vector.empty, right._2, titleB)
    )

  private val e1 = sides("e1", (jane, orcidA), (jane, orcidA))
  private val r2 = sides("r2", (jane, none), ("Zed Quux", none))
  private val f3 = sides("f3", ("John Roe", orcidB), ("John Roe", orcidB))
  private val c4 = sides("c4", (jane, orcidA), (jane, orcidB))
  private val all = Vector(e1, r2, f3, c4)
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
  private val ids = pairs.map(_.pairId)

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

  private def labelsOf(vs: Vector[Verdict]) =
    pairs.zip(vs).map((p, v) => goldFor(p, v))

  // the earlier file: pair 3 was called same
  private val earlier = labelsOf(
    Vector(Verdict.Match, Verdict.NonMatch, Verdict.NonMatch, Verdict.Match)
  )
  // this file: the conflict pair is now called different
  private val current = labelsOf(
    Vector(Verdict.Match, Verdict.NonMatch, Verdict.NonMatch, Verdict.NonMatch)
  )
  private val strata = ids.map(_ -> "exact-id").toMap
  private val decisions = PairDecisions.decide(pairs, index, policy, runInfo)

  // pairs 0 and 1 are pass 2, pairs 2 and 3 are the pass-3 re-review
  private val passesV3 =
    Map(ids(0) -> 2, ids(1) -> 2, ids(2) -> 3, ids(3) -> 3)
  private val passesV2 =
    Map(ids(0) -> 1, ids(1) -> 1, ids(2) -> 2, ids(3) -> 2)
  private val before = Map(ids(2) -> Verdict.NonMatch, ids(3) -> Verdict.Match)

  private def report(
      labels: Vector[GoldLabel],
      passes: Map[String, Int],
      digest: String = "sha-p"
  ) =
    GoldEvaluation.evaluate(
      decisions,
      pairs,
      labels,
      strata,
      policy,
      Map("pairs" -> digest),
      0,
      passes
    )

  private val v3 = report(current, passesV3)
  private val v2 = report(earlier, passesV2)

  // ------------------------------------------------------------ split by pass

  test("pass fields are read, with pass 3 only when it is allowed") {
    val rows = Vector(
      """{"pair_id":"a","pass":1}""",
      """{"pair_id":"b","pass":2}""",
      """{"pair_id":"c","pass":3}""",
      """{"pair_id":"d"}""",
      """{"pair_id":"e","pass":4}"""
    )
    assertEquals(EvaluationRun.passesOf(rows), Map("a" -> 1, "b" -> 2))
    assertEquals(
      EvaluationRun.passesOf(rows, Set(1, 2, 3)),
      Map("a" -> 1, "b" -> 2, "c" -> 3)
    )
  }

  test("the pass-1 label of a pass-3 row is read, and only from pass 3") {
    val rows = Vector(
      """{"pair_id":"a","pass":3,"pass1_label":"same"}""",
      """{"pair_id":"b","pass":3,"pass1_label":"different"}""",
      """{"pair_id":"c","pass":3,"pass1_label":"unsure"}""",
      """{"pair_id":"d","pass":2,"pass1_label":"same"}""",
      """{"pair_id":"e","pass":3,"pass1_label":"nonsense"}""",
      """{"pair_id":"f","pass":3}"""
    )
    assertEquals(
      EvaluationRun.passOneLabels(rows),
      Map("a" -> Verdict.Match, "b" -> Verdict.NonMatch, "c" -> Verdict.Unsure)
    )
  }

  test("a v3 file is reported overall, by pass and by stratum") {
    assertEquals(v3.pooled.pairs, 4)
    assertEquals(
      v3.passes.map(p => p.pass -> p.rates.pairs),
      Vector(2 -> 2, 3 -> 2)
    )
    assertEquals(v3.strata.map(_.stratum), Vector("exact-id"))
    assertEquals(
      v3.pooled.table.same.accepted,
      v3.passes.map(_.rates.table.same.accepted).sum
    )
  }

  test(
    "the text names pass 2 as 77-style blind and pass 3 as a blind re-review"
  ) {
    val text = ReportText.render(v3)
    assert(text.contains("== by pass"), text)
    assert(text.contains("pass 2: 2 blind"), text)
    assert(text.contains("pass 3: 2 blind re-review of pass-1 pairs"), text)
    assert(
      text.contains("no with-suggestion label") || text.contains(
        "none was made with a suggestion"
      ),
      text
    )
    assert(!text.contains("CARRIED"), text)
    assert(!text.contains("NOT re-reviewed"), text)
    assert(text.contains("== by stratum"), text)
  }

  test("the uncertainty section does not claim a suggestion was seen") {
    val lines = v3.uncertainty
    assert(
      lines.exists(
        _.contains("No label in this file was made with a suggestion")
      ),
      lines.toString
    )
    assert(!lines.exists(_.contains("saw an external suggestion")))
    assert(
      lines.exists(_.contains("pass 3")) &&
        lines.exists(_.contains("had seen every pair before")),
      lines.toString
    )
    assert(!lines.exists(_.contains("carried labels as freshly reviewed")))
  }

  test("a pass 1 and 2 file is reported as before") {
    val text = ReportText.render(v2)
    assert(text.contains("CARRIED from the first labeling pass"), text)
    assert(text.contains("NOT re-reviewed"), text)
    assert(text.contains("relabeled BLIND"), text)
    assert(v2.uncertainty.exists(_.contains("saw an external suggestion")))
  }

  test("the json carries the split and reads back") {
    val back = upickle.default.read[FullReport](v3.toJson)
    assertEquals(back.passes, v3.passes)
    assertEquals(back.pooled, v3.pooled)
  }

  // ------------------------------------------------------------ the comparison

  private val changes =
    ReportComparison.changes(decisions, current, strata, before, passesV3)

  private def lines(
      previous: FullReport = v2,
      now: FullReport = v3,
      again: Vector[String] = Vector(ids(2), ids(3))
  ) =
    ReportComparison.lines(
      "evaluation-report.json",
      "abc123",
      previous,
      now,
      again,
      changes
    )

  test(
    "label changes are pass-3 rows whose verdict differs from the pass-1 label"
  ) {
    assertEquals(changes.map(_.pairId), Vector(ids(3)))
    val c = changes.head
    assertEquals((c.before, c.after), (Verdict.Match, Verdict.NonMatch))
    assertEquals(c.outcome, "CONFLICT")
    assertEquals(c.path, Some("exact-id"))
    assertEquals(c.stratum, "exact-id")
    assert(
      c.reasonCodes.exists(_.startsWith("conflict.id")),
      c.reasonCodes.toString
    )
    val none = ReportComparison.changes(
      decisions,
      current,
      strata,
      before.updated(ids(3), Verdict.NonMatch),
      passesV3
    )
    assertEquals(none, Vector.empty[LabelChange])
    val pass2Only = ReportComparison.changes(
      decisions,
      current,
      strata,
      Map(ids(0) -> Verdict.NonMatch),
      passesV3
    )
    assertEquals(
      pass2Only,
      Vector.empty[LabelChange],
      "pass-2 rows are not compared"
    )
  }

  test("the comparison puts the pooled numbers side by side") {
    val text = lines().mkString("\n")
    assert(text.contains("== comparison with evaluation-report.json"), text)
    assert(text.contains("sha256 abc123"), text)
    assert(text.contains("descriptive only"), text)
    assert(text.contains("precision earlier 0.500"), text)
    assert(text.contains("recall earlier 0.500  this 1.000"), text)
    assert(text.contains("F1 earlier"), text)
    assert(text.contains("pairs earlier 4  this 4"), text)
    assert(text.contains("same earlier"), text)
    assert(text.contains("different earlier"), text)
  }

  test("changed labels are listed with the matcher decision beside them") {
    val text = lines().mkString("\n")
    assert(
      text.contains(
        "gold labels changed between the files: 1 of 2 pass-3 pairs"
      ),
      text
    )
    assert(text.contains("same->different 1"), text)
    val row = lines().filter(_.contains(ids(3))).mkString
    assert(row.contains("same->different"), row)
    assert(row.contains("matcher=CONFLICT"), row)
    assert(row.contains("exact-id"), row)
    assert(row.contains("listed in the earlier report: yes"), row)
  }

  test("it shows whether changes sit on pairs the earlier report flagged") {
    val text = lines().mkString("\n")
    assert(
      text.contains(
        "pass-3 pairs listed in the earlier report's error section: 2 of 2; " +
          "labels changed among them: 1"
      ),
      text
    )
    assert(
      text.contains(
        "pass-3 pairs not listed there: 0; labels changed among them: 0"
      ),
      text
    )
    val unflagged = lines(again = Vector(ids(0), ids(3))).mkString("\n")
    assert(unflagged.contains("not listed there: 1;"), unflagged)
  }

  test("a comparison of reports from different inputs carries a warning") {
    val same = lines().mkString("\n")
    assert(!same.contains("WARNING"), same)
    val other = report(current, passesV3, digest = "sha-other")
    val warned = lines(now = other).mkString("\n")
    assert(warned.contains("WARNING"), warned)
    assert(warned.contains("pairs"), warned)
  }

  test("the comparison states that it alters nothing") {
    val text = lines().mkString("\n")
    assert(
      text.contains("never used to alter a threshold, a rule or a label"),
      text
    )
    assert(text.contains("v2 label of a pass-3 pair is its pass-1 label"), text)
  }

  test(
    "the report text without a comparison is unchanged by the comparison code"
  ) {
    val text = ReportText.render(v3)
    assert(!text.contains("== comparison"), text)
    assertEquals(text, ReportText.render(v3))
  }

  // ---------------------------------------------------------------- arguments

  private val base = Seq(
    "--pairs",
    "p",
    "--gold",
    "g",
    "--records",
    "r",
    "--strata-key",
    "k",
    "--out",
    "o"
  )

  test("--compare-with is optional and names the earlier report") {
    val none = EvaluationRun.parseArgs(base).toOption.get
    assertEquals(none.compareWith, None)
    val some = EvaluationRun
      .parseArgs(
        base ++ Seq("--compare-with", "eval-v2/evaluation-report.json")
      )
      .toOption
      .get
    assertEquals(
      some.compareWith,
      Some(Paths.get("eval-v2/evaluation-report.json"))
    )
    assert(EvaluationRun.parseArgs(base ++ Seq("--compare", "x")).isLeft)
  }

  test("an earlier report is read back from its json, or refused") {
    val json = v2.toJson
    assertEquals(
      EvaluationRun.readPrevious(json).toOption.get.pooled,
      v2.pooled
    )
    assert(EvaluationRun.readPrevious("{nope").isLeft)
    assert(EvaluationRun.readPrevious("{}").isLeft)
  }
