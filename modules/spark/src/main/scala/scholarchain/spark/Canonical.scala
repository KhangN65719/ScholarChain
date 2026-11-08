package scholarchain.spark

import org.apache.spark.sql.{DataFrame, SparkSession}

/** The canonical tables of job 1. Frames are lazy and persisted where reused.
  */
final case class CanonicalTables(
    works: DataFrame,
    workMembers: DataFrame,
    mentionBase: DataFrame,
    researchers: DataFrame,
    authored: DataFrame,
    unresolved: DataFrame,
    violations: DataFrame,
    venues: DataFrame,
    topics: DataFrame,
    institutions: DataFrame,
    publishedIn: DataFrame
)

/** Job 1: canonical Researcher, Work, AUTHORED, Venue, Topic and Institution
  * tables from the accepted links and the Hadoop exports.
  *
  * Identity rules. A researcher node is `canonicalResearcherId` when the ER
  * link carries one. A mention with a null canonical ID is its own node
  * (`nodeKind = unresolved-mention`, identity = the mention ID) and is never
  * merged by name or by anything else. A work maps through `canonicalWorkId`;
  * the authors of a canonical work are the authors of its representative record
  * (the anchor record, else the smallest member ID), so a duplicate record is
  * counted once in every publication count, pair weight and team size. Mentions
  * of the other records stay in the AUTHORED table, flagged
  * `countedInMetrics = false`, when they are resolved; an unresolved mention of
  * a non-representative record is not a node and is listed in
  * `unresolved-mentions` with `isNode = false`.
  */
