package scholarchain.hadoop

import java.nio.file.{Files, Path}
import scala.concurrent.duration.{Duration, MINUTES}
import munit.FunSuite
import scholarchain.hadoop.jobs.PipelineDriver
import scholarchain.hadoop.pipeline.*

/** Phase 5 review finding 10: `PipelineDriver.run` itself refuses a dataset
  * with no registered normalizer. Before, it fell back to an empty expectation
  * and relied on `PipelineTool.parseExternal` having rejected the name first,
  * which a direct call bypasses.
  */
class PipelineDriverUnknownDatasetSuite extends FunSuite:
  override def munitTimeout: Duration = Duration(5, MINUTES)

  private def freshRun(): RunContext =
    val workspace = Files.createTempDirectory("scholarchain-unknown-")
    RunContext(
      runId = TestRun.runId,
      transformVersion = RunContext.transformVersion,
      datasetSnapshots = RunContext.localSnapshots,
      outputRoot = workspace.resolve("curated").toString,
      partitionCount = 2,
      maxBlockSize = 50,
      maxAuthorTeamSize = 50,
      maxPairsPerWork = 10000
    )

  private def sample(name: String): String =
    TestRun.samples.resolve(name).toString

  test("a direct run with an unknown dataset throws, naming it and the known") {
    val run = freshRun()
    val inputs = PipelineDriver.Inputs(
      Vector(sample("dblp.xml")),
      Map("nonesuch" -> Vector(sample("orcid.jsonl")))
    )
    val error = intercept[IllegalArgumentException](
      PipelineDriver.run(run, inputs)
    )
    val message = error.getMessage
    assert(message.contains("'nonesuch'"), message)
    SourceNormalizers.byDataset.keySet.foreach { known =>
      assert(message.contains(known), s"$known missing from: $message")
    }
    // Refused before any job was configured: no stage wrote anything.
    assert(!Files.exists(Path.of(run.outputRoot)), run.outputRoot)
  }

  test("an unknown dataset beside a known one is still refused") {
    val run = freshRun()
    val inputs = PipelineDriver.Inputs(
      Vector(sample("dblp.xml")),
      Map(
        "orcid" -> Vector(sample("orcid.jsonl")),
        "zzz" -> Vector(sample("orcid.jsonl"))
      )
    )
    val error = intercept[IllegalArgumentException](
      PipelineDriver.run(run, inputs)
    )
    assert(error.getMessage.contains("'zzz'"), error.getMessage)
    assert(!error.getMessage.contains("'orcid'"), error.getMessage)
    assert(!Files.exists(Path.of(run.outputRoot)), run.outputRoot)
  }

  test("every registered dataset passes the check") {
    PipelineDriver.requireKnownDatasets(SourceNormalizers.byDataset.keySet)
  }

  test("a known dataset still runs to completion") {
    val run = freshRun()
    val inputs = PipelineDriver.Inputs(
      Vector(sample("dblp.xml")),
      Map("orcid" -> Vector(sample("orcid.jsonl")))
    )
    assertEquals(PipelineDriver.run(run, inputs), 0)
    assert(
      Files.isDirectory(
        Path.of(run.artifactPath(Artifact.NormalizedResearchers, "orcid"))
      )
    )
  }
