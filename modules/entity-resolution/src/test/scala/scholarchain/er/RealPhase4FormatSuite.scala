package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.{ContractSchema, SourceType, WorkRecord}
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.{EntityKind, RecordIndex}
import scholarchain.er.sampling.*
import upickle.default.write as writeJson

/** The first match-candidates line and the second graph-export works line of
  * the real Phase 4 run `run-v1-c9952ced-...` on the subset, copied verbatim.
  * They pin that the exporter reads the real wire format.
  */
class RealPhase4FormatSuite extends munit.FunSuite:
  private val realCandidate =
    """{"schemaVersion":1,"recordType":"scholarchain.hadoop.MatchCandidate/v1","kind":"researcher","leftId":"mention:v1:0d7215322ba11bf35fa17323b37a964648ac5ed07840a305c87221ccd62db41f","rightId":"mention:v1:4ef65696f929b8b46d34070e1d7ed0f6add425a5691c3f3c0f2c467e55470e02","leftSource":"openalex","rightSource":"openalex","blockKey":"researcher|name-affiliation|han|d|shanghai maritime university","blockStrategy":"name-affiliation","exactIdentifierAgreements":["openalex-author","orcid"],"exactIdentifierConflicts":["openalex-author-position"],"features":{"title.edit":0.2362204724409449,"title.exact":0,"coauthor.sharedCount":2,"name.jaccard":1,"affiliation.jaccard":1,"name.exact":1,"source.distinct":0,"sameWork":0,"name.familyExact":1,"coauthor.jaccard":0.3333333333333333,"name.initialsExact":1,"title.jaccard":0.07407407407407407,"year.known":1,"name.edit":1,"year.exact":0,"venue.exact":0,"year.proximity":0.8},"provenance":{"schemaVersion":1,"source":"derived","snapshotId":"openalex:2026-10-14","sourceRecordId":"mention:v1:0d7215322ba11bf35fa17323b37a964648ac5ed07840a305c87221ccd62db41f|mention:v1:4ef65696f929b8b46d34070e1d7ed0f6add425a5691c3f3c0f2c467e55470e02","sourceUri":"scholarchain:block/researcher|name-affiliation|han|d|shanghai maritime university","transformVersion":"hadoop-pipeline:v1","runId":"run:v1:c9952ced-c040-4db3-a350-03ddc92c7ec5"}}"""

  private val realWork =
    """{"schemaVersion":1,"workId":"work:v1:crossref:MTAuMTA4MC8wMzA4MTA3OS4yMDIzLjIyOTYwOTk","source":"crossref","sourceRecordId":"10.1080/03081079.2023.2296099","title":"On the choice problem in two-dimensional Ellsberg experiment with randomness and uncertainty","normalizedTitle":"on the choice problem in two dimensional ellsberg experiment with randomness and uncertainty","year":2023,"venueId":"venue:v1:crossref:international-journal-of-general-systems","doi":"10.1080/03081079.2023.2296099","arxivId":null,"authors":[{"schemaVersion":1,"mentionId":"mention:v1:b7d8c5ace1e1a0446a8af3ee03cff76b144de006b555cd25d047bd9b9e5342a5","workId":"work:v1:crossref:MTAuMTA4MC8wMzA4MTA3OS4yMDIzLjIyOTYwOTk","authorPosition":1,"name":"Yang Liu","normalizedName":"yang liu","externalIds":{"orcid":"0000-0003-0780-8267","crossref-sequence":"first","crossref-family":"Liu"},"affiliations":[],"provenance":{"schemaVersion":1,"source":"crossref","snapshotId":"crossref:2026-03","sourceRecordId":"10.1080/03081079.2023.2296099","sourceUri":"https://doi.org/10.1080/03081079.2023.2296099","transformVersion":"hadoop-pipeline:v1","runId":"run:v1:c9952ced-c040-4db3-a350-03ddc92c7ec5"}},{"schemaVersion":1,"mentionId":"mention:v1:1aff3ddf16690b820cf0b3ca475e04550439c1ed74e4a6f2c99e62f6801f2f96","workId":"work:v1:crossref:MTAuMTA4MC8wMzA4MTA3OS4yMDIzLjIyOTYwOTk","authorPosition":2,"name":"Zhongfeng Qin","normalizedName":"zhongfeng qin","externalIds":{"crossref-sequence":"additional","crossref-family":"Qin"},"affiliations":[],"provenance":{"schemaVersion":1,"source":"crossref","snapshotId":"crossref:2026-03","sourceRecordId":"10.1080/03081079.2023.2296099","sourceUri":"https://doi.org/10.1080/03081079.2023.2296099","transformVersion":"hadoop-pipeline:v1","runId":"run:v1:c9952ced-c040-4db3-a350-03ddc92c7ec5"}}],"abstractTextRef":null,"electronicEditionUris":["https://doi.org/10.1080/03081079.2023.2296099"],"sourceUris":["https://doi.org/10.1080/03081079.2023.2296099"],"recordType":"journal-article","provenance":{"schemaVersion":1,"source":"crossref","snapshotId":"crossref:2026-03","sourceRecordId":"10.1080/03081079.2023.2296099","sourceUri":"https://doi.org/10.1080/03081079.2023.2296099","transformVersion":"hadoop-pipeline:v1","runId":"run:v1:c9952ced-c040-4db3-a350-03ddc92c7ec5"}}"""

  private def write(path: Path, text: String): Path =
    Files.createDirectories(path.getParent)
    Files.writeString(path, text, UTF_8)

  test("the real candidate line is accepted as a core MatchCandidate") {
    val file = write(
      Files.createTempDirectory("er-real").resolve("part-r-00000"),
      realCandidate + "\n"
    )
    val candidate = PairsExport.readCandidates(file).head
    assertEquals(candidate.kind, BlockKind.Researcher)
    assertEquals(candidate.leftSource, SourceType.OpenAlex)
    assertEquals(candidate.blockStrategy, BlockStrategy.NameAffiliation)
    assertEquals(
      candidate.exactIdentifierAgreements,
      Vector("openalex-author", "orcid")
    )
    assertEquals(
      candidate.provenance.runId,
      "run:v1:c9952ced-c040-4db3-a350-03ddc92c7ec5"
    )
    assertEquals(candidate.provenance.snapshotId, "openalex:2026-10-14")
    assert(candidate.leftId < candidate.rightId)
  }

  test("the real work line is accepted and indexes the work and its mentions") {
    val file = write(
      Files.createTempDirectory("er-real").resolve("part-r-00000"),
      realWork + "\n"
    )
    val works = PairsExport.readWorks(file)
    assertEquals(works.size, 1)
    val index = RecordIndex.fromCore(works)
    assertEquals(index.size, 3)
    val work = works.head
    assertEquals(index.get(work.workId).map(_.kind), Some(EntityKind.Work))
    work.authors.foreach { author =>
      val side = index.get(author.mentionId).get
      assertEquals(side.kind, EntityKind.Researcher)
      assertEquals(side.workId, work.workId)
      assertEquals(side.snapshotId, "crossref:2026-03")
      assertEquals(side.sourceRecordId, "10.1080/03081079.2023.2296099")
    }
    assertEquals(
      index.get(work.authors.head.mentionId).map(_.externalIds.get("orcid")),
      Some(Some("0000-0003-0780-8267"))
    )
  }

  private def candidateBetween(
      left: String,
      right: String,
      blockKey: String
  ): MatchCandidate =
    MatchCandidate(
      ContractSchema.currentVersion,
      MatchCandidate.recordTypeName,
      BlockKind.Researcher,
      left,
      right,
      SourceType.Dblp,
      SourceType.Crossref,
      blockKey,
      BlockStrategy.NameInitials,
      Vector.empty,
      Vector.empty,
      Map.empty,
      provenance(SourceType.Derived, s"$left|$right")
    )

  private val dblpWork: WorkRecord = coreWork(
    "dblp",
    "conf/x/Wang20",
    "Graph methods for sparse data",
    Vector("Wei Wang"),
    None,
    SourceType.Dblp
  )
  private val crossrefWork: WorkRecord = coreWork(
    "crossref",
    "10.1000/xyz",
    "Sparse data and the study of graphs",
    Vector("Wei Wang"),
    Some("10.1000/xyz"),
    SourceType.Crossref
  )
  private val leftMention = dblpWork.authors.head.mentionId
  private val rightMention = crossrefWork.authors.head.mentionId
  private val (lo, hi) =
    if leftMention < rightMention then (leftMention, rightMention)
    else (rightMention, leftMention)

  private val plan = SamplingPlan("seed-1", 3, includeAgreeingContext = false)

  /** Candidates split over two part files and a nested directory, as the real
    * `match-candidates/v1` and its parents are laid out; works over two parts.
    */
  private def layout(
      records: Vector[WorkRecord],
      candidates: Vector[MatchCandidate]
  ): (Path, Path) =
    val root = Files.createTempDirectory("er-layout")
    val candidateDir = root.resolve("match-candidates")
    candidates.zipWithIndex.groupBy(_._2 % 2).foreach { (part, entries) =>
      write(
        candidateDir.resolve(f"v1/part-r-$part%05d"),
        entries.map((candidate, _) => writeJson(candidate) + "\n").mkString
      )
    }
    write(candidateDir.resolve("v1/.part-r-00000.crc"), "not json")
    write(candidateDir.resolve("_SUCCESS"), "")
    val recordDir = root.resolve("works/v1")
    records.zipWithIndex.foreach { (work, i) =>
      write(recordDir.resolve(f"part-r-$i%05d"), writeJson(work) + "\n")
    }
    write(recordDir.resolve(".part-r-00000.crc"), "not json")
    (candidateDir, recordDir)

  test("one records directory resolves candidates across sources") {
    val (candidates, records) = layout(
      Vector(dblpWork, crossrefWork),
      Vector(candidateBetween(lo, hi, "researcher|name-initials|wang|w|s0"))
    )
    val result = PairsExport.exportFiles(candidates, records, plan)
    assertEquals(result.manifest.excluded.get("missing-record"), None)
    assertEquals(result.manifest.recordsIndexed, 4)
    assertEquals(result.manifest.distinctPairs, 1)
    assertEquals(result.items.size, 1)
    assertEquals(result.keys.map(_.stratum), Vector("hard-common-name"))
  }

  test("records from only one source leave the other source's pairs missing") {
    val (candidates, records) = layout(
      Vector(dblpWork),
      Vector(candidateBetween(lo, hi, "researcher|name-initials|wang|w|s0"))
    )
    val result = PairsExport.exportFiles(candidates, records, plan)
    assertEquals(result.manifest.excluded("missing-record"), 1)
    assertEquals(result.items, Vector.empty)
  }

  test("a pair in several blocks counts once and keeps the smallest block") {
    val (candidates, records) = layout(
      Vector(dblpWork, crossrefWork),
      Vector(
        candidateBetween(lo, hi, "researcher|name-year|wang|w|2020"),
        candidateBetween(lo, hi, "researcher|name-initials|wang|w|s0"),
        candidateBetween(lo, hi, "researcher|name-venue|wang|w|venue-a")
      )
    )
    val result = PairsExport.exportFiles(candidates, records, plan)
    assertEquals(result.manifest.candidatesRead, 3)
    assertEquals(result.manifest.distinctPairs, 1)
    assertEquals(
      result.keys.map(_.blockKey),
      Vector("researcher|name-initials|wang|w|s0")
    )
  }

  test("streaming a candidate directory equals running on the loaded vector") {
    val candidates = Vector(
      candidateBetween(lo, hi, "researcher|name-year|wang|w|2020"),
      candidateBetween(lo, hi, "researcher|name-initials|wang|w|s0")
    )
    val (candidateDir, recordDir) =
      layout(Vector(dblpWork, crossrefWork), candidates)
    val streamed = PairsExport.exportFiles(candidateDir, recordDir, plan)
    val index = RecordIndex.fromCore(Vector(dblpWork, crossrefWork))
    val loaded = PairsExport.run(
      PairsExport.readCandidates(candidateDir),
      index,
      plan,
      ExportInputs(
        candidateDir.toString,
        streamed.manifest.candidatesSha256,
        recordDir.toString,
        streamed.manifest.recordsSha256
      )
    )
    assertEquals(streamed, loaded)
  }

  test("a directory of only markers fails loudly instead of exporting zero") {
    val (_, records) = layout(Vector(dblpWork), Vector.empty)
    val empty = Files.createTempDirectory("er-empty")
    write(empty.resolve("_SUCCESS"), "")
    val error = intercept[IllegalArgumentException](
      PairsExport.exportFiles(empty, records, plan)
    )
    assert(error.getMessage.contains("No data files"), error.getMessage)
  }

  test("a malformed candidate line names its number") {
    val root = Files.createTempDirectory("er-bad")
    val file = write(root.resolve("part-r-00000"), "{\"kind\":\"nope\"}\n")
    val error =
      intercept[IllegalArgumentException](PairsExport.readCandidates(file))
    assert(error.getMessage.startsWith("Invalid candidate on line 1"))
  }
