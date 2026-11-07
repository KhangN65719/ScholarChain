package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.hadoop.pipeline.*
import scholarchain.core.{ContractWire, WorkRecord}

/** Job 1: DBLP record extraction.
  *
  * Map: one complete DBLP element per call, delivered by
  * [[DblpXmlInputFormat]], so no mapper ever sees a partial record. Reduce:
  * groups by work ID and keeps one record per key, which is where a dump
  * containing the same DBLP key twice becomes a counted duplicate instead of
  * two works.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text xmlElement)`
  *   - map out: `(Text "<stream>\u0001<workId|personKey>", Text json)`
  *   - reduce in: `(Text key, Iterable[Text] json)`
  *   - reduce out: `(NullWritable, Text json)` routed by named output
  */
object DblpExtractionJob:
  val stage = Stage.dblpExtraction

  /** Author mentions are not written here: they travel inside the work record,
    * where the contract already enforces that they agree with the author
    * sequence. The graph-export stage projects them into their own artifact, so
    * the mention artifact has exactly one writer.
    */
  val ownedStreams: Map[String, Artifact] = Map(
    TaskSink.works -> Artifact.DblpWorks,
    TaskSink.researchers -> Artifact.DblpResearchers,
    TaskSink.venues -> Artifact.DblpVenues
  )

  class ExtractMapper extends Mapper[LongWritable, Text, Text, Text]:
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
      val xml = value.toString
      sink.count(PipelineCounter.InputRecords)
      val tag = XmlRecordStream.rootTag(xml).getOrElse("")
      if XmlRecordStream.identityTags.contains(tag) then
        DblpParser.parseIdentity(xml, run) match
          case Right(researcher) =>
            sink.count(PipelineCounter.AcceptedRecords)
            sink.emitRecord(
              TaskSink.researchers,
              researcher.researcherId,
              researcher
            )
          case Left((reason, detail)) =>
            rejectRecord(reason, detail, xml, key.get())
      else
        DblpParser.parse(xml, run) match
          case Right(publication) =>
            sink.count(PipelineCounter.AcceptedRecords)
            sink.emitRecord(
              TaskSink.works,
              publication.work.workId,
              publication.work
            )
            publication.venue.foreach(venue =>
              sink.emitRecord(TaskSink.venues, venue.venueId, venue)
            )
            publication.editorResearchers.foreach(researcher =>
              sink.emitRecord(
                TaskSink.researchers,
                researcher.researcherId,
                researcher
              )
            )
          case Left((reason, detail)) =>
            rejectRecord(reason, detail, xml, key.get())

    private def rejectRecord(
        reason: RejectReason,
        detail: String,
        xml: String,
        offset: Long
    ): Unit =
      if reason == RejectReason.MalformedXml ||
        reason == RejectReason.SchemaViolation
      then sink.count(PipelineCounter.SchemaFailureRecords)
      sink.reject(
        RejectRecord(
          stage = stage,
          reason = reason,
          detail = detail,
          sourceRecordId = s"dblp:offset/$offset",
          payload = xml,
          provenance = run.provenance(
            scholarchain.core.SourceType.Dblp,
            s"offset:$offset",
            s"https://dblp.org/xml/#offset=$offset",
            "dblp"
          )
        )
      )

  /** Keeps one record per key. The retained copy is the lexicographically
    * smallest encoding, which makes deduplication independent of the order the
    * shuffle delivered the duplicates in.
    */
  class DeduplicateReducer extends Reducer[Text, Text, NullWritable, Text]:
    private var sink: ArtifactSink[Text, Text] = null

    override def setup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink = new ArtifactSink(context, ownedStreams, stage)

    override def reduce(
        key: Text,
        values: java.lang.Iterable[Text],
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      val (label, logical) = StreamKey.decode(key.toString)
      if label == RejectRecord.stream then
        // A map-detected reject. Committed as-is, before any attempt to read
        // these values as this stage's records, and not re-counted.
        values.asScala.foreach(value =>
          sink.commitReject(logical, value.toString)
        )
      else
        val encodings = values.asScala.iterator.map(_.toString).toVector
        val retained = encodings.min
        if encodings.size > 1 then
          sink.count(
            PipelineCounter.DuplicateRecords,
            (encodings.size - 1).toLong
          )
        if label == TaskSink.works then
          val works = encodings.sorted.map(ContractWire.decode[WorkRecord])
          val titles = works.map(_.normalizedTitle).distinct.sorted
          if titles.size > 1 then
            sink.count(
              PipelineCounter.ConflictRecords,
              (titles.size - 1).toLong
            )
            sink.reject(
              RejectRecord(
                stage = stage,
                reason = RejectReason.IdentifierConflict,
                detail =
                  s"DBLP key $logical has ${titles.size} conflicting titles",
                sourceRecordId = works.head.sourceRecordId,
                payload = titles.mkString(" || "),
                provenance = works.head.provenance
              )
            )
        if ownedStreams.contains(label) then sink.emit(label, logical, retained)
        else
          throw new IllegalStateException(
            s"Stage $stage produced the unowned stream '$label'"
          )

    override def cleanup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink.close()

  def configure(run: RunContext, inputs: Vector[String]): Job =
    val configuration = new Configuration
    JobConfig.write(configuration, run)
    val job = Job.getInstance(configuration, s"scholarchain-$stage")
    job.setJarByClass(classOf[ExtractMapper])
    job.setInputFormatClass(classOf[DblpXmlInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[ExtractMapper])
    job.setReducerClass(classOf[DeduplicateReducer])
    JobSupport.configureOutput(job, run, stage, ownedStreams)
    job

/** `hadoop jar` entry point for the DBLP extraction stage alone. */
class DblpExtractionTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(arguments.length >= 2, "Usage: <outputRoot> <input>...")
    val run = PipelineOptions.runContext(getConf, arguments(0))
    val job =
      DblpExtractionJob.configure(run, arguments.drop(1).toVector)
    if job.waitForCompletion(true) then 0 else 1

object DblpExtractionTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new DblpExtractionTool, arguments)
    )