object Canonical:
  val violationColumns =
    "entityType, entityId, reason, action, workId, mentionId, detail"

  /** One row per identity. A repeated input row (same key) is one fact, not
    * two: without this a repeated works row is a merge of two records and a
    * repeated link row is a second AUTHORED edge. Rows that repeat a key with
    * different contents keep the smallest row (struct order), so the choice
    * does not depend on partitioning or file order.
    */
  def distinctByKey(
      session: SparkSession,
      frame: DataFrame,
      key: Vector[String]
  ): DataFrame =
    val rest = frame.columns.toVector.filterNot(key.contains)
    val keys = key.mkString(", ")
    if rest.isEmpty then
      Sql.run(session, s"SELECT DISTINCT $keys FROM {f}", "f" -> frame)
    else
      Sql.run(
        session,
        s"""SELECT $keys, r.*
           |FROM (SELECT $keys, min(struct(${rest.mkString(", ")})) AS r
           |  FROM {f} GROUP BY $keys)""".stripMargin,
        "f" -> frame
      )

  /** Raw input datasets checked for repeated keys, with their identity key. */
  def duplicateSpecs(
      raw: RawInputs
  ): Vector[(String, Vector[String], DataFrame)] =
    Vector(
      ("researcherLinks", Vector("mentionId"), raw.links),
      ("workLinks", Vector("workId"), raw.workLinks),
      (
        "canonicalResearchers",
        Vector("canonicalResearcherId"),
        raw.canonical
      ),
      ("works", Vector("workId"), raw.works),
      ("mentions", Vector("mentionId"), raw.mentions),
      (
        "authorPapers",
        Vector("mentionId", "workId", "authorPosition"),
        raw.authorPapers
      ),
      (
        "pairCandidates",
        Vector("workId", "mentionA", "mentionB"),
        raw.pairs
      )
    )

  /** One row (duplicateKeyRows, conflictingKeys) for a raw dataset.
    * `duplicateKeyRows` is the number of rows beyond the first of each key;
    * `conflictingKeys` is the number of keys whose rows are not all equal, so
    * `distinctByKey` has to choose one of them.
    */
  def duplicateStats(
      session: SparkSession,
      frame: DataFrame,
      key: Vector[String]
  ): DataFrame =
    val rest = frame.columns.toVector.filterNot(key.contains)
    val variants =
      if rest.isEmpty then "CAST(1 AS BIGINT)"
      else s"count(DISTINCT struct(${rest.mkString(", ")}))"
    Sql.run(
      session,
      s"""SELECT CAST(COALESCE(sum(n - 1), 0) AS BIGINT) AS duplicateKeyRows,
         |  CAST(COALESCE(sum(CASE WHEN variants > 1 THEN 1 ELSE 0 END), 0)
         |    AS BIGINT) AS conflictingKeys
         |FROM (SELECT count(*) AS n, $variants AS variants
         |  FROM {f} GROUP BY ${key.mkString(", ")})""".stripMargin,
      "f" -> frame
    )

  def build(
      session: SparkSession,
      rawInputs: RawInputs,
      ctx: RunContext
  ): CanonicalTables =
    val raw = rawInputs.copy(
      links = distinctByKey(session, rawInputs.links, Vector("mentionId")),
      workLinks = distinctByKey(session, rawInputs.workLinks, Vector("workId")),
      canonical = distinctByKey(
        session,
        rawInputs.canonical,
        Vector("canonicalResearcherId")
      ),
      works = distinctByKey(session, rawInputs.works, Vector("workId")),
      mentions = distinctByKey(session, rawInputs.mentions, Vector("mentionId"))
    )
    val members = Sql.run(
      session,
      """SELECT w.workId,
        |  COALESCE(l.canonicalWorkId, w.workId) AS canonicalWorkId,
        |  l.workId IS NULL AS missingLink,
        |  w.year, w.title, w.venueId, w.doi, w.source, w.sourceRecordId
        |FROM {works} w LEFT JOIN {workLinks} l ON w.workId = l.workId""".stripMargin,
      "works" -> raw.works,
      "workLinks" -> raw.workLinks
    )
    val works = workTable(session, members, ctx).persist()
    val base = authoredBase(session, raw, members, works)
    val authored = authoredEdges(session, base, raw, ctx).persist()
    val unresolved = unresolvedMentions(session, base, raw, ctx)
    val researchers =
      researcherNodes(session, raw, authored, unresolved, ctx).persist()
    val venues = venueNodes(session, raw, works, ctx)
    val violations = allViolations(session, raw, members, base, works, venues)
    CanonicalTables(
      works = works,
      workMembers = members,
      mentionBase = base,
      researchers = researchers,
      authored = authored,
      unresolved = unresolved,
      violations = violations.persist(),
      venues = venues,
      topics = topicNodes(session, raw, ctx),
      institutions = institutionNodes(session, raw, ctx),
      publishedIn = publishedIn(session, works, venues, ctx)
    )

  private def workTable(
      session: SparkSession,
      members: DataFrame,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT canonicalWorkId,
         |  rep.workId AS representativeWorkId,
         |  memberCount, memberWorkIds,
         |  rep.title AS title,
         |  COALESCE(rep.year, minYear) AS year,
         |  rep.year IS NULL AND minYear IS NOT NULL AS yearFromDuplicateRecord,
         |  rep.venueId AS venueId, rep.doi AS doi,
         |  rep.source AS source, rep.sourceRecordId AS sourceRecordId,
         |  ${ctx.provenance("nodes-work", "canonicalWorkId")}
         |FROM (
         |  SELECT canonicalWorkId,
         |    min(struct(CASE WHEN workId = canonicalWorkId THEN 0 ELSE 1 END AS r,
         |      workId, title, year, venueId, doi, source, sourceRecordId)) AS rep,
         |    min(year) AS minYear,
         |    count(*) AS memberCount,
         |    sort_array(collect_list(workId)) AS memberWorkIds
         |  FROM {members} GROUP BY canonicalWorkId)""".stripMargin,
      "members" -> members
    )

  /** Every author-paper row with its link, work, representative and checks. */
  private def authoredBase(
      session: SparkSession,
      raw: RawInputs,
      members: DataFrame,
      works: DataFrame
  ): DataFrame =
    val joined = Sql.run(
      session,
      """SELECT a.mentionId, a.workId, a.authorPosition,
        |  l.mentionId IS NOT NULL AS hasLink,
        |  l.workId AS linkWorkId,
        |  l.canonicalResearcherId AS canonicalResearcherId,
        |  l.status AS linkStatus,
        |  l.clusterAnchor AS clusterAnchor,
        |  l.clusterSize AS clusterSize,
        |  m.canonicalWorkId AS canonicalWorkId,
        |  m.workId IS NOT NULL AS workKnown,
        |  (m.workId IS NOT NULL AND m.workId = w.representativeWorkId)
        |    AS isRepresentativeRecord,
        |  c.canonicalResearcherId IS NOT NULL AS researcherKnown,
        |  w.year AS year
        |FROM (SELECT DISTINCT mentionId, workId, authorPosition
        |      FROM {authorPapers}) a
        |LEFT JOIN {links} l ON a.mentionId = l.mentionId
        |LEFT JOIN {members} m ON a.workId = m.workId
        |LEFT JOIN {works} w ON m.canonicalWorkId = w.canonicalWorkId
        |LEFT JOIN {canonical} c
        |  ON l.canonicalResearcherId = c.canonicalResearcherId""".stripMargin,
      "authorPapers" -> raw.authorPapers,
      "links" -> raw.links,
      "members" -> members,
      "works" -> works,
      "canonical" -> raw.canonical
    )
    Sql
      .run(
        session,
        """SELECT *,
          |  CASE
          |    WHEN NOT workKnown THEN 'work-record-missing'
          |    WHEN hasLink AND linkWorkId <> workId THEN 'link-work-mismatch'
          |    WHEN hasLink AND canonicalResearcherId IS NOT NULL
          |      AND NOT researcherKnown THEN 'canonical-researcher-missing'
          |    WHEN NOT hasLink THEN 'mention-without-link'
          |    ELSE NULL END AS violationReason
          |FROM {joined}""".stripMargin,
        "joined" -> joined
      )
      .persist()

  /** Valid AUTHORED edges. An edge whose researcher node would not exist (an
    * unresolved mention of a duplicate record) is not written here.
    */
  private def authoredEdges(
      session: SparkSession,
      base: DataFrame,
      raw: RawInputs,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT COALESCE(canonicalResearcherId, mentionId) AS researcherNodeId,
         |  CASE WHEN canonicalResearcherId IS NULL
         |    THEN 'unresolved-mention' ELSE 'canonical' END AS nodeKind,
         |  canonicalWorkId, workId, mentionId, authorPosition, year,
         |  isRepresentativeRecord AS countedInMetrics,
         |  ${ctx.provenance("edges-authored", "mentionId")}
         |FROM {base}
         |WHERE (violationReason IS NULL OR violationReason = 'mention-without-link')
         |  AND (canonicalResearcherId IS NOT NULL OR isRepresentativeRecord)""".stripMargin,
      "base" -> base
    )

  private def unresolvedMentions(
      session: SparkSession,
      base: DataFrame,
      raw: RawInputs,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT b.mentionId, b.workId, b.canonicalWorkId,
         |  COALESCE(b.linkStatus, 'no-link-row') AS reason,
         |  b.clusterAnchor, b.clusterSize,
         |  b.isRepresentativeRecord AS isNode,
         |  m.name AS displayName,
         |  ${ctx.provenance("unresolved-mentions", "b.mentionId")}
         |FROM {base} b LEFT JOIN {mentions} m ON b.mentionId = m.mentionId
         |WHERE b.canonicalResearcherId IS NULL
         |  AND (b.violationReason IS NULL
         |    OR b.violationReason = 'mention-without-link')""".stripMargin,
      "base" -> base,
      "mentions" -> raw.mentions
    )

  private def researcherNodes(
      session: SparkSession,
      raw: RawInputs,
      authored: DataFrame,
      unresolved: DataFrame,
      ctx: RunContext
  ): DataFrame =
    val names = Sql.run(
      session,
      """SELECT a.researcherNodeId AS nodeId,
        |  min_by(m.name, concat(m.normalizedName, '|', m.name)) AS displayName,
        |  count(*) AS mentionCount
        |FROM {authored} a LEFT JOIN {mentions} m ON a.mentionId = m.mentionId
        |GROUP BY a.researcherNodeId""".stripMargin,
      "authored" -> authored,
      "mentions" -> raw.mentions
    )
    val nodes = Sql.run(
      session,
      """SELECT c.canonicalResearcherId AS nodeId,
        |  'canonical' AS nodeKind,
        |  c.idRule AS idRule, c.orcid AS orcid, c.status AS linkStatus,
        |  c.memberCount AS memberCount,
        |  c.inResearchersExport AS inResearchersExport,
        |  n.displayName AS displayName,
        |  COALESCE(n.mentionCount, 0) AS mentionCount
        |FROM {canonical} c LEFT JOIN {names} n
        |  ON c.canonicalResearcherId = n.nodeId
        |UNION ALL
        |SELECT u.mentionId AS nodeId,
        |  'unresolved-mention' AS nodeKind,
        |  CAST(NULL AS STRING) AS idRule, CAST(NULL AS STRING) AS orcid,
        |  u.reason AS linkStatus,
        |  CAST(1 AS BIGINT) AS memberCount,
        |  CAST(NULL AS BOOLEAN) AS inResearchersExport,
        |  u.displayName AS displayName,
        |  CAST(1 AS BIGINT) AS mentionCount
        |FROM {unresolved} u WHERE u.isNode""".stripMargin,
      "canonical" -> raw.canonical,
      "names" -> names,
      "unresolved" -> unresolved
    )
    Sql.run(
      session,
      s"""SELECT *, ${ctx.provenance("nodes-researcher", "nodeId")}
         |FROM {nodes}""".stripMargin,
      "nodes" -> nodes
    )

  private def venueNodes(
      session: SparkSession,
      raw: RawInputs,
      works: DataFrame,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT v.venueId, v.best.name AS name, v.best.issns AS issns,
         |  v.best.inputSource AS inputSource,
         |  v.best.inputSnapshotId AS inputSnapshotId,
         |  v.inputRows AS inputRows,
         |  COALESCE(c.canonicalWorkCount, 0) AS canonicalWorkCount,
         |  ${ctx.provenance("nodes-venue", "v.venueId")}
         |FROM (
         |  SELECT venueId, count(*) AS inputRows,
         |    min_by(struct(name, issns, provenance.source AS inputSource,
         |      provenance.snapshotId AS inputSnapshotId),
         |      concat(name, '|', provenance.sourceRecordId)) AS best
         |  FROM {venues} GROUP BY venueId) v
         |LEFT JOIN (
         |  SELECT venueId, count(*) AS canonicalWorkCount
         |  FROM {works} WHERE venueId IS NOT NULL GROUP BY venueId) c
         |  ON v.venueId = c.venueId""".stripMargin,
      "venues" -> raw.venues,
      "works" -> works
    )

  private def topicNodes(
      session: SparkSession,
      raw: RawInputs,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT t.topicId, t.best.name AS name,
         |  t.best.parentTopicId AS parentTopicId,
         |  t.best.inputSource AS inputSource,
         |  t.best.inputSnapshotId AS inputSnapshotId,
         |  t.inputRows AS inputRows,
         |  ${ctx.provenance("nodes-topic", "t.topicId")}
         |FROM (
         |  SELECT topicId, count(*) AS inputRows,
         |    min_by(struct(name, parentTopicId, provenance.source AS inputSource,
         |      provenance.snapshotId AS inputSnapshotId),
         |      concat(name, '|', provenance.sourceRecordId)) AS best
         |  FROM {topics} GROUP BY topicId) t""".stripMargin,
      "topics" -> raw.topics
    )

  private def institutionNodes(
      session: SparkSession,
      raw: RawInputs,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT i.institutionId, i.best.name AS name,
         |  i.best.countryCode AS countryCode,
         |  to_json(i.best.externalIds) AS externalIdsJson,
         |  i.best.inputSource AS inputSource,
         |  i.best.inputSnapshotId AS inputSnapshotId,
         |  i.inputRows AS inputRows,
         |  ${ctx.provenance("nodes-institution", "i.institutionId")}
         |FROM (
         |  SELECT institutionId, count(*) AS inputRows,
         |    min_by(struct(name, countryCode, externalIds,
         |      provenance.source AS inputSource,
         |      provenance.snapshotId AS inputSnapshotId),
         |      concat(name, '|', provenance.sourceRecordId)) AS best
         |  FROM {institutions} GROUP BY institutionId) i""".stripMargin,
      "institutions" -> raw.institutions
    )

  /** Work to venue edges. A work whose venue is not exported has no edge. */
  private def publishedIn(
      session: SparkSession,
      works: DataFrame,
      venues: DataFrame,
      ctx: RunContext
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT w.canonicalWorkId AS fromId, v.venueId AS toId,
         |  ${ctx.provenance(
          "edges-published-in",
          "concat(w.canonicalWorkId, '|', v.venueId)"
        )}
         |FROM {works} w JOIN {venues} v ON w.venueId = v.venueId""".stripMargin,
      "works" -> works,
      "venues" -> venues
    )

  private def allViolations(
      session: SparkSession,
      raw: RawInputs,
      members: DataFrame,
      base: DataFrame,
      works: DataFrame,
      venues: DataFrame
  ): DataFrame =
    Sql.run(
      session,
      s"""SELECT 'authored' AS entityType, mentionId AS entityId,
         |  violationReason AS reason,
         |  CASE WHEN violationReason = 'mention-without-link'
         |    THEN 'kept-as-unresolved-node' ELSE 'edge-dropped' END AS action,
         |  workId, mentionId,
         |  concat('linkWorkId=', COALESCE(linkWorkId, 'null'),
         |    ' canonicalResearcherId=', COALESCE(canonicalResearcherId, 'null'))
         |    AS detail
         |FROM {base} WHERE violationReason IS NOT NULL
         |UNION ALL
         |SELECT 'work', workId, 'work-without-link',
         |  'kept-as-own-canonical-work', workId, CAST(NULL AS STRING),
         |  'no row in accepted work-links'
         |FROM {members} WHERE missingLink
         |UNION ALL
         |SELECT 'work', l.workId, 'work-link-without-record', 'ignored',
         |  l.workId, CAST(NULL AS STRING),
         |  'accepted work-links row for a work missing from the works export'
         |FROM {workLinks} l LEFT JOIN {rawWorks} w ON l.workId = w.workId
         |WHERE w.workId IS NULL
         |UNION ALL
         |SELECT 'published-in', w.canonicalWorkId, 'venue-not-found',
         |  'edge-dropped', w.representativeWorkId, CAST(NULL AS STRING),
         |  concat('venueId=', w.venueId)
         |FROM {works} w LEFT JOIN {venues} v ON w.venueId = v.venueId
         |WHERE w.venueId IS NOT NULL AND v.venueId IS NULL""".stripMargin,
      "base" -> base,
      "members" -> members,
      "workLinks" -> raw.workLinks,
      "rawWorks" -> raw.works,
      "works" -> works,
      "venues" -> venues
    )
