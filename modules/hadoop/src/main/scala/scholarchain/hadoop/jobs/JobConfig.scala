package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.DefaultCodec
import org.apache.hadoop.mapreduce.Job
import scholarchain.hadoop.pipeline.RunContext

/** Reads and writes the pipeline's run parameters through the job
  * configuration, which is the only channel that reaches a task.
  *
  * Tasks receive these values and nothing else. In particular no task reads the
  * dataset manifest, the clock or a static mutable holder, so two tasks of the
  * same job cannot disagree about the run they belong to.
  */
object JobConfig:
  val runId = "scholarchain.run.id"
  val transformVersion = "scholarchain.run.transformVersion"
  val snapshots = "scholarchain.run.snapshots"
  val outputRoot = "scholarchain.run.outputRoot"
  val partitionCount = "scholarchain.run.partitionCount"
  val maxBlockSize = "scholarchain.bounds.maxBlockSize"
  val maxAuthorTeamSize = "scholarchain.bounds.maxAuthorTeamSize"
  val maxPairsPerWork = "scholarchain.bounds.maxPairsPerWork"
  val dataset = "scholarchain.input.dataset"
  val recordTags = "scholarchain.input.recordTags"

  def write(configuration: Configuration, run: RunContext): Unit =
    configuration.set(runId, run.runId)
    configuration.set(transformVersion, run.transformVersion)
    configuration.set(
      snapshots,
      run.datasetSnapshots.toVector.sorted
        .map { case (name, version) => s"$name=$version" }
        .mkString(",")
    )
    configuration.set(outputRoot, run.outputRoot)
    configuration.setInt(partitionCount, run.partitionCount)
    configuration.setInt(maxBlockSize, run.maxBlockSize)
    configuration.setInt(maxAuthorTeamSize, run.maxAuthorTeamSize)
    configuration.setInt(maxPairsPerWork, run.maxPairsPerWork)

  /** The pinned dataset snapshots, or an empty map when none are configured.
    * Read separately from [[read]] so a caller that only needs the snapshot set
    * does not also have to supply a run ID and an output root.
    */
  def readSnapshots(configuration: Configuration): Map[String, String] =
    Option(configuration.get(snapshots))
      .filter(_.trim.nonEmpty)
      .toVector
      .flatMap(_.split(",").toVector)
      .flatMap { entry =>
        entry.split("=", 2) match
          case Array(name, version) if name.nonEmpty && version.nonEmpty =>
            Some(name -> version)
          case _ => None
      }
      .toMap

  def read(configuration: Configuration): RunContext =
    val pinned = readSnapshots(configuration)
    require(pinned.nonEmpty, s"Job configuration is missing $snapshots")
    RunContext(
      runId = requiredValue(configuration, runId),
      transformVersion = Option(configuration.get(transformVersion))
        .getOrElse(RunContext.transformVersion),
      datasetSnapshots = pinned,
      outputRoot = requiredValue(configuration, outputRoot),
      partitionCount = configuration.getInt(partitionCount, 4),
      maxBlockSize = configuration.getInt(maxBlockSize, 50),
      maxAuthorTeamSize = configuration.getInt(maxAuthorTeamSize, 50),
      maxPairsPerWork = configuration.getInt(maxPairsPerWork, 10000)
    )

  private def requiredValue(configuration: Configuration, key: String): String =
    Option(configuration.get(key))
      .filter(_.trim.nonEmpty)
      .getOrElse(
        throw new IllegalStateException(s"Job configuration is missing $key")
      )

  /** Intermediate map output is always compressed.
    *
    * `DefaultCodec` (zlib) is chosen over Snappy or LZ4 because it needs no
    * native library: a missing `libsnappy` on one node turns into a task
    * failure, which is a worse trade than zlib's extra CPU for this pipeline's
    * record sizes. The codec is a configuration value, so an EMR run with
    * verified native codecs can override it without a code change.
    */
  def enableIntermediateCompression(job: Job): Unit =
    val configuration = job.getConfiguration
    configuration.setBoolean("mapreduce.map.output.compress", true)
    if configuration.get("mapreduce.map.output.compress.codec") == null then
      configuration.set(
        "mapreduce.map.output.compress.codec",
        classOf[DefaultCodec].getName
      )

  /** Speculative execution is disabled. A speculative attempt that writes
    * through `MultipleOutputs` can leave a committed duplicate behind, which
    * would break the idempotency the output layout is designed for.
    */
  def disableSpeculation(job: Job): Unit =
    job.getConfiguration.setBoolean("mapreduce.map.speculative", false)
    job.getConfiguration.setBoolean("mapreduce.reduce.speculative", false)
