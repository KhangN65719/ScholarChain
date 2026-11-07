package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.hadoop.pipeline.{
  Blocking,
  LocalPipelineRunner,
  PipelineCounter,
  RecordingSink,
  RejectReason,
  StageOutcome,
  TaskSink,
  TeamExpansion
}

class AdversarialCounterSuite extends FunSuite:
  private def count(stage: StageOutcome, counter: PipelineCounter): Long =
    stage.counters.toMap.getOrElse(counter.name, 0L)

  test("team reduction preserves one count per map emission and reject") {
    val run =
      AdversarialFixtures.run(maxAuthorTeamSize = 2, maxPairsPerWork = 1)
    val work = AdversarialFixtures.work(
      "counter/team",
      Vector("A One", "B Two", "C Three"),
      context = run
    )
    val mapped = new RecordingSink
    TeamExpansion.emit(work, mapped, run)
    assertEquals(mapped.rejects.size, 2)
    val result = LocalPipelineRunner.expandTeams(Vector(work), run)
    assertEquals(result.rejects, mapped.rejects)
    assertEquals(count(result, PipelineCounter.RejectedRecords), 2L)
    assertEquals(
      count(result, PipelineCounter.EmittedRecords),
      mapped.records.size.toLong
    )
    assertEquals(result.records(TaskSink.teams).size, 1)
    assertEquals(result.records(TaskSink.authorPapers).size, 3)
    assertEquals(result.records(TaskSink.pairs).size, 0)
  }

  test("blocking preserves map rejects and counts every map output once") {
    val run = AdversarialFixtures.run(maxPairsPerWork = 1)
    val work = AdversarialFixtures.work(
      "counter/block",
      Vector("A One", "B Two", "C Three"),
      context = run
    )
    val mapped = new RecordingSink
    Blocking.emit(work, mapped, run)
    val result = LocalPipelineRunner.block(Vector(work), run)
    assertEquals(
      result.rejects.map(_.reason),
      Vector(RejectReason.OversizeCoauthorFanOut)
    )
    assertEquals(result.rejects, mapped.rejects)
    assertEquals(count(result, PipelineCounter.RejectedRecords), 1L)
    assertEquals(
      count(result, PipelineCounter.EmittedRecords),
      mapped.records.size.toLong
    )
  }

  test("identifier reduction does not add to the map emission count") {
    val run = AdversarialFixtures.run()
    val work = AdversarialFixtures.work("counter/id", Vector("A One"))
    val result = LocalPipelineRunner.joinIdentifiers(Vector(work, work), run)
    assertEquals(count(result, PipelineCounter.EmittedRecords), 2L)
    assertEquals(count(result, PipelineCounter.DuplicateRecords), 1L)
    assertEquals(result.records(TaskSink.identifierKeys).size, 1)
  }
