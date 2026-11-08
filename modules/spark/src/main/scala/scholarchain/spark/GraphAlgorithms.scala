package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}
import scala.collection.mutable

/** Iteration summary of an algorithm that runs until nothing changes. */
final case class IterationResult(
    frame: DataFrame,
    iterations: Int,
    converged: Boolean
)

/** Job 4: connected components by distributed min-label propagation.
  *
  * Every node starts with its own ID as its label. Each pass gives a node the
  * smallest label among itself and its neighbours; a pass that changes no label
  * ends the run. Labels only decrease, so the run terminates, after at most the
  * longest shortest path of the largest component plus one verification pass.
  * Each pass is checkpointed, so the lineage never grows. A run that reaches
  * the iteration cap fails rather than reporting wrong components. The
  * component ID is the smallest node ID of the component and is window-local.
  */
object Components:
  private val step =
    """SELECT l.window, l.nodeId,
      |  LEAST(l.label, COALESCE(m.minLabel, l.label)) AS label,
      |  LEAST(l.label, COALESCE(m.minLabel, l.label)) <> l.label AS changed
      |FROM {labels} l
      |LEFT JOIN (
      |  SELECT a.window, a.dst AS nodeId, min(s.label) AS minLabel
      |  FROM {adj} a JOIN {labels} s ON a.window = s.window AND a.src = s.nodeId
      |  GROUP BY a.window, a.dst) m
      |ON l.window = m.window AND l.nodeId = m.nodeId""".stripMargin

  def compute(algo: Algo, nodes: DataFrame, adj: DataFrame): IterationResult =
    val start = algo.sql(
      "SELECT window, nodeId, nodeId AS label FROM {nodes}",
      "nodes" -> nodes
    )
    var labels = algo.checkpoint(start)
    var iteration = 0
    var changed = 1L
    while changed > 0 do
      if iteration >= algo.maxIterations then
        throw new IllegalStateException(
          s"Connected components did not converge in ${algo.maxIterations} passes"
        )
      iteration += 1
      val next = algo.sql(step, "labels" -> labels, "adj" -> adj)
      algo.telemetry.plan("components-pass", next)
      val kept = algo.checkpoint(next)
      changed = DriverGuard.count(kept.filter("changed"))
      labels = kept.select("window", "nodeId", "label")
    IterationResult(labels, iteration, true)

  /** Membership, components and the per window summary. */
  def tables(
      algo: Algo,
      labels: DataFrame,
      edges: DataFrame
  ): (DataFrame, DataFrame, DataFrame) =
    val membership = algo.sql(
      """SELECT window, nodeId, label AS componentId,
        |  count(*) OVER (PARTITION BY window, label) AS componentSize
        |FROM {labels}""".stripMargin,
      "labels" -> labels
    )
    val components = algo.sql(
      """SELECT window, componentId, componentSize,
        |  row_number() OVER (PARTITION BY window
        |    ORDER BY componentSize DESC, componentId ASC) = 1 AS isLargest
        |FROM (SELECT window, label AS componentId, count(*) AS componentSize
        |  FROM {labels} GROUP BY window, label)""".stripMargin,
      "labels" -> labels
    )
    val summary = algo.sql(
      """SELECT c.window, n.nodeCount,
        |  COALESCE(e.edgeCount, 0) AS edgeCount,
        |  count(*) AS componentCount,
        |  max(c.componentSize) AS largestComponentSize,
        |  max(c.componentSize) / n.nodeCount AS largestComponentFraction,
        |  sum(CASE WHEN c.componentSize = 1 THEN 1 ELSE 0 END)
        |    AS singletonCount
        |FROM {components} c
        |JOIN (SELECT window, count(*) AS nodeCount FROM {labels}
        |  GROUP BY window) n ON c.window = n.window
        |LEFT JOIN (SELECT window, count(*) AS edgeCount FROM {edges}
        |  GROUP BY window) e ON c.window = e.window
        |GROUP BY c.window, n.nodeCount, e.edgeCount""".stripMargin,
      "components" -> components,
      "labels" -> labels,
      "edges" -> edges
    )
    (membership, components, summary)

