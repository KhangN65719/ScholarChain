package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractWire, SourceType, WorkRecord}
import scholarchain.hadoop.pipeline.*

/** Job 3: exact identifier extraction and join preparation.
  *
  * This is the job that makes cross-source linkage possible without a fuzzy
  * comparison: a DOI shared by a DBLP `ee` link, an OpenAlex work and a
  * Crossref record lands all three on one reducer.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text workJson)`
  *   - map out:
  *     `(Text "<stream>\u0001<namespace>|<canonicalValue>", Text claimJson)`
  *   - reduce in: `(Text identifierKey, Iterable[Text] claimJson)`
  *   - reduce out: `(NullWritable, Text joinKeyJson)`
  */
object IdentifierJoinJob:
  val stage = Stage.identifierJoin

  val streams: Map[String, Artifact] = Map(
    TaskSink.identifierKeys -> Artifact.IdentifierKeys
  )

  class ClaimMapper extends Mapper[LongWritable, Text, Text, Text]:
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
            IdentifierJoin.emit(work, Vector.empty, sink)
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

  class JoinReducer extends Reducer[Text, Text, NullWritable, Text]:
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
      val (label, logical) = StreamKey.decode(key.toString)
      // The reject guard must precede the decode: a reject's JSON is a
      // `RejectRecord` and would throw if read as an `IdentifierClaim`.
      if label == RejectRecord.stream then
        values.asScala.foreach(value =>
          sink.commitReject(logical, value.toString)
        )
      else resolveClaims(logical, values)

    private def resolveClaims(
        logical: String,
        values: java.lang.Iterable[Text]
    ): Unit =
      val claimed = values.asScala.iterator
        .map(value => ContractWire.decode[IdentifierClaim](value.toString))
        .toVector
      IdentifierJoin.resolve(logical, claimed, run) match
        case Some((record, duplicates, conflicts)) =>
          if duplicates > 0 then
            sink.count(PipelineCounter.DuplicateRecords, duplicates)
          if conflicts > 0 then
            sink.count(PipelineCounter.ConflictRecords, conflicts)
            sink.reject(
              IdentifierJoin.conflictReject(stage, logical, claimed, record)
            )
          // `emit`, not `emitRecord`: the mapper already counted its claims
          // under `EmittedRecords`, and one Hadoop counter spans both phases.
          sink.emit(
            TaskSink.identifierKeys,
            logical,
            ContractWire.encode(record)
          )
        case None =>
          sink.count(PipelineCounter.MissingKeyRecords)

    override def cleanup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink.close()

  def configure(run: RunContext, inputs: Vector[String]): Job =
    val configuration = new Configuration
    JobConfig.write(configuration, run)
    val job = Job.getInstance(configuration, s"scholarchain-$stage")
    job.setJarByClass(classOf[ClaimMapper])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[ClaimMapper])
    job.setReducerClass(classOf[JoinReducer])
    JobSupport.configureOutput(job, run, stage, streams)
    job

/** Decodes a persisted work record, turning a schema failure into a value
  * rather than an exception so a reducer can reject the line.
  */
object WorkRecords:
  def decode(line: String): Either[String, WorkRecord] =
    try Right(ContractWire.decode[WorkRecord](line))
    catch
      case error: Exception =>
        Left(
          Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        )

class IdentifierJoinTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <worksInput>...")
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val job = IdentifierJoinJob.configure(context, arguments.drop(1).toVector)
    if job.waitForCompletion(true) then 0 else 1

object IdentifierJoinTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new IdentifierJoinTool, arguments)
    )
