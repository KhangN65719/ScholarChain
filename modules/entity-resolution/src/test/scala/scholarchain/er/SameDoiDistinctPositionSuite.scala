package scholarchain.er

import scholarchain.core.{MatchOutcome, SourceType, WorkRecord}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*

/** Review round 1, finding 1, and round 2, findings 1, 2 and 5: two different
  * coauthors with one name on one paper, seen through two sources, must not be
  * merged, whether the paper is linked by a shared DOI or a shared arXiv ID,
  * and an identifier agreeing across the two slots is a CONFLICT. Synthetic
  * fixtures only; no gold pair is used.
  */
class SameDoiDistinctPositionSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)
  private val doi = "10.1000/synthetic.5authors"
  private val title = "Learning sparse representations of graph data"
  private val authors =
    Vector("Ann Chen", "Wei Wang", "Wei Wang", "Bo Li", "Cy Zhou")
  private val rule = "rule.same-doi-distinct-position"
  private val arxivRule = "rule.same-arxiv-distinct-position"
  private val conflictByPosition =
    "conflict.id-agreement-contradicted-by-position"
  private val arxivCore = "2101.00001"

  /** DBLP side of a preprint: the arXiv ID in its own field, no DOI. */
  private def arxivDblp(
      key: String = "conf/x/1",
      arxivId: String = "2101.00001v2"
  ): WorkRecord =
    coreWork("dblp", key, title, authors, None, SourceType.Dblp)
      .copy(arxivId = Some(arxivId))

  /** OpenAlex side of the same preprint: the arXiv DOI form, no arXiv field. */
  private def arxivOpenAlex(
      doiForm: String = "10.48550/arXiv.2101.00001"
  ): WorkRecord = openAlex(workDoi = Some(doiForm))

  private def dblp(
      names: Vector[String] = authors,
      workDoi: Option[String] = Some(doi)
  ): WorkRecord =
    coreWork("dblp", "conf/x/1", title, names, workDoi, SourceType.Dblp)

  private def openAlex(
      names: Vector[String] = authors,
      workDoi: Option[String] = Some(doi)
  ): WorkRecord =
    coreWork("openalex", "W1", title, names, workDoi, SourceType.OpenAlex)
      .copy(venueId = None)

  private def mentionOf(w: WorkRecord, position: Int): SideRecord =
    RecordIndex.fromCore(Vector(w)).get(w.authors(position - 1).mentionId).get

  private def decide(a: SideRecord, b: SideRecord): ResolutionRecord =
    matcher.decide(MatchInput.of(a, b, "b"))

  private def code(r: ResolutionRecord) = r.decision.reasonCodes

  test(
    "the same-name coauthors of one DOI seen through two sources: rejected"
  ) {
    val record = decide(mentionOf(dblp(), 2), mentionOf(openAlex(), 3))
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assertEquals(record.path, MatchPath.DeterministicBibliographic)
    assert(record.decision.rulesFired.contains(rule), code(record).toString)
    assert(code(record).contains("rejected.same-doi-distinct-position"))
    assertNotEquals(record.decision.decision, MatchOutcome.Accepted)
  }

  test("the decision is the same whichever side is listed first") {
    val a = mentionOf(dblp(), 3)
    val b = mentionOf(openAlex(), 2)
    assertEquals(
      matcher.decide(MatchInput.of(a, b, "b")),
      matcher.decide(MatchInput.of(b, a, "b"))
    )
    assert(code(decide(a, b)).contains("rejected.same-doi-distinct-position"))
  }

  test("control: without the DOI the same pair scores about 0.978, ACCEPTED") {
    val record = decide(
      mentionOf(dblp(workDoi = None), 2),
      mentionOf(openAlex(workDoi = None), 3)
    )
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.Probabilistic)
    assertEqualsDouble(record.score, 0.978, 0.002)
    assert(!record.decision.rulesFired.contains(rule))
  }

  test("control: the same author at the same position still matches") {
    val record = decide(mentionOf(dblp(), 2), mentionOf(openAlex(), 2))
    assert(!record.decision.rulesFired.contains(rule))
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
  }

  test("control: different DOIs do not trigger the rule") {
    val other = openAlex(workDoi = Some("10.1000/another"))
    val record = decide(mentionOf(dblp(), 2), mentionOf(other, 3))
    assert(!record.decision.rulesFired.contains(rule))
  }

  test("control: a DOI on one side only does not trigger the rule") {
    val record = decide(
      mentionOf(dblp(), 2),
      mentionOf(openAlex(workDoi = None), 3)
    )
    assert(!record.decision.rulesFired.contains(rule))
  }

  test("an arXiv-prefixed DOI is not a work DOI; it keys as an arXiv ID") {
    val arxiv = Some("10.48550/arXiv.2101.00001")
    val record = decide(
      mentionOf(dblp(workDoi = arxiv), 2),
      mentionOf(openAlex(workDoi = arxiv), 3)
    )
    assert(!record.decision.rulesFired.contains(rule))
    assert(
      record.decision.rulesFired.contains("rule.same-arxiv-distinct-position")
    )
    assertEquals(mentionOf(dblp(workDoi = arxiv), 2).workDoi, None)
    assertEquals(
      mentionOf(dblp(workDoi = arxiv), 2).workArxiv,
      Some("2101.00001")
    )
  }

  test("control: an unknown position is not guessed") {
    val known = mentionOf(dblp(), 2)
    val unknown = mentionOf(openAlex(), 3).copy(authorPosition = None)
    assertEquals(AuthorSlots.index(unknown), None)
    assert(!decide(known, unknown).decision.rulesFired.contains(rule))
    val outOfRange = mentionOf(openAlex(), 3).copy(authorPosition = Some(9))
    assert(!decide(known, outOfRange).decision.rulesFired.contains(rule))
  }

  test("control: author lists that do not align are not compared") {
    val truncated = openAlex(authors.take(4))
    val reordered =
      openAlex(Vector("Bo Li", "Wei Wang", "Wei Wang", "Ann Chen", "Cy Zhou"))
    Vector(truncated, reordered).foreach { other =>
      val record = decide(mentionOf(dblp(), 2), mentionOf(other, 3))
      assert(!record.decision.rulesFired.contains(rule), code(record).toString)
    }
  }

  test("a different spelling and order of the same names still aligns") {
    val spelled =
      Vector("Chen, Ann", "Wang Wei", "W. Wang", "Li, Bo", "Zhou, Cy")
    val record = decide(mentionOf(dblp(), 2), mentionOf(openAlex(spelled), 3))
    assert(record.decision.rulesFired.contains(rule), code(record).toString)
  }

  // Round 2, finding 2. DELIBERATE CHANGE: until policy 1.2.0 this test pinned
  // ACCEPTED for an agreeing ORCID on two different slots of one aligned list.
  // The human ruled it a data contradiction, so it is now a CONFLICT.
  test("an agreeing ORCID on two slots of one aligned list is a CONFLICT") {
    val orcid = Map("orcid" -> validOrcidA)
    val a = mentionOf(dblp(), 2).copy(externalIds = orcid)
    val b = mentionOf(openAlex(), 3).copy(externalIds = orcid)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assertEquals(record.path, MatchPath.ExactId)
    assertEquals(code(record).take(1), Vector(conflictByPosition))
    assert(record.decision.rulesFired.contains("rule.id-agreement:orcid"))
    assert(record.decision.rulesFired.contains(rule))
    assertNotEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(decide(b, a), record)
  }

  test("an agreeing ORCID on two slots with a shared arXiv ID is a CONFLICT") {
    val orcid = Map("orcid" -> validOrcidA)
    val a = mentionOf(arxivDblp(), 2).copy(externalIds = orcid)
    val b = mentionOf(arxivOpenAlex(), 3).copy(externalIds = orcid)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assertEquals(code(record).take(1), Vector(conflictByPosition))
    assert(
      record.decision.rulesFired.contains(arxivRule),
      code(record).toString
    )
  }

  test("an agreeing aggregator author ID on two slots is a CONFLICT") {
    val ids = Map("openalex" -> "A123")
    val a = mentionOf(dblp(), 2).copy(externalIds = ids)
    val b = mentionOf(openAlex(), 3).copy(externalIds = ids)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assertEquals(record.path, MatchPath.ExactId)
    assertEquals(code(record).take(1), Vector(conflictByPosition))
    assert(
      record.decision.rulesFired.contains("rule.id-agreement:openalex-author")
    )
    assert(record.decision.rulesFired.contains(rule))
  }

  test(
    "control: an aggregator ID the content does not support stays rejected"
  ) {
    val names = Vector("Ann Chen", "Wei Wang", "Li Wang", "Bo Li", "Cy Zhou")
    val ids = Map("openalex" -> "A123")
    val a = mentionOf(dblp(names), 2).copy(externalIds = ids)
    val b = mentionOf(openAlex(names), 3).copy(externalIds = ids)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assert(record.decision.rulesFired.contains(rule), code(record).toString)
    assert(!code(record).contains(conflictByPosition), code(record).toString)
  }

  test("control: differing aggregator IDs on two slots stay rejected by rule") {
    val a = mentionOf(dblp(), 2).copy(externalIds = Map("openalex" -> "A1"))
    val b = mentionOf(openAlex(), 3).copy(externalIds = Map("openalex" -> "A2"))
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assert(record.decision.rulesFired.contains(rule))
    assert(!code(record).contains(conflictByPosition))
  }

  test("control: the same ORCID at the same position is still ACCEPTED") {
    val orcid = Map("orcid" -> validOrcidA)
    val a = mentionOf(dblp(), 2).copy(externalIds = orcid)
    val b = mentionOf(openAlex(), 2).copy(externalIds = orcid)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.ExactId)
    assert(record.decision.rulesFired.contains("rule.id-agreement:orcid"))
    assert(!code(record).contains(conflictByPosition))
  }

  test("control: an ORCID agreeing with no shared work key is unchanged") {
    val orcid = Map("orcid" -> validOrcidA)
    val other = openAlex(workDoi = Some("10.1000/another"))
    val none = openAlex(workDoi = None)
    Vector(other, none).foreach { w =>
      val record = decide(
        mentionOf(dblp(), 2).copy(externalIds = orcid),
        mentionOf(w, 3).copy(externalIds = orcid)
      )
      assertEquals(record.decision.decision, MatchOutcome.Accepted)
      assertEquals(record.path, MatchPath.ExactId)
      assert(!code(record).contains(conflictByPosition))
    }
  }

  test(
    "control: an ORCID with unaligned lists or an unknown position is unchanged"
  ) {
    val orcid = Map("orcid" -> validOrcidA)
    val a = mentionOf(dblp(), 2).copy(externalIds = orcid)
    val truncated =
      mentionOf(openAlex(authors.take(4)), 3).copy(externalIds = orcid)
    val unknown =
      mentionOf(openAlex(), 3).copy(externalIds = orcid, authorPosition = None)
    Vector(truncated, unknown).foreach { b =>
      val record = decide(a, b)
      assertEquals(record.decision.decision, MatchOutcome.Accepted)
      assert(!code(record).contains(conflictByPosition))
    }
  }

  test("control: different ORCIDs on two slots are still a CONFLICT by ORCID") {
    val a = mentionOf(dblp(), 2).copy(externalIds = Map("orcid" -> validOrcidA))
    val b =
      mentionOf(openAlex(), 3).copy(externalIds = Map("orcid" -> validOrcidB))
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assert(code(record).contains("conflict.id:orcid"))
    assert(!code(record).contains(conflictByPosition))
  }

  // ------------------------------------------------ DOI spelling

  test("a DOI that differs only by URL prefix is the same work key") {
    val prefixed = openAlex(workDoi = Some(s"https://doi.org/$doi"))
    val record = decide(mentionOf(dblp(), 2), mentionOf(prefixed, 3))
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assert(record.decision.rulesFired.contains(rule), code(record).toString)
    assertEquals(mentionOf(prefixed, 3).workDoi, Some(doi))
  }

  test("a DOI that differs only by case is the same work key") {
    val shouted = openAlex(workDoi = Some(doi.toUpperCase))
    val record = decide(mentionOf(dblp(), 2), mentionOf(shouted, 3))
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assert(record.decision.rulesFired.contains(rule), code(record).toString)
    assert(code(record).contains("rejected.same-doi-distinct-position"))
  }

  // ------------------------------------------------ arXiv key (round 2, finding 1)

  test("workArxiv: every spelling of one arXiv ID is one key") {
    val forms = Vector[(Option[String], Option[String])](
      (Some("2101.00001"), None),
      (Some("2101.00001v2"), None),
      (Some("arXiv:2101.00001v3"), None),
      (Some("https://arxiv.org/abs/2101.00001"), None),
      (Some("https://arxiv.org/pdf/2101.00001v1.pdf"), None),
      (None, Some("10.48550/arXiv.2101.00001")),
      (None, Some("https://doi.org/10.48550/arxiv.2101.00001")),
      (None, Some("10.48550/ARXIV.2101.00001")),
      (None, Some("doi:10.48550/arXiv.2101.00001"))
    )
    forms.foreach { (id, d) =>
      assertEquals(Identifiers.workArxiv(id, d), Some("2101.00001"), s"$id $d")
    }
    assertEquals(
      Identifiers.workArxiv(None, Some("10.48550/arXiv.cs/0112017")),
      Some("cs/0112017")
    )
  }

  test("workArxiv: a non-arXiv value is not a key") {
    assertEquals(Identifiers.workArxiv(None, None), None)
    assertEquals(Identifiers.workArxiv(Some("not an id"), None), None)
    assertEquals(Identifiers.workArxiv(None, Some("10.1000/journal")), None)
    assertEquals(Identifiers.workArxiv(None, Some("10.48550/other.1")), None)
    assertEquals(Identifiers.workArxiv(Some("12345"), Some("10.1000/x")), None)
  }

  test(
    "a DBLP arXiv ID and an OpenAlex arXiv DOI form: not ACCEPTED, rejected"
  ) {
    val a = mentionOf(arxivDblp(), 2)
    val b = mentionOf(arxivOpenAlex(), 3)
    assertEquals(a.workArxiv, Some(arxivCore))
    assertEquals(b.workArxiv, Some(arxivCore))
    assertEquals(a.workDoi, None)
    assertEquals(b.workDoi, None)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assertEquals(record.path, MatchPath.DeterministicBibliographic)
    assert(
      record.decision.rulesFired.contains(arxivRule),
      code(record).toString
    )
    assert(code(record).contains("rejected.same-arxiv-distinct-position"))
    assert(!record.decision.rulesFired.contains(rule))
    assertNotEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(decide(b, a), record)
  }

  test("a shared arXiv ID in the plain field on both sides fires the rule") {
    val a = mentionOf(arxivDblp(), 2)
    val b = mentionOf(arxivDblp("conf/x/2", "2101.00001v2"), 3)
    assert(decide(a, b).decision.rulesFired.contains(arxivRule))
  }

  test(
    "control: without any arXiv key the same pair is ACCEPTED (failure mode)"
  ) {
    val record = decide(
      mentionOf(dblp(workDoi = None), 2),
      mentionOf(openAlex(workDoi = None), 3)
    )
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assert(!record.decision.rulesFired.contains(arxivRule))
  }

  test("control: different arXiv IDs do not trigger the rule") {
    val other = arxivOpenAlex("10.48550/arXiv.2202.99999")
    val record = decide(mentionOf(arxivDblp(), 2), mentionOf(other, 3))
    assert(!record.decision.rulesFired.contains(arxivRule))
    assert(!record.decision.rulesFired.contains(rule))
  }

  test("control: an arXiv ID on one side only does not trigger the rule") {
    val bare = openAlex(workDoi = None)
    val record = decide(mentionOf(arxivDblp(), 2), mentionOf(bare, 3))
    assert(!record.decision.rulesFired.contains(arxivRule))
    val reverse =
      decide(mentionOf(dblp(workDoi = None), 2), mentionOf(arxivOpenAlex(), 3))
    assert(!reverse.decision.rulesFired.contains(arxivRule))
  }

  test("control: an unknown or out-of-range position is not guessed (arXiv)") {
    val known = mentionOf(arxivDblp(), 2)
    val unknown = mentionOf(arxivOpenAlex(), 3).copy(authorPosition = None)
    assert(!decide(known, unknown).decision.rulesFired.contains(arxivRule))
    val outOfRange =
      mentionOf(arxivOpenAlex(), 3).copy(authorPosition = Some(9))
    assert(!decide(known, outOfRange).decision.rulesFired.contains(arxivRule))
  }

  test(
    "control: the same position under a shared arXiv ID is not forced to REJECTED"
  ) {
    val record =
      decide(mentionOf(arxivDblp(), 2), mentionOf(arxivOpenAlex(), 2))
    assert(!record.decision.rulesFired.contains(arxivRule))
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
  }

  test("control: unaligned author lists are not compared (arXiv)") {
    val truncated =
      openAlex(authors.take(4), Some("10.48550/arXiv.2101.00001"))
    val record = decide(mentionOf(arxivDblp(), 2), mentionOf(truncated, 3))
    assert(!record.decision.rulesFired.contains(arxivRule))
  }

  test(
    "control: a preprint and a published version with different DOIs are not conflated"
  ) {
    val published = openAlex(workDoi = Some("10.1000/journal.2021"))
    val preprint = arxivDblp()
    val record = decide(mentionOf(preprint, 2), mentionOf(published, 3))
    assert(!record.decision.rulesFired.contains(arxivRule))
    assert(!record.decision.rulesFired.contains(rule))
    assertEquals(mentionOf(published, 3).workArxiv, None)
    assertEquals(mentionOf(preprint, 2).workDoi, None)
  }

  test("control: one arXiv ID under two different work DOIs is not conflated") {
    val a = mentionOf(arxivDblp().copy(doi = Some("10.1000/conference")), 2)
    val b = mentionOf(
      openAlex(workDoi = Some("10.1000/journal"))
        .copy(arxivId = Some("2101.00001")),
      3
    )
    assertEquals(a.workArxiv, b.workArxiv)
    assertEquals(AuthorSlots.sharedWorkKey(a, b), None)
    assert(!decide(a, b).decision.rulesFired.contains(arxivRule))
  }

  test("a shared work DOI wins over a shared arXiv ID, with the DOI codes") {
    val a = mentionOf(dblp().copy(arxivId = Some("2101.00001")), 2)
    val b = mentionOf(openAlex().copy(arxivId = Some("2101.00001")), 3)
    val record = decide(a, b)
    assert(record.decision.rulesFired.contains(rule))
    assert(!record.decision.rulesFired.contains(arxivRule))
  }

  test(
    "the arXiv ID of a published version meets the preprint's arXiv DOI form"
  ) {
    val published =
      openAlex(workDoi = Some("10.1000/journal.2021"))
        .copy(arxivId = Some("2101.00001"))
    val a = mentionOf(arxivDblp(), 2)
    val b = mentionOf(published, 3)
    assertEquals(AuthorSlots.sharedWorkKey(a, b), Some(WorkKey.Arxiv))
    assert(decide(a, b).decision.rulesFired.contains(arxivRule))
  }

  test("a position is the zero-based list index in all three sources") {
    Vector(
      coreWork("dblp", "k", title, authors, Some(doi), SourceType.Dblp),
      coreWork(
        "openalex",
        "W9",
        title,
        authors,
        Some(doi),
        SourceType.OpenAlex
      ),
      coreWork("crossref", doi, title, authors, Some(doi), SourceType.Crossref)
    ).foreach { w =>
      val index = RecordIndex.fromCore(Vector(w))
      authors.indices.foreach { i =>
        val side = index.get(w.authors(i).mentionId).get
        assertEquals(AuthorSlots.index(side), Some(i), w.source.toString)
        assertEquals(side.workDoi, Some(doi))
      }
    }
  }

  test("workDoi keeps the wire format: absent when empty, optional on read") {
    val without = mentionOf(dblp(workDoi = None), 2)
    val json = upickle.default.write(without)
    assert(!json.contains("workDoi"), json)
    assert(!json.contains("workArxiv"), json)
    val arxivSide = mentionOf(arxivDblp(), 2)
    assertEquals(
      upickle.default.read[SideRecord](upickle.default.write(arxivSide)),
      arxivSide
    )
    assertEquals(upickle.default.read[SideRecord](json), without)
    val with_ = mentionOf(dblp(), 2)
    assertEquals(
      upickle.default.read[SideRecord](upickle.default.write(with_)),
      with_
    )
  }
