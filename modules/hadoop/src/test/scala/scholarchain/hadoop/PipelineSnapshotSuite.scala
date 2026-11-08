package scholarchain.hadoop

import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.GenericOptionsParser
import scholarchain.core.{ContractWire, WorkRecord}
import scholarchain.core.pairing.MatchCandidate
import scholarchain.hadoop.jobs.{JobConfig, PipelineOptions}
import scholarchain.hadoop.pipeline.*

/** Phase 5 provenance repair: the snapshot IDs a run is launched with, not the
  * baked-in defaults, are what every record cites.
  *
  * The subset derives from the DBLP release 2026-11-05 and was enriched on
  * 2026-11-07, so a v4 run that cited `dblp:2026-11-01` and
  * `openalex:2026-10-14` misattributed its inputs.
  */
class PipelineSnapshotSuite extends FunSuite:
  private val pinned = Map(
    "dblp" -> "2026-11-05",
    "openalex" -> "2026-11-07",
    "crossref" -> "2026-11-07",
    "orcid" -> "2026-11-07"
  )

  private val setting =
    "dblp=2026-11-05,openalex=2026-11-07,crossref=2026-11-07,orcid=2026-11-07"

  private val flag = s"-D${JobConfig.snapshots}=$setting"

  private val run = TestRun.context().copy(datasetSnapshots = pinned)

  private lazy val inputs = LocalPipelineRunner.readLocal(TestRun.samples)

  private lazy val observations =
    LocalPipelineRunner.runAll(inputs._1, inputs._2, run)

  private def configured(value: String): Configuration =
    val configuration = new Configuration(false)
    configuration.set(JobConfig.snapshots, value)
    configuration

  test("the command-line -D flag reaches the run context") {
    val configuration = new Configuration(false)
    val parser = new GenericOptionsParser(
      configuration,
      Array(flag, "out", "dblp.xml")
    )
    assertEquals(parser.getRemainingArgs.toVector, Vector("out", "dblp.xml"))
    assertEquals(
      PipelineOptions.runContext(configuration, "out").datasetSnapshots,
      pinned
    )
  }

  test("snapshot flows into the DBLP work records") {
    val dblpWorks = observations
      .stage(Stage.dblpExtraction)
      .records(TaskSink.works)
      .map(ContractWire.decode[WorkRecord])
    assert(dblpWorks.nonEmpty)
    dblpWorks.foreach(work =>
      assertEquals(work.provenance.snapshotId, "dblp:2026-11-05")
    )
  }

  test("snapshot flows into the normalized enrichment records") {
    Vector("openalex", "crossref", "orcid").foreach { dataset =>
      val stage = observations.stage(s"${Stage.sourceNormalization}/$dataset")
      val records =
        stage.records(TaskSink.works) ++ stage.records(TaskSink.researchers)
      assert(records.nonEmpty, dataset)
      records.foreach { value =>
        assert(value.contains(s""""$dataset:2026-11-07""""), s"$dataset $value")
        assert(!value.contains("2026-10-14"), dataset)
        assert(!value.contains("2026-10-13-v1"), dataset)
        assert(!value.contains("2026-03\""), dataset)
      }
    }
  }

  test("snapshot flows into the match-candidate provenance") {
    val candidates = observations
      .stage(Stage.candidateFeatures)
      .records(TaskSink.matchCandidates)
      .map(ContractWire.decode[MatchCandidate])
    assert(candidates.nonEmpty)
    candidates.foreach { candidate =>
      val snapshot = candidate.provenance.snapshotId
      val dataset = snapshot.takeWhile(_ != ':')
      assertEquals(snapshot, s"$dataset:${pinned(dataset)}")
    }
    assert(
      candidates.exists(_.provenance.snapshotId == "dblp:2026-11-05"),
      "a DBLP-led candidate should cite the 2026-11-05 release"
    )
  }

  test("a different snapshot set changes the digest and the output path") {
    val defaults = TestRun.context()
    assertNotEquals(run.snapshotId, defaults.snapshotId)
    assert(run.runPath.contains(s"snapshot=${run.snapshotId}"))
    assertNotEquals(run.runPath, defaults.runPath)
  }

  test("the default path is unchanged when no snapshots are configured") {
    val context = PipelineOptions.runContext(new Configuration(false), "out")
    assertEquals(context.datasetSnapshots, RunContext.localSnapshots)
    // The digest of the defaults, as recorded in the v4 output path.
    assertEquals(context.snapshotId, "26a3ad334ddf")
  }

  test("an explicit snapshot set must cover exactly the supplied datasets") {
    val datasets = Set("dblp", "openalex", "crossref", "orcid")
    PipelineOptions.validateSnapshots(configured(setting), datasets)
  }

  test("a supplied dataset without a snapshot is an error") {
    val missing = configured("dblp=2026-11-05,openalex=2026-11-07")
    val error = intercept[IllegalArgumentException] {
      PipelineOptions.validateSnapshots(
        missing,
        Set("dblp", "openalex", "crossref")
      )
    }
    assert(error.getMessage.contains("crossref"), error.getMessage)
  }

  test("a snapshot for a dataset that was not supplied is an error") {
    val extra = configured(
      "dblp=2026-11-05,openalex=2026-11-07,orcid=2026-11-07"
    )
    val error = intercept[IllegalArgumentException] {
      PipelineOptions.validateSnapshots(extra, Set("dblp", "openalex"))
    }
    assert(error.getMessage.contains("orcid"), error.getMessage)
  }

  test("a malformed or duplicated snapshot entry is an error, not a default") {
    Vector("dblp", "dblp=", "dblp=a,dblp=b").foreach { value =>
      intercept[IllegalArgumentException] {
        PipelineOptions.validateSnapshots(configured(value), Set("dblp"))
      }
    }
  }

  test("with nothing configured the defaults must cover the datasets") {
    val none = new Configuration(false)
    PipelineOptions.validateSnapshots(none, Set("dblp", "openalex"))
    intercept[IllegalArgumentException] {
      PipelineOptions.validateSnapshots(none, Set("dblp", "unknown"))
    }
  }
