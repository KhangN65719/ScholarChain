package scholarchain.rag

import scala.util.control.NonFatal
import scholarchain.core.{CollaborationMetric, QueryOperation, YearWindow}
import scholarchain.storage.{
  CachedMetrics,
  CoauthorContext,
  CoauthorLink,
  CommunityInfo,
  CommunityLink,
  CommunityReadPort,
  ComponentInfo,
  ComponentReadPort,
  CypherCatalog,
  DynamoTable,
  ErrorCode,
  NeptuneQueryPort,
  PathEvidencePort,
  PathLookup,
  PathQuery,
  ReadLimits,
  ResearcherMetricCache,
  Rows,
  StorageException,
  TransientStorageException,
  VectorFilter,
  VectorIndexes,
  WindowLabels
}

enum MetricLookup:
  case Found(cached: CachedMetrics)
  case Absent(researcherId: String, window: YearWindow)
  case Failed(researcherId: String, window: YearWindow, reason: String)

/** Exact stored metric values with their run ID and metric version. The agent
  * reads the metric cache pinned to one graph run, so a value always names the
  * run it came from. Absent is reported as absent and never defaulted to zero.
  */
final class CollaborationMetricsAgent(
    cache: DynamoTable,
    ranking: MetricRanking,
    graphRunId: String
):
  def metrics(researcherId: String, window: YearWindow): MetricLookup =
    try
      ResearcherMetricCache.get(
        cache,
        researcherId,
        window,
        Some(graphRunId)
      ) match
        case Some(found) => MetricLookup.Found(found)
        case None        => MetricLookup.Absent(researcherId, window)
    catch
      case e: StorageException =>
        MetricLookup.Failed(researcherId, window, e.code.toString)

  def rank(
      window: YearWindow,
      metric: CollaborationMetric,
      limit: Int
  ): Ranking = ranking.top(window, metric, limit)

/** Bounds for graph and path reads. */
final case class PathLimits(
    maxDepth: Int = 6,
    maxResults: Int = 6,
    timeoutMillis: Long = 2000L,
    coauthorLimit: Int = 25
):
  ReadLimits.bounded(maxDepth, ReadLimits.MaxPathDepth, "maxDepth")
  ReadLimits.bounded(maxResults, ReadLimits.MaxPathResults, "maxResults")
  ReadLimits.bounded(coauthorLimit, 1000, "coauthorLimit")
  require(
    timeoutMillis >= 1 && timeoutMillis <= ReadLimits.MaxPathTimeoutMillis,
    "timeoutMillis out of range"
  )

final case class CoauthorPage(
    researcherId: String,
    window: YearWindow,
    links: Vector[CoauthorLink],
    limit: Int
):
  /** A full page may hide more coauthors, so the claim stays "at least". */
  def mayBeTruncated: Boolean = links.size >= limit

/** Direct collaborators and shortest paths. Direct collaborators come from the
  * `coauthors-of` template of the closed Cypher catalog, supporting works from
  * `edge-evidence`. Shortest paths come from the precomputed Spark
  * `path-evidence` table (decision P5); a live Neptune shortest-path template
  * is a Phase 12 item. The Neptune port is wrapped so that any other template
  * name is refused. Depth, result count and timeout are bounded by `limits`.
  */
final class PathFinderAgent(
    neptune: NeptuneQueryPort,
    paths: PathEvidencePort,
    limits: PathLimits = PathLimits()
):
  private val graph = new AllowlistedNeptune(neptune)

  def directCollaborators(
      researcherId: String,
      window: YearWindow
  ): CoauthorPage =
    CoauthorPage(
      researcherId,
      window,
      CoauthorContext.of(
        graph,
        researcherId,
        WindowLabels.of(window),
        limits.coauthorLimit
      ),
      limits.coauthorLimit
    )

  /** The direct edge between two researchers, from a read as wide as the
    * catalog allows. `complete` is true when the read was not full, so a
    * missing edge is a real absence.
    */
  def directEdge(
      a: String,
      b: String,
      window: YearWindow
  ): (Option[CoauthorLink], Boolean) =
    val links = CoauthorContext.of(
      graph,
      a,
      WindowLabels.of(window),
      scholarchain.storage.CypherQuery.MaxLimit
    )
    (
      links.find(_.coauthorId == b),
      links.size < scholarchain.storage.CypherQuery.MaxLimit
    )

  def shortestPath(
      a: String,
      b: String,
      window: YearWindow
  ): PathLookup =
    paths.shortestPath(
      PathQuery(
        WindowLabels.of(window),
        a,
        b,
        limits.maxDepth,
        limits.maxResults,
        limits.timeoutMillis
      )
    )

  /** Supporting work IDs of an edge, from the `edge-evidence` template. */
  def edgeWorks(edgeId: String): Vector[String] =
    try
      graph
        .run(CypherCatalog.edgeEvidence(edgeId, 1))
        .headOption
        .map(row => Rows.strList(row, "representativeWorkIds"))
        .getOrElse(Vector.empty)
    catch case NonFatal(_) => Vector.empty

