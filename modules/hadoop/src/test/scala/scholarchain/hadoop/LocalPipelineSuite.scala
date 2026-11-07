package scholarchain.hadoop

import munit.FunSuite
import scholarchain.core.{
  AuthorshipRecord,
  CoauthorPairCandidate,
  ContractWire,
  PublicationTeam,
  SourceType,
  WorkRecord
}
import scholarchain.hadoop.pipeline.*

/** The bounded local integration run.
  *
  * This drives the whole job graph's transformations over the checked-in
  * fixtures under the repository's sample directory, through the same shuffle
  * grouping and the same partition function the Hadoop jobs use. It is the
  * evidence for the counter inventory, the bounds, the reject outputs and the
  * determinism and idempotency claims.
  *
  * It is still not evidence that a Hadoop job executes: no job submission,
  * split assignment, task attempt, shuffle or output committer takes part here.
  * That is deliberate — this suite covers all seven stages cheaply, which a
  * mini-cluster run cannot do within a reasonable test budget.
  *
  * Hadoop's runtime and mini-cluster are both on the Test classpath as of run
  * 6, so execution evidence is no longer unavailable, merely elsewhere:
  * `MapReduceEndToEndSuite` runs real jobs over a deliberately smaller input.
  * Read the two together — this suite for breadth of logic, that one for proof
  * that the MapReduce wiring works.
  */
