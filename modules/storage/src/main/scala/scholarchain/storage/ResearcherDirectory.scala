package scholarchain.storage

import scholarchain.core.Provenance

/** How a candidate's name matched the query. Both kinds are returned; neither
  * is a decision.
  *
  *   - `Exact`: the normalized names are equal.
  *   - `TokenSet`: the same normalized words in another order, such as
  *     `Smith, John` for `John Smith`.
  */
enum NameMatch:
  case Exact
  case TokenSet

/** One `nodes-researcher` row that matches a name. An unresolved author mention
  * (`nodeKind = unresolved-mention`, a `mention:` ID) is marked `isMention`,
  * never hidden. `linkStatus` and `idRule` are Spark's own text, carried
  * unread. `sharesOrcidWith` is the number of other nodes with the same ORCID,
  * so a split record is visible.
  */
final case class ResearcherCandidate(
    nodeId: String,
    displayName: String,
    nodeKind: String,
    orcid: Option[String],
    linkStatus: Option[String],
    idRule: Option[String],
    memberCount: Long,
    mentionCount: Long,
    isMention: Boolean,
    sharesOrcidWith: Int,
    matchKind: NameMatch,
    provenance: Provenance
)

/** Every candidate of a name lookup, bounded by `limit`; `total` counts all
  * matches. `affiliationAvailable` is false: `nodes-researcher` carries no
  * affiliation. Coauthor context comes from `CoauthorContext`.
  */
final case class CandidateResult(
    query: String,
    normalizedQuery: String,
    candidates: Vector[ResearcherCandidate],
    total: Int,
    truncated: Boolean,
    affiliationAvailable: Boolean,
    note: String
)

/** Runtime read port over the Spark `nodes-researcher` table. */
trait ResearcherDirectoryPort:
  /** Candidates whose normalized name equals the query or is a permutation of
    * its words. Exact matches come first, then by node ID. No candidate is
    * chosen; ambiguity is the caller's to resolve or to report.
    */
  def candidates(name: String, limit: Int): CandidateResult

  /** One node by ID, or `None`. */
  def byId(nodeId: String): Option[ResearcherCandidate]

object ResearcherDirectory:
  val AffiliationNote: String =
    "nodes-researcher has no affiliation column; affiliation is unavailable. " +
      "Use CoauthorContext for coauthor context."

/** Local fake over `nodes-researcher`. Refused on construction: a node ID that
  * is not a core ID, a repeated node ID, a node kind that disagrees with its ID
  * (`mention:` IDs are `unresolved-mention`), a negative count. A node with no
  * display name is kept for `byId` and counted in `unnamedCount`; it cannot be
  * found by name.
  */
final class InMemoryResearcherDirectory(rows: Vector[Row])
    extends ResearcherDirectoryPort:
  private val nodes: Vector[ResearcherCandidate] =
    InMemoryResearcherDirectory.parse(rows)

  private val byNode = nodes.map(n => n.nodeId -> n).toMap
  private val named = nodes.filter(_.displayName.nonEmpty)
  private val normalized =
    named.map(n => n -> Names.normalize(n.displayName)).filter(_._2.nonEmpty)

  def unnamedCount: Int = nodes.size - normalized.size

  def byId(nodeId: String): Option[ResearcherCandidate] = byNode.get(nodeId)

  def candidates(name: String, limit: Int): CandidateResult =
    val bound = ReadLimits.bounded(limit, ReadLimits.MaxCandidates, "limit")
    val query = Names.normalize(name)
    Fail.unless(
      query.nonEmpty,
      ErrorCode.InvalidQuery,
      "The name has no letters or digits"
    )
    val key = Names.tokenKey(query)
    val matches = normalized.flatMap { case (node, text) =>
      if text == query then Some(node.copy(matchKind = NameMatch.Exact))
      else if Names.tokenKey(text) == key then
        Some(node.copy(matchKind = NameMatch.TokenSet))
      else None
    }
    val ordered = matches.sortBy(n => (n.matchKind.ordinal, n.nodeId))
    CandidateResult(
      name,
      query,
      ordered.take(bound),
      ordered.size,
      ordered.size > bound,
      affiliationAvailable = false,
      ResearcherDirectory.AffiliationNote
    )

object InMemoryResearcherDirectory:
  def from(
      tables: TableSource,
      bounds: LoadBounds
  ): InMemoryResearcherDirectory =
    val rows = tables.rows("nodes-researcher")
    bounds.checkRows(rows.size.toLong, "nodes-researcher")
    new InMemoryResearcherDirectory(rows)

  private val UnresolvedKind = "unresolved-mention"

  private[storage] def parse(rows: Vector[Row]): Vector[ResearcherCandidate] =
    val orcidCounts = rows
      .flatMap(row => Rows.optStr(row, "orcid"))
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    val parsed = rows.map { row =>
      val id = Rows.str(row, "nodeId")
      Fail.unless(
        scholarchain.core.StableIds.isCoauthorNode(id),
        ErrorCode.MalformedRow,
        s"Researcher node ID is not a core ID: $id"
      )
      val kind = Rows.str(row, "nodeKind")
      val mention = id.startsWith("mention:")
      Fail.unless(
        mention == (kind == UnresolvedKind),
        ErrorCode.MalformedRow,
        s"Node $id has nodeKind $kind, which disagrees with its ID"
      )
      def count(column: String): Long =
        val value = Rows.optLong(row, column).getOrElse(0L)
        Fail.unless(value >= 0, ErrorCode.MalformedRow, s"$column is negative")
        value
      val orcid = Rows.optStr(row, "orcid")
      ResearcherCandidate(
        id,
        Rows.optStr(row, "displayName").getOrElse(""),
        kind,
        orcid,
        Rows.optStr(row, "linkStatus"),
        Rows.optStr(row, "idRule"),
        count("memberCount"),
        count("mentionCount"),
        mention,
        orcid.fold(0)(o => orcidCounts(o) - 1),
        NameMatch.Exact,
        Rows.provenance(row)
      )
    }
    Fail.unless(
      parsed.map(_.nodeId).distinct.size == parsed.size,
      ErrorCode.DuplicateId,
      "nodes-researcher repeats a node ID"
    )
    parsed

/** One coauthor of a researcher in a window, from the `coauthorsOf` template.
  */
final case class CoauthorLink(
    coauthorId: String,
    edgeId: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    evidencePointer: String
)

/** Coauthor context for a candidate, read through the closed Cypher catalog:
  * the existing `coauthorsOf` template and no other query.
  */
object CoauthorContext:
  def of(
      neptune: NeptuneQueryPort,
      researcherId: String,
      window: String,
      limit: Int
  ): Vector[CoauthorLink] =
    ReadLimits.nodeId(researcherId, "researcherId")
    val bound = ReadLimits.bounded(limit, CypherQuery.MaxLimit, "limit")
    neptune.run(CypherCatalog.coauthorsOf(researcherId, window, bound)).map {
      row =>
        CoauthorLink(
          Rows.str(row, "coauthorId"),
          Rows.str(row, "edgeId"),
          Rows.long(row, "jointPublicationCount"),
          Rows.int(row, "firstCollaborationYear"),
          Rows.int(row, "lastCollaborationYear"),
          Rows.str(row, "evidencePointer")
        )
    }
