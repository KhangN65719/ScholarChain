package scholarchain.hadoop

import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import scholarchain.hadoop.jobs.*

/** A generic option on the tool's `Configuration` (`-D`, `-libjars`, `-files`)
  * must reach every stage's `Job`. Each stage used to start from an empty
  * `Configuration`, so only `scholarchain.*` keys survived.
  *
  * These cases build jobs and read their configuration; none executes one.
  * Execution and identical outputs are covered by the end-to-end suites, which
  * call `configure` without a base and so exercise the default.
  */
class ToolConfigurationPropagationSuite extends FunSuite:
  private val run = TestRun
    .context()
    .copy(outputRoot =
      java.nio.file.Files
        .createTempDirectory("scholarchain-propagation-")
        .toString
    )
  private val marker = "scholarchain.test.marker"
  private val tmpJars = "tmpjars"
  private val queue = "mapreduce.job.queuename"

  private def base(): Configuration =
    val configuration = new Configuration(false)
    configuration.set(marker, "propagated")
    configuration.set(tmpJars, "file:/lib/a.jar,file:/lib/b.jar")
    configuration.set(queue, "ingest")
    configuration

  private val stages: Vector[(String, Configuration => Job)] = Vector(
    "dblp-extraction" -> (c =>
      DblpExtractionJob.configure(run, Vector("in"), c)
    ),
    "source-normalization" -> (c =>
      SourceNormalizationJob.configure(run, "openalex", Vector("in"), c)
    ),
    "identifier-join" -> (c =>
      IdentifierJoinJob.configure(run, Vector("in"), c)
    ),
    "blocking" -> (c => BlockingJob.configure(run, Vector("in"), c)),
    "candidate-features" -> (c =>
      CandidateFeatureJob.configure(run, Vector("in"), c)
    ),
    "team-expansion" -> (c =>
      PublicationTeamJob.configure(run, Vector("in"), c)
    ),
    "graph-export" -> (c =>
      GraphExportJob.configure(
        run,
        Vector(
          GraphExportJob
            .TypedInput("in", classOf[GraphExportJob.WorkExportMapper])
        ),
        c
      )
    )
  )

  stages.foreach { case (name, configure) =>
    test(s"$name carries generic options from the tool configuration") {
      val job = configure(base())
      assertEquals(job.getConfiguration.get(marker), "propagated")
      assertEquals(
        job.getConfiguration.get(tmpJars),
        "file:/lib/a.jar,file:/lib/b.jar"
      )
      assertEquals(job.getConfiguration.get(queue), "ingest")
    }

    test(s"$name still writes the run context over the base") {
      val configuration = base()
      configuration.set(JobConfig.runId, "run:v1:stale")
      val job = configure(configuration)
      assertEquals(JobConfig.read(job.getConfiguration), run)
    }

    test(s"$name does not mutate the tool configuration") {
      val configuration = base()
      configure(configuration)
      assertEquals(configuration.get(JobConfig.runId), null)
    }
  }

  test("the default base leaves no generic option set") {
    val job = BlockingJob.configure(run, Vector("in"))
    assertEquals(job.getConfiguration.get(marker), null)
    assertEquals(JobConfig.read(job.getConfiguration), run)
  }