/** Job 8: coreness by distributed iterative peeling.
  *
  * For k = 1, 2, ...: repeatedly remove every node whose number of surviving
  * neighbours is below k, all at once, until a pass removes nothing. A node
  * removed while peeling at level k has coreness k - 1, so a node with no
  * neighbour gets 0 at k = 1. When nothing survives, every node has a coreness.
  * Each pass removes at least one node or ends its level, and k grows only
  * while nodes survive, so the run terminates after at most (nodes + maximum
  * degree) passes. The surviving set and the assigned set are checkpointed
  * after every pass.
  */
object Coreness:
  def compute(algo: Algo, nodes: DataFrame, adj: DataFrame): DataFrame =
    var alive = algo.checkpoint(
      algo.sql("SELECT window, nodeId FROM {nodes}", "nodes" -> nodes)
    )
    var assigned = algo.checkpoint(
      algo.sql(
        """SELECT window, nodeId, CAST(0 AS INT) AS coreness
          |FROM {nodes} WHERE 1 = 0""".stripMargin,
        "nodes" -> nodes
      )
    )
    var remaining = DriverGuard.count(alive)
    var level = 1
    var passes = 0L
    while remaining > 0 do
      var progress = true
      while progress do
        passes += 1
        val removed = algo.checkpoint(removal(algo, alive, adj, level))
        algo.telemetry.plan("coreness-pass", removed)
        val count = DriverGuard.count(removed)
        if count > 0 then
          assigned = algo.checkpoint(
            algo.sql(
              s"""SELECT * FROM {assigned}
                 |UNION ALL
                 |SELECT window, nodeId, CAST(${level - 1} AS INT) FROM {removed}""".stripMargin,
              "assigned" -> assigned,
              "removed" -> removed
            )
          )
          alive = algo.checkpoint(
            algo.sql(
              """SELECT a.window, a.nodeId FROM {alive} a
                |LEFT JOIN {removed} r
                |  ON a.window = r.window AND a.nodeId = r.nodeId
                |WHERE r.nodeId IS NULL""".stripMargin,
              "alive" -> alive,
              "removed" -> removed
            )
          )
          remaining -= count
        progress = count > 0
      level += 1
    assigned

  private def removal(
      algo: Algo,
      alive: DataFrame,
      adj: DataFrame,
      level: Int
  ): DataFrame =
    algo.sql(
      s"""SELECT v.window, v.nodeId
         |FROM {alive} v
         |LEFT JOIN (
         |  SELECT a.window, a.src AS nodeId, count(*) AS deg
         |  FROM {adj} a
         |  JOIN {alive} s ON a.window = s.window AND a.src = s.nodeId
         |  JOIN {alive} d ON a.window = d.window AND a.dst = d.nodeId
         |  GROUP BY a.window, a.src) g
         |  ON v.window = g.window AND v.nodeId = g.nodeId
         |WHERE COALESCE(g.deg, 0) < $level""".stripMargin,
      "alive" -> alive,
      "adj" -> adj
    )

/** Result of community detection with the per window convergence record. */
final case class CommunityResult(
    labels: DataFrame,
    iterations: Int,
    converged: Boolean,
    perWindow: Map[String, (Int, Boolean)]
)

/** Job 6: deterministic weighted label propagation.
  *
  * Labels start as node IDs. In every pass each node computes a proposal: the
  * label with the largest total edge weight among its neighbours, the smallest
  * label on a tie, and its own label whenever its own label is among the
  * largest. Only the nodes whose hash with (seed, pass) is even adopt their
  * proposal in that pass, which breaks the endless swaps that fully synchronous
  * updates produce on a pair of adjacent nodes; the hash is a fixed function,
  * so the run is reproducible. A window is converged at the first pass in which
  * no node's proposal differs from its label: the labels are then a fixpoint
  * and cannot change again. The run stops when every window is converged or at
  * the iteration cap, and both facts are recorded per window. A window that
  * hits the cap keeps its current labels, which are still a valid partition.
  * Passes are checkpointed.
  */
