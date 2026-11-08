package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{Duration, MINUTES}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.core.{CanonicalResearcher, ContractWire}
import scholarchain.hadoop.jobs.{PipelineDriver, SourceNormalizationJob}
import scholarchain.hadoop.pipeline.*

/** Regression suite for the v2 and v3 subset-run failure: the driver listed an
  * input path for every artifact of every dataset, but a dataset writes only
  * the artifacts its records contain (ORCID only researchers, Crossref no
  * topics or institutions, OpenAlex no venues), so identifier-join failed at
  * submission with `InvalidInputException` on `dataset=orcid/works/v1`.
  *
  * The multi-source cases submit real Hadoop jobs under `LocalJobRunner`
  * through `PipelineDriver.run`, with the same four-dataset shape as the real
  * `PipelineTool` command.
  */
class PipelineDriverOutputsSuite extends FunSuite:
  override def munitTimeout: Duration = Duration(15, MINUTES)

  private def freshRun(): (RunContext, Path) =
    val workspace = Files.createTempDirectory("scholarchain-driver-")
    (
      RunContext(
        runId = TestRun.runId,
        transformVersion = RunContext.transformVersion,
        datasetSnapshots = RunContext.localSnapshots,
        outputRoot = workspace.resolve("curated").toString,
        partitionCount = 2,
        maxBlockSize = 50,
        maxAuthorTeamSize = 50,
        maxPairsPerWork = 10000
      ),
      workspace
    )

  private def sample(name: String): String =
    TestRun.samples.resolve(name).toString

  private def realInputs: PipelineDriver.Inputs =
    PipelineDriver.Inputs(
      Vector(sample("dblp.xml")),
      Map(
        "openalex" -> Vector(sample("openalex.jsonl")),
        "crossref" -> Vector(sample("crossref.jsonl")),
        "orcid" -> Vector(sample("orcid.jsonl"))
      )
    )

  private def exists(path: String): Boolean = Files.isDirectory(Path.of(path))

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
          .filter(_.getFileName.toString.startsWith("part-"))
          .sortBy(_.getFileName.toString)
          .flatMap(entry => Files.readAllLines(entry, UTF_8).asScala.toVector)
          .filter(_.trim.nonEmpty)
      finally listing.close()

  // Hand-built, so the path assertions do not depend on a Hadoop run.
  private val observed = PipelineDriver.Outputs(
    dblp = Set(Artifact.DblpWorks, Artifact.DblpVenues),
    external = Map(
      "openalex" -> Set(
        Artifact.NormalizedWorks,
        Artifact.NormalizedResearchers,
        Artifact.NormalizedTopics,
        Artifact.NormalizedInstitutions
      ),
      "crossref" -> Set(Artifact.NormalizedWorks, Artifact.NormalizedVenues),
      "orcid" -> Set(Artifact.NormalizedResearchers)
    )
  )

  test("work paths exclude a dataset that wrote no works") {
    val run = TestRun.context()
    val paths = PipelineDriver.workPaths(run, observed)
    assertEquals(
      paths,
      Vector(
        run.artifactPath(Artifact.DblpWorks),
        run.artifactPath(Artifact.NormalizedWorks, "crossref"),
        run.artifactPath(Artifact.NormalizedWorks, "openalex")
      )
    )
    assert(!paths.exists(_.contains("dataset=orcid")), paths.toString)
  }

  test("export inputs list exactly the artifacts that were written") {
    val run = TestRun.context()
    val inputs = PipelineDriver.exportInputs(run, observed).map(_.path).toSet
    val expected = Set(
      run.artifactPath(Artifact.DblpWorks),
      run.artifactPath(Artifact.NormalizedWorks, "crossref"),
      run.artifactPath(Artifact.NormalizedWorks, "openalex"),
      run.artifactPath(Artifact.NormalizedResearchers, "openalex"),
      run.artifactPath(Artifact.NormalizedResearchers, "orcid"),
      run.artifactPath(Artifact.NormalizedTopics, "openalex"),
      run.artifactPath(Artifact.NormalizedInstitutions, "openalex"),
      run.artifactPath(Artifact.NormalizedVenues, "crossref"),
      run.artifactPath(Artifact.DblpVenues)
    )
    assertEquals(inputs, expected)
    // The DBLP sample has no person records, so there is no DBLP researcher
    // input either; listing one would fail the export the same way.
    assert(!inputs.contains(run.artifactPath(Artifact.DblpResearchers)))
  }

  test("an expected artifact that was not written is reported missing") {
    assertEquals(
      PipelineDriver.missingExpected(
        Set(Artifact.NormalizedWorks),
        Set(Artifact.NormalizedResearchers)
      ),
      Vector(Artifact.NormalizedWorks)
    )
    assertEquals(
      PipelineDriver.missingExpected(
        Set(Artifact.NormalizedWorks),
        Set(Artifact.NormalizedWorks, Artifact.NormalizedVenues)
      ),
      Vector.empty
    )
  }

  test("expected outputs are declared for every normalizer and are real") {
    // Guards drift between the declaration and what each normalizer emits.
    assertEquals(
      SourceNormalizers.expectedOutputs.keySet,
      SourceNormalizers.byDataset.keySet
    )
    val (_, external) = LocalPipelineRunner.readLocal(TestRun.samples)
    SourceNormalizers.expectedOutputs.foreach { case (dataset, expected) =>
      val outcome =
        LocalPipelineRunner.normalize(
          dataset,
          external(dataset),
          TestRun.context()
        )
      val emitted = SourceNormalizationJob.streams.collect {
        case (label, artifact) if outcome.records(label).nonEmpty => artifact
      }.toSet
      assert(
        expected.subsetOf(emitted),
        s"$dataset declares $expected but its sample emits $emitted"
      )
    }
  }

  test("job stream counters report what a normalization job really wrote") {
    val (run, _) = freshRun()
    def writtenBy(dataset: String): Set[Artifact] =
      val job = SourceNormalizationJob.configure(
        run,
        dataset,
        Vector(sample(s"$dataset.jsonl"))
      )
      assert(job.waitForCompletion(true), s"$dataset normalization failed")
      PipelineDriver.written(job, SourceNormalizationJob.streams)
    assertEquals(writtenBy("orcid"), Set(Artifact.NormalizedResearchers))
    // The Crossref sample carries no ORCID authors, so no researchers: an
    // enrichment is data-dependent, which is why it is not in expectedOutputs.
    assertEquals(
      writtenBy("crossref"),
      Set(Artifact.NormalizedWorks, Artifact.NormalizedVenues)
    )
    assert(!exists(run.artifactPath(Artifact.NormalizedWorks, "orcid")))
    assert(!exists(run.artifactPath(Artifact.NormalizedTopics, "crossref")))
  }

  test(
    "dblp, openalex, crossref and orcid flow through all seven stages"
  ) {
    val (run, _) = freshRun()
    assertEquals(PipelineDriver.run(run, realInputs), 0)

    // Source normalization wrote the per-dataset shapes the failure exposed.
    assert(!exists(run.artifactPath(Artifact.NormalizedWorks, "orcid")))
    assert(exists(run.artifactPath(Artifact.NormalizedResearchers, "orcid")))
    assert(!exists(run.artifactPath(Artifact.NormalizedTopics, "crossref")))
    assert(!exists(run.artifactPath(Artifact.DblpResearchers)))

    // Every later stage committed its artifact.
    Vector(Artifact.IdentifierKeys, Artifact.BlockingMembers, Artifact.Teams)
      .foreach { artifact =>
        assert(
          committed(run.artifactPath(artifact)).nonEmpty,
          s"${artifact.segment} is empty"
        )
      }
    assert(committed(run.artifactPath(Artifact.PairCandidates)).nonEmpty)
    assert(committed(run.artifactPath(Artifact.Works)).nonEmpty)
    assert(committed(run.artifactPath(Artifact.Topics)).nonEmpty)
    assert(committed(run.artifactPath(Artifact.Institutions)).nonEmpty)
    assert(committed(run.artifactPath(Artifact.Venues)).nonEmpty)

    // The ORCID-only identities reached the export beside the OpenAlex ones.
    val researchers = committed(run.artifactPath(Artifact.Researchers))
      .map(ContractWire.decode[CanonicalResearcher])
    assert(
      researchers.exists(_.displayName == "Michael Ley"),
      researchers.map(_.displayName).toString
    )
  }

  test("a dataset that should produce works but writes none fails the run") {
    val (run, workspace) = freshRun()
    val broken = workspace.resolve("openalex-broken.jsonl")
    Files.writeString(broken, "not json\n{\"unrelated\": true}\n", UTF_8)
    val inputs = PipelineDriver.Inputs(
      Vector(sample("dblp.xml")),
      Map("openalex" -> Vector(broken.toString))
    )
    assertEquals(PipelineDriver.run(run, inputs), 1)
    // The failure is the driver's, at the normalization stage, before any
    // consumer is configured against a path that nothing wrote.
    assert(!exists(run.stagePath(Stage.identifierJoin)))
    assert(!exists(run.stagePath(Stage.graphExport)))
  }
