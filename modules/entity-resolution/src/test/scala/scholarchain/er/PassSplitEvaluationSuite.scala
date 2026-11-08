package scholarchain.er

import scholarchain.core.ContractSchema
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.assess.*
import scholarchain.er.eval.{GoldLabel, Verdict}
import scholarchain.er.matching.{EntityKind, Policy, RecordIndex, SideRecord}
import scholarchain.er.pairs.{PairItem, PairSide}

/** A two-pass gold file is reported overall and split by pass, so a carried
  * first-pass label is never presented as freshly reviewed.
  */
class PassSplitEvaluationSuite extends munit.FunSuite:
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

  // accepted by exact ORCID; rejected by name; accepted but human says different;
  // a conflict of two ORCIDs
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

  private val labels = pairs
    .zip(
      Vector(
        Verdict.Match,
        Verdict.NonMatch,
        Verdict.NonMatch,
        Verdict.Match
      )
    )
    .map((p, v) => goldFor(p, v))

  private val strata = pairs.map(_.pairId -> "exact-id").toMap
  private val decisions = PairDecisions.decide(pairs, index, policy, runInfo)

  // pairs 0 and 1 carried from the first pass, pairs 2 and 3 relabeled blind
  private val passes = Map(
    pairs(0).pairId -> 1,
    pairs(1).pairId -> 1,
    pairs(2).pairId -> 2,
    pairs(3).pairId -> 2
  )

  private def report(p: Map[String, Int]) =
    GoldEvaluation.evaluate(
      decisions,
      pairs,
      labels,
      strata,
      policy,
      Map("pairs" -> "sha-p"),
      0,
      p
    )

  test("without a pass field the report is the single-pass report") {
    val plain = report(Map.empty)
    assertEquals(plain.passes, Vector.empty[PassResult])
    val text = ReportText.render(plain)
    assert(!text.contains("by pass"), text)
    assert(!plain.uncertainty.exists(_.contains("two passes")))
  }

  test("a two-pass file is reported overall and split by pass") {
    val split = report(passes)
    assertEquals(split.pooled.pairs, 4)
    assertEquals(
      split.passes.map(p => p.pass -> p.rates.pairs),
      Vector(1 -> 2, 2 -> 2)
    )
    val one = split.passes.find(_.pass == 1).get.rates
    val two = split.passes.find(_.pass == 2).get.rates
    assertEquals(one.table.same.accepted, 1)
    assertEquals(one.table.different.rejected, 1)
    assertEquals(two.table.different.accepted, 1)
    assertEquals(two.table.same.conflict, 1)
    assertEquals(
      split.pooled.table.same.accepted,
      one.table.same.accepted + two.table.same.accepted
    )
  }

  test("the text names carried rows as not re-reviewed and pass 2 as blind") {
    val text = ReportText.render(report(passes))
    assert(text.contains("== by pass"), text)
    assert(text.contains("CARRIED from the first labeling pass"), text)
    assert(text.contains("NOT re-reviewed"), text)
    assert(text.contains("relabeled BLIND"), text)
    assert(
      report(passes).uncertainty.exists(
        _.contains("do not describe the carried labels as freshly reviewed")
      )
    )
  }

  test("a label with no pass in a two-pass file is warned about") {
    val partial = report(passes - pairs(3).pairId)
    assert(
      partial.warnings.exists(_.contains("no pass")),
      partial.warnings.toString
    )
  }

  test("the json carries the split and reads back") {
    val split = report(passes)
    val back = upickle.default.read[FullReport](split.toJson)
    assertEquals(back.passes, split.passes)
  }

  test("passes are read from the pass field of the gold lines") {
    val lines = Vector(
      """{"pair_id":"a","pass":1}""",
      """{"pair_id":"b","pass":2}""",
      """{"pair_id":"c"}""",
      """{"pair_id":"d","pass":3}""",
      ""
    )
    assertEquals(EvaluationRun.passesOf(lines), Map("a" -> 1, "b" -> 2))
    assertEquals(EvaluationRun.passesOf(Vector.empty), Map.empty[String, Int])
  }
