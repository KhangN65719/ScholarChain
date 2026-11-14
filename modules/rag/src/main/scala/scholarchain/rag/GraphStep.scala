package scholarchain.rag

import scala.util.control.NonFatal
import scholarchain.storage.*

/** The Neptune step of the loader: the Spark graph tables, projected to
  * vertices and edges, written as openCypher CSV files under `graph/neptune/`
  * (the manifest in its own dataset, outside the load prefix) and loaded by the
  * bulk loader.
  *
  * `project` is a seam for tests; the real one is `SparkGraphProjector`.
  */
final case class GraphLoad(
    loader: NeptuneBulkLoader,
    project: TableSource => GraphProjection = GraphLoad.sparkProjection
)

object GraphLoad:
  def sparkProjection(tables: TableSource): GraphProjection =
    new SparkGraphProjector(tables, LoadBounds.Default).project()

object GraphStep:
  /** Every table `SparkGraphProjector.project()` reads. A projection with one
    * of them absent cannot be built, and nothing here invents a table.
    */
  val RequiredTables: Vector[String] = Vector(
    "nodes-researcher",
    "nodes-work",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support"
  )

  enum Result:
    case Skipped(reason: String, missing: Vector[String])
    case Loaded(exported: NeptuneExportReport, load: BulkLoadResult)
    case Failed(reason: String)

  def missingTables(tables: TableSource): Vector[String] =
    RequiredTables.filterNot(tables.tableNames.contains)

  /** Skipped when the export lacks a required table (never silent: the caller
    * prints `NEPTUNE_LOAD=SKIPPED`), Failed on any error, Loaded when the
    * loader ended every job in `LOAD_COMPLETED` with no errors.
    */
  def run(
      tables: TableSource,
      runId: String,
      store: ObjectStore,
      graph: GraphLoad
  ): Result =
    val missing = missingTables(tables)
    if missing.nonEmpty then
      Result.Skipped(
        s"the export lacks the graph table(s) ${missing.mkString(", ")}",
        missing
      )
    else
      try
        val projection = graph.project(tables)
        val exported = NeptuneExporter.exportTo(projection, runId, store)
        val load = graph.loader.load(exported.manifestKey)
        Result.Loaded(exported, load)
      catch
        case NonFatal(error) =>
          Result.Failed(
            s"${error.getClass.getSimpleName}: ${error.getMessage}"
          )

  def jobJson(job: LoaderJobReport): ujson.Value =
    ujson.Obj(
      "source" -> job.source,
      "loadId" -> job.loadId,
      "status" -> job.status,
      "totalRecords" -> ujson.Num(job.counts.totalRecords.toDouble),
      "totalDuplicates" -> ujson.Num(job.counts.totalDuplicates.toDouble),
      "parsingErrors" -> ujson.Num(job.counts.parsingErrors.toDouble),
      "datatypeMismatchErrors" ->
        ujson.Num(job.counts.datatypeMismatchErrors.toDouble),
      "insertErrors" -> ujson.Num(job.counts.insertErrors.toDouble),
      "errorSamples" -> ujson.Arr.from(job.errorSamples.map(ujson.Str(_)))
    )
