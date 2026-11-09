package scholarchain.services

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable
import scholarchain.core.{GraphEdgeType, GraphNodeType, YearWindow}
import scholarchain.storage.{CoauthorLink, ResearcherCandidate, WindowLabels}

/** Spark's window text: `all`, or `<start>-<end>` in canonical form. */
object GraphWindow:
  private val pattern = "([0-9]{1,4})-([0-9]{1,4})".r

  def valid(label: String): Boolean =
    label match
      case pattern(a, b) =>
        val (start, end) = (a.toInt, b.toInt)
        start >= 1 && end <= 9999 && start <= end &&
        WindowLabels.of(YearWindow(start, end)) == label
      case "all" => true
      case _     => false

/** A tamper-evident cursor. It holds an offset and a hash of the request it was
  * issued for (node, depth, window, edge types), signed with HMAC-SHA256. It is
  * upper-case hex throughout, so any changed character changes the meaning and
  * is caught.
  */
final class CursorCodec(secret: Array[Byte]):
  private val hex = HexFormat.of().withUpperCase()

  private def mac(payload: Array[Byte]): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(new SecretKeySpec(secret, "HmacSHA256"))
    m.doFinal(payload)

  private def bindingHash(binding: String): String =
    hex
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(binding.getBytes(UTF_8))
      )
      .take(32)

  private def bad(): Nothing =
    throw new ApiException(
      ErrorCode.InvalidRequest,
      "The cursor is not valid for this request"
    )

  def issue(binding: String, offset: Int): String =
    val payload = s"v1:$offset:${bindingHash(binding)}".getBytes(UTF_8)
    hex.formatHex(payload) + "." + hex.formatHex(mac(payload))

  /** The offset the cursor stands for, or 400. */
  def verify(cursor: String, binding: String): Int =
    val parts = cursor.split("\\.", -1)
    if parts.length != 2 then bad()
    val (payload, signature) =
      try (hex.parseHex(parts(0)), hex.parseHex(parts(1)))
      catch case _: IllegalArgumentException => bad()
    if !MessageDigest.isEqual(mac(payload), signature) then bad()
    new String(payload, UTF_8).split(":", -1) match
      case Array("v1", offset, hash)
          if hash == bindingHash(binding) && offset.matches("[0-9]{1,9}") =>
        offset.toInt
      case _ => bad()

object CursorCodec:
  // A configured secret is checked at configuration load and again here, so a
  // codec never holds an empty or short key.
  def apply(secret: Option[String]): CursorCodec =
    val bytes = secret.map(_.getBytes(UTF_8)).getOrElse {
      val random = new Array[Byte](32)
      new SecureRandom().nextBytes(random)
      random
    }
    require(
      bytes.length >= ServiceConfig.MinCursorSecretBytes,
      "The cursor secret is too short"
    )
    new CursorCodec(bytes)

/** Typed query parameters of the neighbors route. A value above a configured
  * maximum is capped by the service and reported in the response; a value that
  * is not a positive integer, an unknown edge type or an empty value is 400.
  */
final case class NeighborQuery(
    depth: Option[Int],
    limit: Option[Int],
    edgeTypes: Option[Vector[GraphEdgeType]],
    cursor: Option[String],
    window: Option[String]
)

object NeighborQuery:
  private val allowed = Set("depth", "limit", "edgeTypes", "cursor", "window")

  private def bad(message: String): Nothing =
    throw new ApiException(ErrorCode.InvalidRequest, message)

  def parse(params: Map[String, String]): NeighborQuery =
    if params.keys.exists(!allowed.contains(_)) then
      bad("The query string holds an unsupported parameter")
    def positive(name: String): Option[Int] =
      params.get(name).map { value =>
        if !value.matches("[0-9]{1,18}") || BigInt(value) < 1 then
          bad(s"$name must be a positive whole number")
        BigInt(value).min(BigInt(Int.MaxValue)).toInt
      }
    val edgeTypes = params.get("edgeTypes").map { value =>
      value
        .split(",", -1)
        .toVector
        .map(w =>
          GraphEdgeType.values
            .find(_.wire == w)
            .getOrElse(bad("edgeTypes holds an unknown edge type"))
        )
        .distinct
    }
    val cursor = params.get("cursor").map { value =>
      if value.isEmpty then bad("cursor must not be empty")
      value
    }
    val window = params.get("window").map { value =>
      if !GraphWindow.valid(value) then bad("window is not a valid window")
      value
    }
    NeighborQuery(
      positive("depth"),
      positive("limit"),
      edgeTypes,
      cursor,
      window
    )

private final case class Found(link: CoauthorLink, distance: Int, via: String)