object Communities:
  private def step(salt: Int): String =
    s"""WITH votes AS (
       |  SELECT a.window, a.dst AS nodeId, s.label AS label,
       |    sum(a.weight) AS score
       |  FROM {adj} a JOIN {labels} s ON a.window = s.window AND a.src = s.nodeId
       |  GROUP BY a.window, a.dst, s.label),
       |tops AS (
       |  SELECT window, nodeId, max(score) AS top FROM votes
       |  GROUP BY window, nodeId),
       |best AS (
       |  SELECT v.window, v.nodeId, min(v.label) AS minLabel,
       |    max(CASE WHEN v.label = l.label THEN 1 ELSE 0 END) AS ownTop
       |  FROM votes v
       |  JOIN tops t ON v.window = t.window AND v.nodeId = t.nodeId
       |    AND v.score = t.top
       |  JOIN {labels} l ON v.window = l.window AND v.nodeId = l.nodeId
       |  GROUP BY v.window, v.nodeId),
       |proposed AS (
       |  SELECT l.window, l.nodeId, l.label AS oldLabel,
       |    CASE WHEN b.nodeId IS NULL THEN l.label
       |      WHEN b.ownTop = 1 THEN l.label ELSE b.minLabel END AS proposal
       |  FROM {labels} l
       |  LEFT JOIN best b ON l.window = b.window AND l.nodeId = b.nodeId)
       |SELECT window, nodeId,
       |  CASE WHEN pmod(hash(nodeId, $salt), 2) = 0
       |    THEN proposal ELSE oldLabel END AS label,
       |  proposal <> oldLabel AS wouldChange
       |FROM proposed""".stripMargin

  def compute(algo: Algo, nodes: DataFrame, adj: DataFrame): CommunityResult =
    var labels = algo.checkpoint(
      algo.sql(
        "SELECT window, nodeId, nodeId AS label FROM {nodes}",
        "nodes" -> nodes
      )
    )
    val stableAt = mutable.LinkedHashMap.empty[String, Int]
    val seen = mutable.LinkedHashSet.empty[String]
    var pass = 0
    var done = false
    while !done && pass < algo.maxIterations do
      pass += 1
      val salt = Math.floorMod(algo.seed * 31L + pass, 1000003L).toInt
      val next = algo.sql(step(salt), "labels" -> labels, "adj" -> adj)
      algo.telemetry.plan("communities-pass", next)
      val kept = algo.checkpoint(next)
      val pending = algo.sql(
        """SELECT window,
          |  sum(CASE WHEN wouldChange THEN 1 ELSE 0 END) AS pending
          |FROM {kept} GROUP BY window""".stripMargin,
        "kept" -> kept
      )
      val rows = algo.rows(pending, "community pass summary")
      rows.foreach { row =>
        val window = row.getString(0)
        seen += window
        if row.getLong(1) == 0L && !stableAt.contains(window) then
          stableAt(window) = pass - 1
      }
      labels = kept.select("window", "nodeId", "label")
      done = rows.forall(_.getLong(1) == 0L)
    val perWindow = seen.toVector
      .map(window =>
        window -> (stableAt.getOrElse(window, pass), stableAt.contains(window))
      )
      .toMap
    CommunityResult(labels, pass, done, perWindow)

  /** Community table, community-to-community edges and per edge communities. */
  def tables(
      algo: Algo,
      labels: DataFrame,
      edges: DataFrame
  ): (DataFrame, DataFrame, DataFrame) =
    val edgeCommunities = algo.sql(
      """SELECT e.window, e.edgeId, e.researcherA AS a, e.researcherB AS b,
        |  x.label AS communityA, y.label AS communityB,
        |  x.label = y.label AS internal,
        |  e.jointPublicationCount AS weight,
        |  e.representativeWorkIds AS representativeWorkIds
        |FROM {edges} e
        |JOIN {labels} x ON e.window = x.window AND e.researcherA = x.nodeId
        |JOIN {labels} y ON e.window = y.window AND e.researcherB = y.nodeId""".stripMargin,
      "edges" -> edges,
      "labels" -> labels
    )
    val communities = algo.sql(
      """SELECT z.window, z.communityId, z.communitySize,
        |  COALESCE(s.internalEdges, 0) AS internalEdges,
        |  COALESCE(s.crossEdges, 0) AS crossEdges,
        |  COALESCE(s.internalWeight, 0) AS internalWeight,
        |  COALESCE(s.crossWeight, 0) AS crossWeight
        |FROM (SELECT window, label AS communityId, count(*) AS communitySize
        |  FROM {labels} GROUP BY window, label) z
        |LEFT JOIN (
        |  SELECT window, communityId, sum(i) AS internalEdges,
        |    sum(c) AS crossEdges, sum(iw) AS internalWeight,
        |    sum(cw) AS crossWeight
        |  FROM (
        |    SELECT window, communityA AS communityId, CAST(1 AS BIGINT) AS i,
        |      CAST(0 AS BIGINT) AS c, weight AS iw, CAST(0 AS BIGINT) AS cw
        |    FROM {ec} WHERE internal
        |    UNION ALL
        |    SELECT window, communityA, CAST(0 AS BIGINT), CAST(1 AS BIGINT),
        |      CAST(0 AS BIGINT), weight FROM {ec} WHERE NOT internal
        |    UNION ALL
        |    SELECT window, communityB, CAST(0 AS BIGINT), CAST(1 AS BIGINT),
        |      CAST(0 AS BIGINT), weight FROM {ec} WHERE NOT internal)
        |  GROUP BY window, communityId) s
        |ON z.window = s.window AND z.communityId = s.communityId""".stripMargin,
      "labels" -> labels,
      "ec" -> edgeCommunities
    )
    val cross = algo.sql(
      """SELECT window, LEAST(communityA, communityB) AS communityA,
        |  GREATEST(communityA, communityB) AS communityB,
        |  count(*) AS edgeCount, sum(weight) AS totalWeight
        |FROM {ec} WHERE NOT internal
        |GROUP BY window, LEAST(communityA, communityB),
        |  GREATEST(communityA, communityB)""".stripMargin,
      "ec" -> edgeCommunities
    )
    (communities, cross, edgeCommunities)

