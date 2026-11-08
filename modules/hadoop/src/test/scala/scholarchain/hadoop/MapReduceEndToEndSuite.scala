package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{Duration, MINUTES}
import scala.jdk.CollectionConverters.*
import org.apache.hadoop.mapreduce.{Job, TaskCounter}
import munit.FunSuite
import scholarchain.core.{
  AuthorshipRecord,
  CoauthorPairCandidate,
  ContractWire,
  PublicationTeam,
  Venue,
  WorkRecord
}
import scholarchain.hadoop.jobs.{
  DblpExtractionJob,
  DblpXmlInputFormat,
  PublicationTeamJob
}
import scholarchain.hadoop.pipeline.*

/** Executes real MapReduce jobs through Hadoop's own runtime.
  *
  * This is the evidence for the specification's hardest gate item: "at least
  * two real MapReduce jobs execute, not local collection operations disguised
  * as MapReduce." Every other suite in this module calls transformation code
  * directly. Nothing here does: both jobs are submitted with
  * `Job.waitForCompletion`, and Hadoop performs split calculation, record
  * reading, map task execution, the partition-sort-shuffle, reduce task
  * execution and output commit. The assertions read committed files off disk
  * and counters off the completed `Job`.
  *
  * Execution model: `mapreduce.framework.name=local`, so Hadoop's
  * `LocalJobRunner` drives the job in-process over `file:///`. That is Hadoop's
  * own runtime — real `InputSplit`s, real `RecordReader`, real `Mapper` and
  * `Reducer` task attempts, real shuffle, real `OutputCommitter` — rather than
  * a substitute for it. Its one material limitation is recorded at
  * [[reduceTaskLimit]].
  *
  * Why these two jobs. `DblpExtractionJob` is the only stage with a custom
  * `InputFormat` and `RecordReader`, so real split assignment tests what the
  * byte-offset suite can only simulate: there, splits are hand-constructed;
  * here Hadoop computes them and assigns each to a task. `PublicationTeamJob`
  * is the widest shuffle in the pipeline — three keyed output streams, one of
  * them consolidating duplicates — so between them they cover input, map,
  * partition, shuffle, reduce, multiple named outputs, rejects and commit.
  *
  * Input is deliberately tiny and shaped to force each mechanism to show
  * itself. See [[writeInput]].
  */
