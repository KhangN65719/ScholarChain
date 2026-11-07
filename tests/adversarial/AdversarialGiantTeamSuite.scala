package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.hadoop.pipeline.Blocking
import scholarchain.hadoop.pipeline.PipelineCounter
import scholarchain.hadoop.pipeline.RecordingSink
import scholarchain.hadoop.pipeline.RejectReason
import scholarchain.hadoop.pipeline.RunContext
import scholarchain.hadoop.pipeline.TaskSink
import scholarchain.hadoop.pipeline.TeamExpansion

/** Giant author teams, which the specification calls out directly: "a paper
  * with n authors generates n * (n - 1) / 2 candidate pairs" and "count and
  * report unusually large author teams because they can create quadratic pair
  * expansion".
  *
  * `TeamExpansionSuite` pins the formula up to n = 3000 and expands teams up to
  * n = 7. These cases expand a real giant team, test the bound at its exact
  * boundary, and then ask the question the module's suites do not: the pair
  * path has a quadratic guard, so what about the other path that fans out
  * quadratically on the same record?
  *
  * Bounds chosen here, and why. n = 300 for pair expansion: 44,850 pairs is
  * genuinely quadratic work, it is above the default per-work bound so it also
  * exercises suppression, and it builds in milliseconds. n = 32 and n = 64 for
  * the blocking fan-out: the pair is what demonstrates growth, 64 authors is an
  * ordinary hyperauthorship paper, and 4,226 emitted records stay small enough
  * to assert exactly. Nothing here allocates more than a few megabytes.
  */
class AdversarialGiantTeamSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private def giantWork(
      key: String,
      size: Int,
      context: RunContext = AdversarialFixtures.run()
  ) =
    AdversarialFixtures.work(
      key = key,
      names = (1 to size).toVector.map(index => s"Author $index"),
      title = s"A Team Of $size.",
      context = context
    )

  test("a 300-author paper expands to exactly 44850 distinct pairs") {
    val bounded = AdversarialFixtures.run(
      maxAuthorTeamSize = 1000,
      maxPairsPerWork = 100000
    )
    val subject = giantWork("giant/300", 300, bounded)
    val expanded = TeamExpansion.expand(subject, bounded)
    assertEquals(expanded.expectedPairCount, 44850L)
    assertEquals(expanded.pairs.size, 44850)
    assertEquals(
      expanded.pairs.map(pair => (pair.mentionA, pair.mentionB)).distinct.size,
      44850
    )
    assert(expanded.pairs.forall(pair => pair.mentionA < pair.mentionB))
    assert(!expanded.oversize)
    assertEquals(expanded.team.orderedMentionIds.size, 300)
    assertEquals(expanded.authorPapers.size, 300)
  }

  test("every author position appears in exactly 299 pairs") {
    // A count-based check that the expansion is the complete graph and not
    // 44,850 of some other set of couples.
    val bounded = AdversarialFixtures.run(
      maxAuthorTeamSize = 1000,
      maxPairsPerWork = 100000
    )
    val expanded =
      TeamExpansion.expand(giantWork("giant/301", 300, bounded), bounded)
    val degrees = expanded.pairs
      .flatMap(pair => Vector(pair.authorPositionA, pair.authorPositionB))
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    assertEquals(degrees.size, 300)
    assertEquals(degrees.values.toSet, Set(299))
  }

  test("the default pair bound excludes any paper above 141 authors") {
    // The default `maxPairsPerWork` of 10000 is crossed between 141 and 142
    // authors, so under default bounds a hyperauthorship paper contributes
    // no coauthor edge at all. That is the documented tradeoff working as
    // designed; it is asserted here so the exact cutoff is on the record and
    // the phase report can quote it rather than estimate it.
    assertEquals(TeamExpansion.expectedPairCount(141), 9870L)
    assertEquals(TeamExpansion.expectedPairCount(142), 10011L)
    assert(TeamExpansion.expectedPairCount(141) <= run.maxPairsPerWork)
    assert(TeamExpansion.expectedPairCount(142) > run.maxPairsPerWork)
    val subject = giantWork("giant/142", 142, run)
    val expanded = TeamExpansion.expand(subject, run)
    assertEquals(expanded.pairs, Vector.empty)
    assertEquals(expanded.expectedPairCount, 10011L)
  }

  test("the pair bound is exclusive exactly at the bound") {
    val atBound = AdversarialFixtures.run(maxPairsPerWork = 45)
    val belowBound = AdversarialFixtures.run(maxPairsPerWork = 44)
    val allowed = giantWork("giant/10a", 10, atBound)
    val refused = giantWork("giant/10b", 10, belowBound)
    assertEquals(TeamExpansion.expand(allowed, atBound).pairs.size, 45)
    assertEquals(TeamExpansion.expand(refused, belowBound).pairs.size, 0)
  }

  test("a suppressed fan-out is reported as an oversize author team") {
    // Minor defect, owner hadoop-engineer. With a generous team bound and a
    // tight pair bound the team is not oversize, yet the suppression reject
    // carries `RejectReason.OversizeAuthorTeam`. `RejectReason` has no code
    // for a suppressed fan-out, so an operator reading the reject output
    // cannot tell "this team is unusually large" from "this team's pairs
    // were withheld", and the two have different remedies.
    val bounded = AdversarialFixtures.run(
      maxAuthorTeamSize = 1000,
      maxPairsPerWork = 10
    )
    val sink = new RecordingSink
    TeamExpansion.emit(giantWork("giant/20", 20, bounded), sink, bounded)
    assertEquals(sink.counterValue(PipelineCounter.OversizeAuthorTeams), 0L)
    assertEquals(sink.counterValue(PipelineCounter.SuppressedPairs), 190L)
    assertEquals(sink.recordsLabelled(TaskSink.pairs).size, 0)
    assertEquals(sink.recordsLabelled(TaskSink.teams).size, 1)
    assertEquals(sink.recordsLabelled(TaskSink.authorPapers).size, 20)
    assertEquals(sink.rejects.size, 1)
    assertEquals(sink.rejects.head.reason, RejectReason.OversizeAuthorTeam)
    assert(sink.rejects.head.detail.contains("Pair expansion suppressed"))
  }

  test("blocking suppresses oversize coauthor fan-out and records its reason") {
    val bounded = AdversarialFixtures.run(
      maxAuthorTeamSize = 4,
      maxPairsPerWork = 1
    )
    val subject = giantWork("giant/64", 64, bounded)
    val sink = new RecordingSink
    Blocking.emit(subject, sink, bounded)
    val keys = sink.recordsLabelled(TaskSink.blockingKeys).map(_._1)
    assertEquals(keys.count(_.contains("|name-coauthor|")), 0)
    assertEquals(keys.size, 2 + 64 * 3)
    assertEquals(sink.counterValue(PipelineCounter.EmittedRecords), 194L)
    assertEquals(
      sink.counterValue(PipelineCounter.SuppressedBlockingKeys),
      4032L
    )
    assertEquals(sink.counterValue(PipelineCounter.RejectedRecords), 1L)
    assertEquals(
      sink.rejects.map(_.reason),
      Vector(RejectReason.OversizeCoauthorFanOut)
    )
    assertEquals(sink.rejects.head.sourceRecordId, subject.sourceRecordId)
    assertEquals(sink.rejects.head.provenance, subject.provenance)
  }

  test("suppressed blocking keeps record and payload growth linear") {
    val small = emitBlocking(32)
    val large = emitBlocking(64)
    assertEquals(small.records, 2 + 32 * 3)
    assertEquals(large.records, 2 + 64 * 3)
    // A small allowance for wider author numbers, while rejecting quadratic bytes.
    assert(large.bytes < small.bytes * 3, s"$small then $large")
    assert(large.widest < small.widest * 2, s"$small then $large")
  }

  test("the coauthor bound admits its exact boundary and rejects one below") {
    val allowed = AdversarialFixtures.run(maxPairsPerWork = 12)
    val refused = AdversarialFixtures.run(maxPairsPerWork = 11)
    val allowedSink = new RecordingSink
    val refusedSink = new RecordingSink
    Blocking.emit(giantWork("boundary/4", 4, allowed), allowedSink, allowed)
    Blocking.emit(giantWork("boundary/4", 4, refused), refusedSink, refused)
    assertEquals(
      allowedSink
        .recordsLabelled(TaskSink.blockingKeys)
        .count(_._1.contains("|name-coauthor|")),
      12
    )
    assertEquals(allowedSink.rejects, Vector.empty)
    assertEquals(
      refusedSink
        .recordsLabelled(TaskSink.blockingKeys)
        .count(_._1.contains("|name-coauthor|")),
      0
    )
    assertEquals(
      refusedSink.counterValue(PipelineCounter.SuppressedBlockingKeys),
      12L
    )
  }

  test("a team sharing one name retains one coauthor key per mention") {
    val subject = AdversarialFixtures.work(
      key = "giant/homonym",
      names = (1 to 20).toVector.map(index => f"Wei Wang $index%04d"),
      title = "A Homonym Team."
    )
    val sink = new RecordingSink
    Blocking.emit(subject, sink, run)
    val keys = sink.recordsLabelled(TaskSink.blockingKeys).map(_._1)
    assertEquals(keys.count(_.contains("|name-coauthor|")), 20)
    assertEquals(keys.count(_.contains("|name-initials|")), 20)
    assertEquals(sink.rejects, Vector.empty)
  }

  test(
    "suppressed pair expansion has a distinct diagnostic from team size".fail
  ) {
    val bounded =
      AdversarialFixtures.run(maxAuthorTeamSize = 1000, maxPairsPerWork = 10)
    val sink = new RecordingSink
    TeamExpansion.emit(giantWork("giant/20", 20, bounded), sink, bounded)
    assertEquals(sink.rejects.size, 1)
    assert(sink.rejects.head.reason != RejectReason.OversizeAuthorTeam)
  }

  private case class Emission(records: Int, bytes: Long, widest: Int)

  private def emitBlocking(size: Int): Emission =
    val context = AdversarialFixtures.run(
      maxAuthorTeamSize = 4,
      maxPairsPerWork = 1
    )
    val sink = new RecordingSink
    Blocking.emit(giantWork(s"growth/$size", size, context), sink, context)
    val emitted = sink.recordsLabelled(TaskSink.blockingKeys)
    Emission(
      records = emitted.size,
      bytes = emitted.map(_._2.length.toLong).sum,
      widest = emitted.map(_._2.length).max
    )
