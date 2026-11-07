package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite
import scholarchain.core.{SourceType, StableIds}
import scholarchain.hadoop.pipeline.{
  DblpParser,
  IdNamespace,
  RejectReason,
  XmlRecordStream
}

/** Parser tests against the pinned bounded fixture and against hand-written
  * adversarial records.
  */
class DblpParserSuite extends FunSuite:
  private val run = TestRun.context()

  private lazy val sampleRecords: Vector[String] =
    XmlRecordStream.records(
      Files.readString(TestRun.samples.resolve("dblp.xml"), UTF_8)
    )

  test("the bounded fixture yields the two pinned publication records") {
    assertEquals(sampleRecords.size, 2)
  }

  test("a single-author article normalizes completely") {
    val parsed = DblpParser.parse(sampleRecords.head, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    val work = publication.work
    assertEquals(work.sourceRecordId, "journals/pvldb/Ley09")
    assertEquals(work.workId, StableIds.work("dblp", "journals/pvldb/Ley09"))
    assertEquals(work.source, SourceType.Dblp)
    assertEquals(work.recordType, "article")
    assertEquals(work.title, "DBLP - Some Lessons Learned.")
    assertEquals(work.normalizedTitle, "dblp some lessons learned")
    assertEquals(work.year, Some(2009))
    assertEquals(work.venueId, Some("venue:v1:dblp:proc-vldb-endow"))
    assertEquals(work.doi, Some("10.14778/1687553.1687577"))
    assertEquals(work.arxivId, None)
    assertEquals(
      work.electronicEditionUris,
      Vector("https://doi.org/10.14778/1687553.1687577")
    )
    assertEquals(work.authors.size, 1)
    val author = work.authors.head
    assertEquals(author.authorPosition, 1)
    assertEquals(author.name, "Michael Ley")
    assertEquals(author.normalizedName, "michael ley")
    assertEquals(
      author.externalIds.get(IdNamespace.Orcid.wire),
      Some("0000-0001-7580-4351")
    )
    assertEquals(author.mentionId, StableIds.mention(work.workId, 1))
  }

  test("author occurrence order is the author sequence and is not sorted") {
    val publication =
      DblpParser.parse(sampleRecords(1), run).getOrElse(fail("parse failed"))
    assertEquals(
      publication.work.authors.map(_.name),
      Vector(
        "Christopher Michels",
        "Ruslan R. Fayzrakhmanov",
        "Michael Ley",
        "Emanuel Sallinger",
        "Ralf Schenkel"
      )
    )
    assertEquals(
      publication.work.authors.map(_.authorPosition),
      Vector(1, 2, 3, 4, 5)
    )
    assertEquals(publication.work.recordType, "inproceedings")
    assertEquals(publication.work.venueId, Some("venue:v1:dblp:jcdl"))
    assertEquals(publication.work.year, Some(2017))
  }

  test("every record carries source and transformation provenance") {
    val publication =
      DblpParser.parse(sampleRecords.head, run).getOrElse(fail("parse failed"))
    val provenance = publication.work.provenance
    assertEquals(provenance.source, SourceType.Dblp)
    assertEquals(provenance.snapshotId, "dblp:2026-11-01")
    assertEquals(provenance.sourceRecordId, "journals/pvldb/Ley09")
    assertEquals(provenance.transformVersion, "hadoop-pipeline:v1")
    assertEquals(provenance.runId, TestRun.runId)
    assertEquals(
      provenance.sourceUri,
      "https://dblp.org/rec/journals/pvldb/Ley09"
    )
    // Mentions carry the same provenance, so a mention is always traceable.
    assertEquals(publication.work.authors.head.provenance, provenance)
  }

  test("parsing is deterministic for identical input") {
    val first = DblpParser.parse(sampleRecords(1), run)
    val second = DblpParser.parse(sampleRecords(1), run)
    assertEquals(first, second)
  }

  test("a DBLP crossref element is never read as a DOI") {
    val xml =
      """<inproceedings key="conf/jcdl/X17"><author>A B</author>
        |<title>T.</title><crossref>conf/jcdl/2017</crossref></inproceedings>""".stripMargin
    val publication = DblpParser.parse(xml, run).getOrElse(fail("parse failed"))
    assertEquals(publication.work.doi, None)
    assertEquals(
      publication.identifiers,
      Vector(IdNamespace.DblpKey -> "conf/jcdl/X17")
    )
  }

  test("a record without a key is rejected") {
    val result =
      DblpParser.parse("<article><title>T.</title></article>", run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("a record without a title is rejected") {
    val result =
      DblpParser.parse(
        """<article key="a/1"><author>A B</author></article>""",
        run
      )
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("a blank author element is rejected rather than renumbered") {
    // Silently dropping the blank author would shift every later author's
    // position and break the mention IDs the whole graph is keyed on.
    val xml =
      """<article key="a/1"><author>A B</author><author> </author>
        |<author>C D</author><title>T.</title></article>""".stripMargin
    val result = DblpParser.parse(xml, run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("malformed XML is rejected with a diagnostic, not an exception") {
    val result = DblpParser.parse("<article key=\"a/1\"><title>T.", run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
    assert(result.left.toOption.exists(_._2.nonEmpty))
  }

  test("a DOCTYPE declaration is refused rather than resolved") {
    // The fixture omits the DTD by dataset policy; a record carrying one must
    // not cause an entity expansion or an external fetch.
    val xml =
      """<!DOCTYPE article [<!ENTITY x "expanded">]>
        |<article key="a/1"><title>&x;</title></article>""".stripMargin
    val result = DblpParser.parse(xml, run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test("an unsupported record type is rejected as unsupported") {
    val result =
      DblpParser.parse(
        """<www key="homepages/x"><author>A B</author></www>""",
        run
      )
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.UnsupportedRecordType)
    )
  }

  test("inline title markup is preserved as text") {
    val xml =
      """<article key="a/1"><author>A B</author><title>On <i>Escherichia</i> coli.</title></article>"""
    val publication = DblpParser.parse(xml, run).getOrElse(fail("parse failed"))
    assertEquals(publication.work.title, "On Escherichia coli.")
  }

  test("Unicode author names normalize without loss") {
    val xml =
      """<article key="a/1"><author>Émile Zola</author><title>T.</title></article>"""
    val publication = DblpParser.parse(xml, run).getOrElse(fail("parse failed"))
    assertEquals(publication.work.authors.head.name, "Émile Zola")
    assertEquals(publication.work.authors.head.normalizedName, "emile zola")
  }

  test("editors are kept out of the author sequence") {
    // Editors are not coauthors, so including them would invent collaboration
    // edges that the source does not claim.
    val xml =
      """<proceedings key="conf/jcdl/2017"><editor>E One</editor>
        |<title>Proceedings.</title><year>2017</year></proceedings>""".stripMargin
    val publication = DblpParser.parse(xml, run).getOrElse(fail("parse failed"))
    assertEquals(publication.work.authors, Vector.empty)
    assertEquals(publication.editorNames, Vector("E One"))
  }

  test("a www identity record normalizes to a canonical researcher") {
    val xml =
      """<www key="homepages/l/MichaelLey"><author orcid="0000-0001-7580-4351">Michael Ley</author>
        |<author>M. Ley</author></www>""".stripMargin
    val researcher =
      DblpParser.parseIdentity(xml, run).getOrElse(fail("parse failed"))
    assertEquals(
      researcher.researcherId,
      StableIds.researcherFromExternal("dblp", "homepages/l/MichaelLey")
    )
    assertEquals(researcher.displayName, "Michael Ley")
    assertEquals(researcher.aliases, Vector("M. Ley"))
    assertEquals(
      researcher.externalIds.get(IdNamespace.Orcid.wire),
      Some("0000-0001-7580-4351")
    )
  }

  test("a publication record is not accepted on the identity path") {
    val result = DblpParser.parseIdentity(sampleRecords.head, run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.UnsupportedRecordType)
    )
  }
