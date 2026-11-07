package scholarchain.hadoop.jobs

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import scholarchain.hadoop.pipeline.{Artifact, RejectRecord, RunContext}

/** Shared job wiring: output location, named outputs, partitioning and
  * compression.
  *
  * Centralizing this is what keeps the operational requirements from being
  * satisfied in six jobs and forgotten in the seventh.
  */
object JobSupport:
  /** Configures a job to write the artifacts it owns, plus its reject stream.
    *
    * The job output directory is the stage directory, so a committed artifact
    * lands exactly at `RunContext.artifactPath`. Every job also registers the
    * reject output, because a job with no reject path has nowhere to put a bad
    * record except a log line.
    */
  def configureOutput(
      job: Job,
      run: RunContext,
      stage: String,
      streams: Map[String, Artifact],
      dataset: Option[String] = None
  ): Unit =
    val artifacts = streams.values.toVector
    require(
      artifacts.forall(_.owningStage == stage),
      s"Stage $stage was given an artifact it does not own"
    )
    job.setOutputFormatClass(classOf[TextOutputFormat[NullWritable, Text]])
    job.setOutputKeyClass(classOf[NullWritable])
    job.setOutputValueClass(classOf[Text])
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setPartitionerClass(classOf[StableKeyPartitioner])
    job.setNumReduceTasks(run.partitionCount)
    FileOutputFormat.setOutputPath(
      job,
      new Path(
        dataset.fold(run.stagePath(stage))(name => run.stagePath(stage, name))
      )
    )
    (artifacts.map(_.namedOutput) :+ RejectRecord.stream).distinct.foreach {
      name =>
        MultipleOutputs.addNamedOutput(
          job,
          name,
          classOf[TextOutputFormat[NullWritable, Text]],
          classOf[NullWritable],
          classOf[Text]
        )
    }
    // `MultipleOutputs` writes every record; the default `part-r-*` files would
    // otherwise be committed empty alongside the real artifacts.
    MultipleOutputs.setCountersEnabled(job, true)
    LazyOutput.enable(job)
    JobConfig.enableIntermediateCompression(job)
    JobConfig.disableSpeculation(job)
    JobConfig.write(job.getConfiguration, run)

/** Suppresses the empty default output files that `MultipleOutputs` leaves
  * behind.
  *
  * Kept in one place and guarded, because `LazyOutputFormat` wraps the output
  * format and a second call would wrap the wrapper.
  */
object LazyOutput:
  def enable(job: Job): Unit =
    import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormat
    LazyOutputFormat.setOutputFormatClass(
      job,
      classOf[TextOutputFormat[NullWritable, Text]]
    )
