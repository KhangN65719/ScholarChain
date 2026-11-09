package scholarchain.storage

import scholarchain.core.Provenance

/** A connected component of one window, from the Spark `components` table. */
final case class ComponentInfo(
    window: String,
    componentId: String,
    size: Long,
    isLargest: Boolean,
    provenance: Provenance
)

/** Runtime read port for connected components. Absent is `None`. */
trait ComponentReadPort:
  def component(window: String, componentId: String): Option[ComponentInfo]

  /** The component a researcher belongs to in a window. */
  def componentOf(window: String, researcherId: String): Option[ComponentInfo]

  /** A keyset page of member IDs in ascending order; `None` for an unknown
    * component.
    */
  def members(
      window: String,
      componentId: String,
      after: Option[String],
      limit: Int
  ): Option[MemberPage]

/** A community of one window, from the Spark `communities` table. */
final case class CommunityInfo(
    window: String,
    communityId: String,
    size: Long,
    internalEdges: Long,
    crossEdges: Long,
    internalWeight: Long,
    crossWeight: Long,
    provenance: Provenance
)

/** An edge bundle between two communities, from `community-cross-edges`. */
final case class CommunityLink(
    window: String,
    communityId: String,
    neighborId: String,
    edgeCount: Long,
    totalWeight: Long,
    provenance: Provenance
)

final case class NeighborPage(
    neighbors: Vector[CommunityLink],
    total: Int,
    truncated: Boolean
)

/** Runtime read port for communities. Absent is `None`. */
trait CommunityReadPort:
  def community(window: String, communityId: String): Option[CommunityInfo]

  def communityOf(window: String, researcherId: String): Option[CommunityInfo]

  def members(
      window: String,
      communityId: String,
      after: Option[String],
      limit: Int
  ): Option[MemberPage]

  /** Edges that leave the community, all neighbors counted. */
  def crossCommunityEdgeCount(
      window: String,
      communityId: String
  ): Option[Long]

  /** Neighboring communities, the most connected first (edge count, then ID).
    */
  def neighbors(
      window: String,
      communityId: String,
      limit: Int
  ): Option[NeighborPage]

private object PartitionRows:
  def windowed(row: Row, id: String): (String, String) =
    (Rows.str(row, "window"), Rows.str(row, id))

  def nodeId(row: Row, column: String): String =
    val id = Rows.str(row, column)
    Fail.unless(
      scholarchain.core.StableIds.isCoauthorNode(id),
      ErrorCode.MalformedRow,
      s"$column is not a core node ID: $id"
    )
    id

  def nonNegative(value: Long, what: String): Long =
    Fail.unless(value >= 0, ErrorCode.MalformedRow, s"$what is negative")
    value

  /** Membership rows grouped by (window, group id) with the group size each row
    * declares. Every researcher appears once per window.
    */
  def membership(
      rows: Vector[Row],
      groupColumn: String
  ): Map[(String, String), (Long, Vector[String])] =
    val seen = scala.collection.mutable.HashSet.empty[(String, String)]
    val parsed = rows.map { row =>
      val window = Rows.str(row, "window")
      val researcher = nodeId(row, "researcherId")
      Fail.unless(
        seen.add((window, researcher)),
        ErrorCode.DuplicateId,
        s"$researcher appears twice in window $window"
      )
      (
        (window, Rows.str(row, groupColumn)),
        Rows.long(row, s"${groupColumn.stripSuffix("Id")}Size"),
        researcher
      )
    }
    parsed.groupBy(_._1).map { case (key, group) =>
      val sizes = group.map(_._2).distinct
      Fail.unless(
        sizes.size == 1 && sizes.head == group.size.toLong,
        ErrorCode.RowCountMismatch,
        s"${key._2} in ${key._1}: declared size ${sizes.mkString("/")}, " +
          s"${group.size} membership rows"
      )
      key -> (sizes.head, group.map(_._3).sorted)
    }

