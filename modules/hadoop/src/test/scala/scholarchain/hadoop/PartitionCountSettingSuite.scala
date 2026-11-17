package scholarchain.hadoop

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import scholarchain.hadoop.jobs.*
import scholarchain.hadoop.pipeline.{Artifact, RunContext}

/** Phase 15 experiment B raises the reducer count of every MapReduce stage
  * through the generic option `-Dscholarchain.run.partitionCount=<n>` and
  * changes no algorithm. These cases prove the setting is reachable from the
  * tool configuration, reaches every stage's reduce count, leaves the default
  * (4) unchanged, and that a larger value runs the fixture to the same records.
  */
class PartitionCountSettingSuite extends FunSuite:
  private val key = JobConfig.partitionCount

  private def toolConfiguration(partitions: Option[Int]): Configuration =
    val configuration = new Configuration(false)
    configuration.set(JobConfig.runId, TestRun.runId)
    partitions.foreach(count => configuration.setInt(key, count))
    configuration

  private def contextFor(partitions: Option[Int], root: String): RunContext =
    PipelineOptions.runContext(toolConfiguration(partitions), root)

  test("the setting name is the documented generic option") {
    assertEquals(key, "scholarchain.run.partitionCount")
  }

  test("the default partition count stays 4 when the option is absent") {
    assertEquals(contextFor(None, "out").partitionCount, 4)
  }

  test("the generic option sets the partition count of the run context") {
    assertEquals(contextFor(Some(16), "out").partitionCount, 16)
  }

  test("the tool plan carries the option into the run context") {
    val configuration = toolConfiguration(Some(12))
    configuration.set(
      JobConfig.snapshots,
      "dblp=2026-11-05,openalex=2026-11-07"
    )
    val plan = PipelineTool.plan(
      configuration,
      Array("out", "dblp.xml", "openalex=o.jsonl"),
      System.out,
      System.err
    )
    assertEquals(plan.context.partitionCount, 12)
  }

  private def stages(run: RunContext): Vector[(String, Job)] = Vector(
    "dblp-extraction" ->
      DblpExtractionJob.configure(run, Vector("in")),
    "source-normalization" ->
      SourceNormalizationJob.configure(run, "openalex", Vector("in")),
    "identifier-join" ->
      IdentifierJoinJob.configure(run, Vector("in")),
    "blocking" -> BlockingJob.configure(run, Vector("in")),
    "candidate-features" ->
      CandidateFeatureJob.configure(run, Vector("in")),
    "team-expansion" ->
      PublicationTeamJob.configure(run, Vector("in")),
    "graph-export" ->
      GraphExportJob.configure(
        run,
        Vector(
          GraphExportJob
            .TypedInput("in", classOf[GraphExportJob.WorkExportMapper])
        )
      )
  )

  test("every stage reduces with the default count without the option") {
    stages(contextFor(None, workspace.resolve("wiring-default").toString))
      .foreach { case (name, job) =>
        assertEquals(job.getNumReduceTasks, 4, s"$name default reduce count")
      }
  }

  test("every stage reduces with the configured count") {
    stages(
      contextFor(Some(16), workspace.resolve("wiring-sixteen").toString)
    ).foreach { case (name, job) =>
      assertEquals(job.getNumReduceTasks, 16, s"$name reduce count")
      assertEquals(
        JobConfig.read(job.getConfiguration).partitionCount,
        16,
        s"$name run context"
      )
    }
  }

  private lazy val workspace: Path =
    Files.createTempDirectory("scholarchain-partition-count-")

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
    job

  private lazy val inputDirectory: Path =
    val directory = Files.createDirectories(workspace.resolve("input"))
    Files.copy(
      TestRun.samples.resolve("dblp.xml"),
      directory.resolve("dblp.xml")
    )
    directory

  /** Every part file under the DBLP works directory as name -> bytes. */
  private def worksBytes(run: RunContext): Map[String, Vector[Byte]] =
    val directory = Path.of(run.artifactPath(Artifact.DblpWorks))
    val listing = Files.list(directory)
    try
      listing
        .iterator()
        .asScala
        .toVector
        .filter(entry => entry.getFileName.toString.startsWith("part-"))
        .map(entry =>
          entry.getFileName.toString -> Files.readAllBytes(entry).toVector
        )
        .toMap
    finally listing.close()

  private def runExtraction(partitions: Option[Int], name: String): RunContext =
    val run =
      contextFor(partitions, workspace.resolve(name).toString)
    val job =
      localize(
        DblpExtractionJob.configure(run, Vector(inputDirectory.toString))
      )
    assert(job.waitForCompletion(true), s"$name did not complete")
    run

  test("the default run and an explicit 4 write byte-identical output") {
    val absent = worksBytes(runExtraction(None, "default"))
    val explicit = worksBytes(runExtraction(Some(4), "explicit-four"))
    assert(absent.nonEmpty, "the default run wrote no works")
    assertEquals(explicit, absent)
  }

  test("a larger partition count runs the fixture to the same records") {
    val baseline = worksBytes(runExtraction(Some(4), "baseline"))
    val larger = runExtraction(Some(16), "larger")
    val records = worksBytes(larger).values
      .flatMap(bytes => new String(bytes.toArray, "UTF-8").linesIterator)
      .filter(_.trim.nonEmpty)
      .toVector
      .sorted
    val expected = baseline.values
      .flatMap(bytes => new String(bytes.toArray, "UTF-8").linesIterator)
      .filter(_.trim.nonEmpty)
      .toVector
      .sorted
    assert(expected.nonEmpty, "the baseline wrote no works")
    assertEquals(records, expected)
  }
