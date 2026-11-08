package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.hadoop.pipeline.{
  LocalPipelineRunner,
  PipelineObservations,
  SourceNormalizers,
  TextNormalizer
}

/** Phase 5 review finding 5. The dataset manifest's privacy note excludes
  * Crossref free-text affiliations, so a Crossref `author[].affiliation[].name`
  * must reach no normalized record, blocking key, candidate or exported record.
  *
  * The checked-in Crossref sample has no affiliation field, so the row used
  * here is the sample's five-author JCDL row with a marker affiliation added to
  * two authors. The same marker is the control for OpenAlex-sourced
  * affiliations, which stay supported.
  */
class CrossrefAffiliationPrivacySuite extends FunSuite:
  private val run = TestRun.context()
  private val marker = "Zyxwv Marker University"

  private def sampleLines(dataset: String): Vector[String] =
    Files
      .readAllLines(TestRun.samples.resolve(s"$dataset.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)

  private def withAffiliation(line: String): String =
    val row = ujson.read(line)
    Vector(0, 2).foreach { index =>
      row("author")(index)("affiliation") =
        ujson.Arr(ujson.Obj("name" -> marker))
    }
    ujson.write(row)

  private lazy val crossrefLines =
    val lines = sampleLines("crossref")
    Vector(lines.head, withAffiliation(lines(1)))

  private def everything(observations: PipelineObservations): Vector[String] =
    observations.stages.flatMap { stage =>
      stage.emitted.values.flatten.flatMap { case (key, value) =>
        Vector(key, value)
      } ++ stage.rejects.flatMap(reject =>
        Vector(reject.detail, reject.payloadExcerpt, reject.sourceRecordId)
      )
    }

  private def mentions(value: String): Boolean =
    val text = value.toLowerCase
    text.contains("zyxwv") ||
    text.contains(TextNormalizer.fold(marker).toLowerCase)

  test("the fixture row really carries the affiliation") {
    assert(crossrefLines(1).contains(marker))
  }

  test("a Crossref author affiliation name gives the mention no affiliation") {
    val record = SourceNormalizers
      .crossref(crossrefLines(1), run)
      .getOrElse(fail("normalization failed"))
    val work = record.work.getOrElse(fail("no work"))
    assertEquals(work.authors.size, 5)
    work.authors.foreach(author =>
      assertEquals(author.affiliations, Vector.empty[String])
    )
    assert(!mentions(scholarchain.core.ContractWire.encode(work)))
  }

  test("the affiliation reaches no stage output of the whole pipeline") {
    val dblp = Files.readString(TestRun.samples.resolve("dblp.xml"), UTF_8)
    val external = Map(
      "openalex" -> sampleLines("openalex"),
      "orcid" -> sampleLines("orcid"),
      "crossref" -> crossrefLines
    )
    val observations = LocalPipelineRunner.runAll(dblp, external, run)
    val leaked = everything(observations).filter(mentions)
    assertEquals(leaked, Vector.empty[String])
    // Control: the Crossref row did pass through every stage, and the
    // OpenAlex affiliation, which is allowed, is visible in the same outputs.
    assertEquals(
      observations
        .stage("source-normalization/crossref")
        .counters
        .toMap
        .get("AcceptedRecords"),
      Some(2L)
    )
    assert(
      everything(observations).exists(_.toLowerCase.contains("trier")),
      "the OpenAlex affiliation control is missing"
    )
  }