/** Job 7: bridge measures per researcher, window and community assignment.
  *
  * With s_ic the total edge weight from researcher i to community c and s_i =
  * sum over c of s_ic (the weighted degree):
  *   - crossCommunityDegree: number of coauthors outside i's community;
  *   - crossCommunityStrength: s_i minus the weight inside i's own community;
  *   - distinctCommunitiesConnected: number of communities other than i's own
  *     that contain a coauthor of i;
  *   - distinctNeighborCommunities: the same count including i's own;
  *   - participationCoefficient: 1 - sum over c of (s_ic / s_i)^2, 0 for a
  *     researcher with no coauthor (Guimera and Amaral).
  * `bridgeScore` is the participation coefficient.
  *
  * Limitations. Every measure depends on the community partition, so a
  * different community method can move a researcher between a bridge and a
  * non-bridge. These are local measures: there is no betweenness centrality
  * here, so a researcher on many shortest paths between large groups can score
  * low when most coauthors share one community.
  */
object Bridge:
  val definitions: Vector[(String, String)] = Vector(
    "crossCommunityDegree" -> "number of coauthors outside the researcher's community",
    "crossCommunityStrength" -> "joint publication weight to coauthors outside the researcher's community",
    "distinctCommunitiesConnected" -> "number of other communities containing a coauthor of the researcher",
    "distinctNeighborCommunities" -> "number of communities containing a coauthor, own community included",
    "participationCoefficient" -> "1 minus the sum over communities of the squared share of the researcher's weighted degree, 0 when there is no coauthor",
    "bridgeScore" -> "equal to participationCoefficient"
  )

  val limitations: String =
    "Bridge measures depend on the community method and its partition; " +
      "they are local measures and no betweenness centrality is computed. " +
      Fragmentation.limitation

  def measures(
      algo: Algo,
      adj: DataFrame,
      communities: DataFrame
  ): DataFrame =
    algo.sql(
      """WITH nb AS (
        |  SELECT a.window, a.src AS nodeId, a.weight,
        |    c.label AS neighborCommunity, o.label AS ownCommunity
        |  FROM {adj} a
        |  JOIN {comm} c ON a.window = c.window AND a.dst = c.nodeId
        |  JOIN {comm} o ON a.window = o.window AND a.src = o.nodeId),
        |per AS (
        |  SELECT window, nodeId, ownCommunity, neighborCommunity,
        |    sum(weight) AS s, count(*) AS k
        |  FROM nb GROUP BY window, nodeId, ownCommunity, neighborCommunity),
        |agg AS (
        |  SELECT window, nodeId,
        |    sum(k) AS degree, sum(s) AS strength,
        |    sum(CASE WHEN neighborCommunity <> ownCommunity THEN k ELSE 0 END)
        |      AS crossCommunityDegree,
        |    sum(CASE WHEN neighborCommunity <> ownCommunity THEN s ELSE 0 END)
        |      AS crossCommunityStrength,
        |    sum(CASE WHEN neighborCommunity <> ownCommunity THEN 1 ELSE 0 END)
        |      AS distinctCommunitiesConnected,
        |    count(*) AS distinctNeighborCommunities,
        |    1.0 - sum(CAST(s AS DOUBLE) * s)
        |      / (CAST(sum(s) AS DOUBLE) * sum(s)) AS participationCoefficient
        |  FROM per GROUP BY window, nodeId)
        |SELECT c.window, c.nodeId AS researcherId, c.label AS communityId,
        |  CAST(COALESCE(a.degree, 0) AS BIGINT) AS degree,
        |  CAST(COALESCE(a.strength, 0) AS BIGINT) AS strength,
        |  CAST(COALESCE(a.crossCommunityDegree, 0) AS BIGINT)
        |    AS crossCommunityDegree,
        |  CAST(COALESCE(a.crossCommunityStrength, 0) AS BIGINT)
        |    AS crossCommunityStrength,
        |  CAST(COALESCE(a.distinctCommunitiesConnected, 0) AS BIGINT)
        |    AS distinctCommunitiesConnected,
        |  CAST(COALESCE(a.distinctNeighborCommunities, 0) AS BIGINT)
        |    AS distinctNeighborCommunities,
        |  CAST(COALESCE(a.participationCoefficient, 0.0) AS DOUBLE)
        |    AS participationCoefficient,
        |  CAST(COALESCE(a.participationCoefficient, 0.0) AS DOUBLE)
        |    AS bridgeScore
        |FROM {comm} c LEFT JOIN agg a
        |  ON c.window = a.window AND c.nodeId = a.nodeId""".stripMargin,
      "adj" -> adj,
      "comm" -> communities
    )

