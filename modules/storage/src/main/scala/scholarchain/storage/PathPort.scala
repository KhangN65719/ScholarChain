package scholarchain.storage

import scholarchain.core.Provenance

/** A bounded shortest-path request over the precomputed `path-evidence` table.
  * `maxDepth` bounds the hops of an answer, `maxResults` the hops returned, and
  * `timeoutMillis` the time the lookup may take.
  */
final case class PathQuery(
    window: String,
    sourceId: String,
    targetId: String,
    maxDepth: Int,
    maxResults: Int,
    timeoutMillis: Long
):
  ReadLimits.nodeId(sourceId, "sourceId")
  ReadLimits.nodeId(targetId, "targetId")
  ReadLimits.bounded(maxDepth, ReadLimits.MaxPathDepth, "maxDepth")
  ReadLimits.bounded(maxResults, ReadLimits.MaxPathResults, "maxResults")
  Fail.unless(
    window.nonEmpty,
    ErrorCode.InvalidQuery,
    "window must not be empty"
  )
  Fail.unless(
    timeoutMillis >= 1 && timeoutMillis <= ReadLimits.MaxPathTimeoutMillis,
    ErrorCode.InvalidQuery,
    s"timeoutMillis must be from 1 to ${ReadLimits.MaxPathTimeoutMillis}"
  )

/** One hop of a path, from the Spark row of that hop. Years are present when
  * the table has them.
  */
final case class PathHop(
    fromId: String,
    toId: String,
    edgeId: String,
    jointPublicationCount: Long,
    supportingWorkIds: Vector[String],
    firstCollaborationYear: Option[Int],
    lastCollaborationYear: Option[Int],
    provenance: Provenance
)

/** Why a pair has no path. Only `DifferentComponent` means that no path exists.
  * `BeyondHopLimit` means a path exists but is longer than the Spark hop limit,
  * and `UnreachableUnexplained` is a legacy row that says only
  * `unreachable-within-bound`; neither may be reported as "no path".
  */
enum NonPathKind:
  case Trivial
  case EndpointNotInWindow
  case DifferentComponent
  case BeyondHopLimit
  case UnreachableUnexplained

enum PathLookup:
  /** A shortest path, hops in order from the source to the target. */
  case Found(
      window: String,
      sourceId: String,
      targetId: String,
      hops: Vector[PathHop]
  )

  /** The table says why the pair has no path. `status` and `unreachableReason`
    * are the table's own text.
    */
  case NonPath(
      window: String,
      sourceId: String,
      targetId: String,
      status: String,
      unreachableReason: Option[String],
      kind: NonPathKind,
      provenance: Provenance
  )

  /** The pair was not selected for the precomputed table. This says nothing
    * about whether a path exists.
    */
  case NotPrecomputed(window: String, sourceId: String, targetId: String)

  /** A path exists with `pathLength` hops, above the requested `maxDepth`. */
  case ExceedsDepth(pathLength: Int, maxDepth: Int)

  /** A path exists with `hopCount` hops, above the requested `maxResults`. */
  case ExceedsResults(hopCount: Int, maxResults: Int)

  /** The lookup cost more than the timeout; nothing is known about the pair. */
  case TimedOut(timeoutMillis: Long, costMillis: Long)

  /** True only for a status that proves there is no path. */
  def noPathExists: Boolean = this match
    case NonPath(_, _, _, _, _, NonPathKind.DifferentComponent, _) => true
    case _                                                         => false

/** Runtime read port for precomputed shortest paths. */
trait PathEvidencePort:
  def shortestPath(query: PathQuery): PathLookup

/** A pair read from `path-evidence`: its hops, or its non-path status. */
private[storage] enum PairEntry:
  case Hops(hops: Vector[PathHop])
  case Status(row: PathEvidenceRow)

/** Local fake of the path port, built from validated `PathEvidenceRow`s.
  *
  * `cost` is the deterministic timeout hook: it returns the simulated cost of a
  * query in milliseconds, and a cost above the query's timeout answers
  * `TimedOut`. Nothing sleeps. A pair is looked up in the stored orientation
  * first, then reversed (hops reversed and flipped), because Spark stores one
  * orientation of an undirected pair.
  */
final class InMemoryPathEvidence(
    rows: Vector[PathEvidenceRow],
    cost: PathQuery => Long = _ => 0L
) extends PathEvidencePort:
  import PathLookup.*

  private val pairs: Map[(String, String, String), PairEntry] =
    InMemoryPathEvidence.index(rows)

  def shortestPath(query: PathQuery): PathLookup =
    val spent = cost(query)
    if spent > query.timeoutMillis then TimedOut(query.timeoutMillis, spent)
    else
      val direct = pairs.get((query.window, query.sourceId, query.targetId))
      val reverse = pairs.get((query.window, query.targetId, query.sourceId))
      (direct, reverse) match
        case (Some(entry), _) => answer(query, entry, flipped = false)
        case (None, Some(e))  => answer(query, e, flipped = true)
        case (None, None)     =>
          NotPrecomputed(query.window, query.sourceId, query.targetId)

  private def answer(
      query: PathQuery,
      entry: PairEntry,
      flipped: Boolean
  ): PathLookup = entry match
    case PairEntry.Status(row) =>
      val kind = InMemoryPathEvidence.kindOf(row)
      NonPath(
        query.window,
        query.sourceId,
        query.targetId,
        row.status.getOrElse(""),
        row.unreachableReason,
        kind,
        row.provenance
      )
    case PairEntry.Hops(hops) =>
      if hops.size > query.maxDepth then ExceedsDepth(hops.size, query.maxDepth)
      else if hops.size > query.maxResults then
        ExceedsResults(hops.size, query.maxResults)
      else
        val oriented =
          if flipped then
            hops.reverse.map(h => h.copy(fromId = h.toId, toId = h.fromId))
          else hops
        Found(query.window, query.sourceId, query.targetId, oriented)

