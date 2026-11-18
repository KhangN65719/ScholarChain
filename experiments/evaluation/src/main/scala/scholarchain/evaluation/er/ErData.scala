package scholarchain.evaluation.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.evaluation.io.J

/** The human verdict of a gold pair, as written in the gold file. */
enum GoldVerdict(val wire: String):
  case Same extends GoldVerdict("match")
  case Different extends GoldVerdict("non-match")
  case Unsure extends GoldVerdict("unsure")

object GoldVerdict:
  def parse(wire: String): Option[GoldVerdict] = values.find(_.wire == wire)

/** The matcher outcome of a decisions file. */
enum Outcome(val wire: String):
  case Accepted extends Outcome("ACCEPTED")
  case Rejected extends Outcome("REJECTED")
  case Unresolved extends Outcome("UNRESOLVED")
  case Conflict extends Outcome("CONFLICT")

object Outcome:
  def parse(wire: String): Option[Outcome] = values.find(_.wire == wire)

final case class Decision(
    pairId: String,
    kind: String,
    outcome: Outcome,
    path: String,
    score: Option[Double]
)

final case class GoldRow(pairId: String, verdict: GoldVerdict)

/** One gold pair with its stratum and, when the matcher decided it, the
  * decision. A pair with no decision stays in the gold counts (as
  * `EvaluationRun` does with its MISSING column).
  */
final case class Scored(
    pairId: String,
    stratum: String,
    gold: GoldVerdict,
    decision: Option[Decision]
):
  def accepted: Boolean = decision.exists(_.outcome == Outcome.Accepted)

final case class Joined(
    items: Vector[Scored],
    decisionsWithoutGold: Vector[String],
    goldWithoutStratum: Vector[String]
):
  def goldWithoutDecision: Vector[String] =
    items.filter(_.decision.isEmpty).map(_.pairId)

/** What `GOLD-MANIFEST.json` says: the number of suspected mislabelled pairs it
  * claims, any pair ids it names for them, and the sha256 of each file it
  * lists.
  */
final case class ManifestInfo(
    claimedMislabelled: Option[Int],
    identifiedPairIds: Vector[String],
    files: Map[String, String]
):
  def identifiesPairs: Boolean = identifiedPairIds.nonEmpty

  /** `match`, `MISMATCH` or `not-listed` for a file given its actual sha256. */
  def check(file: Path, actualSha: String): String =
    val parent =
      Option(file.getParent).map(_.getFileName.toString).getOrElse("")
    val tail = s"/$parent/${file.getFileName}"
    files.find((path, _) => s"/$path".endsWith(tail)) match
      case None           => "not-listed"
      case Some((_, sha)) => if sha == actualSha then "match" else "MISMATCH"

object ManifestInfo:
  private val claim = """(\d+) clear-non-match pairs labeled same""".r

  def parse(text: String): ManifestInfo =
    val json = ujson.read(text)
    val claimed = claim.findFirstMatchIn(text).map(_.group(1).toInt)
    val ids = json.objOpt.toVector.flatMap(_.toVector).flatMap { (key, value) =>
      val k = key.toLowerCase
      if k.contains("suspect") || k.contains("mislabel") then
        value.arrOpt.toVector.flatMap(_.toVector.flatMap(_.strOpt))
      else Vector.empty
    }
    val files = J
      .field(json, "files")
      .flatMap(_.arrOpt)
      .toVector
      .flatMap(_.toVector)
      .flatMap(f => J.str(f, "path").zip(J.str(f, "sha256")))
      .toMap
    ManifestInfo(claimed, ids.distinct, files)

/** Readers and the join of the three ER inputs. Every parser throws
  * `IllegalArgumentException` naming the file and line, so a bad input stops
  * the run before anything is written.
  */
object ErInputs:
  def lines(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector

  private def fail(where: String, message: String): Nothing =
    throw new IllegalArgumentException(s"$where: $message")

  private def need[A](value: Option[A], where: String, field: String): A =
    value.getOrElse(fail(where, s"missing or invalid $field"))

  private def rows[A](source: String, text: Seq[String])(
      f: (ujson.Value, String) => A
  ): Vector[A] =
    text.zipWithIndex
      .filter((line, _) => line.trim.nonEmpty)
      .map { (line, index) =>
        val where = s"$source:${index + 1}"
        val json =
          try ujson.read(line)
          catch
            case e: Exception => fail(where, s"not valid JSON: ${e.getMessage}")
        f(json, where)
      }
      .toVector

  private def unique(source: String, ids: Vector[String]): Unit =
    val seen = scala.collection.mutable.Set.empty[String]
    ids.foreach { id =>
      if !seen.add(id) then fail(source, s"duplicate pair id $id")
    }

  def decisions(source: String, text: Seq[String]): Vector[Decision] =
    val out = rows(source, text) { (json, where) =>
      val outcomeText = need(J.str(json, "outcome"), where, "outcome")
      Decision(
        need(J.str(json, "pair_id"), where, "pair_id"),
        J.str(json, "kind").getOrElse(""),
        need(Outcome.parse(outcomeText), where, s"outcome $outcomeText"),
        need(J.str(json, "path"), where, "path"),
        J.num(json, "score")
      )
    }
    unique(source, out.map(_.pairId))
    out

  def gold(source: String, text: Seq[String]): Vector[GoldRow] =
    val out = rows(source, text) { (json, where) =>
      val id = need(
        J.str(json, "itemId").orElse(J.str(json, "pair_id")),
        where,
        "itemId"
      )
      val verdictText = need(J.str(json, "verdict"), where, "verdict")
      GoldRow(
        id,
        need(GoldVerdict.parse(verdictText), where, s"verdict $verdictText")
      )
    }
    unique(source, out.map(_.pairId))
    out

  def strata(source: String, text: Seq[String]): Map[String, String] =
    val out = rows(source, text) { (json, where) =>
      need(J.str(json, "pair_id"), where, "pair_id") ->
        need(J.str(json, "stratum"), where, "stratum")
    }
    unique(source, out.map(_._1))
    out.toMap

  /** The evaluation universe is the gold file. A pair missing from the strata
    * key gets the stratum `unknown`, which the bootstrap treats as a stratum of
    * its own and the report lists.
    */
  def join(
      gold: Vector[GoldRow],
      decisions: Vector[Decision],
      strata: Map[String, String]
  ): Joined =
    val byId = decisions.map(d => d.pairId -> d).toMap
    val goldIds = gold.map(_.pairId).toSet
    val items = gold
      .sortBy(_.pairId)
      .map(g =>
        Scored(
          g.pairId,
          strata.getOrElse(g.pairId, "unknown"),
          g.verdict,
          byId.get(g.pairId)
        )
      )
    Joined(
      items,
      decisions.map(_.pairId).filterNot(goldIds.contains).sorted,
      items.filterNot(i => strata.contains(i.pairId)).map(_.pairId)
    )
