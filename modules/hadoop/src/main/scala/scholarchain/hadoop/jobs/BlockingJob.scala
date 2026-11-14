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

/** Job 4: researcher and work blocking.
  *
  * The reducer is where the pathological-block defence lives. It streams the
  * values rather than materializing the whole group first, so a hot key is
  * counted and truncated instead of exhausting the reducer's heap on the way to
  * discovering that it was too big.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text workJson)`
  *   - map out: `(Text "<stream>\u0001<blockKey>", Text memberJson)`
  *   - reduce in: `(Text blockKey, Iterable[Text] memberJson)`
  *   - reduce out: `(NullWritable, Text)` to `blocking-groups` and
  *     `blocking-members`
  */
object BlockingJob:
  val stage = Stage.blocking

  val streams: Map[String, Artifact] = Map(
    TaskSink.blockingGroups -> Artifact.BlockingGroups,
    TaskSink.blockingMembers -> Artifact.BlockingMembers
  )

  class BlockingMapper extends Mapper[LongWritable, Text, Text, Text]:
    private var run: RunContext = null
    private var sink: MapOutputSink[LongWritable, Text] = null

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
        WorkRecords.decode(line) match
          case Right(work) =>
            sink.count(PipelineCounter.AcceptedRecords)
            Blocking.emit(work, sink, run)
          case Left(detail) =>
            sink.count(PipelineCounter.SchemaFailureRecords)
            sink.reject(
              RejectRecord(
                stage = stage,
                reason = RejectReason.SchemaViolation,
                detail = detail,
                sourceRecordId = s"works:offset/${key.get()}",
                payload = line,
                provenance = run.provenance(
                  SourceType.Derived,
                  s"works:offset:${key.get()}",
                  s"scholarchain:works#offset=${key.get()}",
                  run.datasetSnapshots.keys.toVector.sorted.head
                )
              )
            )

  /** Bounds each block, records its observed size, and passes the retained
    * members on to the candidate stage.
    *
    * The members are read into a bounded buffer: the reducer keeps at most
    * `maxBlockSize` members plus a running count, so the observed size of a
    * pathological block is reported accurately without the block ever being
    * held in memory in full.
    */
  class BoundingReducer extends Reducer[Text, Text, NullWritable, Text]:
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
      else bound(blockKey, values)

    private def bound(
        blockKey: String,
        values: java.lang.Iterable[Text]
    ): Unit =
      // Selection by ordering, not arrival-order truncation, so the retained
      // subset of an oversize block is a function of its content alone.
      val selector = new BoundedBlockSelector(run.maxBlockSize)
      values.asScala.foreach(value =>
        selector.add(ContractWire.decode[BlockMember](value.toString))
      )
      // `boundSelected` reads both figures off the selector, so this reducer
      // no longer overrides the group it was handed. Two definitions of
      // `observedSize` previously disagreed here.
      val (group, members) = Blocking.boundSelected(blockKey, selector, run)
      val observed = selector.observed
      if observed > run.maxBlockSize then
        sink.count(PipelineCounter.OversizeBlocks)
        sink.reject(
          RejectRecord(
            stage = stage,
            reason = RejectReason.OversizeBlock,
            detail =
              s"Block $blockKey holds $observed records, above the bound of ${run.maxBlockSize}; retained ${members.size} distinct members deterministically",
            sourceRecordId = blockKey,
            payload = members.map(_.memberId).mkString(","),
            provenance = run.provenance(
              SourceType.Derived,
              blockKey,
              s"scholarchain:block/$blockKey",
              run.datasetSnapshots.keys.toVector.sorted.head
            )
          )
        )
      // A block of one cannot yield a candidate pair, so it is not carried
      // forward. Emitting it would cost a shuffle for no comparison.
      if members.size > 1 then
        // `emit`, not `emitRecord`: the mapper already counted these records
        // under `EmittedRecords`, and one Hadoop counter spans both phases.
        sink.emit(
          TaskSink.blockingGroups,
          blockKey,
          ContractWire.encode(group)
        )
        members.foreach(member =>
          sink.emit(
            TaskSink.blockingMembers,
            blockKey,
            ContractWire.encode(BlockEnvelope(blockKey, member))
          )
        )

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
    job.setJarByClass(classOf[BlockingMapper])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[BlockingMapper])
    job.setReducerClass(classOf[BoundingReducer])
    JobSupport.configureOutput(job, run, stage, streams)
    job

class BlockingTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <worksInput>...")
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val job =
      BlockingJob.configure(context, arguments.drop(1).toVector, getConf)
    if job.waitForCompletion(true) then 0 else 1

object BlockingTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new BlockingTool, arguments)
    )
