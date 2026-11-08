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

/** Phase 5 review finding 1, from the specification only.
  *
  * Two different people who share a name can stand at different author
  * positions of one paper. When that paper is read from two sources, each
  * source keeps its own work ID, so a rule keyed on the work ID never sees one
  * paper. These tests build only synthetic papers (no gold pairs) through the
  * core contract and decide the mention pairs through the public matcher.
  */
class AdversarialErSameWorkSuite extends FunSuite:
  private val runId = StableIds.newRun()
  private val run = RunInfo(runId, Instant.parse("2026-11-07T00:00:00Z"))
  private val matcher = Matcher(Policy.v1, run)
  // The old rule: the same work record, so the same mention list.
  private val sameWorkRule = "rule.same-work-distinct-mention"
  // The Phase 5 rule: one work seen through two sources (shared DOI or arXiv
  // ID), the mentions at different positions of one aligned author list.
  private val newRule = "rule.same-doi-distinct-position"
  private val newReason = "rejected.same-doi-distinct-position"
  private val arxivRule = "rule.same-arxiv-distinct-position"
  private val arxivReason = "rejected.same-arxiv-distinct-position"

  private val title = "Sparse tensor decomposition for seismic imaging"
  private val doi = "10.1000/synthetic.5179"

  // Five authors, the same name at positions 2 and 4.
  private val authorNames =
    Vector("Alice Meyer", "Wei Wang", "Carlos Ruiz", "Wei Wang", "Dana Osei")

  private def paper(
      source: SourceType,
      key: String,
      doi: Option[String],
      authors: Vector[String] = authorNames,
      arxivId: Option[String] = None
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
    val mentions = authors.zipWithIndex.map { case (name, index) =>
      AuthorMention(
        ContractSchema.currentVersion,
        StableIds.mention(workId, index + 1),
        workId,
        index + 1,
        name,
        name.toLowerCase,
        Map.empty,
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
      arxivId,
      mentions,
      None,
      Vector.empty,
      Vector.empty,
      "article",
      provenance
    )

  private def dblp(doi: Option[String] = Some(doi)) =
    paper(SourceType.Dblp, "journals/synthetic/WangMRWO20", doi)

  private def openAlex(doi: Option[String] = Some(doi)) =
    paper(SourceType.OpenAlex, "W4200000001", doi)

  private def mentionSide(
      index: RecordIndex,
      work: WorkRecord,
      position: Int
  ): SideRecord =
    index
      .get(work.authors(position - 1).mentionId)
      .getOrElse(fail(s"no record for position $position"))

  private def decide(
      a: WorkRecord,
      positionA: Int,
      b: WorkRecord,
      positionB: Int,
      unknownPosition: Boolean = false,
      idsA: Map[String, String] = Map.empty,
      idsB: Map[String, String] = Map.empty
  ): ResolutionRecord =
    val index = RecordIndex.fromCore(Vector(a, b))
    def side(
        work: WorkRecord,
        position: Int,
        ids: Map[String, String]
    ): SideRecord =
      val record =
        mentionSide(index, work, position).copy(externalIds = ids)
      if unknownPosition then record.copy(authorPosition = None) else record
    matcher.decide(
      MatchInput.of(side(a, positionA, idsA), side(b, positionB, idsB), "k")
    )

  private def clue(record: ResolutionRecord): String =
    s"${record.decision.decision} ${record.path} " +
      s"score=${record.score} rules=${record.decision.rulesFired} " +
      s"reasons=${record.decision.reasonCodes}"

  private def fired(record: ResolutionRecord): Boolean =
    record.decision.rulesFired.exists(Vector(newRule, arxivRule).contains)

  private def assertRejectedBy(
      record: ResolutionRecord,
      rule: String,
      reason: String
  ): Unit =
    assertEquals(record.decision.decision, MatchOutcome.Rejected, clue(record))
    assert(record.decision.rulesFired.contains(rule), clue(record))
    assert(record.decision.reasonCodes.contains(reason), clue(record))

  private def assertRejectedByNewRule(record: ResolutionRecord): Unit =
    assertRejectedBy(record, newRule, newReason)

  private def assertRejectedByArxivRule(record: ResolutionRecord): Unit =
    assertRejectedBy(record, arxivRule, arxivReason)

  private def assertSilent(record: ResolutionRecord): Unit =
    assert(!fired(record), clue(record))
    assert(
      !record.decision.reasonCodes
        .exists(Vector(newReason, arxivReason).contains),
      clue(record)
    )
    assertNotEquals(
      record.decision.decision,
      MatchOutcome.Rejected,
      clue(record)
    )

  test(
    "same name at different positions of one paper, two sources: " +
      "rejected by the new rule"
  ) {
    val result = decide(dblp(), 2, openAlex(), 4)
    assertNotEquals(
      result.decision.decision,
      MatchOutcome.Accepted,
      clue(result)
    )
    assertRejectedByNewRule(result)
  }

  test("the same pair is not accepted whichever source is listed first") {
    val result = decide(openAlex(), 4, dblp(), 2)
    assertNotEquals(
      result.decision.decision,
      MatchOutcome.Accepted,
      clue(result)
    )
    assertRejectedByNewRule(result)
    val other = decide(dblp(), 4, openAlex(), 2)
    assertNotEquals(
      other.decision.decision,
      MatchOutcome.Accepted,
      clue(other)
    )
    assertRejectedByNewRule(other)
  }

  test("the decision is the same whichever way round the pair is given") {
    val forward = decide(dblp(), 2, openAlex(), 4)
    val reverse = decide(openAlex(), 4, dblp(), 2)
    assertEquals(forward.decision.decision, reverse.decision.decision)
  }

  test("within one source the same pair is rejected (anchor)") {
    val work = dblp()
    val result = decide(work, 2, work, 4)
    assertEquals(result.decision.decision, MatchOutcome.Rejected, clue(result))
    assert(result.decision.rulesFired.contains(sameWorkRule), clue(result))
  }

  test("control: the same author at the same position may still match") {
    assertSilent(decide(dblp(), 2, openAlex(), 2))
  }

  test("control: the same author at the last position may still match") {
    assertSilent(decide(dblp(), 4, openAlex(), 4))
  }

  test(
    "control: different DOIs are different papers, so the rule stays silent"
  ) {
    assertSilent(
      decide(dblp(), 2, openAlex(Some("10.1000/synthetic.9999")), 4)
    )
  }

  test("control: a missing DOI on one side cannot prove one paper") {
    assertSilent(decide(dblp(), 2, openAlex(None), 4))
  }

  test("control: an unknown author position cannot prove two people") {
    assertSilent(decide(dblp(), 2, openAlex(), 4, unknownPosition = true))
  }

  test("a DOI that differs only in letter case still names one paper") {
    val result = decide(dblp(), 2, openAlex(Some(doi.toUpperCase)), 4)
    assertRejectedByNewRule(result)
  }

  test("a resolver prefix on one DOI still names one paper") {
    val prefixed = openAlex(Some(s"https://doi.org/$doi"))
    assertRejectedByNewRule(decide(dblp(), 2, prefixed, 4))
    assertRejectedByNewRule(
      decide(dblp(Some(s"https://doi.org/$doi")), 2, openAlex(), 4)
    )
    assertRejectedByNewRule(
      decide(dblp(Some(s"doi:${doi.toUpperCase}")), 2, prefixed, 4)
    )
  }

  // --- arXiv: DBLP carries the arXiv ID, OpenAlex the arXiv DOI form ---

  private val arxivId = "2101.00001"
  private val arxivDoi = "10.48550/arxiv.2101.00001"

  private def dblpPreprint(
      arxiv: Option[String] = Some(arxivId),
      doi: Option[String] = None
  ) = paper(
    SourceType.Dblp,
    "journals/corr/abs-2101-00001",
    doi,
    arxivId = arxiv
  )

  private def openAlexPreprint(doi: Option[String] = Some(arxivDoi)) =
    paper(SourceType.OpenAlex, "W4200000002", doi)

  test("arXiv: DBLP arXiv ID vs OpenAlex arXiv DOI, same-name coauthors") {
    assertRejectedByArxivRule(
      decide(dblpPreprint(), 2, openAlexPreprint(), 4)
    )
  }

  test("arXiv: the decision is the same whichever way round, both ways") {
    val forward = decide(dblpPreprint(), 2, openAlexPreprint(), 4)
    val reverse = decide(openAlexPreprint(), 4, dblpPreprint(), 2)
    assertEquals(forward.decision.decision, reverse.decision.decision)
    assertRejectedByArxivRule(reverse)
    assertRejectedByArxivRule(decide(dblpPreprint(), 4, openAlexPreprint(), 2))
  }

  test("arXiv: an arXiv URL, a version suffix and case do not hide the ID") {
    val spelled =
      openAlexPreprint(Some("https://doi.org/10.48550/arXiv.2101.00001v2"))
    assertRejectedByArxivRule(decide(dblpPreprint(), 2, spelled, 4))
    val urlForm = dblpPreprint(Some("https://arxiv.org/abs/2101.00001v1"))
    assertRejectedByArxivRule(decide(urlForm, 2, openAlexPreprint(), 4))
  }

  test("arXiv: the ID given as a field on both sides") {
    val other = paper(
      SourceType.OpenAlex,
      "W4200000003",
      None,
      arxivId = Some(arxivId)
    )
    assertRejectedByArxivRule(decide(dblpPreprint(), 2, other, 4))
  }

  test("arXiv control: different arXiv IDs are different papers") {
    val other = openAlexPreprint(Some("10.48550/arxiv.2101.99999"))
    assertSilent(decide(dblpPreprint(), 2, other, 4))
  }

  test("arXiv control: an arXiv ID on one side only proves nothing") {
    assertSilent(decide(dblpPreprint(), 2, openAlexPreprint(None), 4))
    assertSilent(decide(dblpPreprint(None), 2, openAlexPreprint(), 4))
  }

  test("arXiv control: an unknown position cannot prove two people") {
    assertSilent(
      decide(dblpPreprint(), 2, openAlexPreprint(), 4, unknownPosition = true)
    )
  }

  test("arXiv control: the same position is not rejected by the rule") {
    assertSilent(decide(dblpPreprint(), 2, openAlexPreprint(), 2))
  }

  // --- CONFLICT rung: an agreeing identifier on two slots of one list ---

  private val orcid = "0000-0002-1825-0097"

  private def assertConflict(record: ResolutionRecord): Unit =
    assertEquals(record.decision.decision, MatchOutcome.Conflict, clue(record))
    assertNotEquals(
      record.decision.decision,
      MatchOutcome.Accepted,
      clue(record)
    )

  test("conflict: an ORCID on two slots of one DOI work is a CONFLICT") {
    val ids = Map("orcid" -> orcid)
    assertConflict(decide(dblp(), 2, openAlex(), 4, idsA = ids, idsB = ids))
    assertConflict(decide(openAlex(), 4, dblp(), 2, idsA = ids, idsB = ids))
  }

  test("conflict: an ORCID on two slots of one arXiv work is a CONFLICT") {
    val ids = Map("orcid" -> orcid)
    assertConflict(
      decide(dblpPreprint(), 2, openAlexPreprint(), 4, idsA = ids, idsB = ids)
    )
  }

  test("conflict: a content-supported aggregator ID on two slots: CONFLICT") {
    val ids = Map("openalex-author" -> "A5012345678")
    assertConflict(decide(dblp(), 2, openAlex(), 4, idsA = ids, idsB = ids))
    assertConflict(
      decide(dblpPreprint(), 2, openAlexPreprint(), 4, idsA = ids, idsB = ids)
    )
  }

  test("conflict control: the same ORCID at the same position is accepted") {
    val ids = Map("orcid" -> orcid)
    val result = decide(dblp(), 2, openAlex(), 2, idsA = ids, idsB = ids)
    assertEquals(result.decision.decision, MatchOutcome.Accepted, clue(result))
    assertEquals(result.path, MatchPath.ExactId, clue(result))
    val preprint =
      decide(dblpPreprint(), 2, openAlexPreprint(), 2, idsA = ids, idsB = ids)
    assertEquals(
      preprint.decision.decision,
      MatchOutcome.Accepted,
      clue(preprint)
    )
    assertEquals(preprint.path, MatchPath.ExactId, clue(preprint))
  }

  test("conflict control: an ORCID across two different works stays accepted") {
    val ids = Map("orcid" -> orcid)
    val elsewhere = paper(
      SourceType.OpenAlex,
      "W4200000009",
      Some("10.1000/synthetic.0001")
    )
    val result = decide(dblp(), 2, elsewhere, 4, idsA = ids, idsB = ids)
    assertEquals(result.decision.decision, MatchOutcome.Accepted, clue(result))
  }
