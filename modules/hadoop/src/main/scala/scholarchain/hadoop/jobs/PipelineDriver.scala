package scholarchain.hadoop.jobs

import java.io.PrintStream
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.util.{Tool, ToolRunner}
import scholarchain.hadoop.pipeline.{
  Artifact,
  RunContext,
  SourceNormalizers,
  Stage,
  StreamCounter
}

/** Runs the seven jobs in dependency order against one run prefix.
  *
  * The chain is explicit rather than inferred: each stage reads the committed
  * output of an earlier stage by path, so a stage can be rerun on its own
  * against an existing prefix.
  *
  * Every path is a pure function of the run ID and the snapshot digest, so the
  * same run always addresses the same prefixes and the output is reproducible.
  * This is **not** overwrite semantics: `FileOutputFormat` refuses an existing
  * output directory, so rerunning a stage requires either deleting that stage
  * directory first or using a new run ID. Stage directories are separate partly
  * for this reason — one stage can be replayed without disturbing the others.
  *
  * Untested at the Hadoop layer: no stage has executed on a Hadoop runtime, so
  * this submission-time behavior is read from Hadoop's contract, not observed.
  */
object PipelineDriver:
  final case class Inputs(
      dblp: Vector[String],
      external: Map[String, Vector[String]]
  )

  /** The job graph, as a list of edges from producing stage to consuming stage.
    * Published so a report or a test can assert it rather than restate it.
    */
  val jobGraph: Vector[(String, String)] = Vector(
    Stage.dblpExtraction -> Stage.identifierJoin,
    Stage.sourceNormalization -> Stage.identifierJoin,
    Stage.dblpExtraction -> Stage.blocking,
    Stage.sourceNormalization -> Stage.blocking,
    Stage.blocking -> Stage.candidateFeatures,
    Stage.dblpExtraction -> Stage.teamExpansion,
    Stage.sourceNormalization -> Stage.teamExpansion,
    Stage.dblpExtraction -> Stage.graphExport,
    Stage.sourceNormalization -> Stage.graphExport
  )

  /** The artifacts the ingestion stages actually wrote, as the jobs themselves
    * reported them through their stream counters.
    *
    * Not every dataset writes every artifact: ORCID writes only researchers,
    * Crossref no topics or institutions, OpenAlex no venues. A reader path for
    * an artifact nothing wrote fails at job submission, so each downstream
    * stage reads only what is listed here.
    */
  final case class Outputs(
      dblp: Set[Artifact],
      external: Map[String, Set[Artifact]]
  ):
    /** Paths of a dataset-scoped artifact, one per dataset that wrote it. */
    def normalized(run: RunContext, artifact: Artifact): Vector[String] =
      external.toVector.sortBy(_._1).collect {
        case (dataset, written) if written.contains(artifact) =>
          run.artifactPath(artifact, dataset)
      }

    def dblpPath(run: RunContext, artifact: Artifact): Vector[String] =
      Vector(artifact).filter(dblp.contains).map(run.artifactPath)

  /** Artifacts a completed job wrote, read from its own stream counters. */
  def written(job: Job, streams: Map[String, Artifact]): Set[Artifact] =
    streams.collect {
      case (label, artifact)
          if job.getCounters
            .findCounter(StreamCounter.group, label)
            .getValue > 0L =>
        artifact
    }.toSet

  /** The artifacts of `expected` that `actual` lacks. A non-empty result fails
    * the run: a dataset that should have produced an artifact never has it
    * silently skipped.
    */
  def missingExpected(
      expected: Set[Artifact],
      actual: Set[Artifact]
  ): Vector[Artifact] =
    expected.diff(actual).toVector.sortBy(_.ordinal)

  /** Every normalized-work path that was written. These are the inputs to the
    * join, blocking, team and export stages. DBLP works are always read: a DBLP
    * extraction that wrote none is a failed run, checked by the driver before
    * any consumer is configured.
    *
    * The per-dataset paths come from `RunContext.artifactPath(artifact,
    * dataset)` rather than from a local path builder, so the driver and any
    * downstream reader compose the identical prefix from one definition.
    */
  def workPaths(run: RunContext, outputs: Outputs): Vector[String] =
    run.artifactPath(Artifact.DblpWorks) +:
      outputs.normalized(run, Artifact.NormalizedWorks)

  def researcherPaths(run: RunContext, outputs: Outputs): Vector[String] =
    outputs.dblpPath(run, Artifact.DblpResearchers) ++
      outputs.normalized(run, Artifact.NormalizedResearchers)

  /** The typed inputs to the graph-export stage.
    *
    * Every enrichment class an ingestion stage persists is listed here, and
    * each is read only where a job reported writing it. Leaving a class out
    * would silently produce an empty artifact rather than an error, so the
    * mapping is written once and reused by the driver and the tool.
    */
  def exportInputs(
      run: RunContext,
      outputs: Outputs
  ): Vector[GraphExportJob.TypedInput] =
    def typed(
        paths: Vector[String],
        mapper: Class[? <: GraphExportJob.ExportMapper]
    ) = paths.map(GraphExportJob.TypedInput(_, mapper))
    typed(
      workPaths(run, outputs),
      classOf[GraphExportJob.WorkExportMapper]
    ) ++ typed(
      researcherPaths(run, outputs),
      classOf[GraphExportJob.ResearcherExportMapper]
    ) ++ typed(
      outputs.normalized(run, Artifact.NormalizedTopics),
      classOf[GraphExportJob.TopicExportMapper]
    ) ++ typed(
      outputs.normalized(run, Artifact.NormalizedInstitutions),
      classOf[GraphExportJob.InstitutionExportMapper]
    ) ++ typed(
      outputs.normalized(run, Artifact.NormalizedVenues) ++
        outputs.dblpPath(run, Artifact.DblpVenues),
      classOf[GraphExportJob.VenueExportMapper]
    )

  /** Refuses a dataset with no registered normalizer or expectation before any
    * job is configured, so an unknown name can never pass with an empty
    * expectation. Throws `IllegalArgumentException` naming the dataset; the
    * tool entry point reports it and exits 2.
    */
  def requireKnownDatasets(datasets: Iterable[String]): Unit =
    val known = SourceNormalizers.byDataset.keySet
      .intersect(SourceNormalizers.expectedOutputs.keySet)
    datasets.toVector.distinct.sorted.filterNot(known.contains).foreach {
      dataset =>
        throw new IllegalArgumentException(
          s"Unknown dataset '$dataset'; known datasets: ${known.toVector.sorted.mkString(", ")}"
        )
    }

  def run(
      run: RunContext,
      inputs: Inputs,
      base: Configuration = new Configuration
  ): Int =
    val datasets = inputs.external.keys.toVector.sorted
    requireKnownDatasets(datasets)
    var dblpWritten = Set.empty[Artifact]
    var externalWritten = Map.empty[String, Set[Artifact]]
    def outputs = Outputs(dblpWritten, externalWritten)

    def requireExpected(
        name: String,
        expected: Set[Artifact],
        actual: Set[Artifact]
    ): Boolean =
      val missing = missingExpected(expected, actual)
      missing.foreach(artifact =>
        System.err.println(
          s"ScholarChain stage $name finished without writing expected output ${artifact.segment}"
        )
      )
      missing.isEmpty

    val stages: Vector[(String, () => Boolean)] = Vector(
      Stage.dblpExtraction -> (() =>
        val job = DblpExtractionJob.configure(run, inputs.dblp, base)
        job.waitForCompletion(true) && {
          dblpWritten = written(job, DblpExtractionJob.ownedStreams)
          requireExpected(
            Stage.dblpExtraction,
            Set(Artifact.DblpWorks),
            dblpWritten
          )
        }
      )
    ) ++ datasets.map(dataset =>
      val name = s"${Stage.sourceNormalization}/$dataset"
      name -> (() =>
        val job = SourceNormalizationJob
          .configure(run, dataset, inputs.external(dataset), base)
        job.waitForCompletion(true) && {
          val actual = written(job, SourceNormalizationJob.streams)
          externalWritten = externalWritten.updated(dataset, actual)
          requireExpected(
            name,
            SourceNormalizers.expectedOutputs(dataset),
            actual
          )
        }
      )
    ) ++ Vector(
      Stage.identifierJoin -> (() =>
        IdentifierJoinJob
          .configure(run, workPaths(run, outputs), base)
          .waitForCompletion(true)
      ),
      Stage.blocking -> (() =>
        BlockingJob
          .configure(run, workPaths(run, outputs), base)
          .waitForCompletion(true)
      ),
      Stage.candidateFeatures -> (() =>
        CandidateFeatureJob
          .configure(
            run,
            Vector(run.artifactPath(Artifact.BlockingMembers)),
            base
          )
          .waitForCompletion(true)
      ),
      Stage.teamExpansion -> (() =>
        PublicationTeamJob
          .configure(run, workPaths(run, outputs), base)
          .waitForCompletion(true)
      ),
      Stage.graphExport -> (() =>
        GraphExportJob
          .configure(run, exportInputs(run, outputs), base)
          .waitForCompletion(true)
      )
    )
    stages
      .collectFirst { case (name, execute) if !execute() => name }
      .fold(0) { failed =>
        System.err.println(s"ScholarChain pipeline failed at stage $failed")
        1
      }

