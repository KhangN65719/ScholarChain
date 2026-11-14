package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractWire, SourceType}
import scholarchain.hadoop.pipeline.*

/** Job 5: candidate feature construction.
  *
  * Joins the members of each bounded block and emits one [[MatchCandidate]] per
  * comparable pair. The job emits features and identifier agreement, and no
  * decision: the accept/reject policy belongs to entity resolution, which reads
  * this artifact.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text blockEnvelopeJson)`
  *   - map out: `(Text "<stream>\u0001<blockKey>", Text memberJson)`
  *   - reduce in: `(Text blockKey, Iterable[Text] memberJson)`
  *   - reduce out: `(NullWritable, Text candidateJson)`
  */
object CandidateFeatureJob:
  val stage = Stage.candidateFeatures

  val streams: Map[String, Artifact] = Map(
    TaskSink.matchCandidates -> Artifact.MatchCandidates
  )

  class RegroupMapper extends Mapper[LongWritable, Text, Text, Text]:
    private var sink: MapOutputSink[LongWritable, Text] = null
    private var run: RunContext = null

    override def setup(
        context: Mapper[LongWritable, Text, Text, Text]#Context
    ): Unit =
      run = JobConfig.read(context.getConfiguration)
      sink = new MapOutputSink(context)

    override def map(
        key: LongWritable,
        value: Text,
        context: Mapper[LongWritable, Text, Text, Text]#Context
    ): Unit =
      val line = value.toString.trim
      if line.nonEmpty then
        sink.count(PipelineCounter.InputRecords)
        try
          val envelope = ContractWire.decode[BlockEnvelope](line)
          sink.count(PipelineCounter.AcceptedRecords)
          sink.emitRecord(
            TaskSink.blockingMembers,
            envelope.blockKey,
            envelope.member
          )
        catch
          case error: Exception =>
            sink.count(PipelineCounter.SchemaFailureRecords)
            sink.reject(
              RejectRecord(
                stage = stage,
                reason = RejectReason.SchemaViolation,
                detail = Option(error.getMessage)
                  .getOrElse(error.getClass.getSimpleName),
                sourceRecordId = s"blocking-members:offset/${key.get()}",
                payload = line,
                provenance = run.provenance(
                  SourceType.Derived,
                  s"blocking-members:offset:${key.get()}",
                  s"scholarchain:blocking-members#offset=${key.get()}",
                  run.datasetSnapshots.keys.toVector.sorted.head
                )
              )
            )

  class FeatureReducer extends Reducer[Text, Text, NullWritable, Text]:
    private var run: RunContext = null
    private var sink: ArtifactSink[Text, Text] = null

    override def setup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      run = JobConfig.read(context.getConfiguration)
      sink = new ArtifactSink(context, streams, stage)

    override def reduce(
        key: Text,
        values: java.lang.Iterable[Text],
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      val (label, blockKey) = StreamKey.decode(key.toString)
      // The reject guard must precede the decode: a reject's JSON would throw
      // if read as a `BlockMember`.
      if label == RejectRecord.stream then
        values.asScala.foreach(value =>
          sink.commitReject(blockKey, value.toString)
        )
      else compare(blockKey, values)

    private def compare(
        blockKey: String,
        values: java.lang.Iterable[Text]
    ): Unit =
      // The upstream job already bounded this block, so the group fits in
      // memory by construction. The bound is re-applied rather than trusted,
      // because this job can also be run directly against a hand-built input.
      val selector = new BoundedBlockSelector(run.maxBlockSize)
      values.asScala.foreach(value =>
        selector.add(ContractWire.decode[BlockMember](value.toString))
      )
      if selector.observed > run.maxBlockSize then
        sink.count(PipelineCounter.OversizeBlocks)
      val members = selector.retained
      val candidates = MatchFeatures.candidates(blockKey, members, run)
      candidates.foreach { candidate =>
        if candidate.exactIdentifierConflicts.nonEmpty then
          sink.count(
            PipelineCounter.ConflictRecords,
            candidate.exactIdentifierConflicts.size.toLong
          )
        // `emit`, not `emitRecord`: the mapper already counted the block
        // members under `EmittedRecords`, and one Hadoop counter spans both
        // phases of a job.
        sink.emit(
          TaskSink.matchCandidates,
          s"${candidate.leftId}|${candidate.rightId}",
          ContractWire.encode(candidate)
        )
      }

    override def cleanup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink.close()

  def configure(
      run: RunContext,
      inputs: Vector[String],
      base: Configuration = new Configuration
  ): Job =
    val configuration = new Configuration(base)
    JobConfig.write(configuration, run)
    val job = Job.getInstance(configuration, s"scholarchain-$stage")
    job.setJarByClass(classOf[RegroupMapper])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[RegroupMapper])
    job.setReducerClass(classOf[FeatureReducer])
    JobSupport.configureOutput(job, run, stage, streams)
    job

class CandidateFeatureTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <blockingMembers>...")
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val job = CandidateFeatureJob.configure(
      context,
      arguments.drop(1).toVector,
      getConf
    )
    if job.waitForCompletion(true) then 0 else 1

object CandidateFeatureTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new CandidateFeatureTool, arguments)
    )