class MapReduceEndToEndSuite extends FunSuite:

  /** A `LocalJobRunner` job takes seconds, not milliseconds, and this suite
    * runs several. The munit default of 30 seconds is not enough.
    */
  override def munitTimeout: Duration = Duration(10, MINUTES)

  /** `LocalJobRunner` executes a single reduce task regardless of
    * `setNumReduceTasks`, so this suite proves the shuffle and the reducer run,
    * but not that `StableKeyPartitioner` spreads keys across several reducers.
    * The partition function itself is covered in-process by
    * `PartitionerWiringSuite` and `IdentifierAndKeySuite`. Multi-reducer
    * distribution would need a YARN mini-cluster, which is a much heavier and
    * flakier dependency than the evidence justifies.
    *
    * Consequently no assertion here depends on the number of part files, and
    * committed records are gathered across whatever part files exist.
    */
  private val reduceTaskLimit = "LocalJobRunner runs one reduce task"

  private lazy val workspace: Path =
    Files.createTempDirectory("scholarchain-mapreduce-")

  /** A run context rooted on the local filesystem. `TestRun.context` points at
    * an `s3://` placeholder, which no job could commit to.
    */
  private lazy val run: RunContext = RunContext(
    runId = TestRun.runId,
    transformVersion = RunContext.transformVersion,
    datasetSnapshots = RunContext.localSnapshots,
    outputRoot = workspace.resolve("curated").toString,
    partitionCount = 2,
    maxBlockSize = 50,
    maxAuthorTeamSize = 50,
    maxPairsPerWork = 10000
  )

  /** The bounded input, assembled from the two pinned DBLP sample records.
    *
    * Four records, each present for a reason:
    *   - the one-author article and the five-author inproceedings, so pair
    *     expansion has both a trivial and a non-trivial team;
    *   - the article **again**, under the same DBLP key, so the reduce side has
    *     a genuine duplicate to collapse. This is the only coverage
    *     `DeduplicateReducer` has of any kind, and a duplicate surviving to the
    *     output would prove the shuffle had not grouped by key;
    *   - a titleless record, so the reject path and its `MultipleOutputs`
    *     stream are exercised by a real committing task rather than inspected.
    */
  private lazy val inputDirectory: Path =
    val directory = Files.createDirectories(workspace.resolve("input"))
    val samples = XmlRecordStream.records(
      Files.readString(TestRun.samples.resolve("dblp.xml"), UTF_8)
    )
    assertEquals(samples.size, 2, "the pinned DBLP fixture changed shape")
    val titleless =
      """<article key="journals/x/Untitled99"><author>No Title</author></article>"""
    val document =
      (Vector("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<dblp>") ++
        samples ++ Vector(samples.head, titleless, "</dblp>")).mkString("\n")
    Files.writeString(directory.resolve("dblp-bounded.xml"), document, UTF_8)
    directory

  /** Points a job at the local filesystem and `LocalJobRunner`, and keeps every
    * scratch directory inside the test workspace so a run leaves nothing
    * behind.
    *
    * `split.maxsize` is set well below the input size so Hadoop computes
    * several splits over one small file. That is the point of the exercise: the
    * record reader's split-boundary logic is driven by Hadoop's own split
    * assignment, not by offsets a test chose.
    */
  private def localize(job: Job): Job =
    val configuration = job.getConfiguration
    configuration.set("fs.defaultFS", "file:///")
    configuration.set("mapreduce.framework.name", "local")
    configuration.set("hadoop.tmp.dir", workspace.resolve("tmp").toString)
    configuration.set(
      "mapreduce.cluster.local.dir",
      workspace.resolve("mapred-local").toString
    )
    configuration.set(
      "mapreduce.jobtracker.staging.root.dir",
      workspace.resolve("staging").toString
    )
    configuration.setLong(
      "mapreduce.input.fileinputformat.split.maxsize",
      320L
    )
    job

  private def counterOf(job: Job, counter: PipelineCounter): Long =
    job.getCounters.findCounter(counter.group, counter.name).getValue

  /** Every committed record under an artifact directory, across all part files.
    */
  private def committed(directory: String): Vector[String] =
    val path = Path.of(directory)
    if !Files.isDirectory(path) then Vector.empty
    else
      val listing = Files.list(path)
      try
        listing
          .iterator()
          .asScala
          .toVector
          .filter(entry =>
            Files.isRegularFile(entry) &&
              entry.getFileName.toString.startsWith("part-")
          )
          .sortBy(_.getFileName.toString)
          .flatMap(entry => Files.readAllLines(entry, UTF_8).asScala.toVector)
          .filter(_.trim.nonEmpty)
      finally listing.close()

  /** Job 1, executed once for the whole suite. */
  private lazy val extraction: Job =
    val job = localize(
      DblpExtractionJob.configure(run, Vector(inputDirectory.toString))
    )
    assert(
      job.waitForCompletion(true),
      "the DBLP extraction job did not complete"
    )
    job

  /** Job 2, executed once, reading job 1's committed works by path. */
  private lazy val teams: Job =
    val job = localize(
      PublicationTeamJob
        .configure(run, Vector(run.artifactPath(Artifact.DblpWorks)))
    )
    assert(
      job.waitForCompletion(true),
      "the publication team job did not complete"
    )
    job

  test("job 1 executes as a real MapReduce job and commits its output") {
    assert(extraction.isSuccessful, "job 1 reported failure")
    assert(extraction.getJobID != null, "no job ID was assigned")
    // Hadoop's own task counters, which only a real task attempt populates.
    // `TaskCounter` is referenced as an enum rather than by string name, so an
    // upstream rename is a compile error instead of a silent zero.
    val mapInput =
      extraction.getCounters.findCounter(TaskCounter.MAP_INPUT_RECORDS).getValue
    val mapOutput = extraction.getCounters
      .findCounter(TaskCounter.MAP_OUTPUT_RECORDS)
      .getValue
    // Four records read by the custom record reader across all map tasks.
    assertEquals(mapInput, 4L)
    // Three accepted records and one reject, each emitting at least one keyed
    // value, so the map side produced genuine shuffle traffic.
    assert(mapOutput >= 4L, s"map output was $mapOutput")
  }

  test(
    "Hadoop's split calculation divides the input, records still read once"
  ) {
    // `split.maxsize` is far below the file size, so Hadoop's own split
    // calculation must divide this single file. Splits are recomputed here
    // through the real `InputFormat` against the job's configuration, rather
    // than hand-built as the byte-offset suite necessarily does.
    val splits = new DblpXmlInputFormat().getSplits(extraction)
    assert(
      splits.size > 1,
      s"expected several splits over the bounded input, got ${splits.size}"
    )
    // The payoff: more than one split, and still exactly four records read and
    // four accounted for. A record straddling a boundary is neither lost nor
    // read twice.
    assertEquals(
      extraction.getCounters
        .findCounter(TaskCounter.MAP_INPUT_RECORDS)
        .getValue,
      4L
    )
    assertEquals(counterOf(extraction, PipelineCounter.InputRecords), 4L)
  }

  test("a reduce task ran and received grouped keys") {
    // Reduce-side evidence from Hadoop's own counters. Key groups arriving at a
    // reducer is what distinguishes a real shuffle from a map-only job.
    val reduceGroups = extraction.getCounters
      .findCounter(TaskCounter.REDUCE_INPUT_GROUPS)
      .getValue
    assert(reduceGroups > 0L, "no key group reached a reducer")
    // Two works, two venues and one reject reason: five distinct keys. The
    // duplicated article joins its group rather than adding a sixth.
    assertEquals(reduceGroups, 5L)
  }

  test("job 1's counters, read off the completed job, match the input") {
    // Counters read back from a finished Hadoop job, not from a test harness.
    assertEquals(counterOf(extraction, PipelineCounter.InputRecords), 4L)
    assertEquals(counterOf(extraction, PipelineCounter.AcceptedRecords), 3L)
    assertEquals(counterOf(extraction, PipelineCounter.RejectedRecords), 1L)
    assertEquals(
      counterOf(extraction, PipelineCounter.MissingKeyRecords),
      0L
    )
    // The titleless record fails a required field, not the XML parse.
    assertEquals(
      counterOf(extraction, PipelineCounter.SchemaFailureRecords),
      0L
    )
  }

  test("the reduce side collapses a duplicated DBLP key") {
    // The strongest available evidence that a real shuffle happened: the
    // duplicate was emitted by two different map calls and could only be
    // collapsed by a reducer that received both under one key.
    //
    // Two duplicate groups are expected, not one: the article appears twice, so
    // both its work record and its venue record arrive twice.
    assertEquals(counterOf(extraction, PipelineCounter.DuplicateRecords), 2L)
    val works = committed(run.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
    assertEquals(works.size, 2, "the duplicate work was not collapsed")
    assertEquals(
      works.map(_.sourceRecordId).sorted,
      Vector("conf/jcdl/MichelsFLSS17", "journals/pvldb/Ley09")
    )
    val venues = committed(run.artifactPath(Artifact.DblpVenues))
      .map(ContractWire.decode[Venue])
    assertEquals(venues.size, 2)
    assertEquals(
      venues.map(_.venueId).sorted,
      Vector("venue:v1:dblp:jcdl", "venue:v1:dblp:proc-vldb-endow")
    )
  }

  test("committed works carry provenance and their full author sequence") {
    val works = committed(run.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
    works.foreach { work =>
      assertEquals(work.provenance.runId, TestRun.runId)
      assertEquals(work.provenance.snapshotId, "dblp:2026-11-01")
      assertEquals(work.provenance.transformVersion, "hadoop-pipeline:v1")
      assert(work.provenance.sourceUri.nonEmpty)
    }
    val team = works.find(_.sourceRecordId == "conf/jcdl/MichelsFLSS17").get
    assertEquals(team.authors.size, 5)
    assertEquals(team.authors.map(_.authorPosition), Vector(1, 2, 3, 4, 5))
    assertEquals(team.authors.head.name, "Christopher Michels")
  }

  test("a real committing task writes the reject to its versioned path") {
    // The reject stream is a `MultipleOutputs` named output, so this also shows
    // that side output commits correctly from a task attempt.
    val rejects = committed(
      s"${run.rejectPath(Stage.dblpExtraction)}/MISSING_REQUIRED_FIELD"
    ).map(ContractWire.decode[RejectRecord])
    assertEquals(rejects.size, 1)
    assertEquals(rejects.head.reason, RejectReason.MissingRequiredField)
    assertEquals(rejects.head.stage, Stage.dblpExtraction)
    assert(rejects.head.detail.contains("title"), rejects.head.detail)
    assertEquals(rejects.head.provenance.runId, TestRun.runId)
    // The payload is retained for diagnosis, bounded in size.
    assert(rejects.head.payloadExcerpt.contains("Untitled99"))
    assert(rejects.head.payloadExcerpt.length <= RejectRecord.payloadLimit)
  }

  test("job 2 executes and consumes job 1's committed output") {
    assert(teams.isSuccessful, "job 2 reported failure")
    assert(teams.getJobID != null)
    assertEquals(teams.getJobID == extraction.getJobID, false)
    // Two works in, from the committed artifact of a previous job.
    assertEquals(counterOf(teams, PipelineCounter.InputRecords), 2L)
    assertEquals(counterOf(teams, PipelineCounter.AcceptedRecords), 2L)

    // Rules out a map-only job: a reduce phase is configured. If this were 0
    // the run-7 counter would have been telling the truth and the artifacts
    // would be map-side `MultipleOutputs` output.
    assert(teams.getNumReduceTasks > 0, "job 2 has no reduce task configured")

    // And a reduce phase actually ran, measured with `TaskCounter`, which the
    // tasks themselves maintain.
    //
    // Two teams, six author-papers and ten pair candidates reach the shuffle,
    // each under a distinct key, so the reducer sees eighteen records in
    // eighteen groups. Exact rather than merely non-zero, so a lost or merged
    // stream fails here. `REDUCE_INPUT_RECORDS` cannot be non-zero without a
    // reduce task having consumed shuffled input.
    val reduceRecords =
      teams.getCounters.findCounter(TaskCounter.REDUCE_INPUT_RECORDS).getValue
    val reduceGroups =
      teams.getCounters.findCounter(TaskCounter.REDUCE_INPUT_GROUPS).getValue
    assertEquals(reduceRecords, 18L, "the reduce phase received no records")
    assertEquals(reduceGroups, 18L)
    assertEquals(
      teams.getCounters.findCounter(TaskCounter.MAP_OUTPUT_RECORDS).getValue,
      18L
    )
  }

  test("LocalJobRunner does not maintain the launched-task counters") {
    // This case exists to establish, rather than assume, why run 7's
    // `JobCounter.TOTAL_LAUNCHED_REDUCES` read returned 0.
    //
    // A zero on its own is ambiguous: it is equally consistent with "no reduce
    // ran" and with "the counter is never incremented on this path". What
    // disambiguates is holding both facts at once — the launched-task counter
    // reads 0 *while* the reduce-side task counters are non-zero. Those cannot
    // both describe a job with no reduce phase, so the counter is unmaintained.
    //
    // The mechanism, from Hadoop's architecture rather than from anything
    // measured here: the `TOTAL_LAUNCHED_*` family is incremented by the YARN
    // ApplicationMaster as it launches task attempts. `LocalJobRunner` runs
    // tasks directly in-process and never goes through an ApplicationMaster,
    // so nothing increments them. `TaskCounter` values come from each task's
    // own counters and are reported regardless.
    //
    // Kept as an assertion rather than a comment so the launched-task counter
    // cannot be quietly reinstated. If a future Hadoop starts maintaining it
    // under `LocalJobRunner`, this fails and the choice gets revisited
    // deliberately instead of by accident.
    val launchedReduces = teams.getCounters
      .findCounter(
        "org.apache.hadoop.mapreduce.JobCounter",
        "TOTAL_LAUNCHED_REDUCES"
      )
      .getValue
    val reduceRecords =
      teams.getCounters.findCounter(TaskCounter.REDUCE_INPUT_RECORDS).getValue
    assertEquals(
      launchedReduces,
      0L,
      "LocalJobRunner now maintains TOTAL_LAUNCHED_REDUCES; reconsider which counter proves reduce execution"
    )
    assert(
      reduceRecords > 0L,
      "a reduce task consumed shuffled input, so the zero above is an unmaintained counter rather than an absent reduce phase"
    )
  }

  test("job 2 shuffles three keyed streams into three committed artifacts") {
    val committedTeams = committed(run.artifactPath(Artifact.Teams))
      .map(ContractWire.decode[PublicationTeam])
    val authorPapers = committed(run.artifactPath(Artifact.AuthorPapers))
      .map(ContractWire.decode[AuthorshipRecord])
    val pairs = committed(run.artifactPath(Artifact.PairCandidates))
      .map(ContractWire.decode[CoauthorPairCandidate])

    assertEquals(committedTeams.size, 2)
    // One author plus five authors.
    assertEquals(authorPapers.size, 6)
    // Only the five-author work yields pairs: 5 * 4 / 2.
    assertEquals(pairs.size, 10)
    assertEquals(
      committedTeams.map(_.orderedMentionIds.size).sorted,
      Vector(1, 5)
    )
    // Every pair endpoint resolves to a committed team member.
    val members = committedTeams
      .flatMap(team => team.orderedMentionIds.map(team.workId -> _))
      .toSet
    pairs.foreach { pair =>
      assert(members.contains(pair.workId -> pair.mentionA))
      assert(members.contains(pair.workId -> pair.mentionB))
      assert(pair.mentionA < pair.mentionB)
      assertEquals(pair.year, Some(2017))
      assertEquals(pair.provenance.runId, TestRun.runId)
    }
  }

  test("the pair counters are not double-counted across map and reduce") {
    // Regression guard for a defect this suite's first execution exposed. A
    // Hadoop counter accumulates over both phases of one job, so the mapper and
    // the reducer incrementing one counter reported twice the real pairs. Map
    // expansion and reduce emission are now separate counters, and both must
    // read ten rather than one of them reading twenty.
    assertEquals(counterOf(teams, PipelineCounter.EmittedRecords), 18L)
    assertEquals(counterOf(teams, PipelineCounter.ExpandedPairs), 10L)
    assertEquals(counterOf(teams, PipelineCounter.EmittedPairs), 10L)
    assertEquals(counterOf(teams, PipelineCounter.SingleAuthorWorks), 1L)
    assertEquals(counterOf(teams, PipelineCounter.DuplicateRecords), 0L)
    assertEquals(counterOf(teams, PipelineCounter.SuppressedPairs), 0L)
    assertEquals(counterOf(teams, PipelineCounter.OversizeAuthorTeams), 0L)
  }

  test("rerunning a committed stage fails rather than overwriting it") {
    // Executed evidence for the F4 correction. The output prefix is a pure
    // function of the run ID and snapshot digest, which makes it reproducible
    // addressing — not overwrite semantics. Hadoop refuses an existing output
    // directory at submission, so the documented rerun policy is to delete the
    // stage directory or use a new run ID.
    assert(extraction.isSuccessful)
    val repeat = localize(
      DblpExtractionJob.configure(run, Vector(inputDirectory.toString))
    )
    val failure = intercept[Exception](repeat.waitForCompletion(true))
    val rendered = s"${failure.getClass.getName}: ${failure.getMessage}"
    assert(
      rendered.contains("FileAlreadyExists") ||
        rendered.toLowerCase.contains("already exists"),
      s"expected an existing-output refusal, got $rendered"
    )
  }

  test("a new run ID writes a separate prefix over the same input") {
    // The other half of the rerun policy: a fresh run ID is always safe,
    // because the prefix carries it. Evidence that the two runs do not collide.
    val second = run.copy(runId = TestRun.secondRunId)
    assertNotEquals(
      second.artifactPath(Artifact.DblpWorks),
      run.artifactPath(Artifact.DblpWorks)
    )
    val job = localize(
      DblpExtractionJob.configure(second, Vector(inputDirectory.toString))
    )
    assert(job.waitForCompletion(true), "the second run did not complete")
    val works = committed(second.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
    assertEquals(works.size, 2)
    assert(works.forall(_.provenance.runId == TestRun.secondRunId))
    // The first run's output is untouched.
    assertEquals(committed(run.artifactPath(Artifact.DblpWorks)).size, 2)
  }

  test("the two runs agree on everything except the run identity") {
    // Determinism across two genuinely separate Hadoop executions, which the
    // in-process suite cannot demonstrate.
    val second = run.copy(runId = TestRun.secondRunId)
    val first = committed(run.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
      .sortBy(_.sourceRecordId)
    val other = committed(second.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
      .sortBy(_.sourceRecordId)
    assertEquals(other.size, first.size)
    first.zip(other).foreach { case (left, right) =>
      assertEquals(right.workId, left.workId)
      assertEquals(right.normalizedTitle, left.normalizedTitle)
      assertEquals(
        right.authors.map(_.mentionId),
        left.authors.map(_.mentionId)
      )
      assertEquals(right.year, left.year)
      assertNotEquals(right.provenance.runId, left.provenance.runId)
    }
  }

  test(
    "malformed records reject without failing the task and native conflicts remain visible"
  ) {
    val input = workspace.resolve("repair-input.xml")
    val good =
      """<article key="repair/good"><author>Ann</author><title>Good</title></article>"""
    val conflict = good.replace("Good", "Different")
    // A name that folds to nothing is still a schema failure. A CJK name used
    // to be the example, but non-Latin names now fold to a non-empty key (F2)
    // and are accepted; a lone combining mark is the remaining empty-key case.
    val unicode =
      "<article key=\"repair/unicode\"><author>́</author><title>Good</title></article>"
    val quoted =
      """<article key="repair/quoted" note="a>b"><title>Quoted</title></article>"""
    val invalid =
      """<article key="repair/bytes"><title>Bad""".getBytes(UTF_8) ++
        Array(0xc3.toByte, 0x28.toByte) ++ "</title></article>".getBytes(UTF_8)
    val torn = """<article key="repair/torn"><title>Unfinished"""
    Files.write(
      input,
      (good + conflict + unicode + quoted).getBytes(UTF_8) ++ invalid ++ torn
        .getBytes(UTF_8)
    )
    val repairRun =
      run.copy(outputRoot = workspace.resolve("repair-curated").toString)
    val job =
      localize(DblpExtractionJob.configure(repairRun, Vector(input.toString)))
    assert(job.waitForCompletion(true))
    assertEquals(counterOf(job, PipelineCounter.InputRecords), 6L)
    assertEquals(counterOf(job, PipelineCounter.AcceptedRecords), 3L)
    assertEquals(counterOf(job, PipelineCounter.DuplicateRecords), 1L)
    assertEquals(counterOf(job, PipelineCounter.ConflictRecords), 1L)
    assertEquals(counterOf(job, PipelineCounter.RejectedRecords), 4L)
    assertEquals(counterOf(job, PipelineCounter.SchemaFailureRecords), 2L)
    val works = committed(repairRun.artifactPath(Artifact.DblpWorks))
      .map(ContractWire.decode[WorkRecord])
    assertEquals(works.size, 2)
    assert(
      works.forall(work =>
        !work.title.contains('\u0000') && !work.title.contains('\ufffd')
      )
    )
    val rejects = Vector(
      RejectReason.MalformedXml,
      RejectReason.MissingRequiredField,
      RejectReason.IdentifierConflict
    )
      .flatMap(reason =>
        committed(
          s"${repairRun.rejectPath(Stage.dblpExtraction)}/${reason.code}"
        )
      )
      .map(ContractWire.decode[RejectRecord])
    assertEquals(rejects.size, 4)
  }
