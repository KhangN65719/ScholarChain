package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{CoauthorPairCandidate, ContractWire, SourceType}
import scholarchain.hadoop.pipeline.*

/** Job 6: publication team extraction and coauthor-pair expansion.
  *
  * The map side expands each retained work into its team, its author-paper
  * records and its `n * (n - 1) / 2` unordered pairs. The reduce side
  * consolidates pairs that arrive for the same mention pair.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text workJson)`
  *   - map out: `(Text "<stream>\u0001<key>", Text json)` where the key is the
  *     work ID for a team, the mention ID for an author-paper record and
  *     `"<mentionA>|<mentionB>"` for a pair
  *   - reduce in: `(Text key, Iterable[Text] json)`
  *   - reduce out: `(NullWritable, Text json)` to `teams`, `author-papers` and
  *     `pair-candidates`
  */
object PublicationTeamJob:
  val stage = Stage.teamExpansion

  val streams: Map[String, Artifact] = Map(
    TaskSink.teams -> Artifact.Teams,
    TaskSink.authorPapers -> Artifact.AuthorPapers,
    TaskSink.pairs -> Artifact.PairCandidates
  )

  class ExpandMapper extends Mapper[LongWritable, Text, Text, Text]:
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
            TeamExpansion.emit(work, sink, run)
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

  class ConsolidateReducer extends Reducer[Text, Text, NullWritable, Text]:
    private var sink: ArtifactSink[Text, Text] = null

    override def setup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink = new ArtifactSink(context, streams, stage)

    override def reduce(
        key: Text,
        values: java.lang.Iterable[Text],
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      val (label, logical) = StreamKey.decode(key.toString)
      val encodings = values.asScala.iterator.map(_.toString).toVector
      label match
        case TaskSink.pairs =>
          val decoded =
            encodings.map(ContractWire.decode[CoauthorPairCandidate])
          val (retained, duplicates) =
            TeamExpansion.consolidate(logical, decoded)
          if duplicates > 0 then
            sink.count(PipelineCounter.DuplicateRecords, duplicates)
          retained.foreach { pair =>
            // `emit`, not `emitRecord`: the latter counts `EmittedRecords`, and
            // the mapper already counted these. One Hadoop counter spans both
            // phases — the same defect the pair-counter split was created for.
            sink.emit(TaskSink.pairs, logical, ContractWire.encode(pair))
            sink.count(PipelineCounter.EmittedPairs)
          }
        case TaskSink.teams | TaskSink.authorPapers =>
          val distinct = encodings.distinct
          if distinct.size < encodings.size then
            sink.count(
              PipelineCounter.DuplicateRecords,
              (encodings.size - distinct.size).toLong
            )
          sink.emit(label, logical, distinct.min)
        case RejectRecord.stream =>
          // A map-detected reject, committed as-is and not re-counted. It was
          // previously dropped here, which lost the record silently.
          encodings.foreach(sink.commitReject(logical, _))
        case other =>
          throw new IllegalStateException(
            s"Stage $stage produced the unowned stream '$other'"
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
    job.setJarByClass(classOf[ExpandMapper])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[ExpandMapper])
    job.setReducerClass(classOf[ConsolidateReducer])
    JobSupport.configureOutput(job, run, stage, streams)
    job

class PublicationTeamTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <worksInput>...")
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val job = PublicationTeamJob.configure(
      context,
      arguments.drop(1).toVector,
      getConf
    )
    if job.waitForCompletion(true) then 0 else 1

object PublicationTeamTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new PublicationTeamTool, arguments)
    )
