package scholarchain.er

import scala.collection.mutable
import scholarchain.core.{MatchOutcome, StableIds}
import scholarchain.er.matching.EntityKind

/** One decided pair, reduced to what clustering reads. */
final case class DecisionEdge(
    left: String,
    right: String,
    outcome: MatchOutcome
)

/** What clustering reads about a record: the work it came from and its
  * normalized authoritative identifiers by namespace.
  */
final case class Member(
    id: String,
    workId: String,
    authoritative: Map[String, Set[String]]
)

/** Why a cluster is not merged. */
enum FailureType(val wire: String):
  case ConflictingIdentifiers
      extends FailureType("conflicting-authoritative-identifiers")
  case ConflictDecisionInside
      extends FailureType("conflict-decision-inside-cluster")
  case SameRecordTwoMentions
      extends FailureType("same-work-record-two-mentions")
  case IdentifierSplitAcrossClusters
      extends FailureType("authoritative-identifier-split-across-clusters")

final case class Failure(failureType: FailureType, detail: Vector[String])

enum LinkStatus(val wire: String):
  case Merged extends LinkStatus("merged")
  case ExternalId extends LinkStatus("external-id")
  case Unlinked extends LinkStatus("unlinked")
  case IntegrityFailure extends LinkStatus("integrity-failure")

/** A connected component of ACCEPTED decisions. `anchor` is the smallest member
  * ID. `canonicalId` and `idRule` are None when no canonical ID may be given:
  * an unlinked researcher mention, or any member of a failed cluster.
  */
final case class Cluster(
    kind: EntityKind,
    anchor: String,
    members: Vector[String],
    failures: Vector[Failure],
    canonicalId: Option[String],
    idRule: Option[String],
    orcid: Option[String]
):
  def size: Int = members.size
  def failed: Boolean = failures.nonEmpty
  def status: LinkStatus =
    if failed then LinkStatus.IntegrityFailure
    else if size > 1 then LinkStatus.Merged
    else if canonicalId.isDefined && kind == EntityKind.Researcher then
      LinkStatus.ExternalId
    else LinkStatus.Unlinked

final case class ClusterResult(
    clusters: Vector[Cluster],
    selfLinksIgnored: Int,
    unknownEndpoints: Int,
    rejectedInside: Int,
    unresolvedInside: Int,
    conflictInside: Int
)

/** Builds clusters from ACCEPTED decisions alone. A CONFLICT, UNRESOLVED or
  * REJECTED decision never joins two records. Union by smallest ID makes the
  * result independent of edge order.
  *
  * Canonical ID rule for researchers: a cluster whose members carry exactly one
  * validated ORCID iD gets `StableIds.researcherFromExternal("orcid", iD)`,
  * which is the ID the graph-export researchers table already uses. A merged
  * cluster with no ORCID gets
  * `StableIds.researcherFromIdentity(identityVersion, anchor)`. A lone mention
  * with no ORCID gets none. A failed cluster gets none. Canonical work ID: the
  * anchor, which is a real work ID.
  */
