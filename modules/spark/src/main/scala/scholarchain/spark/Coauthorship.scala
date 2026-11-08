package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.api.java.UDF4
import org.apache.spark.sql.types.{DataTypes, StructType}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{StableIds, YearWindow}

/** Stable ID of a coauthor edge: always the core `StableIds.coauthorEdgeFor`
  * value, so it equals the ID of the core `CoauthorEdge` for the same
  * endpoints, window and policy. Between two canonical researchers that is
  * `coauthor:v1:<hex>`; an edge with an unresolved-mention endpoint gets
  * `mention-coauthor:v1:<hex>`. A malformed endpoint is a data error and
  * propagates as `IllegalArgumentException`; nothing is replaced by a local ID.
  */
object EdgeIds:
  val policyVersion = "scholarchain.spark.coauthor-policy/1"
  val functionName = "scholarchain_edge_id"

  val mentionPrefix = "mention:"

  def isMention(id: String): Boolean = id.startsWith(mentionPrefix)

  def edgeId(a: String, b: String, first: Int, last: Int): String =
    StableIds.coauthorEdgeFor(a, b, YearWindow(first, last), policyVersion)

  def register(session: SparkSession): Unit =
    session.udf.register(
      functionName,
      new EdgeIdFunction(),
      DataTypes.StringType
    )

/** The SQL-callable form of `EdgeIds.edgeId`. */
final class EdgeIdFunction
    extends UDF4[String, String, Integer, Integer, String]:
  override def call(
      a: String,
      b: String,
      first: Integer,
      last: Integer
  ): String =
    EdgeIds.edgeId(a, b, first.intValue, last.intValue)

object Windowing:
  def frame(session: SparkSession, windows: Vector[NamedWindow]): DataFrame =
    val schema =
      StructType.fromDDL("window STRING, windowStart INT, windowEnd INT")
    val rows = windows.map(w => Row(w.label, w.start, w.end))
    session.createDataFrame(rows.asJava, schema)

final case class CoauthorTables(
    edges: DataFrame,
    edgeSupport: DataFrame,
    metrics: DataFrame,
    adjacency: DataFrame,
    pairViolations: DataFrame,
    pairCounts: DataFrame,
    workTeam: DataFrame
)

/** Jobs 2 and 3: the temporal weighted coauthorship graph and the per
  * researcher breadth and team-size statistics.
  *
  * Pair rule. A Hadoop pair candidate counts only when it belongs to the
  * representative record of its canonical work. Both mentions are mapped to
  * researcher nodes through the accepted links; a pair whose nodes coincide
  * (two mentions of one canonical researcher) is dropped and counted. The
  * endpoints are ordered with the core rule (`StableIds.orderedPair`, string
  * order) and duplicates are aggregated by distinct canonical work.
  *
  * Windows. A work belongs to every window containing its year. A work with no
  * year is in no window, the all-years window included, and is counted.
  *
  * Metrics. For researcher r and window w, over the canonical works of r in w
  * (each counted once): P = number of works; team(x) = number of distinct
  * author nodes of work x; T = sum of (team(x) - 1); A = T / P; M = max of
  * (team(x) - 1) with every tied work kept; C = number of distinct coauthor
  * nodes (graph degree); W = sum of the joint publication counts of the
  * incident edges. An unresolved coauthor mention is its own node, so it adds
  * to C and T like any other coauthor; `unresolvedCoauthorOccurrences` and
  * `distinctUnresolvedCoauthorCount` expose how much of each is unresolved.
  */