/** The fragmentation limit that applies to every path and bridge result. */
object Fragmentation:
  val limitation: String =
    "The coauthorship graph can be fragmented into many small components: a " +
      "path exists only inside one component and a bridge score only sees " +
      "the neighbours inside its own component, so when the largest " +
      "component holds a small share of the nodes (component-summary " +
      "largestComponentFraction and componentCount) most pairs have no path " +
      "and most bridge scores describe very small groups."

/** Small aggregations of the path tables for `summary.txt` and the run
  * manifest. Each result has one row per window (or per window and status), so
  * it passes the driver guard; neither table is collected whole.
  */
object PathReport:
  /** Pairs per window, status and unreachable reason. A found pair has one
    * evidence row per hop, so pairs are counted by distinct pair key.
    */
  def statusCounts(algo: Algo, evidence: DataFrame): DataFrame =
    algo.sql(
      """SELECT window, status, unreachableReason,
        |  count(DISTINCT pairKey) AS pairs
        |FROM {e} GROUP BY window, status, unreachableReason
        |ORDER BY window, status, unreachableReason""".stripMargin,
      "e" -> evidence
    )

  /** Every column of `path-length-estimates` except `provenance`, plus the
    * window bounds, one row per window.
    */
  def lengthEstimates(
      algo: Algo,
      estimates: DataFrame,
      windows: DataFrame
  ): DataFrame =
    algo.sql(
      """SELECT e.*, w.windowStart, w.windowEnd
        |FROM {e} e JOIN {w} w ON e.window = w.window
        |ORDER BY e.window""".stripMargin,
      "e" -> estimates,
      "w" -> windows
    )

  def line(row: Row): String = row.mkString("[", ", ", "]")

  def json(columns: Array[String], data: Vector[Row]): ujson.Arr =
    ujson.Arr.from(data.map { row =>
      ujson.Obj.from(columns.toVector.zipWithIndex.map { (name, index) =>
        name -> (row.get(index) match
          case null       => ujson.Null
          case b: Boolean => ujson.Bool(b)
          case n: Number  => ujson.Num(n.doubleValue)
          case other      => ujson.Str(other.toString))
      })
    })

