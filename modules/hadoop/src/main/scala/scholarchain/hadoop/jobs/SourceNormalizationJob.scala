package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*
import scholarchain.core.SourceType
import scholarchain.hadoop.pipeline.*

/** Job 2: external source normalization.
  *
  * One normalizer per dataset, selected by `scholarchain.input.dataset` so the
  * same mapper class serves OpenAlex, ORCID and Crossref without a branch per
  * record. A dataset with no registered normalizer makes every one of its
  * records an unsupported reject rather than a silent pass-through.
  *
  * Key-value contract:
  *   - map in: `(LongWritable byteOffset, Text jsonLine)`
  *   - map out: `(Text "<stream>\u0001<entityId>", Text json)`
  *   - reduce in: `(Text key, Iterable[Text] json)`
  *   - reduce out: `(NullWritable, Text json)` routed by named output
  */
object SourceNormalizationJob:
  val stage = Stage.sourceNormalization

  val streams: Map[String, Artifact] = Map(
    TaskSink.works -> Artifact.NormalizedWorks,
    TaskSink.researchers -> Artifact.NormalizedResearchers,
    TaskSink.topics -> Artifact.NormalizedTopics,
    TaskSink.institutions -> Artifact.NormalizedInstitutions,
    TaskSink.venues -> Artifact.NormalizedVenues
  )

  class NormalizeMapper extends Mapper[LongWritable, Text, Text, Text]:
    private var run: RunContext = null
    private var sink: MapOutputSink[LongWritable, Text] = null
    private var dataset: String = ""
    private var normalizer: Option[
      (String, RunContext) => Either[(RejectReason, String), NormalizedRecord]
    ] =
      None

    override def setup(
        context: Mapper[LongWritable, Text, Text, Text]#Context
    ): Unit =
      run = JobConfig.read(context.getConfiguration)
      sink = new MapOutputSink(context)
      dataset = Option(context.getConfiguration.get(JobConfig.dataset))
        .filter(_.trim.nonEmpty)
        .getOrElse(
          throw new IllegalStateException(
            s"${JobConfig.dataset} must name the dataset being normalized"
          )
        )
      normalizer = SourceNormalizers.byDataset.get(dataset)

    override def map(
        key: LongWritable,
        value: Text,
        context: Mapper[LongWritable, Text, Text, Text]#Context
    ): Unit =
      val line = value.toString.trim
      if line.nonEmpty then
        sink.count(PipelineCounter.InputRecords)
        normalizer match
          case None =>
            sink.count(PipelineCounter.SchemaFailureRecords)
            sink.reject(
              RejectRecord(
                stage = stage,
                reason = RejectReason.UnsupportedRecordType,
                detail = s"No normalizer registered for dataset '$dataset'",
                sourceRecordId = s"$dataset:offset/${key.get()}",
                payload = line,
                provenance = provenanceFor(key.get())
              )
            )
          case Some(normalize) =>
            normalize(line, run) match
              case Right(record)          => accept(record)
              case Left((reason, detail)) =>
                if reason == RejectReason.MalformedJson then
                  sink.count(PipelineCounter.SchemaFailureRecords)
                if reason == RejectReason.MissingRequiredField then
                  sink.count(PipelineCounter.MissingKeyRecords)
                sink.reject(
                  RejectRecord(
                    stage = stage,
                    reason = reason,
                    detail = detail,
                    sourceRecordId = s"$dataset:offset/${key.get()}",
                    payload = line,
                    provenance = provenanceFor(key.get())
                  )
                )

    private def accept(record: NormalizedRecord): Unit =
      sink.count(PipelineCounter.AcceptedRecords)
      record.work.foreach(work =>
        sink.emitRecord(TaskSink.works, work.workId, work)
      )
      record.researchers.foreach(researcher =>
        sink.emitRecord(
          TaskSink.researchers,
          researcher.researcherId,
          researcher
        )
      )
      record.topics.foreach(topic =>
        sink.emitRecord(TaskSink.topics, topic.topicId, topic)
      )
      record.institutions.foreach(institution =>
        sink.emitRecord(
          TaskSink.institutions,
          institution.institutionId,
          institution
        )
      )
      record.venues.foreach(venue =>
        sink.emitRecord(TaskSink.venues, venue.venueId, venue)
      )
      if record.identifiers.isEmpty then
        sink.count(PipelineCounter.MissingKeyRecords)

    private def provenanceFor(offset: Long) =
      val source = SourceType.values
        .find(_.wire == dataset)
        .getOrElse(SourceType.Derived)
      run.provenance(
        source,
        s"$dataset:offset:$offset",
        s"scholarchain:$dataset#offset=$offset",
        if run.datasetSnapshots.contains(dataset) then dataset
        else run.datasetSnapshots.keys.toVector.sorted.head
      )

  class NormalizeReducer extends Reducer[Text, Text, NullWritable, Text]:
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
      val encodings = values.asScala.iterator.map(_.toString).toVector.distinct
      if label == RejectRecord.stream then
        // A map-detected reject, committed as-is and not re-counted. It was
        // previously dropped here, which lost the record silently.
        encodings.foreach(sink.commitReject(logical, _))
      else
        if encodings.size > 1 then
          // Two different encodings for one entity ID is a content conflict,
          // not a duplicate: both claim the same identity with different facts.
          sink.count(PipelineCounter.ConflictRecords)
        if streams.contains(label) then sink.emit(label, logical, encodings.min)
        else
          throw new IllegalStateException(
            s"Stage $stage produced the unowned stream '$label'"
          )

    override def cleanup(
        context: Reducer[Text, Text, NullWritable, Text]#Context
    ): Unit =
      sink.close()

  def configure(
      run: RunContext,
      dataset: String,
      inputs: Vector[String],
      base: Configuration = new Configuration
  ): Job =
    val configuration = new Configuration(base)
    JobConfig.write(configuration, run)
    configuration.set(JobConfig.dataset, dataset)
    val job =
      Job.getInstance(configuration, s"scholarchain-$stage-$dataset")
    job.setJarByClass(classOf[NormalizeMapper])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(input => FileInputFormat.addInputPath(job, new Path(input)))
    job.setMapperClass(classOf[NormalizeMapper])
    job.setReducerClass(classOf[NormalizeReducer])
    JobSupport.configureOutput(job, run, stage, streams, Some(dataset))
    job.getConfiguration.set(JobConfig.dataset, dataset)
    job

class SourceNormalizationTool extends Configured with Tool:
  def run(arguments: Array[String]): Int =
    require(
      arguments.length >= 3,
      "Usage: <outputRoot> <dataset> <input>..."
    )
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val job = SourceNormalizationJob.configure(
      context,
      arguments(1),
      arguments.drop(2).toVector,
      getConf
    )
    if job.waitForCompletion(true) then 0 else 1

object SourceNormalizationTool:
  def main(arguments: Array[String]): Unit =
    System.exit(
      ToolRunner.run(new Configuration, new SourceNormalizationTool, arguments)
    )
