package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.core.{ContractWire, SourceType, StableIds}
import scholarchain.hadoop.pipeline.{
  ExactIdentifiers,
  IdNamespace,
  RejectReason,
  SourceNormalizers
}

/** Normalizer tests against the pinned bounded fixtures. */
class SourceNormalizerSuite extends FunSuite:
  private val run = TestRun.context()

  private def lines(dataset: String): Vector[String] =
    Files
      .readAllLines(TestRun.samples.resolve(s"$dataset.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)

  test("every selected dataset has a registered normalizer") {
    assertEquals(
      SourceNormalizers.byDataset.keySet,
      Set("openalex", "orcid", "crossref")
    )
  }

  test("an OpenAlex work normalizes with its source-specific identifiers") {
    val record = SourceNormalizers
      .openAlex(lines("openalex").head, run)
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(work.sourceRecordId, "W2147107544")
    assertEquals(work.workId, StableIds.work("openalex", "W2147107544"))
    assertEquals(work.source, SourceType.OpenAlex)
    assertEquals(work.title, "DBLP")
    assertEquals(work.year, Some(2009))
    assertEquals(work.doi, Some("10.14778/1687553.1687577"))
    assertEquals(work.authors.size, 1)
    val author = work.authors.head
    assertEquals(
      author.externalIds.get(IdNamespace.OpenAlexAuthor.wire),
      Some("A5033019634")
    )
    assertEquals(
      author.externalIds.get(IdNamespace.Orcid.wire),
      Some("0000-0001-7580-4351")
    )
    // The coarse OpenAlex position claim is preserved rather than discarded.
    assertEquals(
      author.externalIds.get("openalex-author-position"),
      Some("first")
    )
    assertEquals(author.affiliations, Vector("Universität Trier"))
    assertEquals(work.provenance.snapshotId, "openalex:2026-10-14")
  }

  test("OpenAlex topics and institutions are retained with their IDs") {
    val record = SourceNormalizers
      .openAlex(lines("openalex").head, run)
      .getOrElse(fail("normalization failed"))
    assertEquals(record.topics.size, 3)
    assertEquals(record.topics.head.topicId, "topic:v1:openalex:T14330")
    assertEquals(
      record.topics.head.name,
      "Library Science and Information Systems"
    )
    assertEquals(record.institutions.size, 1)
    val institution = record.institutions.head
    assertEquals(institution.institutionId, "institution:v1:openalex:I89864525")
    assertEquals(
      institution.externalIds.get(IdNamespace.Ror.wire),
      Some("02778hg05")
    )
    assertEquals(record.researchers.size, 1)
    assertEquals(
      record.researchers.head.researcherId,
      StableIds.researcherFromExternal("orcid", "0000-0001-7580-4351")
    )
  }

  test("OpenAlex authorship order is the author sequence") {
    val record = SourceNormalizers
      .openAlex(lines("openalex")(1), run)
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(
      work.authors.map(_.name),
      Vector(
        "Christopher Michels",
        "Ruslan R. Fayzrakhmanov",
        "Michael Ley",
        "Emanuel Sallinger",
        "Ralf Schenkel"
      )
    )
    assertEquals(work.authors.map(_.authorPosition), Vector(1, 2, 3, 4, 5))
    // A null ORCID must not become an identity.
    assertEquals(work.authors(1).externalIds.get(IdNamespace.Orcid.wire), None)
    assertEquals(record.researchers.size, 4)
  }

  test("an OpenAlex record without a work ID is rejected") {
    val result = SourceNormalizers.openAlex("""{"display_name":"T"}""", run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("malformed JSON is rejected, not thrown") {
    val result = SourceNormalizers.openAlex("{not json", run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedJson)
    )
    assertEquals(
      SourceNormalizers.crossref("[]", run).left.toOption.map(_._1),
      Some(RejectReason.MalformedJson)
    )
  }

  test("an ORCID person record becomes identity evidence and not a work") {
    val record = SourceNormalizers
      .orcid(lines("orcid").head, run)
      .getOrElse(fail("normalization failed"))
    assertEquals(record.work, None)
    assertEquals(record.researchers.size, 1)
    val researcher = record.researchers.head
    assertEquals(researcher.displayName, "Michael Ley")
    assertEquals(
      researcher.researcherId,
      StableIds.researcherFromExternal("orcid", "0000-0001-7580-4351")
    )
    assertEquals(
      record.identifiers,
      Vector(IdNamespace.Orcid -> "0000-0001-7580-4351")
    )
    assertEquals(researcher.provenance.source, SourceType.Orcid)
    assertEquals(researcher.provenance.snapshotId, "orcid:2026-10-13-v1")
  }

  test("an ORCID record with an invalid check digit is rejected") {
    val result = SourceNormalizers.orcid(
      """{"name":{"path":"0000-0001-7580-4352","family-name":{"value":"X"}}}""",
      run
    )
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("an ORCID record with no public name is rejected") {
    val result =
      SourceNormalizers.orcid(
        """{"name":{"path":"0000-0001-7580-4351"}}""",
        run
      )
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("a Crossref work normalizes keyed by DOI") {
    val record = SourceNormalizers
      .crossref(lines("crossref").head, run)
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(work.sourceRecordId, "10.14778/1687553.1687577")
    assertEquals(
      work.workId,
      StableIds.work("crossref", "10.14778/1687553.1687577")
    )
    assertEquals(work.title, "DBLP")
    assertEquals(work.year, Some(2009))
    assertEquals(work.doi, Some("10.14778/1687553.1687577"))
    assertEquals(work.recordType, "journal-article")
    assertEquals(work.authors.size, 1)
    assertEquals(work.authors.head.name, "Michael Ley")
    assertEquals(work.authors.head.normalizedName, "michael ley")
    assertEquals(
      work.authors.head.externalIds.get("crossref-family"),
      Some("Ley")
    )
    assertEquals(record.venues.size, 1)
    assertEquals(record.venues.head.issns, Vector("2150-8097"))
  }

  test("Crossref DOI case is folded so the two samples agree on one key") {
    val crossref = SourceNormalizers
      .crossref(lines("crossref")(1), run)
      .getOrElse(fail("normalization failed"))
      .work
      .getOrElse(fail("no work"))
    val openalex = SourceNormalizers
      .openAlex(lines("openalex")(1), run)
      .getOrElse(fail("normalization failed"))
      .work
      .getOrElse(fail("no work"))
    // The DBLP sample spells this DOI with uppercase JCDL, OpenAlex with
    // lowercase. All three must normalize to one join key.
    assertEquals(crossref.doi, Some("10.1109/jcdl.2017.7991609"))
    assertEquals(openalex.doi, crossref.doi)
  }

  test("the Crossref REST envelope and the bulk shape both normalize") {
    val bare = lines("crossref").head
    val wrapped = s"""{"message": $bare}"""
    assertEquals(
      SourceNormalizers.crossref(wrapped, run).map(_.work.map(_.workId)),
      SourceNormalizers.crossref(bare, run).map(_.work.map(_.workId))
    )
  }

  test("a Crossref record without a valid DOI is rejected") {
    val result =
      SourceNormalizers.crossref("""{"title":["T"],"DOI":"nope"}""", run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("normalization is deterministic for identical input") {
    lines("openalex").foreach { line =>
      assertEquals(
        SourceNormalizers.openAlex(line, run),
        SourceNormalizers.openAlex(line, run)
      )
    }
  }

  test("a DOI prefix shorter than four digits is not a DOI") {
    // Registered DOI prefixes are `10.` followed by four to nine digits, so
    // "10.1/a" is not a DOI and a Crossref record keyed on one has no usable
    // identifier. Pinned here because an earlier fixture in this suite used
    // that shape and was rejected before it could reach its own subject.
    assertEquals(ExactIdentifiers.doi("10.1/a"), None)
    assertEquals(
      SourceNormalizers
        .crossref("""{"DOI":"10.1/a","title":["T"]}""", run)
        .left
        .toOption
        .map(_._1),
      Some(RejectReason.MissingRequiredField)
    )
  }

  test("no Crossref abstract text is copied, only a reference") {
    val record = SourceNormalizers
      .crossref(
        """{"DOI":"10.1000/abc","title":["T"],"abstract":"<p>secret</p>"}""",
        run
      )
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(work.abstractTextRef, Some("crossref:abstract/10.1000/abc"))
    // Checking only the reference would still pass if the abstract had been
    // copied into some other field, so the whole encoded record is searched.
    val encoded = ContractWire.encode(work)
    assert(!encoded.contains("secret"), "abstract text reached the work record")
    assert(!encoded.contains("<p>"), "abstract markup reached the work record")
  }

  test("an OpenAlex inverted abstract yields a reference and no text") {
    // `abstract_inverted_index` is a JSON object, not a string. Reading it with
    // a string accessor silently produced no reference at all; this pins the
    // presence test so that regression cannot return unnoticed.
    val line =
      """{"id":"https://openalex.org/W1","display_name":"T",
        |"abstract_inverted_index":{"secret":[0]},
        |"authorships":[{"author":{"display_name":"A B",
        |"id":"https://openalex.org/A1"}}]}""".stripMargin.replace("\n", "")
    val record = SourceNormalizers
      .openAlex(line, run)
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(work.abstractTextRef, Some("openalex:abstract/W1"))
    val encoded = ContractWire.encode(work)
    assert(!encoded.contains("secret"), "abstract text reached the work record")
  }
