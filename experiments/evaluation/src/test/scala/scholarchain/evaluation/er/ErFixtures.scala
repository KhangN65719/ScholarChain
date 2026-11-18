package scholarchain.evaluation.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Hand-built fixtures. Gold-same pairs: 11 (exact-id e1-e5, hard h1 h2 h3 h5,
  * clear c3 c5); gold-different: 4 (h4, c1, c2, c4); gold-unsure: 1 (h6).
  * Accepted: e1 e2 e3 (exact-id), h1 (probabilistic), h5 and h4
  * (deterministic-bibliographic): TP 5, FP 1. e5 has no decision.
  */
object ErFixtures:
  import GoldVerdict.*
  import Outcome.*

  def item(
      id: String,
      stratum: String,
      gold: GoldVerdict,
      decided: Option[(Outcome, String)]
  ): Scored =
    Scored(
      id,
      stratum,
      gold,
      decided.map((o, p) => Decision(id, "researcher", o, p, Some(0.5)))
    )

  val items: Vector[Scored] = Vector(
    item("e1", "exact-id", Same, Some((Accepted, "exact-id"))),
    item("e2", "exact-id", Same, Some((Accepted, "exact-id"))),
    item("e3", "exact-id", Same, Some((Accepted, "exact-id"))),
    item("e4", "exact-id", Same, Some((Conflict, "exact-id"))),
    item("e5", "exact-id", Same, None),
    item("h1", "hard-common-name", Same, Some((Accepted, "probabilistic"))),
    item("h2", "hard-common-name", Same, Some((Rejected, "probabilistic"))),
    item("h3", "hard-common-name", Same, Some((Unresolved, "probabilistic"))),
    item(
      "h4",
      "hard-common-name",
      Different,
      Some((Accepted, "deterministic-bibliographic"))
    ),
    item(
      "h5",
      "hard-common-name",
      Same,
      Some((Accepted, "deterministic-bibliographic"))
    ),
    item("h6", "hard-common-name", Unsure, Some((Rejected, "probabilistic"))),
    item("c1", "clear-non-match", Different, Some((Rejected, "probabilistic"))),
    item("c2", "clear-non-match", Different, Some((Rejected, "probabilistic"))),
    item("c3", "clear-non-match", Same, Some((Rejected, "probabilistic"))),
    item("c4", "clear-non-match", Different, Some((Conflict, "exact-id"))),
    item("c5", "clear-non-match", Same, Some((Conflict, "probabilistic")))
  )

  private def decisionLine(d: Decision): String =
    ujson.write(
      ujson.Obj(
        "pair_id" -> d.pairId,
        "kind" -> d.kind,
        "outcome" -> d.outcome.wire,
        "path" -> d.path,
        "score" -> d.score.getOrElse(0.0)
      )
    )

  def decisionLines: Vector[String] =
    items.flatMap(_.decision).map(decisionLine)

  def goldLines: Vector[String] =
    items.map(i =>
      ujson.write(
        ujson.Obj(
          "itemId" -> i.pairId,
          "kind" -> "researcher",
          "verdict" -> i.gold.wire,
          "pass" -> 3
        )
      )
    )

  def strataLines: Vector[String] =
    items.map(i =>
      ujson.write(ujson.Obj("pair_id" -> i.pairId, "stratum" -> i.stratum))
    )

  def write(path: Path, lines: Seq[String]): Path =
    Files.createDirectories(path.getParent)
    Files.write(path, lines.asJava, UTF_8)
    path

  /** A tiny export: five researcher nodes and four coauthor edges. */
  def writeExport(root: Path): Unit =
    val nodes = Vector(
      ("resolved", "linked"),
      ("resolved", "linked"),
      ("unresolved-mention", "unlinked"),
      ("unresolved-mention", "unlinked"),
      ("unresolved-mention", "ambiguous")
    ).map((kind, link) =>
      ujson.write(
        ujson.Obj(
          "nodeId" -> s"n-$kind-$link",
          "nodeKind" -> kind,
          "linkStatus" -> link
        )
      )
    ) :+ ujson.write(
      ujson.Obj("nodeId" -> "n-nolink", "nodeKind" -> "resolved")
    )
    write(root.resolve("nodes-researcher/part-00000.jsonl"), nodes)
    def edge(
        id: String,
        window: String,
        a: Option[Boolean],
        b: Option[Boolean]
    ): String =
      val base = ujson.Obj("edgeId" -> id, "window" -> window)
      a.foreach(x => base("endpointAUnresolved") = x)
      b.foreach(x => base("endpointBUnresolved") = x)
      ujson.write(base)
    val edges = Vector(
      edge("x1", "all", Some(false), Some(false)),
      edge("x2", "all", Some(true), Some(false)),
      edge("x3", "2020-2029", Some(true), Some(true)),
      edge("x4", "2020-2029", Some(false), None)
    )
    write(root.resolve("edges-coauthored/part-00000.jsonl"), edges)

  def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try
        stream.iterator.asScala.toVector.reverse.foreach(p => Files.delete(p))
      finally stream.close()

  def withTmp[A](body: Path => A): A =
    val dir = Files.createTempDirectory("erextras-test")
    try body(dir)
    finally deleteTree(dir)
