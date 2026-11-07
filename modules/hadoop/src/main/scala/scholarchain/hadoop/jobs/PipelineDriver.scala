package scholarchain.hadoop.jobs

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scholarchain.hadoop.pipeline.{Artifact, RunContext, Stage}

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

  /** Every normalized-work path produced by the ingestion stages. These are the
    * inputs to the join, blocking, team and export stages.
    *
    * The per-dataset paths come from `RunContext.artifactPath(artifact,
    * dataset)` rather than from a local path builder, so the driver and any
    * downstream reader compose the identical prefix from one definition.
    */
  def workPaths(run: RunContext, datasets: Vector[String]): Vector[String] =
    run.artifactPath(Artifact.DblpWorks) +:
      datasets.map(run.artifactPath(Artifact.NormalizedWorks, _))

  def researcherPaths(
      run: RunContext,
      datasets: Vector[String]
  ): Vector[String] =
    run.artifactPath(Artifact.DblpResearchers) +:
      datasets.map(run.artifactPath(Artifact.NormalizedResearchers, _))

  /** The typed inputs to the graph-export stage.
    *
    * Every enrichment class an ingestion stage persists is listed here. Leaving
    * one out would silently produce an empty artifact rather than an error, so
    * the mapping is written once and reused by the driver and the tool.
    */
  def exportInputs(
      run: RunContext,
      datasets: Vector[String]
  ): Vector[GraphExportJob.TypedInput] =
    workPaths(run, datasets).map(path =>
      GraphExportJob.TypedInput(
        path,
        classOf[GraphExportJob.WorkExportMapper]
      )
    ) ++ researcherPaths(run, datasets).map(path =>
      GraphExportJob.TypedInput(
        path,
        classOf[GraphExportJob.ResearcherExportMapper]
      )
    ) ++ datasets.flatMap(dataset =>
      Vector(
        run.artifactPath(Artifact.NormalizedTopics, dataset) ->
          classOf[GraphExportJob.TopicExportMapper],
        run.artifactPath(Artifact.NormalizedInstitutions, dataset) ->
          classOf[GraphExportJob.InstitutionExportMapper],
        run.artifactPath(Artifact.NormalizedVenues, dataset) ->
          classOf[GraphExportJob.VenueExportMapper]
      ).map { case (path, mapper) =>
        GraphExportJob.TypedInput(path, mapper)
      }
    ) :+ GraphExportJob.TypedInput(
      run.artifactPath(Artifact.DblpVenues),
      classOf[GraphExportJob.VenueExportMapper]
    )

  def run(run: RunContext, inputs: Inputs): Int =
    val datasets = inputs.external.keys.toVector.sorted
    val stages: Vector[(String, () => Boolean)] = Vector(
      Stage.dblpExtraction -> (() =>
        DblpExtractionJob.configure(run, inputs.dblp).waitForCompletion(true)
      )
    ) ++ datasets.map(dataset =>
      s"${Stage.sourceNormalization}/$dataset" -> (() =>
        SourceNormalizationJob
          .configure(run, dataset, inputs.external(dataset))
          .waitForCompletion(true)
      )
    ) ++ Vector(
      Stage.identifierJoin -> (() =>
        IdentifierJoinJob
          .configure(run, workPaths(run, datasets))
          .waitForCompletion(true)
      ),
      Stage.blocking -> (() =>
        BlockingJob
          .configure(run, workPaths(run, datasets))
          .waitForCompletion(true)
      ),
      Stage.candidateFeatures -> (() =>
        CandidateFeatureJob
          .configure(
            run,
            Vector(run.artifactPath(Artifact.BlockingMembers))
          )
          .waitForCompletion(true)
      ),
      Stage.teamExpansion -> (() =>
        PublicationTeamJob
          .configure(run, workPaths(run, datasets))
          .waitForCompletion(true)
      ),
      Stage.graphExport -> (() =>
        GraphExportJob
          .configure(run, exportInputs(run, datasets))
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
  /** Arguments are `<outputRoot> <dblpInput>` then `<dataset>=<path>` pairs. */
  def run(arguments: Array[String]): Int =
    require(
      arguments.length >= 2,
      "Usage: <outputRoot> <dblpInput> [<dataset>=<path>]..."
    )
    val context = PipelineOptions.runContext(getConf, arguments(0))
    val external = arguments
      .drop(2)
      .toVector
      .flatMap { argument =>
        argument.split("=", 2) match
          case Array(dataset, path) => Some(dataset -> path)
          case _                    => None
      }
      .groupMap(_._1)(_._2)
    PipelineDriver.run(
      context,
      PipelineDriver.Inputs(Vector(arguments(1)), external)
    )

object PipelineTool:
  def main(arguments: Array[String]): Unit =
    System.exit(ToolRunner.run(new Configuration, new PipelineTool, arguments))
