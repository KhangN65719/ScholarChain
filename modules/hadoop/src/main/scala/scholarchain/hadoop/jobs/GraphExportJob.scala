package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{MultipleInputs, TextInputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{
  CanonicalResearcher,
  ContractWire,
  Institution,
  Topic,
  Venue
}
import scholarchain.hadoop.pipeline.*

/** Job 7: graph export preparation.
  *
  * Merges the per-stage, per-dataset ingestion outputs into one canonical set
  * of partitioned files for Spark, and projects author mentions out of their
  * work records into their own artifact.
  *
  * Input type is resolved by **input path**, using `MultipleInputs` with one
  * mapper per record class, rather than by sniffing JSON fields. Sniffing would
  * silently mis-route a record whose shape overlapped another's; binding the
  * type to the path makes a misrouted input a configuration error that fails at
  * submission.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text recordJson)`
  *   - map out: `(Text "<stream>\u0001<entityId>", Text json)`
  *   - reduce in: `(Text key, Iterable[Text] json)`
  *   - reduce out: `(NullWritable, Text json)` to one artifact per stream
  */
object GraphExportJob:
  val stage = Stage.graphExport

  val streams: Map[String, Artifact] = Map(
    TaskSink.works -> Artifact.Works,
    TaskSink.mentions -> Artifact.AuthorMentions,
    TaskSink.researchers -> Artifact.Researchers,
    TaskSink.topics -> Artifact.Topics,
    TaskSink.institutions -> Artifact.Institutions,
    TaskSink.venues -> Artifact.Venues
  )

  /** Shared mapper behaviour. Subclasses differ only in the record class they
    * decode and the stream they emit, which is what lets `MultipleInputs` bind
    * one per input path.
    */
  abstract class ExportMapper extends Mapper[LongWritable, Text, Text, Text]:
    protected var run: RunContext = null
    protected var sink: MapOutputSink[LongWritable, Text] = null

    def label: String

    /** Emits the records derived from one input line, or throws so the caller
      * can convert the failure into a reject.
      */
    def project(line: String): Unit

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
          project(line)
          sink.count(PipelineCounter.AcceptedRecords)
        catch
          case error: Exception =>
            sink.count(PipelineCounter.SchemaFailureRecords)
            sink.reject(
              RejectRecord(
                stage = stage,
                reason = RejectReason.SchemaViolation,
                detail = Option(error.getMessage)
                  .getOrElse(error.getClass.getSimpleName),
                sourceRecordId = s"$label:offset/${key.get()}",
                payload = line,
                provenance = run.provenance(
                  scholarchain.core.SourceType.Derived,
                  s"$label:offset:${key.get()}",
                  s"scholarchain:$label#offset=${key.get()}",
                  run.datasetSnapshots.keys.toVector.sorted.head
                )
              )
            )

  /** Works, plus the author mentions projected out of them. The projection is
    * done here so the mention artifact cannot disagree with the work artifact
    * about the author sequence: both are written from one decoded record.
    */
  class WorkExportMapper extends ExportMapper:
    def label: String = TaskSink.works

    def project(line: String): Unit =
      val work = ContractWire.decode[scholarchain.core.WorkRecord](line)
      sink.emitRecord(TaskSink.works, work.workId, work)
      work.authors.foreach(mention =>
        sink.emitRecord(TaskSink.mentions, mention.mentionId, mention)
      )

  class ResearcherExportMapper extends ExportMapper:
    def label: String = TaskSink.researchers

    def project(line: String): Unit =
      val researcher = ContractWire.decode[CanonicalResearcher](line)
      sink.emitRecord(
        TaskSink.researchers,
        researcher.researcherId,
        researcher
      )

  class TopicExportMapper extends ExportMapper:
    def label: String = TaskSink.topics

    def project(line: String): Unit =
      val topic = ContractWire.decode[Topic](line)
      sink.emitRecord(TaskSink.topics, topic.topicId, topic)

  class InstitutionExportMapper extends ExportMapper:
    def label: String = TaskSink.institutions

    def project(line: String): Unit =
      val institution = ContractWire.decode[Institution](line)
      sink.emitRecord(
        TaskSink.institutions,
        institution.institutionId,
        institution
      )

  class VenueExportMapper extends ExportMapper:
    def label: String = TaskSink.venues

    def project(line: String): Unit =
      val venue = ContractWire.decode[Venue](line)
      sink.emitRecord(TaskSink.venues, venue.venueId, venue)

  /** Merges records that several sources claim for one entity ID.
    *
    * A researcher ID derived from an ORCID is reached by DBLP, OpenAlex and
    * Crossref alike, so this reducer is where the same person stops being three
    * rows. The retained encoding is the lexicographic minimum, which makes the
    * choice independent of arrival order; the merge is recorded as a duplicate
    * rather than discarded silently.
    */
  class MergeReducer extends Reducer[Text, Text, NullWritable, Text]:
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
      if label == RejectRecord.stream then
        // A map-detected reject, committed as-is and not re-counted. It was
        // previously dropped here, which lost the record silently.
        encodings.foreach(sink.commitReject(logical, _))
      else
        val distinct = encodings.distinct
        if distinct.size < encodings.size then
          sink.count(
            PipelineCounter.DuplicateRecords,
            (encodings.size - distinct.size).toLong
          )
        if distinct.size > 1 then sink.count(PipelineCounter.ConflictRecords)
        if streams.contains(label) then sink.emit(label, logical, distinct.min)
        else
          throw new IllegalStateException(
            s"Stage $stage produced the unowned stream '$label'"
          )

    override def cleanup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink.close()

  /** One input path and the mapper that reads it. */
  final case class TypedInput(path: String, mapper: Class[? <: ExportMapper])

  def configure(
      run: RunContext,
      inputs: Vector[TypedInput],
      base: Configuration = new Configuration
  ): Job =
    require(inputs.nonEmpty, "Graph export requires at least one typed input")
    val configuration = new Configuration(base)
    JobConfig.write(configuration, run)
    val job = Job.getInstance(configuration, s"scholarchain-$stage")
    job.setJarByClass(classOf[WorkExportMapper])
    inputs.foreach { input =>
      MultipleInputs.addInputPath(
        job,
        new Path(input.path),
        classOf[TextInputFormat],
        input.mapper
      )
    }
    job.setReducerClass(classOf[MergeReducer])
    JobSupport.configureOutput(job, run, stage, streams)
    job

class GraphExportTool extends Configured with Tool:
  /** Arguments are `<outputRoot>` then `<kind>=<path>` pairs, where kind is one
    * of `works`, `researchers`, `topics`, `institutions`, `venues`.
    */
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <kind>=<path>...")
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val typed = arguments.drop(1).toVector.map { argument =>
      argument.split("=", 2) match
        case Array(kind, path) =>
          GraphExportTool.mapperFor(kind) match
            case Some(mapper) => GraphExportJob.TypedInput(path, mapper)
            case None         =>
              throw new IllegalArgumentException(s"Unknown input kind: $kind")
        case _ =>
          throw new IllegalArgumentException(
            s"Expected <kind>=<path>, got: $argument"
          )
    }
    val job = GraphExportJob.configure(context, typed, getConf)
    if job.waitForCompletion(true) then 0 else 1

object GraphExportTool:
  val mappers: Map[String, Class[? <: GraphExportJob.ExportMapper]] = Map(
    TaskSink.works -> classOf[GraphExportJob.WorkExportMapper],
    TaskSink.researchers -> classOf[GraphExportJob.ResearcherExportMapper],
    TaskSink.topics -> classOf[GraphExportJob.TopicExportMapper],
    TaskSink.institutions -> classOf[GraphExportJob.InstitutionExportMapper],
    TaskSink.venues -> classOf[GraphExportJob.VenueExportMapper]
  )

  def mapperFor(kind: String): Option[Class[? <: GraphExportJob.ExportMapper]] =
    mappers.get(kind)

  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new GraphExportTool, arguments)
    )