/** Component, community and bridge facts about one researcher in one window.
  * Direct degree (`distinctCoauthors`, `weightedDegree`) and structural bridge
  * importance (`bridgeScore`, and the community's edges to other communities)
  * are kept as separate fields and never merged.
  */
final case class CommunityAnalysis(
    researcherId: String,
    window: YearWindow,
    component: Option[ComponentInfo],
    community: Option[CommunityInfo],
    neighbors: Vector[CommunityLink],
    neighborsTotal: Int,
    distinctCoauthors: Option[Long],
    weightedDegree: Option[Double],
    bridgeScore: Option[Double]
)

final class CommunityAnalystAgent(
    components: ComponentReadPort,
    communities: CommunityReadPort,
    neighborLimit: Int = 5
):
  def analyze(
      researcherId: String,
      window: YearWindow,
      metrics: Option[CachedMetrics]
  ): CommunityAnalysis =
    val label = WindowLabels.of(window)
    val community = communities.communityOf(label, researcherId)
    val page = community.flatMap(c =>
      communities.neighbors(label, c.communityId, neighborLimit)
    )
    CommunityAnalysis(
      researcherId,
      window,
      components.componentOf(label, researcherId),
      community,
      page.map(_.neighbors).getOrElse(Vector.empty),
      page.map(_.total).getOrElse(0),
      metrics.map(_.metrics.distinctCoauthorCount),
      metrics.map(_.metrics.weightedDegree),
      metrics.flatMap(_.metrics.bridgeScore)
    )

/** Semantic context for the question. It is descriptive only: the agent returns
  * snippets with vector distances and source pointers, and the evidence builder
  * marks every item as unable to support a number, a coauthorship, a path, a
  * component or a bridge claim.
  */
final class SemanticContextAgent(
    retrieval: SemanticRetrieval,
    topK: Int = 2
):
  /** The indexes a question needs, by operation. */
  def indexesFor(operation: QueryOperation): Vector[String] =
    operation match
      case QueryOperation.ShortestPath =>
        Vector(VectorIndexes.CollaborationPath)
      case QueryOperation.Community | QueryOperation.BridgeAnalysis |
          QueryOperation.Component =>
        Vector(
          VectorIndexes.CollaborationCommunity,
          VectorIndexes.ResearcherProfile
        )
      case QueryOperation.Topic | QueryOperation.Institution =>
        Vector(VectorIndexes.TopicInstitutionSummary)
      case QueryOperation.EvidenceLookup =>
        Vector(VectorIndexes.PaperAbstract, VectorIndexes.ResearcherProfile)
      case QueryOperation.CollaborationMetrics =>
        Vector(VectorIndexes.ResearcherProfile, VectorIndexes.PaperAbstract)
      case _ => Vector(VectorIndexes.ResearcherProfile)

  /** Context for each needed index. A failed read is reported and skipped:
    * semantic context is optional, deterministic evidence is not.
    */
  def context(
      operation: QueryOperation,
      queryText: String,
      researcherIds: Vector[String],
      window: Option[YearWindow]
  ): (Vector[SemanticContext], Vector[String]) =
    val text = queryText.take(SemanticContext.MaxQueryChars)
    val results = indexesFor(operation).map { index =>
      val researcher =
        if index == VectorIndexes.ResearcherProfile ||
          index == VectorIndexes.CollaborationPath
        then researcherIds.headOption
        else None
      val filter = VectorFilter(
        researcher = researcher,
        yearFrom = window.map(_.startYear).filter(_ > 1),
        yearTo = window.map(_.endYear).filter(_ < 9999)
      )
      try
        val hits = retrieval.search(index, text, topK, filter)
        Right(retrieval.context(text, hits))
      catch
        case _: StorageException | _: TransientStorageException =>
          Left(SemanticContextAgent.degradedNote(index))
    }
    (
      results.collect { case Right(c) if c.items.nonEmpty => c },
      results.collect { case Left(note) => note }
    )

object SemanticContextAgent:
  /** The known gap for a failed vector read. Fixed text plus the system's own
    * index name: no exception message, class or code is echoed.
    */
  def degradedNote(index: String): String =
    s"Semantic context from index $index was unavailable (the vector read failed), so none is shown; deterministic evidence is unaffected."
