package scholarchain.er.eval

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.ContractSchema
import scholarchain.er.matching.EntityKind
import upickle.default.{ReadWriter, readwriter}

enum Verdict(val wire: String):
  case Match extends Verdict("match")
  case NonMatch extends Verdict("non-match")
  case Unsure extends Verdict("unsure")

object Verdict:
  given ReadWriter[Verdict] = readwriter[String].bimap[Verdict](
    _.wire,
    wire =>
      values
        .find(_.wire == wire)
        .getOrElse(
          throw new IllegalArgumentException(s"Unknown verdict: $wire")
        )
  )

/** A human judgment about one pair, traceable to the source records.
  *
  * The constructor refuses a label that is not marked as human reviewed, has no
  * named reviewer or review time, or lacks the source record IDs and snapshot
  * it was judged from. A machine suggestion or an unsigned label therefore
  * cannot become a gold label by accident.
  */
final case class GoldLabel(
    schemaVersion: ContractSchema.Version,
    itemId: String,
    kind: EntityKind,
    leftId: String,
    rightId: String,
    leftSourceRecordId: String,
    rightSourceRecordId: String,
    snapshotId: String,
    verdict: Verdict,
    reviewMethod: String,
    reviewer: String,
    reviewedAt: String,
    notes: Option[String]
) derives ReadWriter:
  Vector(
    itemId,
    leftId,
    rightId,
    leftSourceRecordId,
    rightSourceRecordId,
    snapshotId,
    reviewer
  ).foreach(value => require(value.trim.nonEmpty, "Label field is blank"))
  require(
    reviewMethod == GoldLabel.HumanMethod,
    s"A label must record reviewMethod '${GoldLabel.HumanMethod}'"
  )
  Instant.parse(reviewedAt)
  require(leftId < rightId, "Label endpoints must be in ascending ID order")

  def pairKey: (EntityKind, String, String) = (kind, leftId, rightId)

object GoldLabel:
  val HumanMethod = "human"

/** Reads a label file: one JSON `GoldLabel` per line, blank lines ignored. A
  * pair labeled twice must carry one verdict; two verdicts for one pair mean
  * the file is inconsistent and nothing is evaluated.
  */
object GoldFile:
  def parse(lines: Iterable[String]): Vector[GoldLabel] =
    val labels = lines.iterator.zipWithIndex
      .filter((line, _) => line.trim.nonEmpty)
      .map { (line, number) =>
        try upickle.default.read[GoldLabel](line)
        catch
          case error: Exception =>
            throw new IllegalArgumentException(
              s"Invalid label on line ${number + 1}: ${error.getMessage}",
              error
            )
      }
      .toVector
    val conflicting = labels
      .groupBy(_.pairKey)
      .filter((_, group) => group.map(_.verdict).distinct.size > 1)
      .keys
    require(
      conflicting.isEmpty,
      s"Pairs labeled with different verdicts: ${conflicting.mkString(", ")}"
    )
    labels.distinctBy(_.pairKey)

  def read(path: Path): Vector[GoldLabel] =
    parse(Files.readAllLines(path, UTF_8).asScala)
