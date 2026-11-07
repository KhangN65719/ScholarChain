package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.SourceType
import scholarchain.hadoop.pipeline.BlockStrategy
import scholarchain.hadoop.pipeline.Blocking
import scholarchain.hadoop.pipeline.DblpParser
import scholarchain.hadoop.pipeline.IdNamespace
import scholarchain.hadoop.pipeline.IdentifierJoin
import scholarchain.hadoop.pipeline.PipelineCounter
import scholarchain.hadoop.pipeline.RecordingSink
import scholarchain.hadoop.pipeline.RejectReason
import scholarchain.hadoop.pipeline.TaskSink
import scholarchain.hadoop.pipeline.TeamExpansion

/** Missing fields, in combination.
  *
  * `DblpParserSuite` covers a record with no key, a record with no title and a
  * blank author element one at a time. The cases here are the combinations and
  * the absences it does not try: no authors at all, a title that is present but
  * blank, a key that is present but unusable, a record with neither a year nor
  * a venue, and a work with no exact identifier of any kind. The question each
  * one asks is what the pipeline does with a record that is valid XML and
  * useless as evidence.
  */
class AdversarialMissingFieldSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private def reasonOf(xml: String): Option[RejectReason] =
    DblpParser.parse(xml, run).left.toOption.map(_._1)

  private def detailOf(xml: String): Option[String] =
    DblpParser.parse(xml, run).left.toOption.map(_._2)

  test("a record with no author element at all is accepted") {
    // A publication with no authors is a graph node that can never carry an
    // edge. It is accepted silently: no counter separates it from a record
    // that simply has not been parsed yet.
    val xml =
      """<article key="noauthor/1"><title>Anonymous.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(publication.work.authors, Vector.empty)
    val expanded = TeamExpansion.expand(publication.work, run)
    assertEquals(expanded.team.orderedMentionIds, Vector.empty)
    assertEquals(expanded.authorPapers, Vector.empty)
    assertEquals(expanded.expectedPairCount, 0L)
  }

  test("a zero-author work is counted as a single-author work") {
    // Minor defect, owner hadoop-engineer. `TeamExpansion.emit` counts
    // SingleAuthorWorks whenever the team holds fewer than two mentions, so
    // the counter the report quotes for "papers that can carry no edge"
    // conflates none with one. Required: count them apart, or rename the
    // counter to what it measures.
    val xml =
      """<article key="noauthor/2"><title>Anonymous.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    val sink = new RecordingSink
    TeamExpansion.emit(publication.work, sink, run)
    assertEquals(sink.counterValue(PipelineCounter.SingleAuthorWorks), 1L)
    assertEquals(sink.recordsLabelled(TaskSink.teams).size, 1)
    assertEquals(sink.recordsLabelled(TaskSink.authorPapers).size, 0)
    assertEquals(sink.rejects, Vector.empty)
  }

  test("a blank title is missing, in every shape it can take") {
    val empty = """<article key="t/1"><title></title></article>"""
    val spaces = """<article key="t/2"><title>   </title></article>"""
    val selfClosed = """<article key="t/3"><title/></article>"""
    assertEquals(reasonOf(empty), Some(RejectReason.MissingRequiredField))
    assertEquals(reasonOf(spaces), Some(RejectReason.MissingRequiredField))
    assertEquals(
      reasonOf(selfClosed),
      Some(RejectReason.MissingRequiredField)
    )
    assertEquals(detailOf(spaces), Some("DBLP record has no title"))
  }

  test("a key that cannot be a join key is refused, delimiter included") {
    // A key carrying the join-key delimiter would let a record forge an
    // identifier key, so the refusal is load-bearing rather than cosmetic.
    val spaced = """<article key="a 1"><title>T.</title></article>"""
    val piped = """<article key="a|1"><title>T.</title></article>"""
    val tabbed = """<article key="a&#9;1"><title>T.</title></article>"""
    assertEquals(reasonOf(spaced), Some(RejectReason.MissingRequiredField))
    assertEquals(reasonOf(piped), Some(RejectReason.MissingRequiredField))
    assertEquals(reasonOf(tabbed), Some(RejectReason.MissingRequiredField))
    assert(detailOf(piped).exists(_.startsWith("Malformed DBLP key")))
  }

  test("the missing-field checks have a fixed precedence") {
    // Nothing else pins the order, and a report that quotes reject details
    // depends on it. Key is checked before title, and both before authors.
    val nothing = """<article><blurb>x</blurb></article>"""
    val titleOnly = """<article><title>T.</title></article>"""
    val keyOnly = """<article key="p/1"><author>A B</author></article>"""
    assertEquals(detailOf(nothing), Some("DBLP record has no key"))
    assertEquals(detailOf(titleOnly), Some("DBLP record has no key"))
    assertEquals(detailOf(keyOnly), Some("DBLP record has no title"))
  }

  test("a self-closing author element is refused like a blank one") {
    // Accepting it would renumber every later author and break the mention
    // IDs the graph is keyed on.
    val xml =
      """<article key="a/1"><author>A B</author><author/>""" +
        """<author>C D</author><title>T.</title></article>"""
    assertEquals(reasonOf(xml), Some(RejectReason.MissingRequiredField))
    assertEquals(
      detailOf(xml),
      Some("DBLP record has a blank or unmatchable author element")
    )
  }

  test("a record with neither year nor venue keeps only unsalted recall") {
    // Both selective researcher strategies need a year or a venue, so a
    // record missing both is blocked by NameInitials alone, which is the
    // strategy that goes pathological on a common name. The two failure
    // modes compound: the records with the least evidence are the ones that
    // land in the least selective block.
    val subject = AdversarialFixtures.work(
      key = "sparse/1",
      names = Vector("Michael Ley"),
      year = None,
      venueId = None
    )
    val keys =
      Blocking.researcherKeys(subject.authors.head, subject, Vector.empty)
    assertEquals(keys.map(_._1), Vector(BlockStrategy.NameInitials))
    assertEquals(
      subject.authors.head.normalizedName,
      "michael ley"
    )
    assertEquals(
      Blocking.workKeys(subject).map(_._1),
      Vector(BlockStrategy.TitlePrefix)
    )
    assert(!BlockStrategy.NameInitials.selective)
    assert(!BlockStrategy.TitlePrefix.selective)
  }

  test("a blank normalized title is unreachable, so its guard is dead code") {
    // `Blocking.workKeys` opens with `if title.isEmpty then Vector.empty`.
    // No valid WorkRecord can reach it: the contract asserts a nonblank
    // normalized title. The parser must reject such input before construction.
    val valid =
      AdversarialFixtures.work("dead/1", Vector("Michael Ley"))
    intercept[IllegalArgumentException] {
      valid.copy(normalizedTitle = "")
    }
    assertEquals(Blocking.workKeys(valid).size, 2)
  }

  test("an unusable ORCID is dropped with no counter and no reject") {
    // Minor defect, owner hadoop-engineer. The check digit here is wrong by
    // one, so `ExactIdentifiers.orcid` returns None and the identifier
    // disappears. A corrupt identifier and an absent identifier become the
    // same observation, and the quality counters cannot tell them apart.
    val xml =
      """<article key="orc/1"><author orcid="0000-0001-7580-4352">A B""" +
        """</author><title>T.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(
      publication.work.authors.head.externalIds,
      Map.empty[String, String]
    )
    assertEquals(
      publication.identifiers,
      Vector(IdNamespace.DblpKey -> "orc/1")
    )
  }

  test("an ee link that is not an identifier is kept but joins nothing") {
    val xml =
      """<article key="ee/1"><author>A B</author><title>T.</title>""" +
        """<ee>https://example.org/paper.pdf</ee></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(
      publication.work.electronicEditionUris,
      Vector("https://example.org/paper.pdf")
    )
    assertEquals(publication.work.doi, None)
    assertEquals(
      publication.identifiers,
      Vector(IdNamespace.DblpKey -> "ee/1")
    )
  }

  test("a work with no exact identifier counts as a missing join key") {
    // A source with no native work identifier and no DOI can only ever be
    // linked by the fuzzy path, which is what the counter is for.
    val subject = AdversarialFixtures.work(
      key = "0000-0001-7580-4351",
      names = Vector("Michael Ley"),
      source = SourceType.Orcid
    )
    val sink = new RecordingSink
    IdentifierJoin.emit(subject, Vector.empty, sink)
    assertEquals(IdentifierJoin.claims(subject, Vector.empty), Vector.empty)
    assertEquals(sink.counterValue(PipelineCounter.MissingKeyRecords), 1L)
    assertEquals(
      sink.recordsLabelled(TaskSink.identityLinkInputs),
      Vector.empty
    )
  }

  test("zero-author works do not increment SingleAuthorWorks".fail) {
    val subject = AdversarialFixtures.work("noauthor/3", Vector.empty)
    val sink = new RecordingSink
    TeamExpansion.emit(subject, sink, run)
    assertEquals(sink.counterValue(PipelineCounter.SingleAuthorWorks), 0L)
  }
