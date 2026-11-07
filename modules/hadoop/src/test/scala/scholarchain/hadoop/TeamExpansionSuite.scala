package scholarchain.hadoop

import munit.FunSuite
import scholarchain.core.{
  AuthorMention,
  ContractSchema,
  Provenance,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.hadoop.pipeline.{
  PipelineCounter,
  RecordingSink,
  RejectReason,
  RunContext,
  TaskSink,
  TeamExpansion
}

/** Pair-generation tests.
  *
  * These cover the gate item "pair generation passes hand-checkable papers with
  * 1, 2, 3, and larger author lists", and the oversize-team bound that keeps
  * the quadratic step from taking the pipeline down.
  */
class TeamExpansionSuite extends FunSuite:
  private val run = TestRun.context()

  /** Builds a work with `authorCount` synthetic authors. */
  private def work(
      key: String,
      authorCount: Int,
      year: Option[Int] = Some(2017),
      context: RunContext = run
  ): WorkRecord =
    val workId = StableIds.work("dblp", key)
    val provenance = context.provenance(
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
      year = year,
      venueId = Some("venue:v1:dblp:jcdl"),
      doi = None,
      arxivId = None,
      authors = (1 to authorCount).toVector.map { position =>
        AuthorMention(
          schemaVersion = ContractSchema.currentVersion,
          mentionId = StableIds.mention(workId, position),
          workId = workId,
          authorPosition = position,
          name = s"Author $position",
          normalizedName = s"author $position",
          externalIds = Map.empty,
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

  test("the pair count formula is n * (n - 1) / 2") {
    assertEquals(TeamExpansion.expectedPairCount(0), 0L)
    assertEquals(TeamExpansion.expectedPairCount(1), 0L)
    assertEquals(TeamExpansion.expectedPairCount(2), 1L)
    assertEquals(TeamExpansion.expectedPairCount(3), 3L)
    assertEquals(TeamExpansion.expectedPairCount(5), 10L)
    assertEquals(TeamExpansion.expectedPairCount(10), 45L)
    // A hyperauthorship paper: the number the bound exists to contain.
    assertEquals(TeamExpansion.expectedPairCount(3000), 4498500L)
  }

  test("the formula does not overflow at large team sizes") {
    // Int arithmetic would overflow here; the count is computed in Long.
    assertEquals(TeamExpansion.expectedPairCount(100000), 4999950000L)
    assert(TeamExpansion.expectedPairCount(Int.MaxValue) > 0L)
  }

  test("a one-author paper produces a team and no pairs") {
    val expanded = TeamExpansion.expand(work("a/1", 1), run)
    assertEquals(expanded.team.orderedMentionIds.size, 1)
    assertEquals(expanded.authorPapers.size, 1)
    assertEquals(expanded.pairs.size, 0)
    assertEquals(expanded.expectedPairCount, 0L)
  }

  test("a two-author paper produces exactly one pair") {
    val subject = work("a/2", 2)
    val expanded = TeamExpansion.expand(subject, run)
    assertEquals(expanded.pairs.size, 1)
    val pair = expanded.pairs.head
    assertEquals(pair.workId, subject.workId)
    assertEquals(pair.year, Some(2017))
    assertEquals(
      Set(pair.mentionA, pair.mentionB),
      subject.authors.map(_.mentionId).toSet
    )
    assertEquals(Set(pair.authorPositionA, pair.authorPositionB), Set(1, 2))
  }

  test("a three-author paper produces exactly three distinct pairs") {
    val subject = work("a/3", 3)
    val expanded = TeamExpansion.expand(subject, run)
    assertEquals(expanded.pairs.size, 3)
    assertEquals(
      expanded.pairs.map(pair => (pair.mentionA, pair.mentionB)).distinct.size,
      3
    )
    assertEquals(
      expanded.pairs
        .map(pair => Set(pair.authorPositionA, pair.authorPositionB))
        .toSet,
      Set(Set(1, 2), Set(1, 3), Set(2, 3))
    )
  }

  test("a five-author paper produces exactly ten pairs covering every couple") {
    val subject = work("a/5", 5)
    val expanded = TeamExpansion.expand(subject, run)
    assertEquals(expanded.pairs.size, 10)
    assertEquals(
      expanded.pairs
        .map(pair => Set(pair.authorPositionA, pair.authorPositionB))
        .toSet,
      (1 to 5).flatMap(a => (a + 1 to 5).map(b => Set(a, b))).toSet
    )
  }

  test("pair endpoints are in ascending mention-ID order, not position order") {
    // The contract requires ascending ID order, and mention IDs are digests,
    // so the ordering is unrelated to author position. The positions must
    // still travel with the endpoint they belong to.
    val subject = work("a/6", 6)
    val expanded = TeamExpansion.expand(subject, run)
    val byMention =
      subject.authors.iterator.map(m => m.mentionId -> m.authorPosition).toMap
    expanded.pairs.foreach { pair =>
      assert(pair.mentionA < pair.mentionB, "endpoints out of order")
      assertEquals(byMention(pair.mentionA), pair.authorPositionA)
      assertEquals(byMention(pair.mentionB), pair.authorPositionB)
    }
  }

  test("every pair preserves work ID, year and source provenance") {
    val subject = work("a/4", 4)
    val expanded = TeamExpansion.expand(subject, run)
    expanded.pairs.foreach { pair =>
      assertEquals(pair.workId, subject.workId)
      assertEquals(pair.year, subject.year)
      assertEquals(pair.provenance.source, SourceType.Dblp)
      assertEquals(pair.provenance.sourceRecordId, "a/4")
      assertEquals(pair.provenance.runId, TestRun.runId)
      assertEquals(pair.provenance.snapshotId, "dblp:2026-11-01")
    }
  }

  test("a work with no year still yields pairs with an open year") {
    val expanded = TeamExpansion.expand(work("a/7", 3, year = None), run)
    assertEquals(expanded.pairs.size, 3)
    assert(expanded.pairs.forall(_.year.isEmpty))
  }

  test("expansion is deterministic") {
    val subject = work("a/8", 7)
    assertEquals(
      TeamExpansion.expand(subject, run).pairs,
      TeamExpansion.expand(subject, run).pairs
    )
  }

  test("an oversize team is counted and reported but still expanded") {
    val bounded = TestRun.context(maxAuthorTeamSize = 4, maxPairsPerWork = 1000)
    val sink = new RecordingSink
    TeamExpansion.emit(work("a/9", 6, context = bounded), sink, bounded)
    assertEquals(sink.counterValue(PipelineCounter.OversizeAuthorTeams), 1L)
    // `emit` is the map side, so expansion is counted as `ExpandedPairs`.
    assertEquals(sink.counterValue(PipelineCounter.ExpandedPairs), 15L)
    assertEquals(sink.counterValue(PipelineCounter.EmittedPairs), 0L)
    assertEquals(sink.rejects.size, 1)
    assertEquals(sink.rejects.head.reason, RejectReason.OversizeAuthorTeam)
    assert(sink.rejects.head.detail.contains("6 authors"))
  }

  test("pair expansion is suppressed above the per-work pair bound") {
    val bounded = TestRun.context(maxAuthorTeamSize = 4, maxPairsPerWork = 5)
    val sink = new RecordingSink
    TeamExpansion.emit(work("a/10", 6, context = bounded), sink, bounded)
    // The team and author-paper records survive; only the fan-out is withheld.
    assertEquals(sink.recordsLabelled(TaskSink.teams).size, 1)
    assertEquals(sink.recordsLabelled(TaskSink.authorPapers).size, 6)
    assertEquals(sink.recordsLabelled(TaskSink.pairs).size, 0)
    assertEquals(sink.counterValue(PipelineCounter.ExpandedPairs), 0L)
    assertEquals(sink.counterValue(PipelineCounter.EmittedPairs), 0L)
    assertEquals(sink.counterValue(PipelineCounter.SuppressedPairs), 15L)
    assert(
      sink.rejects.exists(reject =>
        reject.detail.contains("Pair expansion suppressed")
      )
    )
  }

  test("a single-author work is counted as such") {
    val sink = new RecordingSink
    TeamExpansion.emit(work("a/11", 1), sink, run)
    assertEquals(sink.counterValue(PipelineCounter.SingleAuthorWorks), 1L)
    assertEquals(sink.counterValue(PipelineCounter.ExpandedPairs), 0L)
    assertEquals(sink.counterValue(PipelineCounter.EmittedPairs), 0L)
  }

  test("duplicate pair records for one mention pair consolidate") {
    val subject = work("a/12", 2)
    val pair = TeamExpansion.expand(subject, run).pairs.head
    val (retained, duplicates) =
      TeamExpansion.consolidate("key", Vector(pair, pair, pair))
    assertEquals(retained.size, 1)
    assertEquals(duplicates, 2L)
    // Consolidation is independent of arrival order.
    val other = TeamExpansion.expand(work("a/13", 2), run).pairs.head
    val (bothOrders, _) = TeamExpansion.consolidate("key", Vector(pair, other))
    val (reversed, _) = TeamExpansion.consolidate("key", Vector(other, pair))
    assertEquals(bothOrders, reversed)
    assertEquals(bothOrders.size, 2)
  }

  test("author-paper records leave researcher resolution open") {
    // Resolving a mention to a canonical researcher is entity resolution's
    // decision, so this job must not guess it.
    val expanded = TeamExpansion.expand(work("a/14", 3), run)
    assert(expanded.authorPapers.forall(_.researcherId.isEmpty))
    assertEquals(expanded.authorPapers.map(_.authorPosition), Vector(1, 2, 3))
  }
