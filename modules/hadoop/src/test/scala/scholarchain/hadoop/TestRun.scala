package scholarchain.hadoop

import java.nio.file.{Path, Paths}
import scholarchain.hadoop.pipeline.RunContext

/** Fixed run parameters for the module's tests.
  *
  * The run ID is a constant rather than `StableIds.newRun()`, because a fresh
  * UUID per test would make every provenance field and every output path differ
  * between runs, which would make the determinism assertions vacuous.
  */
object TestRun:
  val runId = "run:v1:1e7c6f4a-2b55-4d38-9a1f-0c2b7d6e5f40"
  val secondRunId = "run:v1:9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

  def context(
      runIdentifier: String = runId,
      partitionCount: Int = 4,
      maxBlockSize: Int = 50,
      maxAuthorTeamSize: Int = 50,
      maxPairsPerWork: Int = 10000
  ): RunContext =
    RunContext(
      runId = runIdentifier,
      transformVersion = RunContext.transformVersion,
      datasetSnapshots = RunContext.localSnapshots,
      outputRoot = "s3://local-curated-example/curated",
      partitionCount = partitionCount,
      maxBlockSize = maxBlockSize,
      maxAuthorTeamSize = maxAuthorTeamSize,
      maxPairsPerWork = maxPairsPerWork
    )

  private val workingDirectory: Path =
    Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath

  private val repositoryRoot: Path =
    if workingDirectory.endsWith(Paths.get("modules", "hadoop")) then
      workingDirectory.getParent.getParent
    else workingDirectory

  /** The repository's bounded sample directory, resolved from the module base
    * directory so the tests read the checked-in `datasets/samples` fixtures and
    * nothing else. No test in this module opens a network connection or an
    * object store.
    */
  val samples: Path =
    repositoryRoot.resolve("datasets").resolve("samples")