object Coauthorship:
  def build(
      session: SparkSession,
      raw: RawInputs,
      canon: CanonicalTables,
      windows: DataFrame,
      ctx: RunContext,
      representativeWorkLimit: Int
  ): CoauthorTables =
    EdgeIds.register(session)
    val dispositions = pairDispositions(session, raw, canon)
    val pairWorks = Sql
      .run(
        session,
        """SELECT DISTINCT a, b, canonicalWorkId
          |FROM {dispositions} WHERE disposition = 'mapped'""".stripMargin,
        "dispositions" -> dispositions
      )
      .persist()
    val windowed = Sql
      .run(
        session,
        """SELECT w.window, w.windowStart, w.windowEnd, p.a, p.b,
          |  p.canonicalWorkId, k.year
          |FROM {pairWorks} p
          |JOIN {works} k ON p.canonicalWorkId = k.canonicalWorkId
          |JOIN {windows} w ON k.year BETWEEN w.windowStart AND w.windowEnd""".stripMargin,
        "pairWorks" -> pairWorks,
        "works" -> canon.works,
        "windows" -> windows
      )
      .persist()
    val edges = edgeTable(session, windowed, ctx, representativeWorkLimit)
      .persist()
    val support = Sql.run(
      session,
      s"""SELECT e.edgeId, p.window, p.windowStart, p.windowEnd,
         |  p.a AS researcherA, p.b AS researcherB,
         |  p.canonicalWorkId, p.year,
         |  ${ctx.provenance(
          "edge-support",
          "concat(e.edgeId, '|', p.canonicalWorkId)"
        )}
         |FROM {windowed} p JOIN {edges} e
         |  ON p.window = e.window AND p.a = e.researcherA
         |  AND p.b = e.researcherB""".stripMargin,
      "windowed" -> windowed,
      "edges" -> edges
    )
    val adjacency = Sql
      .run(
        session,
        """SELECT window, researcherA AS src, researcherB AS dst,
          |  jointPublicationCount AS weight FROM {edges}
          |UNION ALL
          |SELECT window, researcherB, researcherA, jointPublicationCount
          |FROM {edges}""".stripMargin,
        "edges" -> edges
      )
      .persist()
    val team = workTeam(session, canon)
    val metrics =
      metricsTable(session, canon, team, adjacency, windows, ctx).persist()
    CoauthorTables(
      edges = edges,
      edgeSupport = support,
      metrics = metrics,
      adjacency = adjacency,
      pairViolations = pairViolations(session, dispositions),
      pairCounts = Sql.run(
        session,
        """SELECT disposition, count(*) AS pairs,
          |  count(DISTINCT struct(workId, a, b)) AS distinctPairs
          |FROM {dispositions} GROUP BY disposition""".stripMargin,
        "dispositions" -> dispositions
      ),
      workTeam = team
    )

  /** Every Hadoop pair candidate with what happened to it. */
  private def pairDispositions(
      session: SparkSession,
      raw: RawInputs,
      canon: CanonicalTables
  ): DataFrame =
    val mapped = Sql.run(
      session,
      """SELECT p.workId, p.mentionA, p.mentionB,
        |  COALESCE(x.canonicalResearcherId, x.mentionId) AS nodeA,
        |  COALESCE(y.canonicalResearcherId, y.mentionId) AS nodeB,
        |  (x.mentionId IS NULL OR y.mentionId IS NULL
        |    OR (x.violationReason IS NOT NULL
        |      AND x.violationReason <> 'mention-without-link')
        |    OR (y.violationReason IS NOT NULL
        |      AND y.violationReason <> 'mention-without-link'))
        |    AS endpointMissing,
        |  (x.workId <> p.workId OR y.workId <> p.workId) AS workMismatch,
        |  COALESCE(x.isRepresentativeRecord, false) AS representative,
        |  x.canonicalWorkId AS canonicalWorkId
        |FROM {pairs} p
        |LEFT JOIN {base} x ON p.mentionA = x.mentionId
        |LEFT JOIN {base} y ON p.mentionB = y.mentionId""".stripMargin,
      "pairs" -> raw.pairs,
      "base" -> canon.mentionBase
    )
    Sql.run(
      session,
      """SELECT workId, mentionA, mentionB, canonicalWorkId,
        |  LEAST(nodeA, nodeB) AS a, GREATEST(nodeA, nodeB) AS b,
        |  CASE
        |    WHEN endpointMissing THEN 'endpoint-missing'
        |    WHEN workMismatch THEN 'work-mismatch'
        |    WHEN NOT representative THEN 'duplicate-record'
        |    WHEN nodeA = nodeB THEN 'self-pair'
        |    ELSE 'mapped' END AS disposition
        |FROM {mapped}""".stripMargin,
      "mapped" -> mapped
    )

  private def pairViolations(
      session: SparkSession,
      dispositions: DataFrame
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT 'pair' AS entityType, concat(mentionA, '|', mentionB) AS entityId,
         |  CASE disposition WHEN 'endpoint-missing' THEN 'pair-endpoint-unknown'
         |    ELSE 'pair-work-mismatch' END AS reason,
         |  'pair-dropped' AS action, workId, mentionA AS mentionId,
         |  concat('mentionB=', mentionB) AS detail
         |FROM {dispositions}
         |WHERE disposition IN ('endpoint-missing', 'work-mismatch')""".stripMargin,
      "dispositions" -> dispositions
    )

  private def edgeTable(
      session: SparkSession,
      windowed: DataFrame,
      ctx: RunContext,
      limit: Int
  ): DataFrame =
    val grouped = Sql.run(
      session,
      s"""SELECT window, windowStart, windowEnd, a AS researcherA,
         |  b AS researcherB,
         |  count(*) AS jointPublicationCount,
         |  min(year) AS firstCollaborationYear,
         |  max(year) AS lastCollaborationYear,
         |  transform(slice(array_sort(collect_list(struct(year, canonicalWorkId))),
         |    1, $limit), x -> x.canonicalWorkId) AS representativeWorkIds
         |FROM {windowed} GROUP BY window, windowStart, windowEnd, a, b""".stripMargin,
      "windowed" -> windowed
    )
    Sql.run(
      session,
      s"""SELECT ${EdgeIds.functionName}(researcherA, researcherB,
         |    windowStart, windowEnd) AS edgeId,
         |  window, windowStart, windowEnd, researcherA, researcherB,
         |  '${EdgeIds.policyVersion}' AS policyVersion,
         |  jointPublicationCount, firstCollaborationYear,
         |  lastCollaborationYear, representativeWorkIds,
         |  'edge-support' AS supportTable,
         |  researcherA LIKE 'mention:%' AS endpointAUnresolved,
         |  researcherB LIKE 'mention:%' AS endpointBUnresolved,
         |  ${ctx.provenance(
          "edges-coauthored",
          "concat(window, '|', researcherA, '|', researcherB)"
        )}
         |FROM {grouped}""".stripMargin,
      "grouped" -> grouped
    )

  /** Authors per canonical work, counted once: distinct researcher nodes of the
    * representative record.
    */
  private def workTeam(
      session: SparkSession,
      canon: CanonicalTables
  ): DataFrame =
    Sql.run(
      session,
      """SELECT canonicalWorkId,
        |  count(DISTINCT researcherNodeId) AS teamSize,
        |  count(DISTINCT CASE WHEN nodeKind = 'unresolved-mention'
        |    THEN researcherNodeId END) AS unresolvedAuthors
        |FROM {authored} WHERE countedInMetrics
        |GROUP BY canonicalWorkId""".stripMargin,
      "authored" -> canon.authored
    )

  private def metricsTable(
      session: SparkSession,
      canon: CanonicalTables,
      team: DataFrame,
      adjacency: DataFrame,
      windows: DataFrame,
      ctx: RunContext
  ): DataFrame =
    val nodeWork = Sql.run(
      session,
      """SELECT DISTINCT researcherNodeId AS nodeId, nodeKind, canonicalWorkId
        |FROM {authored} WHERE countedInMetrics""".stripMargin,
      "authored" -> canon.authored
    )
    val windowedWork = Sql
      .run(
        session,
        """SELECT w.window, w.windowStart, w.windowEnd, n.nodeId, n.nodeKind,
          |  n.canonicalWorkId, t.teamSize, t.unresolvedAuthors, k.year
          |FROM {nodeWork} n
          |JOIN {team} t ON n.canonicalWorkId = t.canonicalWorkId
          |JOIN {works} k ON n.canonicalWorkId = k.canonicalWorkId
          |JOIN {windows} w ON k.year BETWEEN w.windowStart AND w.windowEnd""".stripMargin,
        "nodeWork" -> nodeWork,
        "team" -> team,
        "works" -> canon.works,
        "windows" -> windows
      )
      .persist()
    val base = Sql
      .run(
        session,
        """SELECT window, windowStart, windowEnd, nodeId,
          |  max(nodeKind) AS nodeKind,
          |  count(*) AS publicationCount,
          |  sum(teamSize - 1) AS totalCoauthorOccurrences,
          |  sum(teamSize - 1) / count(*) AS averageCoauthorsPerPaper,
          |  max(teamSize - 1) AS maximumCoauthorsOnOnePaper,
          |  sum(unresolvedAuthors
          |    - CASE WHEN nodeKind = 'unresolved-mention' THEN 1 ELSE 0 END)
          |    AS unresolvedCoauthorOccurrences
          |FROM {windowedWork}
          |GROUP BY window, windowStart, windowEnd, nodeId""".stripMargin,
        "windowedWork" -> windowedWork
      )
      .persist()
    val maxWorks = Sql.run(
      session,
      """SELECT n.window, n.nodeId,
        |  sort_array(collect_list(n.canonicalWorkId)) AS maximumTeamWorkIds
        |FROM {windowedWork} n JOIN {base} b
        |  ON n.window = b.window AND n.nodeId = b.nodeId
        |  AND n.teamSize - 1 = b.maximumCoauthorsOnOnePaper
        |GROUP BY n.window, n.nodeId""".stripMargin,
      "windowedWork" -> windowedWork,
      "base" -> base
    )
    val degree = Sql.run(
      session,
      """SELECT window, src AS nodeId,
        |  count(*) AS distinctCoauthorCount,
        |  sum(weight) AS weightedDegree,
        |  sum(CASE WHEN dst LIKE 'mention:%' THEN 1 ELSE 0 END)
        |    AS distinctUnresolvedCoauthorCount
        |FROM {adjacency} GROUP BY window, src""".stripMargin,
      "adjacency" -> adjacency
    )
    Sql.run(
      session,
      s"""SELECT b.window, b.windowStart, b.windowEnd,
         |  b.nodeId AS researcherId, b.nodeKind,
         |  b.publicationCount,
         |  COALESCE(d.distinctCoauthorCount, 0) AS distinctCoauthorCount,
         |  b.totalCoauthorOccurrences,
         |  b.averageCoauthorsPerPaper,
         |  b.maximumCoauthorsOnOnePaper,
         |  m.maximumTeamWorkIds,
         |  CAST(COALESCE(d.weightedDegree, 0) AS DOUBLE) AS weightedDegree,
         |  b.unresolvedCoauthorOccurrences,
         |  COALESCE(d.distinctUnresolvedCoauthorCount, 0)
         |    AS distinctUnresolvedCoauthorCount
         |FROM {base} b
         |JOIN {maxWorks} m ON b.window = m.window AND b.nodeId = m.nodeId
         |LEFT JOIN {degree} d ON b.window = d.window AND b.nodeId = d.nodeId""".stripMargin,
      "base" -> base,
      "maxWorks" -> maxWorks,
      "degree" -> degree
    )