/** Local fake of the component port, from the Spark `components` and
  * `component-membership` tables. Construction refuses a membership row of an
  * unknown component, a repeated researcher, and a component whose declared
  * size is not its member count.
  */
final class InMemoryComponents(
    components: Vector[ComponentInfo],
    membership: Map[(String, String), Vector[String]]
) extends ComponentReadPort:
  private val byKey = components.map(c => (c.window, c.componentId) -> c).toMap
  private val ofResearcher: Map[(String, String), ComponentInfo] =
    membership.flatMap { case ((window, component), ids) =>
      ids.map(id => (window, id) -> byKey((window, component)))
    }

  def component(window: String, componentId: String): Option[ComponentInfo] =
    byKey.get((window, componentId))

  def componentOf(window: String, researcherId: String): Option[ComponentInfo] =
    ofResearcher.get((window, researcherId))

  def members(
      window: String,
      componentId: String,
      after: Option[String],
      limit: Int
  ): Option[MemberPage] =
    ReadLimits.bounded(limit, ReadLimits.MaxPage, "limit")
    membership
      .get((window, componentId))
      .map(ids => MemberPage.of(ids, after, limit))

object InMemoryComponents:
  def from(tables: TableSource, bounds: LoadBounds): InMemoryComponents =
    def read(name: String): Vector[Row] =
      val rows = tables.rows(name)
      bounds.checkRows(rows.size.toLong, name)
      rows
    val infos = read("components").map { row =>
      ComponentInfo(
        Rows.str(row, "window"),
        Rows.str(row, "componentId"),
        PartitionRows.nonNegative(Rows.long(row, "componentSize"), "size"),
        Rows.optBool(row, "isLargest").getOrElse(false),
        Rows.provenance(row)
      )
    }
    val keys = infos.map(c => (c.window, c.componentId))
    Fail.unless(
      keys.distinct.size == keys.size,
      ErrorCode.DuplicateId,
      "components has a repeated (window, componentId)"
    )
    val member = PartitionRows.membership(
      read("component-membership"),
      "componentId"
    )
    val known = infos.map(c => (c.window, c.componentId) -> c).toMap
    member.foreach { case (key, (size, _)) =>
      val info = known.getOrElse(
        key,
        Fail(
          ErrorCode.MissingEndpoint,
          s"component-membership names unknown component ${key._2}"
        )
      )
      Fail.unless(
        info.size == size,
        ErrorCode.RowCountMismatch,
        s"Component ${key._2}: size ${info.size} differs from members $size"
      )
    }
    new InMemoryComponents(
      infos,
      member.map { case (key, (_, ids)) => key -> ids }
    )

/** Local fake of the community port, from the Spark `communities`,
  * `community-membership` and `community-cross-edges` tables. Construction
  * refuses unknown communities, a size that is not the member count, a cross
  * edge pair that is not ordered `communityA < communityB`, and cross-edge
  * counts that do not add up to the community's `crossEdges`.
  */
final class InMemoryCommunities(
    communities: Vector[CommunityInfo],
    membership: Map[(String, String), Vector[String]],
    links: Vector[CommunityLink]
) extends CommunityReadPort:
  private val byKey = communities.map(c => (c.window, c.communityId) -> c).toMap
  private val ofResearcher: Map[(String, String), CommunityInfo] =
    membership.flatMap { case ((window, community), ids) =>
      ids.map(id => (window, id) -> byKey((window, community)))
    }
  private val neighborsOf: Map[(String, String), Vector[CommunityLink]] =
    links.groupBy(l => (l.window, l.communityId)).map { case (key, group) =>
      key -> group.sortBy(l => (-l.edgeCount, l.neighborId))
    }

  def community(window: String, communityId: String): Option[CommunityInfo] =
    byKey.get((window, communityId))

  def communityOf(window: String, researcherId: String): Option[CommunityInfo] =
    ofResearcher.get((window, researcherId))

  def members(
      window: String,
      communityId: String,
      after: Option[String],
      limit: Int
  ): Option[MemberPage] =
    ReadLimits.bounded(limit, ReadLimits.MaxPage, "limit")
    membership
      .get((window, communityId))
      .map(ids => MemberPage.of(ids, after, limit))

  def crossCommunityEdgeCount(
      window: String,
      communityId: String
  ): Option[Long] =
    community(window, communityId).map(_.crossEdges)

  def neighbors(
      window: String,
      communityId: String,
      limit: Int
  ): Option[NeighborPage] =
    val bound = ReadLimits.bounded(limit, ReadLimits.MaxPage, "limit")
    community(window, communityId).map { _ =>
      val all = neighborsOf.getOrElse((window, communityId), Vector.empty)
      NeighborPage(all.take(bound), all.size, all.size > bound)
    }