class LocalPipelineSuite extends FunSuite:
  private val run = TestRun.context()

  private lazy val inputs = LocalPipelineRunner.readLocal(TestRun.samples)

  private lazy val observations =
    LocalPipelineRunner.runAll(inputs._1, inputs._2, run)

  private val externalDatasets = Vector("openalex", "orcid", "crossref")

  private def works: Vector[WorkRecord] =
    val stages = observations.stage(Stage.dblpExtraction) +:
      externalDatasets.map(dataset =>
        observations.stage(s"${Stage.sourceNormalization}/$dataset")
      )
    stages
      .flatMap(_.records(TaskSink.works))
      .map(ContractWire.decode[WorkRecord])

  test("the DBLP stage accepts both pinned records and rejects none") {
    val stage = observations.stage(Stage.dblpExtraction)
    assertEquals(stage.counters.toMap.get("InputRecords"), Some(2L))
    assertEquals(stage.counters.toMap.get("AcceptedRecords"), Some(2L))
    assertEquals(stage.rejects, Vector.empty)
    assertEquals(stage.records(TaskSink.works).size, 2)
    assertEquals(stage.records(TaskSink.venues).size, 2)
  }

  test("each external dataset normalizes its two pinned records") {
    externalDatasets.foreach { dataset =>
      val stage = observations.stage(s"${Stage.sourceNormalization}/$dataset")
      assertEquals(
        stage.counters.toMap.get("InputRecords"),
        Some(2L),
        s"$dataset input count"
      )
      assertEquals(
        stage.counters.toMap.get("AcceptedRecords"),
        Some(2L),
        s"$dataset accepted count"
      )
      assertEquals(stage.rejects, Vector.empty, s"$dataset rejects")
    }
  }

  test("ORCID yields identity evidence and no works") {
    val stage = observations.stage(s"${Stage.sourceNormalization}/orcid")
    assertEquals(stage.records(TaskSink.works), Vector.empty)
    assertEquals(stage.records(TaskSink.researchers).size, 2)
  }

  test("the bounded run yields six works across three work-bearing sources") {
    assertEquals(works.size, 6)
    assertEquals(
      works.map(_.source.wire).distinct.sorted,
      Vector("crossref", "dblp", "openalex")
    )
  }

  test("every emitted work carries complete provenance") {
    works.foreach { work =>
      assert(work.provenance.snapshotId.contains(":"), work.workId)
      assertEquals(work.provenance.transformVersion, "hadoop-pipeline:v1")
      assertEquals(work.provenance.runId, TestRun.runId)
      assertEquals(work.provenance.source, work.source)
      assertEquals(work.provenance.sourceRecordId, work.sourceRecordId)
      assert(work.provenance.sourceUri.nonEmpty)
    }
  }

  test("the maximum author team and largest pair expansion are reported") {
    assertEquals(observations.maximumAuthorTeamSize, 5)
    assertEquals(observations.largestPairExpansion, 10L)
    assert(observations.maximumAuthorTeamWorkId.startsWith("work:v1:"))
  }

  test("pair generation totals the per-work formula across the run") {
    // Three five-author works yield ten pairs each; three single-author works
    // yield none. No two works share a mention ID, so nothing consolidates.
    val teams = observations.stage(Stage.teamExpansion)
    assertEquals(teams.records(TaskSink.pairs).size, 30)
    assertEquals(teams.counters.toMap.get("EmittedPairs"), Some(30L))
    assertEquals(teams.counters.toMap.get("SingleAuthorWorks"), Some(3L))
    assertEquals(teams.counters.toMap.get("DuplicateRecords"), None)
    assertEquals(teams.records(TaskSink.teams).size, 6)
    assertEquals(teams.records(TaskSink.authorPapers).size, 18)
    assertEquals(
      works.map(work => TeamExpansion.expectedPairCount(work.authors.size)).sum,
      30L
    )
  }

  test("no oversize team or oversize block appears at the default bounds") {
    assertEquals(observations.counter("OversizeAuthorTeams"), 0L)
    assertEquals(observations.counter("SuppressedPairs"), 0L)
    assertEquals(observations.counter("OversizeBlocks"), 0L)
  }

  test("every pair record resolves to a real team member") {
    val teams = observations.stage(Stage.teamExpansion)
    val teamMembers = teams
      .records(TaskSink.teams)
      .map(ContractWire.decode[PublicationTeam])
      .flatMap(team => team.orderedMentionIds.map(team.workId -> _))
      .toSet
    teams.records(TaskSink.pairs).foreach { encoded =>
      val pair = ContractWire.decode[CoauthorPairCandidate](encoded)
      assert(
        teamMembers.contains(pair.workId -> pair.mentionA),
        s"unknown endpoint ${pair.mentionA}"
      )
      assert(teamMembers.contains(pair.workId -> pair.mentionB))
      assert(pair.mentionA < pair.mentionB)
    }
  }

  test("a repeated run over the same input produces identical output") {
    // The idempotency requirement: equivalent logical output, record for record
    // and counter for counter.
    val repeat = LocalPipelineRunner.runAll(inputs._1, inputs._2, run)
    assertEquals(repeat.stages.map(_.stage), observations.stages.map(_.stage))
    repeat.stages.zip(observations.stages).foreach { case (again, original) =>
      assertEquals(again.emitted, original.emitted, s"${original.stage} output")
      assertEquals(
        again.counters,
        original.counters,
        s"${original.stage} counters"
      )
      assertEquals(
        again.rejects.map(_.reason),
        original.rejects.map(_.reason),
        s"${original.stage} rejects"
      )
    }
    assertEquals(
      repeat.maximumAuthorTeamSize,
      observations.maximumAuthorTeamSize
    )
    assertEquals(repeat.largestPairExpansion, observations.largestPairExpansion)
    assertEquals(
      repeat.largestBlockingGroup,
      observations.largestBlockingGroup
    )
    assertEquals(repeat.partitionCounts, observations.partitionCounts)
  }

  test("changing only the run ID leaves the derived content alone") {
    val second = LocalPipelineRunner.runAll(
      inputs._1,
      inputs._2,
      TestRun.context(runIdentifier = TestRun.secondRunId)
    )
    assertEquals(
      second.maximumAuthorTeamSize,
      observations.maximumAuthorTeamSize
    )
    assertEquals(second.largestPairExpansion, observations.largestPairExpansion)
    assertEquals(
      second.largestBlockingGroup,
      observations.largestBlockingGroup
    )
    assertEquals(
      second.stage(Stage.teamExpansion).records(TaskSink.pairs).size,
      observations.stage(Stage.teamExpansion).records(TaskSink.pairs).size
    )
    assertEquals(second.partitionCounts, observations.partitionCounts)
  }

  test("output paths are a function of the run and the snapshot set only") {
    val first = TestRun.context()
    val same = TestRun.context()
    val prefix =
      "s3://local-curated-example/curated/run=run-v1-1e7c6f4a-2b55-4d38-9a1f-0c2b7d6e5f40"
    assertEquals(
      first.artifactPath(Artifact.Teams),
      same.artifactPath(Artifact.Teams)
    )
    assertEquals(
      first.artifactPath(Artifact.Teams),
      s"$prefix/snapshot=${first.snapshotId}/stage=team-expansion/teams/v1"
    )
    // The reject output is versioned beside the stage's accepted records.
    assertEquals(
      first.rejectPath(Stage.blocking),
      s"$prefix/snapshot=${first.snapshotId}/stage=blocking/rejects/v1"
    )
    // A different snapshot set must not overwrite this run's prefix.
    val otherSnapshot = first.copy(
      datasetSnapshots = first.datasetSnapshots.updated("dblp", "2026-12-01")
    )
    assertNotEquals(otherSnapshot.snapshotId, first.snapshotId)
  }

  test("every artifact has exactly one owning stage and output path") {
    // Dataset-scoped artifacts are addressed per dataset, so each is expanded
    // across the pinned datasets rather than asked for a datasetless path.
    val paths = Artifact.values.toVector.flatMap { artifact =>
      if artifact.datasetScoped then
        externalDatasets.map(run.artifactPath(artifact, _))
      else Vector(run.artifactPath(artifact))
    }
    assertEquals(
      paths.distinct.size,
      paths.size,
      "two artifacts share an output path"
    )
    Artifact.values.foreach(artifact =>
      assert(
        Stage.ordered.contains(artifact.owningStage),
        s"${artifact.segment} names an unknown stage"
      )
    )
  }

  test("a dataset-scoped artifact cannot be addressed without its dataset") {
    // The accessor used to return `…/stage=source-normalization/works/v1`, a
    // prefix no job ever writes, because source normalization commits beneath
    // `dataset=<name>`. Asking the wrong way is now an error rather than a
    // plausible-looking path handed to a downstream reader.
    assert(Artifact.NormalizedWorks.datasetScoped)
    intercept[IllegalArgumentException] {
      run.artifactPath(Artifact.NormalizedWorks)
    }
    intercept[IllegalArgumentException] {
      run.artifactPath(Artifact.Teams, "openalex")
    }
    intercept[IllegalArgumentException] {
      run.artifactPath(Artifact.NormalizedWorks, "  ")
    }
  }

  test("a dataset-scoped path matches what the sink actually commits") {
    // The committed location is the per-dataset job output directory plus the
    // artifact segment and schema version. Pinned literally so the accessor and
    // `ArtifactSink` cannot drift apart silently.
    val dataset = "openalex"
    assertEquals(
      run.artifactPath(Artifact.NormalizedWorks, dataset),
      s"${run.stagePath(Stage.sourceNormalization, dataset)}/works/v1"
    )
    assert(
      run
        .artifactPath(Artifact.NormalizedWorks, dataset)
        .contains("/dataset=openalex/"),
      "the dataset segment is missing"
    )
    // Two datasets never collide.
    assertNotEquals(
      run.artifactPath(Artifact.NormalizedWorks, "openalex"),
      run.artifactPath(Artifact.NormalizedWorks, "crossref")
    )
  }

  test("the run manifest pins every dataset snapshot the run read") {
    val manifest = run.manifest("scholarchain-local.v1")
    assertEquals(manifest.runId, TestRun.runId)
    assertEquals(manifest.datasetSnapshots, RunContext.localSnapshots)
    assertEquals(manifest.provenance.runId, TestRun.runId)
  }

  test("every blocking group is non-trivial and within its bound") {
    val groups = observations
      .stage(Stage.blocking)
      .records(TaskSink.blockingGroups)
      .map(ContractWire.decode[BlockingGroup])
    assert(groups.nonEmpty, "the bounded run should produce blocking groups")
    groups.foreach { group =>
      assert(group.retainedSize > 1, s"${group.blockKey} is trivial")
      assert(
        group.retainedSize <= run.maxBlockSize,
        s"${group.blockKey} exceeded the bound"
      )
      assertEquals(group.memberIds.size, group.retainedSize)
      assert(!group.truncated, s"${group.blockKey} should not be truncated")
    }
    assertEquals(
      observations.largestBlockingGroup,
      groups.map(_.observedSize).max
    )
    assert(observations.largestBlockingGroupKey.nonEmpty)
  }

  test("both researcher and work blocks are produced") {
    val kinds = observations
      .stage(Stage.blocking)
      .records(TaskSink.blockingGroups)
      .map(ContractWire.decode[BlockingGroup])
      .map(_.kind.wire)
      .distinct
      .sorted
    assertEquals(kinds, Vector("researcher", "work"))
  }

  test("candidates compare across sources and never within one work") {
    val candidates = observations
      .stage(Stage.candidateFeatures)
      .records(TaskSink.matchCandidates)
      .map(ContractWire.decode[MatchCandidate])
    assert(candidates.nonEmpty, "the bounded run should produce candidates")
    candidates.foreach { candidate =>
      assert(candidate.leftId < candidate.rightId)
      assertEquals(candidate.recordType, MatchCandidate.recordTypeName)
      assert(candidate.features.values.forall(_.isFinite))
      assertEquals(candidate.provenance.runId, TestRun.runId)
      assertEquals(candidate.features.get("sameWork"), Some(0.0))
    }
    assert(
      candidates.exists(candidate =>
        candidate.leftSource != candidate.rightSource
      ),
      "cross-source candidates should be found"
    )
  }

  test("the graph export emits one record per distinct entity") {
    val exported = observations.stage(Stage.graphExport)
    assertEquals(exported.records(TaskSink.works).size, 6)
    // Mentions are projected out of the works: 3 * 5 + 3 * 1 = 18.
    assertEquals(exported.records(TaskSink.mentions).size, 18)
    assert(exported.records(TaskSink.researchers).nonEmpty)
    assert(exported.records(TaskSink.topics).nonEmpty)
    assert(exported.records(TaskSink.institutions).nonEmpty)
    assert(exported.records(TaskSink.venues).nonEmpty)
    assertEquals(
      exported.keys(TaskSink.works).distinct.size,
      exported.keys(TaskSink.works).size
    )
    assertEquals(
      exported.keys(TaskSink.researchers).distinct.size,
      exported.keys(TaskSink.researchers).size
    )
  }

  /** Prints the run's measured figures so the required report can quote
    * observed values instead of predictions.
    *
    * These are deliberately not pinned to literals. The blocking-group maximum
    * depends on how many sources agree on a name or title, so pinning it would
    * turn an ordinary fixture edit into a failing test for no gain. The
    * invariants that actually matter are asserted in the blocking tests above;
    * this case only surfaces the numbers.
    */
  test("report the run's measured figures") {
    val groups = observations
      .stage(Stage.blocking)
      .records(TaskSink.blockingGroups)
      .map(ContractWire.decode[BlockingGroup])
    val lines = Vector(
      s"works=${works.size}",
      s"maximumAuthorTeamSize=${observations.maximumAuthorTeamSize}",
      s"maximumAuthorTeamWorkId=${observations.maximumAuthorTeamWorkId}",
      s"largestPairExpansion=${observations.largestPairExpansion}",
      s"emittedPairs=${observations.counter("EmittedPairs")}",
      s"blockingGroups=${groups.size}",
      s"largestBlockingGroup=${observations.largestBlockingGroup}",
      s"largestBlockingGroupKey=${observations.largestBlockingGroupKey}",
      // Name states the truncation: this is a descending top-10, not the full
      // distribution, and the group count is printed alongside so a reader can
      // see how many sizes are not shown.
      s"blockingGroupSizesTop10of${groups.size}=" +
        groups
          .map(_.observedSize)
          .sorted
          .reverse
          .take(10)
          .mkString("[", ",", "]"),
      s"totalRejects=${observations.totalRejects}",
      s"partitionCount=${run.partitionCount}",
      // Sorted by stream name only: there is no `Ordering` for the per-stream
      // partition map, so sorting the pairs directly would not compile.
      s"partitionSpread=${observations.partitionCounts.toVector
          .sortBy(_._1)
          .map { case (stream, spread) =>
            s"$stream->${spread.toVector.sortBy(_._1)}"
          }
          .mkString(",")}"
    )
    println(s"[phase-4 measured] ${lines.mkString(" ")}")
    observations.stages.foreach { stage =>
      println(
        s"[phase-4 stage] stage=${stage.stage} counters=${stage.counters.mkString(",")} rejects=${stage.rejects.size} reasons=${stage.rejects.groupMapReduce(_.reason.code)(_ => 1)(_ + _).toVector.sorted.mkString(",")}"
      )
    }
    Artifact.values.filterNot(_.datasetScoped).foreach { artifact =>
      println(
        s"[phase-4 output] ${artifact.segment}=${run.artifactPath(artifact)}"
      )
    }
    Stage.ordered.foreach { stage =>
      println(s"[phase-4 reject-output] $stage=${run.rejectPath(stage)}")
    }
    assert(groups.nonEmpty)
  }

  test("the counter inventory required by the specification is declared") {
    val declared = PipelineCounter.values.map(_.name).toSet
    Vector(
      "InputRecords",
      "AcceptedRecords",
      "RejectedRecords",
      "DuplicateRecords",
      "ConflictRecords",
      "OversizeBlocks",
      "OversizeAuthorTeams",
      "EmittedPairs",
      "MissingKeyRecords",
      "SchemaFailureRecords"
    ).foreach(name => assert(declared.contains(name), s"$name is not declared"))
  }

  test("every key lands in range under the declared partition count") {
    observations.partitionCounts.foreach { case (label, byPartition) =>
      byPartition.keys.foreach(partition =>
        assert(
          partition >= 0 && partition < run.partitionCount,
          s"$label used partition $partition"
        )
      )
    }
    assert(observations.partitionCounts.nonEmpty)
  }

  test("the identifier join unifies the two DOIs across all three sources") {
    val keys = observations
      .stage(Stage.identifierJoin)
      .records(TaskSink.identifierKeys)
      .map(ContractWire.decode[IdentifierJoinKey])
    val dois = keys.filter(_.namespace == IdNamespace.Doi.wire)
    assertEquals(dois.size, 2)
    dois.foreach { key =>
      assertEquals(
        key.sources,
        Vector("crossref", "dblp", "openalex"),
        s"${key.value} should be claimed by all three sources"
      )
      assertEquals(key.entityIds.size, 3)
    }
    assertEquals(
      dois.map(_.value).sorted,
      Vector("10.1109/jcdl.2017.7991609", "10.14778/1687553.1687577")
    )
  }

  test("ORCID join keys are person-scoped and report no false conflicts") {
    val keys = observations
      .stage(Stage.identifierJoin)
      .records(TaskSink.identifierKeys)
      .map(ContractWire.decode[IdentifierJoinKey])
    val orcids = keys.filter(_.namespace == IdNamespace.Orcid.wire)
    assertEquals(orcids.size, 4)
    // One ORCID legitimately appears on several papers from one source. That
    // is not a duplicate and its differing titles are not a conflict.
    orcids.foreach { key =>
      assertEquals(key.duplicateSources, Vector.empty, key.value)
      assertEquals(key.conflictingTitles, Vector.empty, key.value)
    }
    assert(
      orcids.exists(key =>
        key.value == "0000-0001-7580-4351" && key.entityIds.size == 3
      ),
      "Michael Ley's ORCID should gather three mentions"
    )
  }

  test("a genuine title disagreement on one DOI is reported as a conflict") {
    // The 10.14778 DOI is titled "DBLP - Some Lessons Learned." by DBLP and
    // "DBLP" by OpenAlex and Crossref. That is a real cross-source conflict and
    // must surface in both the counter and the reject output.
    val join = observations.stage(Stage.identifierJoin)
    assert(join.counters.toMap.get("ConflictRecords").exists(_ > 0L))
    val conflicts =
      join.rejects.filter(_.reason == RejectReason.IdentifierConflict)
    assertEquals(conflicts.size, 1)
    assert(conflicts.head.sourceRecordId.startsWith("doi|10.14778/"))
    assertEquals(conflicts.head.provenance.runId, TestRun.runId)
  }

  test("no work reaches the join without an exact identifier") {
    assertEquals(
      observations
        .stage(Stage.identifierJoin)
        .counters
        .toMap
        .get("MissingKeyRecords"),
      None
    )
  }

  test("author-paper records cover every author position exactly once") {
    val authorships = observations
      .stage(Stage.teamExpansion)
      .records(TaskSink.authorPapers)
      .map(ContractWire.decode[AuthorshipRecord])
    assertEquals(authorships.size, 18)
    authorships.groupBy(_.workId).foreach { case (workId, group) =>
      assertEquals(
        group.map(_.authorPosition).sorted,
        (1 to group.size).toVector,
        s"author positions for $workId"
      )
    }
  }

  test(
    "local map rejects transfer exactly once through blocking and team reduction"
  ) {
    val xml =
      """<article key="repair/team"><title>Team</title><author>Ann</author><author>Bo</author><author>Cal</author></article>"""
    val bounded = TestRun.context(maxPairsPerWork = 1)
    val work = DblpParser.parse(xml, bounded).toOption.get.work
    val blocked = LocalPipelineRunner.block(Vector(work), bounded)
    val teams = LocalPipelineRunner.expandTeams(Vector(work), bounded)
    Vector(blocked, teams).foreach { stage =>
      assertEquals(stage.rejects.size, 1)
      assertEquals(stage.counters.toMap.get("RejectedRecords"), Some(1L))
    }
    assertEquals(
      blocked.rejects.head.reason,
      RejectReason.OversizeCoauthorFanOut
    )
    val sink = new RecordingSink
    Blocking.emit(work, sink, bounded)
    assertEquals(
      blocked.counters.toMap.get("EmittedRecords"),
      Some(sink.records.size.toLong)
    )
    assertEquals(teams.counters.toMap.get("EmittedRecords"), Some(4L))
  }
