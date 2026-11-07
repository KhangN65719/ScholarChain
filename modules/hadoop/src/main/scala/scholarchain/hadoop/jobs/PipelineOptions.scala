package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.Configuration
import scholarchain.core.StableIds
import scholarchain.hadoop.pipeline.RunContext

/** Builds the [[RunContext]] for a job launched from the command line.
  *
  * A run ID may be supplied so a replay addresses the same output prefix, which
  * is what makes the output reproducible. Reusing a run ID requires the stage
  * directory to have been deleted first: `FileOutputFormat` refuses an existing
  * output directory, so a reuse against surviving output fails at submission
  * rather than replacing it.
  *
  * When no run ID is supplied a fresh one is minted. That is deliberate — a
  * silently reused prefix would either collide with a previous run's evidence
  * or require deleting it, and neither should happen without being asked for.
  */
object PipelineOptions:
  def runContext(configuration: Configuration, outputRoot: String): RunContext =
    val configured = JobConfig.readSnapshots(configuration)
    val snapshots =
      if configured.nonEmpty then configured else RunContext.localSnapshots
    RunContext(
      runId = Option(configuration.get(JobConfig.runId))
        .filter(_.trim.nonEmpty)
        .getOrElse(StableIds.newRun()),
      transformVersion = Option(configuration.get(JobConfig.transformVersion))
        .filter(_.trim.nonEmpty)
        .getOrElse(RunContext.transformVersion),
      datasetSnapshots = snapshots,
      outputRoot = outputRoot,
      partitionCount = configuration.getInt(JobConfig.partitionCount, 4),
      maxBlockSize = configuration.getInt(JobConfig.maxBlockSize, 50),
      maxAuthorTeamSize = configuration.getInt(JobConfig.maxAuthorTeamSize, 50),
      maxPairsPerWork = configuration.getInt(JobConfig.maxPairsPerWork, 10000)
    )
