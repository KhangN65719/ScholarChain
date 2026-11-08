package scholarchain.er

import scholarchain.core.{MatchOutcome, SourceType, WorkRecord}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*

/** Review round 3, finding 1 (human ruling): an agreeing ORCID on two slots of
  * the SAME work record is a CONFLICT, whether or not the work has a DOI or an
  * arXiv ID, with the one reason code
  * `conflict.id-agreement-contradicted-by-position`. Synthetic fixtures only;
  * no gold pair is used.
  */
class SameRecordIdAgreementSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)
  private val title = "Learning sparse representations of graph data"
  private val names = Vector("Ann Chen", "Wei Wang", "Wei Wang", "Bo Li")
  private val orcid = Map("orcid" -> validOrcidA)
  private val reason = "conflict.id-agreement-contradicted-by-position"

  private def record(
      doi: Option[String] = None,
      arxivId: Option[String] = None
  ): WorkRecord =
    coreWork("dblp", "conf/x/9", title, names, doi, SourceType.Dblp)
      .copy(arxivId = arxivId)

  private def mentionOf(w: WorkRecord, position: Int): SideRecord =
    RecordIndex.fromCore(Vector(w)).get(w.authors(position - 1).mentionId).get

  private def decide(a: SideRecord, b: SideRecord): ResolutionRecord =
    matcher.decide(MatchInput.of(a, b, "b"))

  private def symmetric(a: SideRecord, b: SideRecord): ResolutionRecord =
    val forward = decide(a, b)
    assertEquals(decide(b, a), forward)
    forward

  private def assertConflict(r: ResolutionRecord, positionRule: String): Unit =
    assertEquals(r.decision.decision, MatchOutcome.Conflict)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.decision.reasonCodes.take(1), Vector(reason))
    assert(r.decision.rulesFired.contains("rule.id-agreement:orcid"))
    assert(
      r.decision.rulesFired.contains(positionRule),
      r.decision.rulesFired.toString
    )
    assertNotEquals(r.decision.decision, MatchOutcome.Accepted)

  test("same record, two slots, agreeing ORCID, WITH a DOI: CONFLICT") {
    val w = record(doi = Some("10.1000/synthetic.same-record"))
    val a = mentionOf(w, 2).copy(externalIds = orcid)
    val b = mentionOf(w, 3).copy(externalIds = orcid)
    assertEquals(a.workId, b.workId)
    assert(a.workDoi.isDefined)
    assertConflict(symmetric(a, b), "rule.same-doi-distinct-position")
  }

  test("same record, two slots, agreeing ORCID, WITH an arXiv ID: CONFLICT") {
    val w = record(arxivId = Some("2101.00001v2"))
    val a = mentionOf(w, 2).copy(externalIds = orcid)
    val b = mentionOf(w, 3).copy(externalIds = orcid)
    assert(a.workDoi.isEmpty && a.workArxiv.isDefined)
    assertConflict(symmetric(a, b), "rule.same-arxiv-distinct-position")
  }

  test("same record, two slots, agreeing ORCID, NO DOI and NO arXiv ID") {
    val w = record()
    val a = mentionOf(w, 2).copy(externalIds = orcid)
    val b = mentionOf(w, 3).copy(externalIds = orcid)
    assert(a.workDoi.isEmpty && a.workArxiv.isEmpty)
    assertEquals(AuthorSlots.sharedWorkKey(a, b), None)
    assertConflict(symmetric(a, b), Matcher.SameRecordRule)
  }

  test("the no-id and with-id cases carry the same reason code") {
    val withId = decide(
      mentionOf(record(doi = Some("10.1000/x")), 2).copy(externalIds = orcid),
      mentionOf(record(doi = Some("10.1000/x")), 3).copy(externalIds = orcid)
    )
    val noId = decide(
      mentionOf(record(), 2).copy(externalIds = orcid),
      mentionOf(record(), 3).copy(externalIds = orcid)
    )
    assertEquals(
      withId.decision.reasonCodes.take(1),
      noId.decision.reasonCodes.take(1)
    )
    assertEquals(withId.decision.decision, noId.decision.decision)
  }

  test("same record and same slot is not a CONFLICT by this rule") {
    val w = record()
    val a = mentionOf(w, 2).copy(externalIds = orcid)
    val b = a.copy(id = "zz-same-slot", sourceRecordId = "rec-zz")
    val r = symmetric(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
    assert(!r.decision.reasonCodes.contains(reason))
    assert(!r.decision.rulesFired.contains(Matcher.SameRecordRule))
  }

  test("an unknown position on one side is not guessed: ACCEPTED as before") {
    val w = record()
    val a = mentionOf(w, 2).copy(externalIds = orcid)
    val b = mentionOf(w, 3).copy(externalIds = orcid, authorPosition = None)
    val r = symmetric(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Accepted)
    assert(!r.decision.reasonCodes.contains(reason))
  }

  test("different records with no shared key keep the ORCID ACCEPTED") {
    val a = mentionOf(record(), 2).copy(externalIds = orcid)
    val other = coreWork("dblp", "conf/x/10", title, names, None)
    val b = mentionOf(other, 3).copy(externalIds = orcid)
    assertNotEquals(a.workId, b.workId)
    val r = symmetric(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Accepted)
    assert(!r.decision.reasonCodes.contains(reason))
  }

  test("same record, no ORCID agreement: REJECTED as distinct mentions") {
    val w = record()
    val noIds = symmetric(mentionOf(w, 2), mentionOf(w, 3))
    val oneSide = symmetric(
      mentionOf(w, 2).copy(externalIds = orcid),
      mentionOf(w, 3)
    )
    Vector(noIds, oneSide).foreach { r =>
      assertEquals(r.decision.decision, MatchOutcome.Rejected)
      assertEquals(r.path, MatchPath.DeterministicBibliographic)
      assert(r.decision.rulesFired.contains("rule.same-work-distinct-mention"))
      assert(
        r.decision.reasonCodes.contains("rejected.same-work-distinct-mention")
      )
    }
  }

  test("same record, no shared key: an aggregator ID stays REJECTED") {
    val w = record()
    val ids = Map("openalex" -> "A123")
    val r = symmetric(
      mentionOf(w, 2).copy(externalIds = ids),
      mentionOf(w, 3).copy(externalIds = ids)
    )
    assertEquals(r.decision.decision, MatchOutcome.Rejected)
    assert(!r.decision.reasonCodes.contains(reason))
  }

  test("two different ORCIDs on one record stay the ID-conflict rung") {
    val w = record()
    val r = symmetric(
      mentionOf(w, 2).copy(externalIds = orcid),
      mentionOf(w, 3).copy(externalIds = Map("orcid" -> validOrcidB))
    )
    assertEquals(r.decision.decision, MatchOutcome.Conflict)
    assert(r.decision.rulesFired.contains("rule.id-conflict"))
    assert(!r.decision.reasonCodes.contains(reason))
  }

  test("the slot rule needs researchers, equal work IDs and known positions") {
    val w = record()
    val a = mentionOf(w, 2)
    val b = mentionOf(w, 3)
    assert(AuthorSlots.distinctSlotsOfOneRecord(a, b))
    assert(AuthorSlots.distinctSlotsOfOneRecord(b, a))
    assert(!AuthorSlots.distinctSlotsOfOneRecord(a, a.copy(id = "other")))
    assert(
      !AuthorSlots.distinctSlotsOfOneRecord(a, b.copy(authorPosition = None))
    )
    assert(!AuthorSlots.distinctSlotsOfOneRecord(a, b.copy(workId = "w-other")))
    assert(
      !AuthorSlots.distinctSlotsOfOneRecord(a, b.copy(kind = EntityKind.Work))
    )
  }
