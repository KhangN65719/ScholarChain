package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import munit.FunSuite
import scholarchain.hadoop.jobs.JobConfig
import scholarchain.hadoop.pipeline.RunContext

/** The first tests to touch the `jobs` package.
  *
  * Every other suite in this module exercises the Hadoop-free transformation
  * layer. These reach the job-wiring classes, which until now were compiled and
  * never executed.
  *
  * **Classpath history, now resolved.** Run 4 answered the `Provided` question
  * in two parts: `Test/compile` succeeded, so `Provided` is on the Test compile
  * classpath and job-class tests are writable; but every case here constructs
  * `Configuration`, which failed to class-load with `NoClassDefFoundError:
  * org/apache/hadoop/shaded/com/ctc/wstx/io/InputBootstrapper` — the shaded
  * Woodstox parser `Configuration` needs to read XML resources, and which even
  * `new Configuration(false)` requires. That was a missing Test-scope
  * dependency, not a defect here. `build-engineer` added
  * `hadoop-client-runtime % Test`, and run 6 passed this suite 5 of 5.
  *
  * Recorded because the failure mode is worth remembering: a
  * `NoClassDefFoundError` is a `LinkageError`, which is fatal rather than an
  * ordinary test failure, so it aborted the whole suite instead of failing one
  * case. That is why run 4 reported one failure and seven skips rather than
  * eight failures.
  *
  * What these tests still cannot establish: that any job *executes*. They
  * construct and interrogate configuration, not jobs. Execution evidence lives
  * in `MapReduceEndToEndSuite`.
  */
class JobWiringSuite extends FunSuite:
  private val run = TestRun.context()

  /** `loadDefaults = false` so no `core-default.xml` or `core-site.xml` lookup
    * takes place. The round-trip under test is this module's own keys.
    */
  private def emptyConfiguration(): Configuration = new Configuration(false)

  test("a run context survives a job configuration round-trip") {
    // This is the only channel into a task, so a value lost here is a value a
    // mapper silently runs without.
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(restored.runId, run.runId)
    assertEquals(restored.transformVersion, run.transformVersion)
    assertEquals(restored.datasetSnapshots, run.datasetSnapshots)
    assertEquals(restored.outputRoot, run.outputRoot)
    assertEquals(restored.partitionCount, run.partitionCount)
    assertEquals(restored.maxBlockSize, run.maxBlockSize)
    assertEquals(restored.maxAuthorTeamSize, run.maxAuthorTeamSize)
    assertEquals(restored.maxPairsPerWork, run.maxPairsPerWork)
    assertEquals(restored, run)
  }

  test("the round-trip preserves a non-default bound") {
    val custom = TestRun.context(
      partitionCount = 9,
      maxBlockSize = 11,
      maxAuthorTeamSize = 13,
      maxPairsPerWork = 17
    )
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, custom)
    assertEquals(JobConfig.read(configuration), custom)
  }

  test("snapshots round-trip as a sorted dataset list") {
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    assertEquals(
      configuration.get(JobConfig.snapshots),
      run.datasetSnapshots.toVector.sorted
        .map { case (name, version) => s"$name=$version" }
        .mkString(",")
    )
    assertEquals(
      JobConfig.readSnapshots(configuration),
      RunContext.localSnapshots
    )
  }

  test("an absent or malformed snapshot list yields no snapshots") {
    assertEquals(JobConfig.readSnapshots(emptyConfiguration()), Map.empty)
    val blank = emptyConfiguration()
    blank.set(JobConfig.snapshots, "   ")
    assertEquals(JobConfig.readSnapshots(blank), Map.empty)
    val malformed = emptyConfiguration()
    malformed.set(JobConfig.snapshots, "nodelimiter,=noname,dblp=")
    assertEquals(JobConfig.readSnapshots(malformed), Map.empty)
  }

  test("a configuration missing the run identity is refused, not defaulted") {
    // Reading a half-configured job must fail loudly: a task that silently
    // defaulted its run ID would write provenance naming a run that never ran.
    val onlySnapshots = emptyConfiguration()
    onlySnapshots.set(JobConfig.snapshots, "dblp=2026-11-01")
    intercept[IllegalStateException](JobConfig.read(onlySnapshots))
    intercept[IllegalArgumentException](JobConfig.read(emptyConfiguration()))
  }

// The three `StableKeyPartitioner` cases live in `PartitionerWiringSuite`.
// They need no `Configuration`, so keeping them here only made them collateral
// of this suite's abort when `Configuration` failed to class-load.
