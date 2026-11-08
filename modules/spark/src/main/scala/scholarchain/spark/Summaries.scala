package scholarchain.spark

import org.apache.spark.sql.DataFrame

/** Job 10: template summaries for later embedding.
  *
  * Every summary is a fixed template filled from computed columns and source
  * records: researcher metrics, community tables, path evidence and work
  * records. The text holds no number that is not a column value, no model is
  * called and nothing is fetched. Each row lists the evidence it used: the work
  * IDs, the metric names, the node IDs and the window, and carries provenance.
  *
  * Types: `researcher-profile` (every researcher in every window), `community`
  * (every community in every window), `collaboration-path` (every selected
  * pair) and `work` (the `paperLimit` canonical works with the largest teams, a
  * window-independent selection). There is no topic summary: the Hadoop exports
  * hold no work to topic link, so no topic statement could be supported by
  * source records.
  */
object Summaries:
  private val metricNames =
    """array('publicationCount', 'distinctCoauthorCount',
      |  'totalCoauthorOccurrences', 'averageCoauthorsPerPaper',
      |  'maximumCoauthorsOnOnePaper', 'weightedDegree', 'componentSize',
      |  'communitySize', 'coreness', 'participationCoefficient',
      |  'unresolvedCoauthorOccurrences')""".stripMargin

  private val emptyStrings = "CAST(array() AS ARRAY<STRING>)"

  def build(
      algo: Algo,
      metrics: DataFrame,
      researchers: DataFrame,
      communities: DataFrame,
      edgeCommunities: DataFrame,
      paths: DataFrame,
      works: DataFrame,
      workTeam: DataFrame,
      windows: DataFrame,
      paperLimit: Int,
      ctx: RunContext
  ): DataFrame =
    val profiles = algo.sql(
      s"""WITH n AS (
         |  SELECT nodeId, displayName, nodeKind,
         |    COALESCE(orcid IS NOT NULL
         |      AND count(*) OVER (PARTITION BY orcid) > 1, false) AS splitOrcid
         |  FROM {researchers})
         |SELECT concat('summary:v1:', sha2(concat_ws('|', 'researcher-profile',
         |    m.window, m.researcherId), 256)) AS summaryId,
         |  'researcher-profile' AS summaryType, m.window, m.windowStart,
         |  m.windowEnd, m.researcherId AS subjectId,
         |  n.nodeKind AS nodeKind,
         |  concat(
         |    CASE WHEN n.nodeKind = 'unresolved-mention'
         |      THEN 'Unresolved author mention ' ELSE 'Researcher ' END,
         |    format_string('%s in window %s: %d publications, %d distinct coauthors, %d coauthor occurrences, %.2f average coauthors per paper, at most %d coauthors on one paper (work %s), weighted degree %.0f, component of %d nodes, community of %d nodes, coreness %d, participation coefficient %.3f.',
         |      COALESCE(n.displayName, m.researcherId), m.window,
         |      m.publicationCount, m.distinctCoauthorCount,
         |      m.totalCoauthorOccurrences, m.averageCoauthorsPerPaper,
         |      m.maximumCoauthorsOnOnePaper,
         |      concat_ws(', ', m.maximumTeamWorkIds), m.weightedDegree,
         |      m.componentSize, m.communitySize, m.coreness,
         |      m.participationCoefficient),
         |    ' Nodes are canonical researchers and unresolved author mentions.',
         |    CASE WHEN n.nodeKind = 'unresolved-mention'
         |      THEN ' This node is one author mention that no accepted link joins to a canonical researcher; the same person may appear as other nodes.'
         |      ELSE '' END,
         |    CASE WHEN n.splitOrcid
         |      THEN ' Uncertainty: other canonical researchers share this ORCID, so this profile may cover only part of the record of one person.'
         |      ELSE '' END,
         |    CASE WHEN m.unresolvedCoauthorOccurrences > 0
         |      THEN format_string(' %d of the %d coauthor occurrences are unresolved author mentions.',
         |        m.unresolvedCoauthorOccurrences, m.totalCoauthorOccurrences)
         |      ELSE '' END) AS summaryText,
         |  m.maximumTeamWorkIds AS evidenceWorkIds,
         |  $metricNames AS evidenceMetrics,
         |  array(m.researcherId) AS evidenceNodeIds
         |FROM {metrics} m LEFT JOIN n ON m.researcherId = n.nodeId""".stripMargin,
      "metrics" -> metrics,
      "researchers" -> researchers
    )
    val community = algo.sql(
      s"""WITH ranked AS (
         |  SELECT m.window, m.communityId, m.researcherId,
         |    COALESCE(n.displayName, m.researcherId) AS name,
         |    row_number() OVER (PARTITION BY m.window, m.communityId
         |      ORDER BY m.weightedDegree DESC, m.researcherId ASC) AS r
         |  FROM {metrics} m LEFT JOIN {researchers} n
         |    ON m.researcherId = n.nodeId),
         |top AS (
         |  SELECT window, communityId,
         |    array_sort(collect_list(struct(r, name, researcherId))) AS members
         |  FROM ranked WHERE r <= 3 GROUP BY window, communityId),
         |heavy AS (
         |  SELECT window, communityA AS communityId, representativeWorkIds
         |  FROM (SELECT *, row_number() OVER (PARTITION BY window, communityA
         |      ORDER BY weight DESC, edgeId ASC) AS r
         |    FROM {ec} WHERE internal)
         |  WHERE r = 1)
         |SELECT concat('summary:v1:', sha2(concat_ws('|', 'community',
         |    c.window, c.communityId), 256)) AS summaryId,
         |  'community' AS summaryType, c.window, w.windowStart, w.windowEnd,
         |  c.communityId AS subjectId,
         |  CAST(NULL AS STRING) AS nodeKind,
         |  format_string('Community %s in window %s has %d nodes (canonical researchers and unresolved author mentions), %d internal collaboration edges and %d edges to other communities. Most connected members by weighted degree: %s.',
         |    c.communityId, c.window, c.communitySize, c.internalEdges,
         |    c.crossEdges,
         |    concat_ws('; ', transform(t.members, x -> x.name))) AS summaryText,
         |  COALESCE(h.representativeWorkIds, $emptyStrings) AS evidenceWorkIds,
         |  array('communitySize', 'internalEdges', 'crossEdges',
         |    'weightedDegree') AS evidenceMetrics,
         |  transform(t.members, x -> x.researcherId) AS evidenceNodeIds
         |FROM {communities} c
         |JOIN {windows} w ON c.window = w.window
         |JOIN top t ON c.window = t.window AND c.communityId = t.communityId
         |LEFT JOIN heavy h ON c.window = h.window
         |  AND c.communityId = h.communityId""".stripMargin,
      "metrics" -> metrics,
      "researchers" -> researchers,
      "communities" -> communities,
      "ec" -> edgeCommunities,
      "windows" -> windows
    )
    val pathSummaries = algo.sql(
      s"""WITH named AS (
         |  SELECT p.pairKey, p.window, p.sourceId, p.targetId, p.status,
         |    p.unreachableReason, p.hopLimit, p.pathLength, p.hopIndex, p.jointPublicationCount,
         |    p.supportingWorkIds,
         |    COALESCE(sn.displayName, p.sourceId) AS sourceName,
         |    COALESCE(tn.displayName, p.targetId) AS targetName,
         |    COALESCE(fn.displayName, p.fromId) AS fromName,
         |    COALESCE(un.displayName, p.toId) AS toName
         |  FROM {paths} p
         |  LEFT JOIN {researchers} sn ON p.sourceId = sn.nodeId
         |  LEFT JOIN {researchers} tn ON p.targetId = tn.nodeId
         |  LEFT JOIN {researchers} fn ON p.fromId = fn.nodeId
         |  LEFT JOIN {researchers} un ON p.toId = un.nodeId),
         |per AS (
         |  SELECT pairKey, max(window) AS window, max(sourceId) AS sourceId,
         |    max(targetId) AS targetId, max(status) AS status,
         |    max(unreachableReason) AS unreachableReason,
         |    max(hopLimit) AS hopLimit, max(pathLength) AS pathLength,
         |    max(sourceName) AS sourceName, max(targetName) AS targetName,
         |    array_sort(collect_list(CASE WHEN hopIndex IS NOT NULL
         |      THEN struct(hopIndex, format_string('%s and %s share %d joint publication(s) (works %s)',
         |        fromName, toName, jointPublicationCount,
         |        concat_ws(', ', supportingWorkIds)) AS hopText) END)) AS hops,
         |    array_sort(array_distinct(flatten(collect_list(supportingWorkIds))))
         |      AS evidenceWorkIds
         |  FROM named GROUP BY pairKey)
         |SELECT concat('summary:v1:', sha2(concat_ws('|', 'collaboration-path',
         |    p.pairKey), 256)) AS summaryId,
         |  'collaboration-path' AS summaryType, p.window, w.windowStart,
         |  w.windowEnd, p.pairKey AS subjectId,
         |  CAST(NULL AS STRING) AS nodeKind,
         |  CASE p.status
         |    WHEN 'found' THEN format_string('In window %s the shortest collaboration path from %s to %s has %d hops: %s.',
         |      p.window, p.sourceName, p.targetName, p.pathLength,
         |      concat_ws('; ', transform(p.hops, x -> x.hopText)))
         |    WHEN 'unreachable-within-bound' THEN
         |      CASE p.unreachableReason
         |        WHEN 'different-component' THEN format_string('In window %s %s and %s are in different connected components of the collaboration graph, so no collaboration path connects them.',
         |          p.window, p.sourceName, p.targetName)
         |        ELSE format_string('In window %s no collaboration path within the configured hop limit connects %s and %s.',
         |          p.window, p.sourceName, p.targetName) END
         |    WHEN 'trivial' THEN format_string('In window %s the researchers %s and %s are the same node.',
         |      p.window, p.sourceName, p.targetName)
         |    ELSE format_string('In window %s %s or %s has no dated publication in the window, so no path is defined.',
         |      p.window, p.sourceName, p.targetName) END AS summaryText,
         |  COALESCE(p.evidenceWorkIds, $emptyStrings) AS evidenceWorkIds,
         |  array('pathLength', 'jointPublicationCount') AS evidenceMetrics,
         |  array(p.sourceId, p.targetId) AS evidenceNodeIds
         |FROM per p JOIN {windows} w ON p.window = w.window""".stripMargin,
      "paths" -> paths,
      "researchers" -> researchers,
      "windows" -> windows
    )
    val papers = algo.sql(
      s"""SELECT concat('summary:v1:', sha2(concat_ws('|', 'work',
         |    w.canonicalWorkId), 256)) AS summaryId,
         |  'work' AS summaryType, 'not-windowed' AS window,
         |  CAST(NULL AS INT) AS windowStart, CAST(NULL AS INT) AS windowEnd,
         |  w.canonicalWorkId AS subjectId,
         |  CAST(NULL AS STRING) AS nodeKind,
         |  format_string('Work %s (%s, year %d) has %d authors, of which %d are unresolved author mentions. Venue: %s. DOI: %s.',
         |    w.title, w.canonicalWorkId, w.year, t.teamSize,
         |    t.unresolvedAuthors, COALESCE(w.venueId, 'none recorded'),
         |    COALESCE(w.doi, 'none recorded')) AS summaryText,
         |  array(w.canonicalWorkId) AS evidenceWorkIds,
         |  array('teamSize', 'unresolvedAuthors', 'year') AS evidenceMetrics,
         |  $emptyStrings AS evidenceNodeIds
         |FROM {works} w JOIN {team} t ON w.canonicalWorkId = t.canonicalWorkId
         |WHERE w.year IS NOT NULL
         |ORDER BY t.teamSize DESC, w.canonicalWorkId ASC
         |LIMIT $paperLimit""".stripMargin,
      "works" -> works,
      "team" -> workTeam
    )
    algo.sql(
      s"""SELECT u.*, ${ctx.provenance("semantic-summaries", "u.summaryId")}
         |FROM (
         |  SELECT * FROM {profiles}
         |  UNION ALL SELECT * FROM {community}
         |  UNION ALL SELECT * FROM {paths}
         |  UNION ALL SELECT * FROM {papers}) u""".stripMargin,
      "profiles" -> profiles,
      "community" -> community,
      "paths" -> pathSummaries,
      "papers" -> papers
    )
