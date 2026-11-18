package scholarchain.evaluation.er

import java.nio.file.Path
import scala.collection.mutable
import scholarchain.evaluation.io.{ExportFiles, J}

/** Researcher nodes of the export: how entity resolution left them. */
final case class NodeView(
    present: Boolean,
    rows: Long,
    byNodeKind: Map[String, Long],
    byLinkStatus: Map[String, Long],
    byKindAndStatus: Map[String, Long]
)

/** Coauthor edges. A flag that is absent or not a boolean is not counted in its
  * denominator; `pairKnown` counts the rows where both flags are present, the
  * denominator of the either-side and both-sides counts.
  */
final case class EdgeCounts(
    rows: Long,
    aKnown: Long,
    aUnresolved: Long,
    bKnown: Long,
    bUnresolved: Long,
    pairKnown: Long,
    eitherUnresolved: Long,
    bothUnresolved: Long
)

final case class EdgeView(
    present: Boolean,
    overall: EdgeCounts,
    byWindow: Map[String, EdgeCounts]
)

/** The tier S population view: not a sample, every row of the export. */
final case class Population(nodes: NodeView, edges: EdgeView):
  def incomplete: Boolean = !nodes.present || !edges.present

object ErPopulation:
  val NodeTable = "nodes-researcher"
  val EdgeTable = "edges-coauthored"
  val Absent = "(absent)"

  private final class Acc:
    var rows, aKnown, aUnres, bKnown, bUnres, pairKnown, either, both = 0L

    def add(a: Option[Boolean], b: Option[Boolean]): Unit =
      rows += 1L
      a.foreach { x =>
        aKnown += 1L
        if x then aUnres += 1L
      }
      b.foreach { x =>
        bKnown += 1L
        if x then bUnres += 1L
      }
      for
        x <- a
        y <- b
      do
        pairKnown += 1L
        if x || y then either += 1L
        if x && y then both += 1L

    def freeze: EdgeCounts =
      EdgeCounts(rows, aKnown, aUnres, bKnown, bUnres, pairKnown, either, both)

  private def bump(m: mutable.Map[String, Long], key: String): Unit =
    m.update(key, m.getOrElse(key, 0L) + 1L)

  private def flag(v: ujson.Value, key: String): Option[Boolean] =
    J.field(v, key).flatMap(_.boolOpt)

  def windowOf(v: ujson.Value): String =
    J.str(v, "window").getOrElse {
      (J.field(v, "windowStart"), J.field(v, "windowEnd")) match
        case (Some(_), Some(_)) =>
          s"${J.scalar(v, "windowStart")}-${J.scalar(v, "windowEnd")}"
        case _ => Absent
    }

  def nodes(root: Path): NodeView =
    val present = ExportFiles.partFiles(root.resolve(NodeTable)).nonEmpty
    val kinds = mutable.Map.empty[String, Long]
    val status = mutable.Map.empty[String, Long]
    val both = mutable.Map.empty[String, Long]
    val rows = ExportFiles.scan(root, NodeTable) { row =>
      val kind = J.str(row.json, "nodeKind").getOrElse(Absent)
      val link = J.str(row.json, "linkStatus").getOrElse(Absent)
      bump(kinds, kind)
      bump(status, link)
      bump(both, s"$kind/$link")
    }
    NodeView(present, rows, kinds.toMap, status.toMap, both.toMap)

  def edges(root: Path): EdgeView =
    val present = ExportFiles.partFiles(root.resolve(EdgeTable)).nonEmpty
    val overall = new Acc
    val windows = mutable.Map.empty[String, Acc]
    ExportFiles.scan(root, EdgeTable) { row =>
      val a = flag(row.json, "endpointAUnresolved")
      val b = flag(row.json, "endpointBUnresolved")
      overall.add(a, b)
      windows.getOrElseUpdate(windowOf(row.json), new Acc).add(a, b)
    }
    EdgeView(
      present,
      overall.freeze,
      windows.view.mapValues(_.freeze).toMap
    )

  def read(root: Path): Population = Population(nodes(root), edges(root))
