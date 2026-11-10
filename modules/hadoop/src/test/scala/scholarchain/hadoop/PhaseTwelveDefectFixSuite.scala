package scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.core.{
  AuthorMention,
  CoauthorPairCandidate,
  ContractSchema,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.hadoop.pipeline.{
  LocalPipelineRunner,
  PipelineCounter,
  RecordingSink,
  RejectReason,
  TaskSink,
  TeamExpansion,
  XmlRecordStream
}

/** Module tests for the four defects carried out of Phase 11 (P-4).
  *
  * They cover the edges the adversarial twins do not: split boundaries inside a
  * comment, regions inside a record, and every arrival order of three or more
  * candidates.
  */
class PhaseTwelveDefectFixSuite extends FunSuite:
  private val run = TestRun.context()

  // ---- 1. comment and CDATA regions -------------------------------------

  private val real = """<article key="real/1"><title>Real.</title></article>"""

  private val commented =
    s"""<dblp><!-- <article key="ghost/1"><title>Ghost.</title></article> -->$real</dblp>"""

  private val cdata =
    s"""<dblp><![CDATA[ <article key="ghost/2"><title>G.</title></article> ]]>$real</dblp>"""

  private val tight =
    s"""<dblp><!--<article key="ghost/3"/>-->$real</dblp>"""

  private def readSplit(
      xml: String,
      scanStart: Long,
      start: Long,
      end: Long
  ): Vector[String] =
    val bytes = xml.getBytes(UTF_8)
    val input = new ByteArrayInputStream(bytes)
    input.skip(scanStart)
    new XmlRecordStream(
      input,
      XmlRecordStream.publicationTags,
      scanStart,
      start,
      end
    ).iterator.map(_._2).toVector

  test("comment, tight comment and CDATA regions yield only the live record") {
    Vector(commented, cdata, tight).foreach { xml =>
      assertEquals(XmlRecordStream.records(xml), Vector(real), xml)
    }
  }

  test("a lone region with a following literal-looking dash run is skipped") {
    val xml = s"""<dblp><!-- a - b -- c ---><article key="x/1"/></dblp>"""
    assertEquals(
      XmlRecordStream.records(xml),
      Vector("""<article key="x/1"/>""")
    )
  }

  test("an unterminated comment or CDATA section yields nothing") {
    assertEquals(
      XmlRecordStream.records("""<dblp><!-- <article key="g/1"/>"""),
      Vector.empty
    )
    assertEquals(
      XmlRecordStream.records("""<dblp><![CDATA[ <article key="g/1"/>"""),
      Vector.empty
    )
  }

  test("a bang that opens neither region does not hide the next record") {
    val xml = s"""<dblp><!DOCTYPE x>$real</dblp>"""
    assertEquals(XmlRecordStream.records(xml), Vector(real))
    val dash = s"""<dblp><!-x$real</dblp>"""
    assertEquals(XmlRecordStream.records(dash), Vector(real))
  }

  test("every split point yields the live record exactly once with look-back") {
    Vector(commented, cdata, tight).foreach { xml =>
      val length = xml.getBytes(UTF_8).length.toLong
      (0L to length).foreach { boundary =>
        val first = readSplit(xml, 0L, 0L, boundary)
        // The second reader starts at the boundary but scans from the head, as
        // the input format does within its look-back.
        val second = readSplit(xml, 0L, boundary, length)
        assertEquals(
          first ++ second,
          Vector(real),
          s"boundary $boundary in $xml"
        )
      }
    }
  }

  test("a bounded look-back that covers the opener keeps the region skipped") {
    val xml = commented
    val length = xml.getBytes(UTF_8).length.toLong
    val inside = xml.indexOf("<article").toLong
    val opener = xml.indexOf("<!--").toLong
    // Scanning from the opener finds the same answer as scanning from the head.
    assertEquals(readSplit(xml, opener, inside, length), Vector(real))
    // Scanning from inside the region cannot know it is inside one: the
    // documented limit of the look-back.
    assertEquals(readSplit(xml, inside, inside, length).size, 2)
  }

  test("a record opening in the look-back zone is left to the earlier reader") {
    val xml = s"""<dblp>$real<article key="b/2"/></dblp>"""
    val bytes = xml.getBytes(UTF_8).length.toLong
    val secondStart = xml.indexOf("""<article key="b/2"/>""").toLong
    val tail = readSplit(xml, 0L, secondStart, bytes)
    assertEquals(tail, Vector("""<article key="b/2"/>"""))
  }

  test("a comment inside a record neither ends nor corrupts it") {
    val record =
      """<article key="c/1"><!-- it's </article> --><title>T.</title></article>"""
    val xml = s"""<dblp>$record<article key="c/2"/></dblp>"""
    assertEquals(
      XmlRecordStream.records(xml),
      Vector(record, """<article key="c/2"/>""")
    )
  }

  test("a CDATA section inside a record may hold tags and quotes") {
    val record =
      """<article key="c/3"><note><![CDATA[ </article> " ' > ]]></note></article>"""
    assertEquals(
      XmlRecordStream.records(s"<dblp>$record</dblp>"),
      Vector(record)
    )
  }

  // ---- 2. zero-author works ---------------------------------------------

  private def work(
      key: String,
      names: Vector[String],
      orcid: Vector[Option[String]] = Vector.empty
  ): WorkRecord =
    val workId = StableIds.work("dblp", key)
    val provenance = run.provenance(
      SourceType.Dblp,
      key,
      s"https://dblp.org/rec/$key",
      "dblp"
    )
    WorkRecord(
      schemaVersion = ContractSchema.currentVersion,
      workId = workId,
      source = SourceType.Dblp,
      sourceRecordId = key,
      title = s"Title $key",
      normalizedTitle = s"title ${key.replace('/', ' ')}",
      year = Some(2017),
      venueId = Some("venue:v1:dblp:jcdl"),
      doi = None,
      arxivId = None,
      authors = names.zipWithIndex.map { case (name, index) =>
        AuthorMention(
          schemaVersion = ContractSchema.currentVersion,
          mentionId = StableIds.mention(workId, index + 1),
          workId = workId,
          authorPosition = index + 1,
          name = name,
          normalizedName = name.toLowerCase,
          externalIds = orcid
            .lift(index)
            .flatten
            .map(value => Map("orcid" -> value))
            .getOrElse(Map.empty),
          affiliations = Vector.empty,
          provenance = provenance
        )
      },
      abstractTextRef = None,
      electronicEditionUris = Vector.empty,
      sourceUris = Vector.empty,
      recordType = "inproceedings",
      provenance = provenance
    )

  test("author counts map to exactly one of the two quality counters") {
    def counters(authorCount: Int): (Long, Long) =
      val sink = new RecordingSink
      TeamExpansion.emit(
        work("n/1", Vector.tabulate(authorCount)(i => s"Author $i")),
        sink,
        run
      )
      (
        sink.counterValue(PipelineCounter.ZeroAuthorWorks),
        sink.counterValue(PipelineCounter.SingleAuthorWorks)
      )
    assertEquals(counters(0), (1L, 0L))
    assertEquals(counters(1), (0L, 1L))
    assertEquals(counters(2), (0L, 0L))
    assertEquals(counters(3), (0L, 0L))
  }

  test("a zero-author work still emits its empty team and no pairs") {
    val sink = new RecordingSink
    TeamExpansion.emit(work("n/0", Vector.empty), sink, run)
    assertEquals(sink.recordsLabelled(TaskSink.teams).size, 1)
    assertEquals(sink.recordsLabelled(TaskSink.pairs), Vector.empty)
    assertEquals(sink.counterValue(PipelineCounter.ExpandedPairs), 0L)
  }

  // ---- 3. shared ORCID --------------------------------------------------

  private val orcid = "0000-0001-7580-4351"

  private def join(works: WorkRecord*) =
    LocalPipelineRunner.joinIdentifiers(works.toVector, run)

  private def conflicts(outcome: scholarchain.hadoop.pipeline.StageOutcome) =
    outcome.counters.toMap.getOrElse("ConflictRecords", 0L)

  test("two authors of one work with one ORCID are reported, not merged") {
    val outcome = join(
      work("o/1", Vector("A One", "B Two"), Vector(Some(orcid), Some(orcid)))
    )
    assertEquals(conflicts(outcome), 1L)
    val reject =
      outcome.rejects.find(_.reason == RejectReason.IdentifierConflict)
    val found = reject.getOrElse(fail("no IdentifierConflict reject"))
    assertEquals(found.stage, "identifier-join")
    assertEquals(found.provenance.source, SourceType.Derived)
    assertEquals(found.provenance.runId, run.runId)
    val workId = StableIds.work("dblp", "o/1")
    assert(found.payloadExcerpt.contains(StableIds.mention(workId, 1)))
    assert(found.payloadExcerpt.contains(StableIds.mention(workId, 2)))
    // Both mentions stay on the join key rather than collapsing to the first.
    val keyed = outcome
      .records(TaskSink.identifierKeys)
      .filter(_.contains("\"orcid\""))
    assertEquals(keyed.size, 1)
    assert(keyed.head.contains(StableIds.mention(workId, 1)))
    assert(keyed.head.contains(StableIds.mention(workId, 2)))
  }

  test("three authors of one work with one ORCID are two disagreements") {
    val outcome = join(
      work(
        "o/3",
        Vector("A", "B", "C"),
        Vector(Some(orcid), Some(orcid), Some(orcid))
      )
    )
    assertEquals(conflicts(outcome), 2L)
  }

  test("one ORCID on mentions of different works is not a conflict") {
    val outcome = join(
      work("o/4", Vector("A One", "B Two"), Vector(Some(orcid), None)),
      work("o/5", Vector("A One", "C Three"), Vector(Some(orcid), None))
    )
    assertEquals(conflicts(outcome), 0L)
    assertEquals(
      outcome.rejects.count(_.reason == RejectReason.IdentifierConflict),
      0
    )
  }

  test("distinct ORCIDs on one work are not a conflict") {
    val outcome = join(
      work(
        "o/6",
        Vector("A One", "B Two"),
        Vector(Some(orcid), Some("0000-0002-1825-0097"))
      )
    )
    assertEquals(conflicts(outcome), 0L)
  }

  // ---- 4. pair consolidation order --------------------------------------

  private val base: CoauthorPairCandidate =
    TeamExpansion.expand(work("p/1", Vector("A One", "B Two")), run).pairs.head

  private def withRun(
      runIdentifier: String,
      sourceRecordId: String = "p/1",
      uriSuffix: String = ""
  ): CoauthorPairCandidate =
    val provenance = TestRun
      .context(runIdentifier)
      .provenance(
        SourceType.Dblp,
        sourceRecordId,
        s"https://dblp.org/rec/$sourceRecordId$uriSuffix",
        "dblp"
      )
    base.copy(provenance = provenance)

  test("consolidation is identical for every permutation of three records") {
    val candidates = Vector(
      base,
      withRun(TestRun.secondRunId),
      withRun(TestRun.secondRunId, sourceRecordId = "p/0")
    )
    val expected = TeamExpansion.consolidate("k", candidates)
    candidates.permutations.foreach { order =>
      assertEquals(TeamExpansion.consolidate("k", order), expected)
    }
    assertEquals(expected._2, 2L)
    assertEquals(expected._1.size, 1)
    assertEquals(expected._1.head.provenance.runId, TestRun.runId)
  }

  test("consolidation orders several works and replays by any arrival order") {
    val otherWork = TeamExpansion
      .expand(work("p/2", Vector("A One", "B Two")), run)
      .pairs
      .head
    val candidates = Vector(
      base,
      withRun(TestRun.secondRunId),
      otherWork,
      otherWork.copy(provenance =
        TestRun
          .context(TestRun.secondRunId)
          .provenance(
            SourceType.Dblp,
            "p/2",
            "https://dblp.org/rec/p/2",
            "dblp"
          )
      )
    )
    val expected = TeamExpansion.consolidate("k", candidates)
    candidates.permutations.foreach { order =>
      assertEquals(TeamExpansion.consolidate("k", order), expected)
    }
    assertEquals(expected._2, 2L)
    assertEquals(expected._1.map(_.workId), expected._1.map(_.workId).sorted)
  }

  test("candidates tying on run and record ID are still totally ordered") {
    val candidates = Vector(
      withRun(TestRun.runId, uriSuffix = "?b"),
      withRun(TestRun.runId, uriSuffix = "?a")
    )
    val forward = TeamExpansion.consolidate("k", candidates)
    val reversed = TeamExpansion.consolidate("k", candidates.reverse)
    assertEquals(forward, reversed)
    assert(forward._1.head.provenance.sourceUri.endsWith("?a"))
    assertEquals(forward._2, 1L)
  }