/** Job 5: bounded multi-source breadth-first search and path evidence.
  *
  * The search is a join per level, never a driver traversal. It keeps, per
  * (pair, node), the hop distance and the smallest predecessor ID on the
  * previous level, so the shortest path is deterministic: walk back from the
  * target through those predecessors. A pair stops expanding when its target is
  * reached, and the whole search stops at the hop limit or when every frontier
  * is empty. Frontier and visited sets are checkpointed per level.
  */
object PathSearch:
  val limitations: String =
    "Paths are shortest in hops over the window graph and bounded by the hop " +
      "limit; status unreachable-within-bound has two causes, named in " +
      "unreachableReason: different-component (no path exists) and " +
      "beyond-hop-limit (a path exists but is longer than the limit). " +
      "Path-length estimates cover only the largest component. " +
      Fragmentation.limitation

  def bfs(
      algo: Algo,
      starts: DataFrame,
      adj: DataFrame,
      maxHops: Int,
      targets: Option[DataFrame]
  ): DataFrame =
    var visited = algo.checkpoint(
      algo.sql(
        """SELECT pairKey, window, nodeId, CAST(0 AS INT) AS dist,
          |  CAST(NULL AS STRING) AS parent FROM {starts}""".stripMargin,
        "starts" -> starts
      )
    )
    var frontier = visited.select("pairKey", "window", "nodeId")
    var level = 0
    while level < maxHops && !frontier.isEmpty do
      level += 1
      val next = algo.sql(
        """SELECT f.pairKey, f.window, a.dst AS nodeId, min(a.src) AS parent
          |FROM {frontier} f
          |JOIN {adj} a ON f.window = a.window AND f.nodeId = a.src
          |LEFT JOIN {visited} v ON v.pairKey = f.pairKey AND v.nodeId = a.dst
          |WHERE v.pairKey IS NULL
          |GROUP BY f.pairKey, f.window, a.dst""".stripMargin,
        "frontier" -> frontier,
        "adj" -> adj,
        "visited" -> visited
      )
      algo.telemetry.plan("bfs-level", next)
      val kept = algo.checkpoint(next)
      visited = algo.checkpoint(
        algo.sql(
          s"""SELECT * FROM {visited}
             |UNION ALL
             |SELECT pairKey, window, nodeId, CAST($level AS INT) AS dist, parent
             |FROM {kept}""".stripMargin,
          "visited" -> visited,
          "kept" -> kept
        )
      )
      frontier = targets match
        case Some(goal) =>
          algo.checkpoint(
            algo.sql(
              """SELECT k.pairKey, k.window, k.nodeId FROM {kept} k
                |LEFT JOIN (
                |  SELECT DISTINCT t.pairKey FROM {goal} t
                |  JOIN {visited} v
                |    ON t.pairKey = v.pairKey AND t.target = v.nodeId) d
                |  ON k.pairKey = d.pairKey
                |WHERE d.pairKey IS NULL""".stripMargin,
              "kept" -> kept,
              "goal" -> goal,
              "visited" -> visited
            )
          )
        case None => kept.select("pairKey", "window", "nodeId")
    visited

  /** Path evidence for the selected pairs: one row per hop of a found path, and
    * one row for a pair that is trivial, outside the window or unreachable
    * within the hop limit.
    */
  def evidence(
      algo: Algo,
      pairs: DataFrame,
      nodes: DataFrame,
      adj: DataFrame,
      edges: DataFrame,
      labels: DataFrame,
      hopLimit: Int
  ): DataFrame =
    val checked = algo
      .checkpoint(
        algo.sql(
          """SELECT concat_ws('|', p.window, p.source, p.target) AS pairKey,
            |  p.window, p.source, p.target, p.selection,
            |  (s.nodeId IS NOT NULL) AS sourceKnown,
            |  (t.nodeId IS NOT NULL) AS targetKnown,
            |  COALESCE(sl.label = tl.label, false) AS sameComponent
            |FROM (SELECT DISTINCT window, source, target, selection
            |  FROM {pairs}) p
            |LEFT JOIN {nodes} s ON p.window = s.window AND p.source = s.nodeId
            |LEFT JOIN {nodes} t ON p.window = t.window AND p.target = t.nodeId
            |LEFT JOIN {labels} sl
            |  ON p.window = sl.window AND p.source = sl.nodeId
            |LEFT JOIN {labels} tl
            |  ON p.window = tl.window AND p.target = tl.nodeId""".stripMargin,
          "pairs" -> pairs,
          "nodes" -> nodes,
          "labels" -> labels
        )
      )
    val runnable = algo.sql(
      """SELECT * FROM {checked}
        |WHERE sourceKnown AND targetKnown AND source <> target""".stripMargin,
      "checked" -> checked
    )
    val starts = algo.sql(
      "SELECT pairKey, window, source AS nodeId FROM {runnable}",
      "runnable" -> runnable
    )
    val goals = algo.sql(
      "SELECT pairKey, target FROM {runnable}",
      "runnable" -> runnable
    )
    val visited = bfs(algo, starts, adj, hopLimit, Some(goals))
    val found = algo.checkpoint(
      algo.sql(
        """SELECT r.pairKey, r.window, r.target, v.dist
          |FROM {runnable} r JOIN {visited} v
          |  ON r.pairKey = v.pairKey AND v.nodeId = r.target""".stripMargin,
        "runnable" -> runnable,
        "visited" -> visited
      )
    )
    val hops = walkBack(algo, found, visited, hopLimit)
    val result = algo.sql(
      s"""SELECT c.pairKey, c.window, c.source AS sourceId,
         |  c.target AS targetId, c.selection,
         |  CASE
         |    WHEN c.source = c.target THEN 'trivial'
         |    WHEN NOT c.sourceKnown OR NOT c.targetKnown
         |      THEN 'endpoint-not-in-window'
         |    WHEN f.pairKey IS NOT NULL THEN 'found'
         |    ELSE 'unreachable-within-bound' END AS status,
         |  CASE
         |    WHEN c.source = c.target THEN CAST(NULL AS STRING)
         |    WHEN NOT c.sourceKnown OR NOT c.targetKnown
         |      THEN CAST(NULL AS STRING)
         |    WHEN f.pairKey IS NOT NULL THEN CAST(NULL AS STRING)
         |    WHEN c.sameComponent THEN 'beyond-hop-limit'
         |    ELSE 'different-component' END AS unreachableReason,
         |  CAST($hopLimit AS INT) AS hopLimit,
         |  CASE WHEN c.source = c.target THEN 0 ELSE f.dist END AS pathLength,
         |  h.hop AS hopIndex, h.fromId, h.toId,
         |  e.edgeId, e.jointPublicationCount, e.firstCollaborationYear,
         |  e.lastCollaborationYear,
         |  e.representativeWorkIds AS supportingWorkIds
         |FROM {checked} c
         |LEFT JOIN {found} f ON c.pairKey = f.pairKey
         |LEFT JOIN {hops} h ON c.pairKey = h.pairKey
         |LEFT JOIN {edges} e ON h.window = e.window
         |  AND e.researcherA = LEAST(h.fromId, h.toId)
         |  AND e.researcherB = GREATEST(h.fromId, h.toId)""".stripMargin,
      "checked" -> checked,
      "found" -> found,
      "hops" -> hops,
      "edges" -> edges
    )
    algo.telemetry.plan("path-evidence", result)
    result

  /** One row per hop, walking from each found target back to its source. */
  private def walkBack(
      algo: Algo,
      found: DataFrame,
      visited: DataFrame,
      hopLimit: Int
  ): DataFrame =
    var hops = algo.checkpoint(
      algo.sql(
        """SELECT pairKey, window, CAST(0 AS INT) AS hop,
          |  CAST(NULL AS STRING) AS fromId, CAST(NULL AS STRING) AS toId
          |FROM {found} WHERE 1 = 0""".stripMargin,
        "found" -> found
      )
    )
    var current = algo.sql(
      """SELECT pairKey, window, target AS nodeId, dist AS hop FROM {found}""",
      "found" -> found
    )
    var steps = 0
    while steps < hopLimit && !current.isEmpty do
      steps += 1
      val step = algo.checkpoint(
        algo.sql(
          """SELECT c.pairKey, c.window, c.hop AS hop,
            |  v.parent AS fromId, c.nodeId AS toId
            |FROM {current} c JOIN {visited} v
            |  ON c.pairKey = v.pairKey AND c.nodeId = v.nodeId""".stripMargin,
          "current" -> current,
          "visited" -> visited
        )
      )
      hops = algo.checkpoint(hops.union(step))
      current = algo.checkpoint(
        algo.sql(
          """SELECT pairKey, window, fromId AS nodeId, hop - 1 AS hop
            |FROM {step} WHERE hop > 1""".stripMargin,
          "step" -> step
        )
      )
    hops

  /** Sampled estimate of the average path length and the effective diameter of
    * the largest component of each window.
    *
    * Sources: the `sampleSize` nodes of that component with the smallest
    * hash(nodeId, seed), ties broken by node ID, so the sample is fixed by the
    * seed. Each source runs a breadth-first search over the whole component up
    * to `depthCap` hops. The measured pairs are (source, other node) with the
    * source in the sample; the average is their mean distance and the effective
    * diameter is the smallest distance d such that at least 90 percent of the
    * measured pairs are within d hops (integer arithmetic, no interpolation).
    * When the sample covers the component (`allNodesSampled`) the values are
    * exact for that component.
    *
    * Limitations: only the largest component of each window is measured, the
    * sample estimates the mean over random sources only when it is smaller than
    * the component, and pairs beyond `depthCap` are reported in
    * `unreachedPairsDueToDepthCap` and left out of the estimate.
    */
  def estimates(
      algo: Algo,
      labels: DataFrame,
      components: DataFrame,
      adj: DataFrame,
      sampleSize: Int,
      depthCap: Int
  ): DataFrame =
    val seedInt = Math.floorMod(algo.seed, 1000003L).toInt
    val starts = algo.checkpoint(
      algo.sql(
        s"""SELECT concat_ws('|', r.window, r.nodeId) AS pairKey, r.window,
           |  r.nodeId
           |FROM (
           |  SELECT l.window, l.nodeId,
           |    row_number() OVER (PARTITION BY l.window
           |      ORDER BY hash(l.nodeId, $seedInt), l.nodeId) AS rnk
           |  FROM {labels} l JOIN {components} c
           |    ON l.window = c.window AND l.label = c.componentId
           |  WHERE c.isLargest) r
           |WHERE r.rnk <= $sampleSize""".stripMargin,
        "labels" -> labels,
        "components" -> components
      )
    )
    val visited = bfs(algo, starts, adj, depthCap, None)
    val result = algo.sql(
      s"""WITH h AS (
         |  SELECT window, dist, count(*) AS c FROM {visited}
         |  WHERE dist > 0 GROUP BY window, dist),
         |cum AS (
         |  SELECT window, dist, c,
         |    sum(c) OVER (PARTITION BY window ORDER BY dist) AS cumulative,
         |    sum(c) OVER (PARTITION BY window) AS total
         |  FROM h),
         |est AS (
         |  SELECT window,
         |    min(CASE WHEN cumulative * 10 >= total * 9 THEN dist END)
         |      AS effectiveDiameter90,
         |    max(dist) AS maxObservedDistance,
         |    max(total) AS pairsMeasured,
         |    sum(dist * c) / max(total) AS averagePathLength
         |  FROM cum GROUP BY window),
         |src AS (
         |  SELECT window, count(DISTINCT pairKey) AS sampledSources
         |  FROM {starts} GROUP BY window)
         |SELECT e.window, c.componentId, c.componentSize,
         |  s.sampledSources, CAST($sampleSize AS INT) AS requestedSampleSize,
         |  s.sampledSources >= c.componentSize AS allNodesSampled,
         |  e.pairsMeasured, e.averagePathLength, e.effectiveDiameter90,
         |  e.maxObservedDistance,
         |  s.sampledSources * (c.componentSize - 1) - e.pairsMeasured
         |    AS unreachedPairsDueToDepthCap,
         |  CAST(${algo.seed} AS BIGINT) AS seed, CAST($depthCap AS INT) AS depthCap
         |FROM est e
         |JOIN {components} c ON e.window = c.window AND c.isLargest
         |JOIN src s ON e.window = s.window""".stripMargin,
      "visited" -> visited,
      "starts" -> starts,
      "components" -> components
    )
    algo.telemetry.plan("path-length-estimates", result)
    result