object Clusters:
  val identityVersion = "scholarchain.er.identity/1"
  val identityRule = "scholarchain-identity/1"
  val orcidRule = "orcid"
  val orcidNamespace = "orcid"
  private val maxDetail = 50

  private final class UnionFind(size: Int):
    private val parent = Array.tabulate(size)(identity)

    def find(item: Int): Int =
      var root = item
      while parent(root) != root do root = parent(root)
      var walk = item
      while parent(walk) != root do
        val next = parent(walk)
        parent(walk) = root
        walk = next
      root

    def union(a: Int, b: Int): Unit =
      val rootA = find(a)
      val rootB = find(b)
      if rootA < rootB then parent(rootB) = rootA
      else if rootB < rootA then parent(rootA) = rootB

  /** Value groups of one namespace over a cluster: values listed together on
    * one record are one group. More than one group is a disagreement.
    */
  private def valueGroups(sets: Vector[Set[String]]): Int =
    val values = sets.flatten.distinct.sorted
    val index = values.zipWithIndex.toMap
    val groups = UnionFind(values.size)
    sets.foreach { set =>
      set.headOption.foreach(first =>
        set.foreach(value => groups.union(index(first), index(value)))
      )
    }
    values.indices.map(groups.find).distinct.size

  private def identifierFailures(
      cluster: Vector[Member]
  ): Vector[Failure] =
    val namespaces = cluster.flatMap(_.authoritative.keys).distinct.sorted
    namespaces.flatMap { namespace =>
      val sets = cluster.flatMap(
        _.authoritative.get(namespace).filter(_.nonEmpty)
      )
      if valueGroups(sets) > 1 then
        Some(
          Failure(
            FailureType.ConflictingIdentifiers,
            Vector(s"$namespace:${sets.flatten.distinct.sorted.mkString(",")}")
          )
        )
      else None
    }

  private def sameRecordFailures(cluster: Vector[Member]): Vector[Failure] =
    cluster
      .groupBy(_.workId)
      .toVector
      .filter(_._2.size > 1)
      .sortBy(_._1)
      .map((workId, mentions) =>
        Failure(
          FailureType.SameRecordTwoMentions,
          Vector(s"$workId:${mentions.map(_.id).sorted.mkString(",")}")
        )
      )

  def build(
      kind: EntityKind,
      members: Iterable[Member],
      edges: Vector[DecisionEdge]
  ): ClusterResult =
    val byId = members.map(m => m.id -> m).toMap
    val ids = byId.keys.toVector.sorted
    val index = ids.zipWithIndex.toMap
    val sets = UnionFind(ids.size)
    var selfLinks = 0
    var unknown = 0
    edges.foreach { edge =>
      if edge.outcome == MatchOutcome.Accepted then
        if edge.left == edge.right then selfLinks += 1
        else
          (index.get(edge.left), index.get(edge.right)) match
            case (Some(a), Some(b)) => sets.union(a, b)
            case _                  => unknown += 1
    }
    val roots = ids.indices.map(sets.find).toVector
    val grouped =
      ids.indices.groupBy(roots).map((root, items) => root -> items.toVector)
    val rootsSorted = grouped.keys.toVector.sorted
    val conflictsByRoot = mutable.Map.empty[Int, mutable.ArrayBuffer[String]]
    var rejected = 0
    var unresolved = 0
    var conflicts = 0
    edges.foreach { edge =>
      if edge.outcome != MatchOutcome.Accepted && edge.left != edge.right then
        (index.get(edge.left), index.get(edge.right)) match
          case (Some(a), Some(b)) if roots(a) == roots(b) =>
            edge.outcome match
              case MatchOutcome.Rejected   => rejected += 1
              case MatchOutcome.Unresolved => unresolved += 1
              case _                       =>
                conflicts += 1
                conflictsByRoot
                  .getOrElseUpdate(roots(a), mutable.ArrayBuffer.empty)
                  .append(s"${edge.left}|${edge.right}")
          case _ => ()
    }
    val first = rootsSorted.map { root =>
      val memberIds = grouped(root).map(ids)
      val infos = memberIds.map(byId)
      val inside = conflictsByRoot
        .get(root)
        .map(_.toVector.sorted)
        .getOrElse(Vector.empty)
      val failures =
        if memberIds.size < 2 then Vector.empty
        else
          identifierFailures(infos) ++ sameRecordFailures(infos) ++
            Option
              .when(inside.nonEmpty)(
                Failure(
                  FailureType.ConflictDecisionInside,
                  inside.take(maxDetail) ++
                    Option
                      .when(inside.size > maxDetail)(
                        s"+${inside.size - maxDetail} more"
                      )
                      .toVector
                )
              )
              .toVector
      val orcids =
        infos.flatMap(_.authoritative.getOrElse(orcidNamespace, Set.empty))
      (memberIds, failures, orcids.distinct.sorted)
    }
    val orcidOwners = first
      .collect {
        case (members, failures, Vector(orcid)) if failures.isEmpty =>
          orcid -> members.head
      }
      .groupMap(_._1)(_._2)
    val clusters = first.map { (memberIds, failures, orcids) =>
      val anchor = memberIds.head
      val split =
        kind == EntityKind.Researcher && failures.isEmpty && orcids.size == 1 &&
          orcidOwners(orcids.head).size > 1
      val allFailures =
        if split then
          failures :+ Failure(
            FailureType.IdentifierSplitAcrossClusters,
            Vector(s"orcid:${orcids.head}") ++
              orcidOwners(orcids.head).sorted.take(maxDetail).map("anchor:" + _)
          )
        else failures
      val (canonical, rule, orcid) =
        if allFailures.nonEmpty then (None, None, orcids.headOption)
        else if kind == EntityKind.Work then (Some(anchor), None, None)
        else if orcids.size == 1 then
          (
            Some(StableIds.researcherFromExternal(orcidNamespace, orcids.head)),
            Some(orcidRule),
            orcids.headOption
          )
        else if memberIds.size > 1 then
          (
            Some(StableIds.researcherFromIdentity(identityVersion, anchor)),
            Some(identityRule),
            None
          )
        else (None, None, None)
      Cluster(kind, anchor, memberIds, allFailures, canonical, rule, orcid)
    }
    ClusterResult(clusters, selfLinks, unknown, rejected, unresolved, conflicts)
