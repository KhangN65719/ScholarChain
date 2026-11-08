package scholarchain.storage

import scholarchain.core.{Provenance, StableIds}

final case class PathEvidenceRow(
    pairKey: String,
    window: String,
    sourceId: String,
    targetId: String,
    status: Option[String],
    unreachableReason: Option[String],
    hopIndex: Option[Int],
    fromId: Option[String],
    toId: Option[String],
    edgeId: Option[String],
    jointPublicationCount: Option[Long],
    supportingWorkIds: Vector[String],
    provenance: Provenance
)

/** Reader and validator for the Spark `path-evidence` table.
  *
  * A row is a hop row (it has an `edgeId`) or a status row. Hop rows need both
  * endpoints, a hop index and supporting works, and their edge ID must have the
  * form that matches the endpoints: `mention-coauthor:v1:` when either endpoint
  * is an unresolved mention, `coauthor:v1:` otherwise. A legacy `coauthor:v1:`
  * ID on a mention endpoint is refused, as for edges and edge-support. The
  * Phase 6 column `unreachableReason` is carried and is allowed only on
  * `unreachable-within-bound` rows.
  */
object PathEvidence:
  val UnreachableStatus = "unreachable-within-bound"
  val Reasons = Set("different-component", "beyond-hop-limit")
  val Statuses = Set("trivial", "endpoint-not-in-window", UnreachableStatus)

  private def parse(row: Row): PathEvidenceRow =
    PathEvidenceRow(
      Rows.str(row, "pairKey"),
      Rows.str(row, "window"),
      Rows.str(row, "sourceId"),
      Rows.str(row, "targetId"),
      Rows.optStr(row, "status"),
      Rows.optStr(row, "unreachableReason"),
      Rows.optLong(row, "hopIndex").map(_.toInt),
      Rows.optStr(row, "fromId"),
      Rows.optStr(row, "toId"),
      Rows.optStr(row, "edgeId"),
      Rows.optLong(row, "jointPublicationCount"),
      Rows.strList(row, "supportingWorkIds"),
      Rows.provenance(row)
    )

  private val hex64 = "[0-9a-f]{64}"

  private def validate(
      row: PathEvidenceRow,
      knownEdges: Option[Map[String, Set[String]]]
  ): Unit =
    Vector(row.sourceId, row.targetId).foreach { id =>
      Fail.unless(
        StableIds.isCoauthorNode(id),
        ErrorCode.MalformedRow,
        s"Path endpoint is not a core node ID: $id"
      )
    }
    row.unreachableReason.foreach { reason =>
      Fail.unless(
        row.status.contains(UnreachableStatus) && Reasons.contains(reason),
        ErrorCode.MalformedRow,
        s"unreachableReason $reason is not valid for status ${row.status}"
      )
    }
    row.edgeId match
      case Some(edge) =>
        val from = row.fromId.getOrElse(missing("fromId"))
        val to = row.toId.getOrElse(missing("toId"))
        val mention = from.startsWith("mention:") || to.startsWith("mention:")
        Fail.unless(
          !(mention && edge.startsWith("coauthor:v1:")),
          ErrorCode.LegacyEdgeId,
          s"Legacy Spark-local ID $edge on a mention endpoint in " +
            "path-evidence; expected mention-coauthor:v1:. Rerun Spark"
        )
        val expected =
          if mention then "mention-coauthor:v1:" else "coauthor:v1:"
        Fail.unless(
          edge.startsWith(expected),
          ErrorCode.InvalidEdgeId,
          s"Path edge $edge does not have the form $expected"
        )
        Fail.unless(
          edge.stripPrefix(expected).matches(hex64),
          ErrorCode.InvalidEdgeId,
          s"Path edge $edge is not $expected plus 64 hex digits"
        )
        Vector(from, to).foreach { id =>
          Fail.unless(
            StableIds.isCoauthorNode(id),
            ErrorCode.MalformedRow,
            s"Hop endpoint is not a core node ID: $id"
          )
        }
        row.supportingWorkIds.foreach { work =>
          Fail.unless(
            work.startsWith("work:v1:") && work.length > "work:v1:".length,
            ErrorCode.MalformedRow,
            s"Supporting work is not a work ID: $work"
          )
        }
        knownEdges.foreach { known =>
          val ends = known.getOrElse(
            edge,
            Fail(
              ErrorCode.MissingEvidence,
              s"Hop edge $edge is not an edge of the coauthor-edges table"
            )
          )
          Fail.unless(
            ends == Set(from, to),
            ErrorCode.InvalidEdgeId,
            s"Hop $from - $to is not the endpoints of its cited edge $edge"
          )
        }
        Fail.unless(
          row.hopIndex.isDefined && row.supportingWorkIds.nonEmpty,
          ErrorCode.MissingEvidence,
          s"Hop row of $edge has no hop index or supporting works"
        )
        Fail.unless(
          row.unreachableReason.isEmpty,
          ErrorCode.MalformedRow,
          "A hop row carries no unreachableReason"
        )
      case None =>
        Fail.unless(
          row.status.exists(Statuses.contains),
          ErrorCode.MalformedRow,
          s"Status row has unknown status ${row.status}"
        )

  private def missing(column: String): Nothing =
    Fail(ErrorCode.MalformedRow, s"Hop row needs $column")

  /** Reads and validates the table. When `edges-coauthored` is among the
    * tables, every hop edge must be one of its edges.
    */
  def read(tables: TableSource, bounds: LoadBounds): Vector[PathEvidenceRow] =
    val rows = tables.rows("path-evidence")
    bounds.checkRows(rows.size.toLong, "path-evidence")
    val known =
      if tables.tableNames.contains("edges-coauthored") then
        Some(
          tables
            .rows("edges-coauthored")
            .map { r =>
              Rows.str(r, "edgeId") ->
                Set(Rows.str(r, "researcherA"), Rows.str(r, "researcherB"))
            }
            .toMap
        )
      else None
    rows.map { row =>
      val parsed = parse(row)
      validate(parsed, known)
      parsed
    }
