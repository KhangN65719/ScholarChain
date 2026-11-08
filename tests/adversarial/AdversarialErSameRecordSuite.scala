package scholarchain.tests.adversarial

import java.time.Instant
import munit.FunSuite
import scholarchain.core.{
  AuthorMention,
  ContractSchema,
  MatchOutcome,
  Provenance,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.er.matching.*

/** Human ruling, from the specification only: an agreeing ORCID on two
  * different author slots of the same work record is a CONFLICT, whether or not
  * the work has a DOI or an arXiv ID. Every fixture is synthetic.
  */
class AdversarialErSameRecordSuite extends FunSuite:
  private val runId = StableIds.newRun()
  private val run = RunInfo(runId, Instant.parse("2026-11-07T00:00:00Z"))
  private val matcher = Matcher(Policy.v1, run)

  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0001-5109-3700"
  private val conflictCodePrefix = "conflict.id-agreement-contradicted-by"
  private val sameWorkRule = "rule.same-work-distinct-mention"

  private val title = "Sparse tensor decomposition for seismic imaging"
  private val authorNames =
    Vector("Alice Meyer", "Wei Wang", "Carlos Ruiz", "Wei Wang", "Dana Osei")

  private def paper(
      source: SourceType,
      key: String,
      doi: Option[String],
      arxiv: Option[String],
      orcids: Map[Int, String]
  ): WorkRecord =
    val workId = StableIds.work(source.wire, key)
    val provenance = Provenance(
      ContractSchema.currentVersion,
      source,
      "snap-1",
      key,
      s"https://example.org/${source.wire}/$key",
      "test-1",
      runId
    )
    val mentions = authorNames.zipWithIndex.map { case (name, index) =>
      AuthorMention(
        ContractSchema.currentVersion,
        StableIds.mention(workId, index + 1),
        workId,
        index + 1,
        name,
        name.toLowerCase,
        orcids.get(index + 1).map("orcid" -> _).toMap,
        Vector.empty,
        provenance
      )
    }
    WorkRecord(
      ContractSchema.currentVersion,
      workId,
      source,
      key,
      title,
      title.toLowerCase,
      Some(2020),
      Some("venue-a"),
      doi,
      arxiv,
      mentions,
      None,
      Vector.empty,
      Vector.empty,
      "article",
      provenance
    )

  private val doi = Some("10.1000/synthetic.7711")
  private val arxiv = Some("2011.01234")
  private val bothSlots = Map(2 -> orcidA, 4 -> orcidA)

  private def dblp(
      doi: Option[String],
      arxiv: Option[String],
      orcids: Map[Int, String]
  ) = paper(SourceType.Dblp, "journals/synthetic/SameRec20", doi, arxiv, orcids)

  private def openAlex(
      doi: Option[String],
      arxiv: Option[String],
      orcids: Map[Int, String]
  ) = paper(SourceType.OpenAlex, "W4200000777", doi, arxiv, orcids)

  private def decide(
      a: WorkRecord,
      positionA: Int,
      b: WorkRecord,
      positionB: Int
  ): ResolutionRecord =
    val index = RecordIndex.fromCore(Vector(a, b))
    def side(work: WorkRecord, position: Int): SideRecord =
      index
        .get(work.authors(position - 1).mentionId)
        .getOrElse(fail(s"no record for position $position"))
    matcher.decide(
      MatchInput.of(side(a, positionA), side(b, positionB), "name:wang")
    )

  private def clue(r: ResolutionRecord): String =
    s"${r.decision.decision} ${r.path} rules=${r.decision.rulesFired} " +
      s"reasons=${r.decision.reasonCodes}"

  private def assertConflict(r: ResolutionRecord): Unit =
    assertEquals(r.decision.decision, MatchOutcome.Conflict, clue(r))
    assert(
      r.decision.reasonCodes.exists(_.startsWith(conflictCodePrefix)),
      clue(r)
    )

  private def assertBothWays(work: WorkRecord)(
      check: ResolutionRecord => Unit
  ) =
    val forward = decide(work, 2, work, 4)
    val reverse = decide(work, 4, work, 2)
    check(forward)
    check(reverse)
    assertEquals(forward.decision.decision, reverse.decision.decision)
    assertEquals(forward.decision.reasonCodes, reverse.decision.reasonCodes)

  test("same record with a DOI: an agreeing ORCID on two slots is CONFLICT") {
    assertBothWays(dblp(doi, None, bothSlots))(assertConflict)
  }

  test("same record with no DOI and no arXiv ID: still CONFLICT") {
    assertBothWays(dblp(None, None, bothSlots))(assertConflict)
  }

  test("same record with only an arXiv ID: still CONFLICT") {
    assertBothWays(dblp(None, arxiv, bothSlots))(assertConflict)
  }

  test("same record with a DOI and an arXiv ID: still CONFLICT") {
    assertBothWays(dblp(doi, arxiv, bothSlots))(assertConflict)
  }

  test("the ruling holds for the other source and for other slot pairs") {
    assertBothWays(openAlex(None, None, bothSlots))(assertConflict)
    val work = dblp(None, None, Map(1 -> orcidB, 3 -> orcidB))
    assertConflict(decide(work, 1, work, 3))
    assertConflict(decide(work, 3, work, 1))
  }

  test("same slot, same ORCID, two sources: not CONFLICT by this rule") {
    val a = dblp(doi, None, bothSlots)
    val b = openAlex(doi, None, bothSlots)
    Vector(2, 4).foreach { slot =>
      val result = decide(a, slot, b, slot)
      assertNotEquals(
        result.decision.decision,
        MatchOutcome.Conflict,
        clue(result)
      )
      assert(
        !result.decision.reasonCodes.exists(_.startsWith(conflictCodePrefix)),
        clue(result)
      )
    }
  }

  test("same record without ORCID agreement is unchanged: REJECTED") {
    assertBothWays(dblp(doi, None, Map.empty)) { r =>
      assertEquals(r.decision.decision, MatchOutcome.Rejected, clue(r))
      assert(r.decision.rulesFired.contains(sameWorkRule), clue(r))
    }
    assertBothWays(dblp(None, None, Map.empty)) { r =>
      assertEquals(r.decision.decision, MatchOutcome.Rejected, clue(r))
      assert(r.decision.rulesFired.contains(sameWorkRule), clue(r))
    }
  }

  test("an ORCID on one slot only is not an agreement: REJECTED") {
    assertBothWays(dblp(None, None, Map(2 -> orcidA))) { r =>
      assertEquals(r.decision.decision, MatchOutcome.Rejected, clue(r))
      assert(r.decision.rulesFired.contains(sameWorkRule), clue(r))
    }
  }

  test("different ORCIDs on the two slots of one record are a CONFLICT too") {
    assertBothWays(dblp(None, None, Map(2 -> orcidA, 4 -> orcidB))) { r =>
      assertEquals(r.decision.decision, MatchOutcome.Conflict, clue(r))
    }
  }