/** `hadoop jar` entry point for the whole pipeline. */
class PipelineTool extends Configured with Tool:
  /** Arguments are `<outputRoot> <dblpInput>` then `<dataset>=<path>` pairs.
    * Snapshot IDs go in front as a generic option,
    * `-Dscholarchain.run.snapshots=dblp=<id>,<dataset>=<id>,...`, which
    * `ToolRunner` strips before this method sees the arguments.
    */
  def run(arguments: Array[String]): Int =
    try
      val plan = PipelineTool.plan(getConf, arguments, System.out, System.err)
      PipelineDriver.run(plan.context, plan.inputs, getConf)
    catch
      case error: IllegalArgumentException =>
        System.err.println(
          s"ScholarChain pipeline not started: ${error.getMessage}"
        )
        2

object PipelineTool:
  /** A validated launch: the run context, the inputs and any warnings that were
    * already printed.
    */
  final case class Plan(
      context: RunContext,
      inputs: PipelineDriver.Inputs,
      warnings: Vector[String]
  )

  /** Parses the `<dataset>=<path>` arguments after the DBLP input.
    *
    * Nothing is skipped or defaulted: an argument without `=`, an empty dataset
    * or path, or a dataset with no registered normalizer is an error naming the
    * argument. An unknown dataset would otherwise get an empty expectation in
    * the driver and pass with no output.
    */
  def parseExternal(arguments: Vector[String]): Map[String, Vector[String]] =
    val parsed = arguments.map { argument =>
      argument.split("=", 2) match
        case Array(dataset, path)
            if dataset.trim.nonEmpty && path.trim.nonEmpty =>
          dataset.trim -> path.trim
        case _ =>
          throw new IllegalArgumentException(
            s"Argument '$argument' is not <dataset>=<path> with a non-empty dataset and path"
          )
    }
    val known = SourceNormalizers.byDataset.keySet
    parsed.map(_._1).distinct.filterNot(known.contains).sorted.foreach {
      dataset =>
        throw new IllegalArgumentException(
          s"Unknown dataset '$dataset'; known datasets: ${known.toVector.sorted.mkString(", ")}"
        )
    }
    parsed.groupMap(_._1)(_._2)

  def defaultSnapshotWarning(configuration: Configuration): Option[String] =
    val configured = Option(configuration.get(JobConfig.snapshots))
      .exists(_.trim.nonEmpty)
    Option.unless(configured) {
      val effective = RunContext.localSnapshots.toVector.sorted
        .map { case (dataset, id) => s"$dataset=$id" }
        .mkString(",")
      s"WARNING: -D${JobConfig.snapshots} is not set; using the built-in default " +
        s"snapshot ids $effective. Every record will cite these ids; " +
        "pass the snapshots of the inputs actually read."
    }

  /** Validates the arguments and snapshots, prints the summary to `out` and any
    * warning to both `out` and `err`, and returns the launch plan.
    */
  def plan(
      configuration: Configuration,
      arguments: Array[String],
      out: PrintStream,
      err: PrintStream
  ): Plan =
    if arguments.length < 2 then
      throw new IllegalArgumentException(
        "Usage: <outputRoot> <dblpInput> [<dataset>=<path>]..."
      )
    val external = parseExternal(arguments.drop(2).toVector)
    PipelineOptions.validateSnapshots(configuration, external.keySet + "dblp")
    val warnings = defaultSnapshotWarning(configuration).toVector
    val context = PipelineOptions.runContext(configuration, arguments(0))
    warnings.foreach { warning =>
      err.println(warning)
      out.println(warning)
    }
    val snapshots = context.datasetSnapshots.toVector.sorted
      .map { case (dataset, id) => s"$dataset=$id" }
      .mkString(",")
    out.println(
      s"ScholarChain pipeline run ${context.runId} datasets=${(external.keySet + "dblp").toVector.sorted
          .mkString(",")} snapshots=$snapshots output=${context.runPath}"
    )
    Plan(
      context,
      PipelineDriver.Inputs(Vector(arguments(1)), external),
      warnings
    )

  def main(arguments: Array[String]): Unit =
    System.exit(ToolRunner.run(new Configuration, new PipelineTool, arguments))