object InMemoryPathEvidence:
  /** Reads, validates and indexes the `path-evidence` table of a run. */
  def from(
      tables: TableSource,
      bounds: LoadBounds,
      cost: PathQuery => Long = _ => 0L
  ): InMemoryPathEvidence =
    new InMemoryPathEvidence(PathEvidence.read(tables, bounds), cost)

  private[storage] def kindOf(row: PathEvidenceRow): NonPathKind =
    row.status match
      case Some("trivial")                => NonPathKind.Trivial
      case Some("endpoint-not-in-window") => NonPathKind.EndpointNotInWindow
      case Some(PathEvidence.UnreachableStatus) =>
        row.unreachableReason match
          case Some("different-component") => NonPathKind.DifferentComponent
          case Some("beyond-hop-limit")    => NonPathKind.BeyondHopLimit
          case _                           => NonPathKind.UnreachableUnexplained
      case other =>
        Fail(ErrorCode.MalformedRow, s"Status row has unknown status $other")

  private def malformed(message: String): Nothing =
    Fail(ErrorCode.MalformedRow, message)

  private def hopOf(row: PathEvidenceRow): PathHop =
    PathHop(
      row.fromId.getOrElse(malformed("Hop row has no fromId")),
      row.toId.getOrElse(malformed("Hop row has no toId")),
      row.edgeId.getOrElse(malformed("Hop row has no edgeId")),
      row.jointPublicationCount.getOrElse(
        malformed(s"Hop ${row.edgeId} has no jointPublicationCount")
      ),
      row.supportingWorkIds,
      row.firstCollaborationYear,
      row.lastCollaborationYear,
      row.provenance
    )

  private[storage] def index(
      rows: Vector[PathEvidenceRow]
  ): Map[(String, String, String), PairEntry] =
    rows
      .groupBy(_.pairKey)
      .map { case (pairKey, group) =>
        val head = group.head
        Fail.unless(
          pairKey == s"${head.window}|${head.sourceId}|${head.targetId}" &&
            group.forall(r =>
              r.window == head.window && r.sourceId == head.sourceId &&
                r.targetId == head.targetId
            ),
          ErrorCode.MalformedRow,
          s"Rows of pair $pairKey disagree with the pair key"
        )
        val hopRows = group.filter(_.edgeId.isDefined)
        val statusRows = group.filter(_.edgeId.isEmpty)
        val entry =
          if hopRows.nonEmpty then
            Fail.unless(
              statusRows.isEmpty,
              ErrorCode.MalformedRow,
              s"Pair $pairKey has hop rows and a status row"
            )
            PairEntry.Hops(chain(pairKey, head, hopRows))
          else
            Fail.unless(
              statusRows.size == 1,
              ErrorCode.DuplicateId,
              s"Pair $pairKey has ${statusRows.size} status rows"
            )
            kindOf(statusRows.head)
            PairEntry.Status(statusRows.head)
        (head.window, head.sourceId, head.targetId) -> entry
      }

  /** Hop rows ordered by hop index: indexes 1 to n without gap or repeat, each
    * hop starting where the previous one ended, the first at the source and the
    * last at the target.
    */
  private def chain(
      pairKey: String,
      head: PathEvidenceRow,
      hopRows: Vector[PathEvidenceRow]
  ): Vector[PathHop] =
    val ordered = hopRows.sortBy(_.hopIndex.getOrElse(Int.MaxValue))
    Fail.unless(
      ordered.map(_.hopIndex) == Vector.range(1, ordered.size + 1).map(Some(_)),
      ErrorCode.MalformedRow,
      s"Hop indexes of pair $pairKey are not 1 to ${ordered.size}"
    )
    val hops = ordered.map(hopOf)
    Fail.unless(
      hops.head.fromId == head.sourceId && hops.last.toId == head.targetId,
      ErrorCode.MalformedRow,
      s"Hops of pair $pairKey do not run from its source to its target"
    )
    hops.zip(hops.tail).foreach { (a, b) =>
      Fail.unless(
        a.toId == b.fromId,
        ErrorCode.MalformedRow,
        s"Hops of pair $pairKey are not contiguous at ${a.toId}"
      )
    }
    ordered.foreach { r =>
      Fail.unless(
        r.pathLength.forall(_ == hops.size),
        ErrorCode.MalformedRow,
        s"Pair $pairKey: pathLength ${r.pathLength} differs from ${hops.size} hops"
      )
      val yearsOrdered = for
        first <- r.firstCollaborationYear
        last <- r.lastCollaborationYear
      yield first <= last
      Fail.unless(
        yearsOrdered.getOrElse(true),
        ErrorCode.MalformedRow,
        s"Hop ${r.edgeId} has first collaboration year after the last"
      )
    }
    hops
