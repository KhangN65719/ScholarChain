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
  /** Fails fast when the snapshots a run would cite do not match its inputs.
    *
    * Snapshots are supplied as a Hadoop generic option, for example
    * `-Dscholarchain.run.snapshots=dblp=2026-11-05,openalex=2026-11-07`.
    *
    *   - Nothing configured: the local defaults apply and must cover every
    *     dataset, so a dataset without a default is rejected here instead of at
    *     the first record.
    *   - Anything configured: the set must name exactly the supplied datasets.
    *     A missing dataset would otherwise fall back to a default or fail late,
    *     and an extra one would pin a snapshot nothing read. A malformed or
    *     duplicated entry is an error too, never silently dropped to defaults.
    */
  def validateSnapshots(
      configuration: Configuration,
      datasets: Set[String]
  ): Unit =
    val raw = Option(configuration.get(JobConfig.snapshots))
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
      .flatMap(_.split(",", -1).toVector.map(_.trim))
    if raw.isEmpty then
      val unpinned = datasets.diff(RunContext.localSnapshots.keySet)
      require(
        unpinned.isEmpty,
        s"No default snapshot for dataset(s) ${unpinned.toVector.sorted.mkString(", ")}; " +
          s"set -D${JobConfig.snapshots}=<dataset>=<id>,..."
      )
    else
      val parsed = JobConfig.readSnapshots(configuration)
      require(
        parsed.size == raw.size,
        s"Malformed or duplicate entry in ${JobConfig.snapshots}: ${raw.mkString(",")}"
      )
      val missing = datasets.diff(parsed.keySet)
      val extra = parsed.keySet.diff(datasets)
      require(
        missing.isEmpty,
        s"No snapshot configured for dataset(s) ${missing.toVector.sorted.mkString(", ")}"
      )
      require(
        extra.isEmpty,
        s"Snapshot configured for dataset(s) not supplied: ${extra.toVector.sorted.mkString(", ")}"
      )

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