object GraphViews:
  def node(c: ResearcherCandidate): ujson.Value =
    ujson.Obj(
      "nodeId" -> c.nodeId,
      "nodeType" -> (if c.isMention then GraphNodeType.AuthorMention.wire
                     else GraphNodeType.Researcher.wire),
      "displayName" -> c.displayName,
      "nodeKind" -> c.nodeKind,
      "orcid" -> J.opt(c.orcid),
      "linkStatus" -> J.opt(c.linkStatus),
      "idRule" -> J.opt(c.idRule),
      "memberCount" -> c.memberCount.toDouble,
      "mentionCount" -> c.mentionCount.toDouble,
      "sharesOrcidWith" -> c.sharesOrcidWith,
      "provenance" -> upickle.default.writeJs(c.provenance)
    )

/** Graph exploration over the graph read port. Depth, page size, fan-out and
  * the number of expansions are all bounded by configuration.
  */
// Boundary note (decision D16). The multi-hop traversal and its ordering
// (distance, then joint publication count) live here as a bounded read-through
// for the exploration API: each hop is one bounded CoauthorContext read behind
// the port, and nothing here scores evidence or calls a model. Phase 10
// accepts this. Moving the traversal into storage or rag, behind the live
// Neptune templates, is deferred to Phase 12.
final class GraphService(
    port: GraphReadPort,
    call: Downstream,
    config: ServiceConfig,
    cursors: CursorCodec
):
  def node(nodeId: String, deadline: Deadline): ujson.Value =
    call
      .call(deadline, idempotent = true)(port.node(nodeId))
      .map(GraphViews.node)
      .getOrElse(throw new ApiException(ErrorCode.NotFound, "No such node"))

  private def expand(
      start: String,
      depth: Int,
      window: String,
      deadline: Deadline
  ): (Vector[Found], Boolean) =
    val visited = mutable.HashSet(start)
    val found = mutable.ArrayBuffer.empty[Found]
    var frontier = Vector(start)
    var expansions = 0
    var truncated = false
    var level = 1
    while level <= depth && frontier.nonEmpty do
      val next = mutable.ArrayBuffer.empty[String]
      frontier.foreach { origin =>
        if expansions >= config.graphMaxExpansions then truncated = true
        else
          expansions += 1
          val links = call.call(deadline, idempotent = true)(
            port.coauthors(origin, window, config.graphMaxFanout)
          )
          if links.size >= config.graphMaxFanout then truncated = true
          links.foreach { link =>
            if visited.add(link.coauthorId) then
              found += Found(link, level, origin)
              next += link.coauthorId
          }
      }
      frontier = next.toVector
      level += 1
    (
      found.toVector.sortBy(f =>
        (f.distance, -f.link.jointPublicationCount, f.link.coauthorId)
      ),
      truncated
    )

  def neighbors(
      nodeId: String,
      query: NeighborQuery,
      deadline: Deadline
  ): ujson.Value =
    val depth = math.min(query.depth.getOrElse(1), config.graphMaxDepth)
    val limit =
      math.min(
        query.limit.getOrElse(config.graphDefaultLimit),
        config.graphMaxLimit
      )
    val window = query.window.getOrElse(config.graphDefaultWindow)
    val requested = query.edgeTypes.map(_.map(_.wire).distinct.sorted)
    val binding =
      s"$nodeId|$depth|$window|${requested.fold("*")(_.mkString(","))}"
    val offset = query.cursor.fold(0)(cursors.verify(_, binding))
    call
      .call(deadline, idempotent = true)(port.node(nodeId))
      .getOrElse(throw new ApiException(ErrorCode.NotFound, "No such node"))
    val supported =
      requested.forall(_.contains(GraphEdgeType.CoauthoredWith.wire))
    val (all, truncated) =
      if supported then expand(nodeId, depth, window, deadline)
      else (Vector.empty[Found], false)
    val page = all.slice(offset, offset + limit)
    val next =
      if offset + limit < all.size then
        ujson.Str(cursors.issue(binding, offset + limit))
      else ujson.Null
    ujson.Obj(
      "nodeId" -> nodeId,
      "window" -> window,
      "depth" -> depth,
      "limit" -> limit,
      "edgeTypes" -> J.strs(
        if supported then Vector(GraphEdgeType.CoauthoredWith.wire)
        else Vector.empty
      ),
      "neighbors" -> J.arr(page.map { f =>
        ujson.Obj(
          "neighborId" -> f.link.coauthorId,
          "distance" -> f.distance,
          "via" -> f.via,
          "edgeType" -> GraphEdgeType.CoauthoredWith.wire,
          "edgeId" -> f.link.edgeId,
          "jointPublicationCount" -> f.link.jointPublicationCount.toDouble,
          "firstCollaborationYear" -> f.link.firstCollaborationYear,
          "lastCollaborationYear" -> f.link.lastCollaborationYear,
          "evidencePointer" -> f.link.evidencePointer
        ): ujson.Value
      }),
      "total" -> all.size,
      "truncated" -> truncated,
      "nextCursor" -> next
    )