object InMemoryCommunities:
  def from(tables: TableSource, bounds: LoadBounds): InMemoryCommunities =
    def read(name: String): Vector[Row] =
      val rows = tables.rows(name)
      bounds.checkRows(rows.size.toLong, name)
      rows
    val infos = read("communities").map { row =>
      def count(column: String): Long =
        PartitionRows.nonNegative(Rows.long(row, column), column)
      CommunityInfo(
        Rows.str(row, "window"),
        Rows.str(row, "communityId"),
        count("communitySize"),
        count("internalEdges"),
        count("crossEdges"),
        count("internalWeight"),
        count("crossWeight"),
        Rows.provenance(row)
      )
    }
    val byKey = infos.map(c => (c.window, c.communityId) -> c).toMap
    Fail.unless(
      byKey.size == infos.size,
      ErrorCode.DuplicateId,
      "communities has a repeated (window, communityId)"
    )
    val member = PartitionRows.membership(
      read("community-membership"),
      "communityId"
    )
    member.foreach { case (key, (size, _)) =>
      val info = byKey.getOrElse(
        key,
        Fail(
          ErrorCode.MissingEndpoint,
          s"community-membership names unknown community ${key._2}"
        )
      )
      Fail.unless(
        info.size == size,
        ErrorCode.RowCountMismatch,
        s"Community ${key._2}: size ${info.size} differs from members $size"
      )
    }
    val cross = read("community-cross-edges").map { row =>
      val window = Rows.str(row, "window")
      val a = Rows.str(row, "communityA")
      val b = Rows.str(row, "communityB")
      Fail.unless(
        a < b,
        ErrorCode.MalformedRow,
        s"Cross edge $a / $b is not ordered communityA < communityB"
      )
      Fail.unless(
        byKey.contains((window, a)) && byKey.contains((window, b)),
        ErrorCode.MissingEndpoint,
        s"Cross edge $a / $b names an unknown community in $window"
      )
      (
        window,
        a,
        b,
        PartitionRows.nonNegative(Rows.long(row, "edgeCount"), "edgeCount"),
        PartitionRows.nonNegative(Rows.long(row, "totalWeight"), "totalWeight"),
        Rows.provenance(row)
      )
    }
    Fail.unless(
      cross.map(c => (c._1, c._2, c._3)).distinct.size == cross.size,
      ErrorCode.DuplicateId,
      "community-cross-edges has a repeated community pair"
    )
    val links = cross.flatMap { (window, a, b, edges, weight, provenance) =>
      Vector(
        CommunityLink(window, a, b, edges, weight, provenance),
        CommunityLink(window, b, a, edges, weight, provenance)
      )
    }
    val summed = links
      .groupBy(l => (l.window, l.communityId))
      .map { case (key, group) => key -> group.map(_.edgeCount).sum }
    infos.foreach { c =>
      val rowsSum = summed.getOrElse((c.window, c.communityId), 0L)
      Fail.unless(
        rowsSum == c.crossEdges,
        ErrorCode.RowCountMismatch,
        s"Community ${c.communityId}: cross-edge rows sum to $rowsSum, " +
          s"crossEdges is ${c.crossEdges}"
      )
    }
    new InMemoryCommunities(
      infos,
      member.map { case (key, (_, ids)) => key -> ids },
      links
    )
